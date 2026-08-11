import request from './request'
import type { ApiResponse } from '../types/api'

export interface HealthInfo {
  service: string
  status: string
  version: string
  time: string
}

export async function getHealth(): Promise<HealthInfo> {
  const { data } = await request.get<ApiResponse<HealthInfo>>('/health')
  return data.data
}
