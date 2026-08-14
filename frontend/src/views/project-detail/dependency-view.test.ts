import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DependencyView from './dependency-view.vue'
import * as depApi from '../../api/dependency'
import type { DependencyItem, DependencyResult } from '../../types/api'

/**
 * dependency-view 组件测试：
 * - 渲染依赖列表
 * - 无依赖时显示空态
 * - 统计（总数/EOL/高风险）
 */

vi.mock('../../api/dependency', () => ({
  fetchDependencies: vi.fn(),
}))

const shallowStubs = {
  NCard: { template: '<div><slot /><slot name="header" /></div>' },
  NAlert: { template: '<div class="n-alert"><slot /></div>' },
  NEmpty: { template: '<div class="n-empty"><slot /></div>' },
  NSpin: { template: '<div><slot /></div>' },
  NDataTable: { template: '<div class="n-data-table"><slot /></div>' },
  NStatistic: { template: '<div class="n-statistic"><slot /></div>' },
  NTag: { template: '<span class="n-tag"><slot /></span>' },
}

function makeDep(over: Partial<DependencyItem> = {}): DependencyItem {
  return {
    name: 'spring-boot',
    version: '2.5.14',
    type: 'MAVEN',
    file: null,
    risk: 'HIGH',
    isEol: true,
    reason: 'Spring Boot 2.5 已 EOL',
    latest: '3.2+',
    suggestion: '升级到 3.x（当前版本已停止支持）',
    ...over,
  }
}

describe('DependencyView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('渲染依赖统计（总/EOL/高风险）', async () => {
    vi.mocked(depApi.fetchDependencies).mockResolvedValue({
      available: true,
      dependencies: [
        makeDep({ name: 'a', risk: 'HIGH', isEol: true }),
        makeDep({ name: 'b', risk: 'MEDIUM', isEol: false }),
        makeDep({ name: 'c', risk: 'LOW', isEol: false }),
      ],
    } as DependencyResult)
    const wrapper = mount(DependencyView, {
      props: { projectId: 1 },
      global: { stubs: shallowStubs },
    })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('总依赖')
    expect(text).toContain('EOL 依赖')
    expect(text).toContain('高风险')
    // 审查修复：建议列渲染 suggestion
    expect(text).toContain('升级到 3.x（当前版本已停止支持）')
  })

  it('无依赖时显示空态', async () => {
    vi.mocked(depApi.fetchDependencies).mockResolvedValue({
      available: false,
      dependencies: [],
    })
    const wrapper = mount(DependencyView, {
      props: { projectId: 1 },
      global: { stubs: shallowStubs },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('未检测到')
  })
})
