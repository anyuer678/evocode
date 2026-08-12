import request, { getData } from './request'
import type { ApiResponse, DependencyResult } from '../types/api'

/**
 * 依赖清单（docs/06-API契约.md §3.14，P9d）。
 */

/** 项目依赖：EOL 判定清单。无 Maven/npm 依赖文件 → available=false。 */
export async function fetchDependencies(projectId: number): Promise<DependencyResult> {
  return getData(request.get<ApiResponse<DependencyResult>>(`/projects/${projectId}/dependencies`))
}
