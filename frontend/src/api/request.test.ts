import { describe, expect, it, vi } from 'vitest'
import request, { getData } from './request'

/**
 * request.ts 拦截器（docs/03-API规范.md §3.3）：
 * - code === 0 → 放行
 * - code !== 0 → reject，error.code 附业务码（如 2010 空态）
 * - 非 2xx → error.code 附后端业务码
 */

function mockRespond(body: unknown, status = 200): void {
  // 拦截 axios 实例的 adapter，直接返回构造的响应（绕过真实网络层）
  const adapter = vi.fn(async () => ({
    data: body,
    status,
    statusText: status === 200 ? 'OK' : 'ERR',
    headers: {},
    config: {},
  }))
  request.defaults.adapter = adapter as never
}

describe('request 响应拦截器', () => {
  it('code===0 时正常放行并返回响应', async () => {
    mockRespond({ code: 0, message: 'ok', data: { id: 1 } })
    const resp = await request.get('/projects/1')
    expect(resp.data.data).toEqual({ id: 1 })
  })

  it('code!==0 时 reject，error.code 携带业务码（如 2010 空态）', async () => {
    mockRespond({ code: 2010, message: '无匹配内容', data: null })
    const err = (await request.get('/projects').catch((e) => e)) as Error & { code?: number }
    expect(err.code).toBe(2010)
    expect(err.message).toContain('无匹配内容')
  })

  it('HTTP 非 2xx 时仍附上后端业务码', async () => {
    mockRespond({ code: 2001, message: '项目不存在', data: null }, 404)
    const err = (await request.get('/projects/999').catch((e) => e)) as Error & { code?: number }
    expect(err.code).toBe(2001)
  })

  it('getData 解包出 data.data', async () => {
    mockRespond({ code: 0, message: 'ok', data: { name: 'demo' } })
    const data = await getData(
      request.get<{ code: number; message: string; data: { name: string } }>('/x'),
    )
    expect(data).toEqual({ name: 'demo' })
  })
})
