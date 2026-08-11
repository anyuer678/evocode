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
      // code ≠ 0 → 统一错误提示（03 §3.3；v1.0 前无鉴权）
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return resp
  },
  (error) => Promise.reject(error),
)

/** 取 data.data，类型安全地交给调用方。 */
export async function getData<T>(promise: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await promise
  return data.data
}

export default request
