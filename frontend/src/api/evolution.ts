import request from './request'
import type { ApiResponse, EvolutionResult } from '../types/api'

/** 获取项目演化统计（06 §3.13；range=30d/90d/180d/all，非 Git/无数据 → available=false） */
export async function fetchEvolution(projectId: number, range = '30d'): Promise<EvolutionResult> {
  const resp = await request.get<ApiResponse<EvolutionResult>>(`/projects/${projectId}/evolution`, {
    params: { range },
  })
  return resp.data.data
}
