package com.evocode.service.project;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.dto.project.ProjectDetailResp;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.dto.project.ProjectUpdateReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目门面（06 §3.1~3.4；AD-6：磁盘存代码，DB 存元数据）。
 * 继续实现 ProjectService 接口供 ProjectController 注入（Controller 零改动），
 * 内部按领域委托子服务：
 * <ul>
 *   <li>{@link ProjectLifecycleService}：zip / Git 创建编排（无事务，长 IO 不占 DB 连接）；</li>
 *   <li>{@link ProjectQueryService}：分页列表 / 详情 / PATCH 更新；</li>
 *   <li>{@link ProjectDeleteService}：15 表级联清库 + 事务提交后删盘。</li>
 * </ul>
 * 缓存注解（projectList）保留在门面 public 方法上（Controller 直调 bean，AOP 生效）；
 * 门面 @Transactional 与子服务同为默认 REQUIRED——跨 bean 调用加入同一事务，级联整体原子。
 */
@Slf4j
@Service
public class ProjectServiceImpl implements ProjectService {

    private final ProjectLifecycleService lifecycleService;
    private final ProjectQueryService queryService;
    private final ProjectDeleteService deleteService;

    public ProjectServiceImpl(ProjectLifecycleService lifecycleService,
                              ProjectQueryService queryService,
                              ProjectDeleteService deleteService) {
        this.lifecycleService = lifecycleService;
        this.queryService = queryService;
        this.deleteService = deleteService;
    }

    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
    public ProjectResp createFromZip(String name, String description, MultipartFile file) {
        return lifecycleService.createFromZip(name, description, file);
    }

    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
    public ProjectResp createFromGit(String name, String description, String repoUrl, Integer cloneDepth) {
        return lifecycleService.createFromGit(name, description, repoUrl, cloneDepth);
    }

    @Override
    @Cacheable(cacheNames = "projectList")
    public IPage<ProjectSummaryResp> list(int page, int size, String keyword, String language,
                                          String status, String sort, String order) {
        return queryService.list(page, size, keyword, language, status, sort, order);
    }

    @Override
    public ProjectDetailResp detail(Long id) {
        return queryService.detail(id);
    }

    @Transactional
    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
    public ProjectResp update(Long id, ProjectUpdateReq req) {
        return queryService.update(id, req);
    }

    @Transactional
    @Override
    @CacheEvict(cacheNames = "projectList", allEntries = true)
    public void delete(Long id) {
        deleteService.delete(id);
    }
}
