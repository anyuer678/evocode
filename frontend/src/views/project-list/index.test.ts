import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { NMessageProvider, NDialogProvider } from 'naive-ui'
import ProjectList from './index.vue'
import * as projectApi from '../../api/project'
import type { ProjectSummary } from '../../types/api'

/**
 * project-list 组件测试：
 * - 摘要统计条（项目数/平均健康分/就绪/失败/总代码行）
 * - 表格渲染项目行
 * - 无数据时显示引导空态
 * - 接口失败时不得谎报「还没有项目」
 */

vi.mock('../../api/project', () => ({
  listProjects: vi.fn(),
  deleteProject: vi.fn(),
  exportReport: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

// 演示模式开关：文案仍取真实模块，只把 isDemoMode 换成可切换的 getter。
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

// Naive UI 组件用浅渲染 stub，避免依赖完整 provider
const shallowStubs = {
  NCard: { template: '<div><slot /><slot name="header" /></div>' },
  NButton: { template: '<button @click="$emit(\'click\')"><slot /></button>' },
  NInput: { template: '<input />' },
  NSelect: { template: '<select />' },
  NDataTable: { template: '<div class="n-data-table"><slot /></div>' },
  NProgress: { template: '<div class="n-progress" />' },
  NTag: { template: '<span class="n-tag"><slot /></span>' },
  NSpace: { template: '<div><slot /></div>' },
}

function mountWithProviders(component: typeof ProjectList) {
  return mount(
    {
      components: { ProjectList: component },
      template:
        '<n-message-provider><n-dialog-provider><ProjectList /></n-dialog-provider></n-message-provider>',
    },
    { global: { stubs: { ...shallowStubs, NMessageProvider, NDialogProvider } } },
  )
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
    frameworkTags: ['Spring Boot'],
    lastAnalyzedAt: '2026-08-01T00:00:00Z',
    createdAt: '2026-08-01T00:00:00Z',
    healthScore: 82,
    ...over,
  }
}

const FAIL = new Error('Request failed with status code 404')

describe('ProjectList', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    demoFlag.demo = false
  })

  it('渲染项目行与健康分', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 1,
      page: 1,
      size: 12,
      items: [makeProject()],
    })
    const wrapper = mountWithProviders(ProjectList)
    await flushPromises()
    expect(projectApi.listProjects).toHaveBeenCalled()
    // 摘要条出现（有项目时）
    expect(wrapper.text()).toContain('平均健康分')
  })

  it('摘要统计聚合正确（1 项目 82 分）', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 1,
      page: 1,
      size: 12,
      items: [makeProject()],
    })
    const wrapper = mountWithProviders(ProjectList)
    await flushPromises()
    // 平均健康分 82（单项目即自身分数）
    expect(wrapper.text()).toContain('82')
    expect(wrapper.text()).toContain('1,500')
  })

  it('无数据时显示引导空态', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 0,
      page: 1,
      size: 12,
      items: [],
    })
    const wrapper = mountWithProviders(ProjectList)
    await flushPromises()
    expect(wrapper.text()).toContain('还没有项目')
  })

  it('FAILED 项目计入失败统计', async () => {
    vi.mocked(projectApi.listProjects).mockResolvedValue({
      total: 2,
      page: 1,
      size: 12,
      items: [
        makeProject({ id: 1, status: 'READY', healthScore: 90 }),
        makeProject({ id: 2, status: 'FAILED', healthScore: 40 }),
      ],
    })
    const wrapper = mountWithProviders(ProjectList)
    await flushPromises()
    // 平均分 (90+40)/2 = 65
    expect(wrapper.text()).toContain('65')
    // 失败计数 1
    expect(wrapper.text()).toContain('1')
  })

  it('接口失败时不谎报「还没有项目」，而是给出失败说明', async () => {
    vi.mocked(projectApi.listProjects).mockRejectedValue(FAIL)
    const wrapper = mountWithProviders(ProjectList)
    await flushPromises()
    const card = wrapper.find('.empty-state')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('项目列表加载失败')
    expect(card.text()).toContain('status code 404')
    // 关键：失败 ≠ 没有项目，不能把用户往「去建一个项目」的错误结论上引
    expect(wrapper.text()).not.toContain('还没有项目')
  })

  it('演示构建（VITE_DEMO=1）失败时说明「本页没有后端」', async () => {
    demoFlag.demo = true
    vi.mocked(projectApi.listProjects).mockRejectedValue(FAIL)
    const wrapper = mountWithProviders(ProjectList)
    await flushPromises()
    const card = wrapper.find('.empty-state')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('未连接后端')
    expect(card.text()).toContain('docker compose up')
    expect(wrapper.text()).not.toContain('还没有项目')
  })
})
