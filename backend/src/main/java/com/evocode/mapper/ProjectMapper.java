package com.evocode.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evocode.dto.project.ProjectSummaryResp;
import com.evocode.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 列表页（06 §3.2）：healthScore = 最近一次 SUCCEEDED 报告的 healthScore（JOIN 子查询，避免全表扫描）。
     * orderColumn/order 由服务层白名单映射后传入，防注入。
     */
    @Select("""
            <script>
            SELECT p.id, p.name, p.source_type, p.lang_stats, p.framework_tags, p.loc_total,
                   p.file_count, p.status, p.last_analyzed_at, p.created_at,
                   (SELECT (a.report_json ->> 'healthScore')::int FROM analysis a
                    WHERE a.project_id = p.id AND a.deleted = 0 AND a.status = 'SUCCEEDED'
                      AND a.report_json IS NOT NULL
                    ORDER BY a.id DESC LIMIT 1) AS health_score
            FROM project p
            WHERE p.deleted = 0
            <if test="keyword != null and keyword != ''">
                AND p.name LIKE CONCAT('%', #{keyword}, '%')
            </if>
            <if test="language != null and language != ''">
                AND p.lang_stats ? #{language}
            </if>
            <if test="status != null and status != ''">
                AND p.status = #{status}
            </if>
            ORDER BY ${orderColumn} ${order}
            </script>
            """)
    IPage<ProjectSummaryResp> selectSummaryPage(Page<?> page,
                                                @Param("keyword") String keyword,
                                                @Param("language") String language,
                                                @Param("status") String status,
                                                @Param("orderColumn") String orderColumn,
                                                @Param("order") String order);
}
