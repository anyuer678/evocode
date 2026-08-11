package com.evocode.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evocode.entity.CommitStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CommitStatMapper extends BaseMapper<CommitStat> {

    /** 按 ISO 周聚合（to_char 输出 YYYY-MM-DD 周一起始），升序。 */
    @Select("SELECT to_char(date_trunc('week', committed_at), 'YYYY-MM-DD') AS week, "
            + "COUNT(*) AS commits, COALESCE(SUM(lines_added), 0) AS lines_added, "
            + "COALESCE(SUM(lines_removed), 0) AS lines_removed "
            + "FROM commit_stat WHERE analysis_id = #{analysisId} AND committed_at >= #{since} "
            + "GROUP BY week ORDER BY week")
    List<Map<String, Object>> selectTrendByWeek(@Param("analysisId") Long analysisId,
                                                @Param("since") OffsetDateTime since);

    /** 按作者聚合，提交数降序。 */
    @Select("SELECT COALESCE(author_name, 'unknown') AS author_name, COUNT(*) AS commits, "
            + "COALESCE(SUM(lines_added), 0) AS lines_added "
            + "FROM commit_stat WHERE analysis_id = #{analysisId} AND committed_at >= #{since} "
            + "GROUP BY author_name ORDER BY commits DESC")
    List<Map<String, Object>> selectAuthors(@Param("analysisId") Long analysisId,
                                            @Param("since") OffsetDateTime since);
}
