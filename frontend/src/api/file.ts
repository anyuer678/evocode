import request, { getData } from './request'
import type { ApiResponse, FileContent, FileNodeItem, PageResult } from '../types/api'

export interface FileListParams {
  page?: number
  size?: number
  language?: string
  keyword?: string
  sort?: 'path' | 'loc' | 'sizeBytes'
  order?: 'asc' | 'desc'
}

/**
 * 项目地图与文件内容（docs/06-API契约.md §3.8）。
 * 数据源为最近一次成功快扫（file_node 白名单），路径校验在后端完成。
 */

/** 文件分页列表 */
export async function listFiles(
  projectId: number,
  params: FileListParams = {},
): Promise<PageResult<FileNodeItem>> {
  return getData(
    request.get<ApiResponse<PageResult<FileNodeItem>>>(`/projects/${projectId}/files`, { params }),
  )
}

/** 读取单文件内容（≤2MB、UTF-8、非二进制） */
export async function getFileContent(projectId: number, path: string): Promise<FileContent> {
  return getData(
    request.get<ApiResponse<FileContent>>(`/projects/${projectId}/files/content`, {
      params: { path },
    }),
  )
}
