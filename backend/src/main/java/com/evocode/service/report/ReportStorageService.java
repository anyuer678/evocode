package com.evocode.service.report;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evocode.entity.AnalysisReport;
import com.evocode.mapper.AnalysisReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 报告存储（SPI-6 拆表）：analysis_report 表读写封装，全链路唯一读写入口。
 *
 * <p>写 = 分析完成/重新生成时 upsert（analysis_id 唯一）；读 = 详情/历史/导出。
 * 列表排序的 healthScore 改走 health_score 列（ProjectMapper JOIN），不再解析 JSONB 子查询。
 */
@Service
@RequiredArgsConstructor
public class ReportStorageService {

    private final AnalysisReportMapper analysisReportMapper;

    /** 保存/覆盖报告：health_score/level/summary 提列，report_json 完整保留。 */
    public void saveReport(Long analysisId, Map<String, Object> reportJson) {
        if (reportJson == null || analysisId == null) {
            return;
        }
        AnalysisReport row = new AnalysisReport();
        row.setAnalysisId(analysisId);
        row.setReportJson(reportJson);
        row.setHealthScore(extractInt(reportJson.get("healthScore")));
        row.setLevel(toStr(reportJson.get("level")));
        row.setSummary(toStr(reportJson.get("summary")));
        AnalysisReport existing = getByAnalysisId(analysisId);
        if (existing == null) {
            analysisReportMapper.insert(row);
        } else {
            row.setId(existing.getId());
            analysisReportMapper.updateById(row);
        }
    }

    /** 单分析报告；无 → null。 */
    public AnalysisReport getByAnalysisId(Long analysisId) {
        return analysisReportMapper.selectOne(new QueryWrapper<AnalysisReport>()
                .eq("analysis_id", analysisId)
                .last("LIMIT 1"));
    }

    /** 项目最近 SUCCEEDED 分析的报告；无 → null。 */
    public AnalysisReport getLatestByProject(Long projectId) {
        return analysisReportMapper.selectLatestByProject(projectId);
    }

    /** 数值防御：healthScore 可能是 Integer/Double 或数字字符串（LLM 输出未规范化时）。 */
    private static Integer extractInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String toStr(Object o) {
        return o == null ? null : o.toString();
    }
}
