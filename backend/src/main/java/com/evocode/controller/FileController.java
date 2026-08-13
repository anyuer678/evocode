package com.evocode.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.common.BusinessException;
import com.evocode.common.ErrorCode;
import com.evocode.common.PageResultResp;
import com.evocode.common.Result;
import com.evocode.common.util.PathSafetyUtil;
import com.evocode.config.EvocodeProperties;
import com.evocode.dto.file.FileContentResp;
import com.evocode.dto.file.FileNodeResp;
import com.evocode.entity.FileNode;
import com.evocode.mapper.FileNodeMapper;
import com.evocode.mapper.ProjectMapper;
import com.evocode.service.scan.FileNodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 项目地图与文件内容（06 §3.8；FR-6.3 引用预览的唯一数据来源）。
 * 安全实现：白名单 file_node + 根内规范化 + ≤2MB + 二进制探测（T-U-17/18）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/projects/{id}/files")
public class FileController {

    private final ProjectMapper projectMapper;
    private final FileNodeMapper fileNodeMapper;
    private final FileNodeService fileNodeService;
    private final EvocodeProperties props;

    public FileController(ProjectMapper projectMapper, FileNodeMapper fileNodeMapper,
                          FileNodeService fileNodeService, EvocodeProperties props) {
        this.projectMapper = projectMapper;
        this.fileNodeMapper = fileNodeMapper;
        this.fileNodeService = fileNodeService;
        this.props = props;
    }

    @GetMapping
    public Result<PageResultResp<FileNodeResp>> list(@PathVariable Long id,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @RequestParam(required = false) String language,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) String order) {
        if (page < 1 || size < 1 || size > 100) {
            throw new BusinessException(ErrorCode.PAGE_PARAM_INVALID, null);
        }
        requireProject(id);
        String column = switch (sort == null ? "path" : sort) {
            case "path" -> "path";
            case "loc" -> "loc";
            case "sizeBytes" -> "size_bytes";
            default -> throw new BusinessException(ErrorCode.PARAM_INVALID, "sort 不在白名单");
        };
        boolean asc = order == null || "asc".equalsIgnoreCase(order);
        if (!asc && !"desc".equalsIgnoreCase(order)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "order 仅支持 asc/desc");
        }

        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FileNode> qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.eq("project_id", id)
                .inSql("analysis_id", FileNodeService.latestSucceededSql(id));
        if (language != null && !language.isBlank()) {
            qw.eq("language", language);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.like("path", keyword);
        }
        qw.orderBy(true, asc, column);

        Page<FileNode> result = fileNodeMapper.selectPage(new Page<>(page, size), qw);
        IPage<FileNodeResp> resp = result.convert(n -> new FileNodeResp(
                n.getPath(), n.getLanguage(), n.getLoc(), n.getSizeBytes()));
        PageResultResp<FileNodeResp> paged = PageResultResp.of(resp);
        return Result.ok(paged);
    }

    @GetMapping("/content")
    public Result<FileContentResp> content(@PathVariable Long id,
                                           @RequestParam("path") String path) {
        requireProject(id);
        if (path == null || path.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_MISSING, "path 不能为空");
        }
        // ① 白名单：path 必须存在于该项目的最近成功快照（T-U-17）
        FileNode node = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getProjectId, id)
                .eq(FileNode::getPath, path)
                .inSql(FileNode::getAnalysisId, FileNodeService.latestSucceededSql(id))
                .last("LIMIT 1"));
        if (node == null) {
            throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "文件不在该项目的白名单内");
        }
        // ② 根内规范化（../、绝对路径在此被拒）
        Path root = Path.of(requireProject(id).getStoragePath()).toAbsolutePath().normalize();
        Path target;
        try {
            target = PathSafetyUtil.resolveInside(root, path);
            // 符号链接逃逸防护（审查 H1）：resolveInside 仅文本归一化，须 toRealPath 解析
            // 真实路径并校验仍在根内（与 ChatStreamer.loadFileRef 一致）；文件不存在亦拒绝
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot)) {
                throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "路径逃逸拦截");
            }
            target = realTarget;
        } catch (IOException | IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, null);
        }
        // ③ ≤2MB ④ 二进制探测 ⑤ UTF-8 读取
        try {
            if (!Files.isRegularFile(target)) {
                throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "目标不是普通文件");
            }
            if (Files.size(target) > props.getContentMaxBytes()) {
                throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "文件超过 2MB 限制");
            }
            byte[] head = Files.readAllBytes(target);
            int probe = Math.min(head.length, 512);
            for (int i = 0; i < probe; i++) {
                if (head[i] == 0) {
                    throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "二进制文件不支持预览");
                }
            }
            String content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(head))
                    .toString();
            return Result.ok(new FileContentResp(path, node.getLanguage(), node.getLoc(), content, false));
        } catch (BusinessException e) {
            throw e;
        } catch (CharacterCodingException e) {
            throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "非 UTF-8 文本");
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.FILE_CONTENT_FORBIDDEN, "文件读取失败");
        }
    }

    private com.evocode.entity.Project requireProject(Long id) {
        com.evocode.entity.Project project = projectMapper.selectById(id);
        if (project == null) {
            throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND, null);
        }
        return project;
    }
}
