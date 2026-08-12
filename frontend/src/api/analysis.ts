import request, { getData } from './request'
import type {
  AnalysisCreated,
  AnalysisHistoryItem,
  AnalysisStatus,
  ApiResponse,
  PageResult,
  ReportDetail,
  ReportHistoryItem,
} from '../types/api'

/**
 * 分析任务（docs/06-API契约.md §3.5/3.6/3.7）。
 */

/** 发起分析（v0.1 仅 FULL）；项目已有运行中任务 → 2002 */
export async function createAnalysis(projectId: number, type = 'FULL'): Promise<AnalysisCreated> {
  return getData(
    request.post<ApiResponse<AnalysisCreated>>(`/projects/${projectId}/analyses`, { type }),
  )
}

/** 分析历史（分页，按 id 倒序） */
export async function listAnalyses(projectId: number): Promise<PageResult<AnalysisHistoryItem>> {
  return getData(
    request.get<ApiResponse<PageResult<AnalysisHistoryItem>>>(`/projects/${projectId}/analyses`),
  )
}

/** 单任务状态轮询（2s 间隔） */
export async function getAnalysisStatus(analysisId: number): Promise<AnalysisStatus> {
  return getData(request.get<ApiResponse<AnalysisStatus>>(`/analyses/${analysisId}`))
}

/** 报告详情；分析不存在或无报告 → 2001 */
export async function getReport(analysisId: number): Promise<ReportDetail> {
  return getData(request.get<ApiResponse<ReportDetail>>(`/analyses/${analysisId}/report`))
}

/** 重新生成报告（不重扫）；返回 202 轮询体 */
export async function regenerateReport(analysisId: number): Promise<AnalysisStatus> {
  return getData(
    request.post<ApiResponse<AnalysisStatus>>(`/analyses/${analysisId}/report/regenerate`),
  )
}

/** P9c：报告历史（SUCCEEDED + report_json 摘要，limit 默认 10 上限 20） */
export async function getReportHistory(
  projectId: number,
  limit = 10,
): Promise<ReportHistoryItem[]> {
  const data = await getData(
    request.get<ApiResponse<{ items: ReportHistoryItem[] }>>(
      `/projects/${projectId}/report/history`,
      { params: { limit } },
    ),
  )
  return data.items
}

// ---------------- P9e：分析进度 SSE ----------------

export interface AnalysisProgressEvent {
  analysisId: number
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  stage: string | null
  progress: number
  message: string | null
}

/**
 * 订阅项目分析进度（P9e E1）：GET /projects/{id}/analyses/events（text/event-stream）。
 * 断线 → onError 回调（前端回退现有 2s 轮询）；返回关闭函数。
 * 注：进度 SSE 无需自定义 header（与 chat 不同），可用原生 EventSource。
 */
export function subscribeAnalysisProgress(
  projectId: number,
  handlers: { onEvent: (e: AnalysisProgressEvent) => void; onError?: () => void },
): () => void {
  const source = new EventSource(`/api/v1/projects/${projectId}/analyses/events`)
  source.addEventListener('analysis-progress', (ev) => {
    try {
      handlers.onEvent(JSON.parse((ev as MessageEvent).data) as AnalysisProgressEvent)
    } catch {
      // 解析失败忽略（下一条事件仍会到达）
    }
  })
  source.onerror = () => {
    source.close()
    handlers.onError?.()
  }
  return () => source.close()
}
