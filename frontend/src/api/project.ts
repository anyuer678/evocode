import request, { getData } from './request'
import type {
  ApiResponse,
  PageResult,
  ProjectDetail,
  ProjectResp,
  ProjectSummary,
} from '../types/api'

export interface ProjectListParams {
  page?: number
  size?: number
  keyword?: string
  language?: string
  status?: string
  sort?: 'createdAt' | 'lastAnalyzedAt' | 'locTotal' | 'name' | 'healthScore'
  order?: 'asc' | 'desc'
}

/**
 * 项目 CRUD（docs/06-API契约.md §3.1~3.4）。
 * 后端 Result 统一解包，错误统一在 request 拦截器抛出。
 */

/** 方式 A：zip 上传创建（multipart/form-data） */
export async function createFromZip(
  name: string,
  file: File,
  description?: string,
): Promise<ProjectResp> {
  const form = new FormData()
  form.append('name', name)
  if (description) {
    form.append('description', description)
  }
  form.append('file', file)
  return getData(
    request.post<ApiResponse<ProjectResp>>('/projects', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 300_000, // 上传+解压可能较慢
    }),
  )
}

/** 方式 B：GitHub 仓库创建 */
export async function createFromGit(
  name: string,
  repoUrl: string,
  description?: string,
  cloneDepth = 1,
): Promise<ProjectResp> {
  return getData(
    request.post<ApiResponse<ProjectResp>>('/projects', {
      name,
      description: description ?? null,
      repoUrl,
      cloneDepth,
    }),
  )
}

/** 项目列表（分页 + 筛选 + 排序） */
export async function listProjects(
  params: ProjectListParams = {},
): Promise<PageResult<ProjectSummary>> {
  return getData(request.get<ApiResponse<PageResult<ProjectSummary>>>('/projects', { params }))
}

/** 项目详情 */
export async function getProjectDetail(id: number): Promise<ProjectDetail> {
  return getData(request.get<ApiResponse<ProjectDetail>>(`/projects/${id}`))
}

/** 删除项目（含任务取消 + 级联清库 + 磁盘清理） */
export async function deleteProject(id: number): Promise<void> {
  await request.delete<ApiResponse<null>>(`/projects/${id}`)
}

/** 更新项目 name/description（06 §3.2 PATCH，字段可选） */
export async function updateProject(
  id: number,
  patch: { name?: string; description?: string },
): Promise<ProjectResp> {
  return getData(request.patch<ApiResponse<ProjectResp>>(`/projects/${id}`, patch))
}

/** 导出 Markdown 报告（06 §3.2.1 GET，blob 下载） */
export async function exportReport(id: number): Promise<Blob> {
  const resp = await request.get<Blob>(`/projects/${id}/report/export`, {
    responseType: 'blob',
  })
  return resp.data
}
