import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Dashboard from './index.vue'
import * as projectApi from '../../api/project'
import type { ProjectSummary } from '../../types/api'

/**
 * dashboard 组件测试：
 * - 统计卡渲染（项目数/平均健康分/已分析/总代码行）
 * - 图表在无项目时不渲染（引导区）
 * - 空态展示
 * - 接口失败时给出说明，而不是静默渲染一排 0
 */

vi.mock('../../api/project', () => ({
  listProjects: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

// 演示模式开关：真值仍取真实模块（文案不写死，避免与 demo/index.ts 漂移），
// 只把 isDemoMode 换成可切换的 getter。
const demoFlag = vi.hoisted(() => ({ demo: false }))
vi.mock('../../demo', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../demo')>()
  return {
    ...actual,
    get isDemoMode() {
      return demoFlag.demo
    },
  }
})

// mock ECharts（happy-dom 无 canvas）
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() })),
}))
vi.mock('echarts/charts', () => ({ BarChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  LegendComponent: {},
  TitleComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

const shallowStubs = {
  NCard: { template: '<div><slot /><slot name="header" /></div>' },
  NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  // description 是 prop，stub 里要显式声明并渲染出来，否则断言读不到
  NEmpty: {
    props: ['description'],
    template: '<div class="n-empty">{{ description }}<slot name="extra" /></div>',
  },
  NList: { template: '<ul><slot /></ul>' },
  NListItem: { template: '<li><slot /></li>' },
}

function makeProject(over: Partial<ProjectSummary> = {}): ProjectSummary {
  return {
    id: 1,
    name: 'demo-app',
    description: null,
    sourceType: 'ZIP',
    status: 'READY',
    langStats: { Java: 60, Python: 40 },
    locTotal: 1500,
    fileCount: 20,
    frameworkTags: [],
    lastAnalyzedAt: '2026-08-01T00:00:00Z',
    createdAt: '2026-08-01T00:00:00Z',
    healthScore: 82,
    ...over,
  }
}

const FAIL = new Error('Request failed with status code 404')

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    demoFlag.demo = false
  })

  it('渲染统计卡（项目数/平均健康分/总行数）', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 2,
      page: 1,
      size: 100,
      items: [
        makeProject({ id: 1, healthScore: 90, locTotal: 1000 }),
        makeProject({ id: 2, healthScore: 70, locTotal: 2000 }),
      ],
    })
    const wrapper = mount(Dashboard, { global: { stubs: shallowStubs } })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('2') // 项目数
    expect(text).toContain('80') // 平均分 (90+70)/2
    expect(text).toContain('3,000') // 总行数
  })

  it('无项目时显示空态引导', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 0,
      page: 1,
      size: 100,
      items: [],
    })
    const wrapper = mount(Dashboard, { global: { stubs: shallowStubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('全局总览')
  })

  it('有项目时图表容器存在', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 1,
      page: 1,
      size: 100,
      items: [makeProject()],
    })
    const wrapper = mount(Dashboard, { global: { stubs: shallowStubs } })
    await flushPromises()
    // 图表 ref 容器应存在（echarts.init 被 mock）
    expect(wrapper.findAll('.dash__chart').length).toBeGreaterThan(0)
  })

  it('接口失败时不再静默渲染全 0：给出失败说明并保留技术细节', async () => {
    vi.mocked(projectApi.listProjects).mockRejectedValue(FAIL)
    const wrapper = mount(Dashboard, { global: { stubs: shallowStubs } })
    await flushPromises()
    const notice = wrapper.find('.dash__notice')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('数据加载失败')
    expect(notice.text()).toContain('status code 404')
    // 空态不再谎报「暂无项目」
    expect(wrapper.find('.n-empty').text()).toContain('未能加载项目列表')
    expect(wrapper.find('.n-empty').text()).not.toContain('暂无项目')
  })

  it('演示构建（VITE_DEMO=1）把「本页没有后端」讲清楚', async () => {
    demoFlag.demo = true
    vi.mocked(projectApi.listProjects).mockRejectedValue(FAIL)
    const wrapper = mount(Dashboard, { global: { stubs: shallowStubs } })
    await flushPromises()
    const notice = wrapper.find('.dash__notice')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('未连接后端')
    expect(notice.text()).toContain('docker compose up') // 如何拿到完整版
    // 演示模式下仍保留技术细节行
    expect(notice.find('.dash__notice-detail').exists()).toBe(true)
    // 不再是「数据加载失败」这种会被误读为产品故障的措辞
    expect(notice.text()).not.toContain('数据加载失败')
  })
})
