import axios from 'axios'
import type { ApiResponse } from '../types/api'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

request.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>
    if (body.code !== 0) {
      // code ≠ 0 → 统一错误提示（03 §3.3；v1.0 前无鉴权）；附带 code 供调用方精确分支（如 2010 空态）
      const err = new Error(body.message || '请求失败') as Error & { code?: number }
      err.code = body.code
      return Promise.reject(err)
    }
    return resp
  },
  (error) => {
    // 非 2xx（如后端 404/2010 空态）：把业务 code 附到 error 上，供调用方精确分支
    const body = error.response?.data as ApiResponse<unknown> | undefined
    if (body && typeof body.code === 'number') {
      ;(error as Error & { code?: number }).code = body.code
    }
    return Promise.reject(error)
  },
)

/** 取 data.data，类型安全地交给调用方。 */
export async function getData<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await promise
  return data.data
}

export default request
