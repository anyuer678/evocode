import request from './request'
import type { ApiResponse, DocItem, DocType } from '../types/api'

/** 项目全部文档（06 §3.14，README/ARCH/API 三类） */
export async function fetchDocs(projectId: number): Promise<DocItem[]> {
  const resp = await request.get<ApiResponse<DocItem[]>>(`/projects/${projectId}/docs`)
  return resp.data.data
}

/** 生成/重新生成（同步调 analyzer，可能耗时 10-30s；edited 文档需 force=true 覆盖，否则 2014） */
export async function generateDoc(
  projectId: number,
  docType: DocType,
  force = false,
): Promise<DocItem> {
  // 审查修复：契约标注生成 10-30s，默认 30s 超时临界偶发失败 → 覆盖为 60s
  const resp = await request.post<ApiResponse<DocItem>>(
    `/projects/${projectId}/docs/${docType}/generate`,
    undefined,
    { params: { force }, timeout: 60000 },
  )
  return resp.data.data
}

/** 人工编辑（version+1、edited=true） */
export async function editDoc(docId: number, content: string): Promise<DocItem> {
  const resp = await request.post<ApiResponse<DocItem>>(`/docs/${docId}/edit`, { content })
  return resp.data.data
}
