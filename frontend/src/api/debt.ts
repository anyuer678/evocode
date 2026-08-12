import request from './request'
import type { ApiResponse, PageResult, TechDebtItem, TechDebtStatus } from '../types/api'

/** 技术债列表（06 §3.12；status 可选筛选，分页） */
export async function fetchTechDebts(
  projectId: number,
  status?: TechDebtStatus,
  page = 1,
  size = 50,
): Promise<PageResult<TechDebtItem>> {
  const resp = await request.get<ApiResponse<PageResult<TechDebtItem>>>(
    `/projects/${projectId}/tech-debts`,
    { params: { status, page, size } },
  )
  return resp.data.data
}

/** TD-04：手动登记技术债（source=MANUAL） */
export async function createTechDebt(
  projectId: number,
  data: { title: string; level?: string; description?: string; suggestion?: string },
): Promise<TechDebtItem> {
  const resp = await request.post<ApiResponse<TechDebtItem>>(`/projects/${projectId}/tech-debts`, {
    source: 'MANUAL',
    ...data,
  })
  return resp.data.data
}

/** 更新技术债状态（06 §3.12 状态机；DONE 必填 resolveNote、WONTFIX 必填 wonfixReason） */
export async function updateTechDebtStatus(
  id: number,
  status: TechDebtStatus,
  resolveNote?: string,
  wonfixReason?: string,
): Promise<void> {
  await request.post<ApiResponse<null>>(`/tech-debts/${id}/status`, {
    status,
    resolveNote,
    wonfixReason,
  })
}
