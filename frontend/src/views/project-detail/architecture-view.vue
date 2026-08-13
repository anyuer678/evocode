<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ComposeOption, ECharts } from 'echarts/core'
import type { GraphSeriesOption } from 'echarts/charts'
import type { TooltipComponentOption } from 'echarts/components'
import { fetchArchitecture } from '../../api/architecture'
import type { ArchitectureNode, ArchitectureResult, ArchitectureViolation } from '../../types/api'

echarts.use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

type ECOption = ComposeOption<GraphSeriesOption | TooltipComponentOption>

/** 审查 X1：ECharts tooltip 默认 HTML 渲染，节点名/文件路径来自被分析仓库，须转义 */
function escapeHtml(s: unknown): string {
  return String(s ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c,
  )
}

const props = defineProps<{ projectId: number }>()

const arch = ref<ArchitectureResult | null>(null)
const loading = ref(true)
const error = ref('')
const empty = ref(false)
const activeViolation = ref<ArchitectureViolation | null>(null)

let chart: ECharts | null = null
const chartEl = ref<HTMLDivElement | null>(null)
/** 回调 ref（字符串 ref 在 <script setup> 中不会回填 .value，实测踩坑） */
function setChartEl(el: Element | ComponentPublicInstance | null): void {
  chartEl.value = (el as HTMLDivElement | null) ?? null
}

const LAYER: Record<ArchitectureNode['nodeType'], number> = {
  CONTROLLER: 0,
  SERVICE: 1,
  REPOSITORY: 2,
  ENTITY: 3,
  UTIL: 4,
  OTHER: 5,
}
const TYPE_COLOR: Record<ArchitectureNode['nodeType'], string> = {
  CONTROLLER: '#4f7cff',
  SERVICE: '#38b26d',
  REPOSITORY: '#f0a53c',
  ENTITY: '#9b6bf0',
  UTIL: '#5aa7c9',
  OTHER: '#9aa4b2',
}
const SEV_META: Record<ArchitectureViolation['severity'], { label: string; cls: string }> = {
  HIGH: { label: '高', cls: 'high' },
  MEDIUM: { label: '中', cls: 'medium' },
  LOW: { label: '低', cls: 'low' },
}

function buildOption(data: ArchitectureResult, width: number): ECOption {
  const byLayer: ArchitectureNode[][] = [[], [], [], [], [], []]
  for (const n of data.nodes) byLayer[LAYER[n.nodeType] ?? 5].push(n)

  const xGap = 190
  const layerY = [70, 210, 350, 490, 630, 770]
  const graphNodes = data.nodes.map((n) => {
    const layer = LAYER[n.nodeType] ?? 5
    const group = byLayer[layer]
    const idx = group.indexOf(n)
    const x = width / 2 + (idx - (group.length - 1) / 2) * xGap
    return {
      id: String(n.id),
      name: n.name,
      category: n.nodeType,
      x,
      y: layerY[layer],
      symbolSize: 44,
      outDegree: n.metrics.outDegree,
      inDegree: n.metrics.inDegree,
      filePath: n.filePath,
    }
  })

  const vioPair = new Set(
    data.violations
      .filter((v) => v.sourceNodeId != null && v.targetNodeId != null)
      .map((v) => `${v.sourceNodeId}-${v.targetNodeId}`),
  )
  const activePair = activeViolation.value
    ? `${activeViolation.value.sourceNodeId}-${activeViolation.value.targetNodeId}`
    : null

  const graphEdges = data.edges.map((e) => {
    const pair = `${e.sourceNodeId}-${e.targetNodeId}`
    const isViolation = vioPair.has(pair)
    const isActive = pair === activePair
    return {
      id: String(e.id),
      source: e.sourceNodeId,
      target: e.targetNodeId,
      lineStyle: {
        color: isActive ? '#e5484d' : isViolation ? '#f28b82' : '#c3cad6',
        width: isActive ? 3.5 : isViolation ? 2.2 : 1.4,
        curveness: 0.08,
      },
    }
  })

  return {
    tooltip: {
      trigger: 'item',
      formatter: (p: unknown) => {
        const d = p as { dataType?: string; data?: Record<string, unknown>; name?: string }
        if (d.dataType !== 'edge') {
          const n = d.data ?? {}
          return `<b>${escapeHtml(n.name ?? d.name)}</b><br/>类型：${escapeHtml(n.category ?? '')}<br/>出度：${escapeHtml(n.outDegree ?? 0)} / 入度：${escapeHtml(n.inDegree ?? 0)}<br/><span style="color:#888">${escapeHtml(n.filePath ?? '')}</span>`
        }
        return escapeHtml(d.name ?? '')
      },
    },
    legend: {
      bottom: 0,
      data: Object.keys(TYPE_COLOR),
      textStyle: { fontSize: 11, color: 'var(--text-secondary)' },
    },
    series: [
      {
        type: 'graph',
        layout: 'none',
        roam: true,
        data: graphNodes,
        edges: graphEdges,
        categories: Object.keys(TYPE_COLOR).map((name) => ({
          name,
          itemStyle: { color: TYPE_COLOR[name as ArchitectureNode['nodeType']] },
        })),
        label: {
          show: true,
          fontSize: 11,
          color: '#1f2937',
          formatter: (p: unknown) => (p as { data?: { name?: string } }).data?.name ?? '',
        },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 8,
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3 },
        },
      },
    ],
  }
}

