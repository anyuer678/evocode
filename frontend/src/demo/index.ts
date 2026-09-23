/**
 * 静态演示模式（GitHub Pages 上那份 Vite 产物）。
 *
 * Pages 只部署前端产物、没有后端，所以任何 `/api/v1/**` 请求都必然 404。
 * `docs.yml` 用 `VITE_DEMO=1` 构建线上产物，界面据此把「连不上后端」讲清楚，
 * 而不是把请求失败静默渲染成一排 0（那看起来像产品坏了，而不是环境没起）。
 *
 * 本机 `npm run dev` 与 `docker compose up` 都不受影响 —— 常量恒为 false。
 */
export const isDemoMode: boolean = import.meta.env.VITE_DEMO === '1'

/** 演示环境一句话说明（放在页头醒目位置）。 */
export const DEMO_TITLE = '静态演示环境 · 未连接后端'

/** 演示环境的展开说明 + 如何拿到完整版。 */
export const DEMO_DESC =
  '这一份只是前端产物，没有 API 服务，因此项目列表与各分析图表都不会加载到真实数据。' +
  '在本机仓库根目录执行 docker compose up 后访问 http://127.0.0.1:18080 即为完整版。'

/** 把请求失败的原因说清楚：演示模式下几乎必然是「没有后端」，而非代码出错。 */
export function describeLoadFailure(err: unknown): string {
  const detail = err instanceof Error ? err.message : String(err)
  // 演示模式：把「没有后端」这层原因放在最前面；技术细节保留，方便排查
  if (isDemoMode) {
    return `无法访问 /api/v1（本页没有后端服务）：${detail}`
  }
  return detail
}
