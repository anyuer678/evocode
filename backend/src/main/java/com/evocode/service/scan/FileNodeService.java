package com.evocode.service.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evocode.dto.scan.ScanFileResp;
import com.evocode.entity.FileNode;
import com.evocode.mapper.FileNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件快照落库（04 §3.2 FileNodeService；T-U-16：先删后插无堆积）。
 */
@Service
@RequiredArgsConstructor
public class FileNodeService {

    private final FileNodeMapper fileNodeMapper;

    /** 快照重建：同 analysis_id 二次写入先删后插。 */
    public void replaceSnapshot(Long projectId, Long analysisId, List<ScanFileResp> files) {
        fileNodeMapper.delete(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getProjectId, projectId)
                .eq(FileNode::getAnalysisId, analysisId));
        if (files == null || files.isEmpty()) {
            return;
        }
        List<FileNode> nodes = files.stream().map(f -> {
            FileNode node = new FileNode();
            node.setProjectId(projectId);
            node.setAnalysisId(analysisId);
            node.setPath(f.path());
            node.setLanguage(f.language());
            node.setLoc(f.loc());
            node.setSizeBytes(f.sizeBytes());
            return node;
        }).toList();
        nodes.forEach(fileNodeMapper::insert);
    }

    /** path 是否在最近成功快照白名单内（06 §3.8 安全①）。 */
    public boolean isPathInWhitelist(Long projectId, String path) {
        FileNode node = fileNodeMapper.selectOne(new LambdaQueryWrapper<FileNode>()
                .eq(FileNode::getProjectId, projectId)
                .eq(FileNode::getPath, path)
                .inSql(FileNode::getAnalysisId, latestSucceededSql(projectId))
                .last("LIMIT 1"));
        return node != null;
    }

    /** 最近成功快照的全部文件（report 重新生成时重建 scan 摘要用，06 §5.2 不重扫）。 */
    public List<ScanFileResp> listAllForReport(Long projectId) {
        return fileNodeMapper.selectList(new LambdaQueryWrapper<FileNode>()
                        .eq(FileNode::getProjectId, projectId)
                        .inSql(FileNode::getAnalysisId, latestSucceededSql(projectId)))
                .stream()
                .map(n -> new ScanFileResp(n.getPath(), n.getLanguage(), n.getLoc(), n.getSizeBytes()))
                .toList();
    }

    public static String latestSucceededSql(Long projectId) {
        return "SELECT id FROM analysis WHERE project_id = " + projectId
                + " AND deleted = 0 AND status = 'SUCCEEDED' ORDER BY id DESC LIMIT 1";
    }
}