function render(data: ArchitectureResult): void {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  chart.setOption(buildOption(data, chartEl.value.clientWidth), { notMerge: true })
}

function onResize(): void {
  chart?.resize()
}

function onViolationClick(v: ArchitectureViolation): void {
  activeViolation.value = activeViolation.value?.id === v.id ? null : v
  if (chart && arch.value) render(arch.value)
}

// 架构数据到位后渲染图表。用 watch + flush:'post' 而非 onMounted 里 await nextTick：
// 实测 nextTick 在异步 ref 回填前提前 resolve，chartEl.value 为 null 导致 render 静默跳过。
watch(
  () => arch.value,
  (val) => {
    if (!val || !val.nodes.length) return
    render(val)
  },
  { flush: 'post' },
)

onMounted(async () => {
  window.addEventListener('resize', onResize)
  try {
    const data = await fetchArchitecture(props.projectId)
    arch.value = data
  } catch (e) {
    const code = (e as { code?: number }).code
    if (code === 2010) empty.value = true
    else error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="arch">
    <div class="arch-head">
      <h2>架构分析</h2>
      <span v-if="arch && arch.violations.length" class="arch-badge warn">
        {{ arch.violations.length }} 处违规
      </span>
      <span v-else-if="arch" class="arch-badge ok">无违规</span>
    </div>

    <div v-if="loading" class="arch-state">架构分析加载中…</div>
    <div v-else-if="error" class="arch-state fail">{{ error }}</div>
    <div v-else-if="empty" class="arch-state">
      该项目尚无架构分析，发起一次完整分析后即可查看架构视图。
    </div>

    <template v-else-if="arch">
      <div v-if="arch.nodes.length" :ref="setChartEl" class="arch-canvas"></div>
      <p v-else class="arch-empty">架构分析完成，未发现代码单元。</p>

      <div v-if="arch.violations.length" class="arch-violations">
        <h3>架构违规</h3>
        <table class="table">
          <thead>
            <tr>
              <th>严重度</th>
              <th>类型</th>
              <th>描述</th>
              <th>建议</th>
              <th>AI 解读</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="v in arch.violations"
              :key="v.id"
              :class="{ active: activeViolation?.id === v.id }"
              @click="onViolationClick(v)"
            >
              <td>
                <span class="sev" :class="SEV_META[v.severity].cls">
                  {{ SEV_META[v.severity].label }}
                </span>
              </td>
              <td>
                <span class="tag">{{ v.violationType }}</span>
              </td>
              <td class="q-msg">{{ v.description }}</td>
              <td class="muted">{{ v.suggestion }}</td>
              <td class="muted">{{ v.aiNote || '待填充' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.arch-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 4px 0 10px;
}
.arch-head h2 {
  font-size: 16px;
  margin: 0;
}
.arch-badge {
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 12px;
}
.arch-badge.warn {
  color: var(--fail-color);
  background: rgba(229, 72, 77, 0.1);
}
.arch-badge.ok {
  color: var(--ok-color);
  background: rgba(22, 163, 74, 0.1);
}
.arch-state {
  padding: 28px 0;
  text-align: center;
  color: var(--text-secondary);
}
.arch-state.fail {
  color: var(--fail-color);
}
.arch-canvas {
  width: 100%;
  height: 440px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  background: var(--bg-card);
}
.arch-empty {
  color: var(--text-secondary);
  text-align: center;
  padding: 24px 0;
}
.arch-violations {
  margin-top: 18px;
}
.arch-violations h3 {
  font-size: 14px;
  margin: 0 0 8px;
}
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.table th {
  text-align: left;
  padding: 6px 8px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-color, #e5e7eb);
  font-weight: 500;
}
.table td {
  padding: 7px 8px;
  border-bottom: 1px solid var(--border-color, #e5e7eb);
  vertical-align: top;
}
.table tbody tr {
  cursor: pointer;
  transition: background 0.15s;
}
.table tbody tr:hover {
  background: rgba(79, 124, 255, 0.05);
}
.table tbody tr.active {
  background: rgba(229, 72, 77, 0.08);
}
.sev {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 9px;
  font-size: 12px;
}
.sev.high {
  color: var(--fail-color);
  background: rgba(229, 72, 77, 0.1);
}
.sev.medium {
  color: #d97706;
  background: rgba(217, 119, 6, 0.1);
}
.sev.low {
  color: var(--text-secondary);
  background: rgba(107, 114, 128, 0.12);
}
.tag {
  font-size: 12px;
  color: var(--text-secondary);
  border: 1px solid var(--border-color, #e5e7eb);
  padding: 1px 6px;
  border-radius: 4px;
}
.q-msg {
  min-width: 220px;
}
.muted {
  color: var(--text-secondary);
}
</style>
