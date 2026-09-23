import { describe, expect, it, vi, afterEach } from 'vitest'

/**
 * demo 模块：区分「本页没有后端」（Pages 静态产物）与「接口真的失败了」（本机开发）。
 *
 * `isDemoMode` 读的是构建期注入的 `import.meta.env.VITE_DEMO`，
 * 所以这里必须 stubEnv + resetModules 后重新 import，才能拿到不同取值下的真实行为。
 */
describe('demo 模块', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
    vi.resetModules()
  })

  it('未设置 VITE_DEMO 时视为非演示环境，失败说明原样透出', async () => {
    vi.stubEnv('VITE_DEMO', '')
    vi.resetModules()
    const m = await import('./index')
    expect(m.isDemoMode).toBe(false)
    expect(m.describeLoadFailure(new Error('Request failed with status code 404'))).toBe(
      'Request failed with status code 404',
    )
  })

  it('VITE_DEMO=1 时识别为演示环境，并点明「本页没有后端服务」', async () => {
    vi.stubEnv('VITE_DEMO', '1')
    vi.resetModules()
    const m = await import('./index')
    expect(m.isDemoMode).toBe(true)
    // 原因在前、技术细节在后：先让人知道是环境问题，再看得到排查线索
    expect(m.describeLoadFailure(new Error('Request failed with status code 404'))).toBe(
      '无法访问 /api/v1（本页没有后端服务）：Request failed with status code 404',
    )
  })

  it('非 Error 的抛出物也能得到可读说明', async () => {
    vi.stubEnv('VITE_DEMO', '1')
    vi.resetModules()
    const m = await import('./index')
    expect(m.describeLoadFailure('boom')).toContain('boom')
  })

  it('演示文案包含「如何拿到完整版」的指引', async () => {
    vi.stubEnv('VITE_DEMO', '1')
    vi.resetModules()
    const m = await import('./index')
    expect(m.DEMO_TITLE).toContain('未连接后端')
    expect(m.DEMO_DESC).toContain('docker compose up')
    expect(m.DEMO_DESC).toContain('18080')
  })
})
