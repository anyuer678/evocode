package com.evocode.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evocode.entity.AnalysisReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AnalysisReportMapper extends BaseMapper<AnalysisReport> {

    /** 项目最近一次 SUCCEEDED 且有报告的分析的报告（export 用，06 §3.7）。
     *  @ResultMap 引用 autoResultMap 生成的 resultMap（JSONB report_json 需 PgJsonbTypeHandler）。 */
    @Select("""
            SELECT ar.*
            FROM analysis_report ar
            JOIN analysis a ON a.id = ar.analysis_id
            WHERE a.project_id = #{projectId} AND a.deleted = 0 AND a.status = 'SUCCEEDED'
            ORDER BY a.id DESC
            LIMIT 1
            """)
    @ResultMap("mybatis-plus_AnalysisReport")
    AnalysisReport selectLatestByProject(@Param("projectId") Long projectId);
}
