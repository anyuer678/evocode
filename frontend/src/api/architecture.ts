import request from './request'
import type { ApiResponse, ArchitectureResult } from '../types/api'

/** 获取项目架构分析结果（analysisId 缺省取最新一次；无数据后端返回 404/2010） */
export async function fetchArchitecture(
  projectId: number,
  analysisId?: number,
): Promise<ArchitectureResult> {
  const params = analysisId ? `?analysisId=${analysisId}` : ''
  const resp = await request.get<ApiResponse<ArchitectureResult>>(
    `/projects/${projectId}/architecture${params}`,
  )
  return resp.data.data
}
