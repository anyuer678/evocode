package com.evocode.service.project;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.evocode.dto.project.ProjectDetailResp;
import com.evocode.dto.project.ProjectResp;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.dto.project.ProjectUpdateReq;
import com.evocode.entity.Project;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目 CRUD + 删除级联编排（04 §3.2）。
 */
public interface ProjectService {

    /** zip 上传创建（06 §3.1 方式 A）。成功后异步快扫。 */
    ProjectResp createFromZip(String name, String description, MultipartFile file);

    /** GitHub 克隆创建（06 §3.1 方式 B）。成功后异步快扫。 */
    ProjectResp createFromGit(String name, String description, String repoUrl, Integer cloneDepth);

    /** 列表（06 §3.2）：分页/关键字/语言/状态/排序。 */
    IPage<ProjectSummaryResp> list(int page, int size, String keyword, String language, String status, String sort, String order);

    /** 详情（06 §3.3）。 */
    ProjectDetailResp detail(Long id);

    /** P9b：更新 name/description（06 §3.2 PATCH；1001/1002/2001）。 */
    ProjectResp update(Long id, ProjectUpdateReq req);

    /** 删除（06 §3.4：级联清库 + 磁盘目录）。 */
    void delete(Long id);
}
