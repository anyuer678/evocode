import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ReportHistory from './report-history-view.vue'
import * as analysisApi from '../../api/analysis'
import type { ReportHistoryItem } from '../../types/api'

/**
 * report-history-view 测试（风险 diff 逻辑）：
 * - 展开后渲染趋势
 * - 两期风险对比：新增/已消失/持续
 */

vi.mock('../../api/analysis', () => ({
  getReportHistory: vi.fn(),
}))

// mock ECharts（happy-dom 无 canvas）
vi.mock('echarts/core', () => ({
  use: vi.fn(),
  init: vi.fn(() => ({
    setOption: vi.fn(),
    dispose: vi.fn(),
    resize: vi.fn(),
    on: vi.fn(),
    off: vi.fn(),
  })),
}))
vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  MarkLineComponent: {},
  TooltipComponent: {},
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

const shallowStubs = {
  NCard: { template: '<div><slot /><slot name="header" /></div>' },
  NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  NAlert: { template: '<div class="n-alert"><slot /></div>' },
  NEmpty: { template: '<div class="n-empty"><slot /></div>' },
  NSpin: { template: '<div><slot /></div>' },
  NTag: { template: '<span class="n-tag"><slot /></span>' },
}

function makeHistory(over: Partial<ReportHistoryItem> = {}): ReportHistoryItem {
  return {
    analysisId: 1,
    createdAt: '2026-08-01T00:00:00Z',
    healthScore: 80,
    level: 'GOOD',
    dimensions: [
      { key: 'quality', score: 80, stars: 4 },
      { key: 'structure', score: 70, stars: 4 },
    ],
    risks: [],
    source: 'RULES',
    ...over,
  }
}

describe('ReportHistory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('展开后加载历史并显示趋势', async () => {
    vi.mocked(analysisApi.getReportHistory).mockResolvedValue([makeHistory()])
    const wrapper = mount(ReportHistory, {
      props: { projectId: 1 },
      global: { stubs: shallowStubs },
    })
    // 点击展开
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(analysisApi.getReportHistory).toHaveBeenCalledWith(1, 20)
    expect(wrapper.text()).toContain('健康分趋势')
  })

  it('风险 diff：新增与已消失分类', async () => {
    vi.mocked(analysisApi.getReportHistory).mockResolvedValue([
      // 最新（index 0）：risks 含 A（新增）+ C（持续）
      makeHistory({
        analysisId: 2,
        risks: [
          { level: 'HIGH', title: 'RiskA' },
          { level: 'LOW', title: 'RiskC' },
        ],
      }),
      // 基准（index 1）：risks 含 B（已消失）+ C（持续）
      makeHistory({
        analysisId: 1,
        risks: [
          { level: 'HIGH', title: 'RiskB' },
          { level: 'LOW', title: 'RiskC' },
        ],
      }),
    ])
    const wrapper = mount(ReportHistory, {
      props: { projectId: 1 },
      global: { stubs: shallowStubs },
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('新增')
    expect(text).toContain('RiskA')
    expect(text).toContain('已消失')
    expect(text).toContain('RiskB')
    expect(text).toContain('持续')
    expect(text).toContain('RiskC')
  })
})
