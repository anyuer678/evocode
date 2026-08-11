import request, { getData } from './request'
import type { ApiResponse, QualityIssuesResult } from '../types/api'

export interface QualityParams {
  page?: number
  size?: number
  severity?: string
  kind?: string
  status?: string
}

/** 质量 issues 查询（docs/06-API契约.md §3.10）。metrics.available=false 表示 Sonar 未启用。 */
export async function getQualityIssues(
  projectId: number,
  params: QualityParams = {},
): Promise<QualityIssuesResult> {
  return getData(
    request.get<ApiResponse<QualityIssuesResult>>(`/projects/${projectId}/quality-issues`, {
      params,
    }),
  )
}
