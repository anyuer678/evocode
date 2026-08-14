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
 */

vi.mock('../../api/project', () => ({
  listProjects: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

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
  NEmpty: { template: '<div class="n-empty"><slot /><slot name="extra" /></div>' },
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

describe('Dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
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
})
