<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ECharts } from 'echarts/core'
import type { ECBasicOption } from 'echarts/types/dist/shared'
import { NAlert, NButton, NCard, NEmpty, NSpin, NTag } from 'naive-ui'
import { getReportHistory } from '../../api/analysis'
import type { ReportHistoryItem } from '../../types/api'

echarts.use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  MarkLineComponent,
  CanvasRenderer,
])

const props = defineProps<{ projectId: number }>()

const DIM_LABEL: Record<string, string> = {
  quality: '质量',
  structure: '结构',
  dependency: '依赖',
  scale: '规模',
}

const expanded = ref(false)
const loading = ref(true)
const errorMsg = ref('')
const items = ref<ReportHistoryItem[]>([])
const baseIndex = ref(1)

const trendEl = ref<HTMLDivElement | null>(null)
function setTrendRef(el: Element | ComponentPublicInstance | null): void {
  trendEl.value = (el as HTMLDivElement | null) ?? null
}
const compareEl = ref<HTMLDivElement | null>(null)
function setCompareRef(el: Element | ComponentPublicInstance | null): void {
  compareEl.value = (el as HTMLDivElement | null) ?? null
}

const charts = new Map<HTMLDivElement, ECharts>()

function renderChart(holder: { value: HTMLDivElement | null }, option: ECBasicOption): void {
  const el = holder.value
  if (!el) return
  for (const [key, c] of charts) {
    if (!key.isConnected) {
      c.dispose()
      charts.delete(key)
    }
  }
  let chart = charts.get(el)
  if (!chart) {
    chart = echarts.init(el)
    charts.set(el, chart)
  }
  chart.setOption(option, { notMerge: true })
}

function renderTrend(): void {
  const data = items.value
  const asc = [...data].reverse()
  const base = baseIndex.value
  const baseId = base < data.length ? data[base].analysisId : undefined
  renderChart(trendEl, {
    tooltip: { trigger: 'axis' },
    grid: { left: 44, right: 24, top: 28, bottom: 28 },
    xAxis: {
      type: 'category',
      data: asc.map((d) => fmtDate(d.createdAt)),
      axisLabel: { rotate: 30 },
    },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: [
      {
        name: '健康分',
        type: 'line',
        smooth: true,
        data: asc.map((d) => d.healthScore ?? null),
        connectNulls: false,
        markLine: {
          silent: true,
          symbol: 'none',
          data: [
            {
              yAxis: 80,
              lineStyle: { color: '#3fae4f', type: 'dashed' },
              label: { formatter: '优良线 80' },
            },
            {
              yAxis: 60,
              lineStyle: { color: '#e8890c', type: 'dashed' },
              label: { formatter: '及格线 60' },
            },
          ],
        },
        itemStyle: {
          color: (p: { dataIndex: number }) =>
            baseId != null && asc[p.dataIndex].analysisId === baseId ? '#e8890c' : '#1668dc',
        },
      },
    ],
  })
  const chart = trendEl.value ? charts.get(trendEl.value) : undefined
  chart?.off('click')
  chart?.on('click', (p: { dataIndex?: number }) => {
    if (p.dataIndex == null) return
    const idx = data.length - 1 - p.dataIndex
    if (idx > 0) {
      baseIndex.value = idx
    }
  })
}

function renderCompare(): void {
  const data = items.value
  const cur = data[0]
  const base = baseIndex.value < data.length ? data[baseIndex.value] : data[1]
  if (!cur || !base || cur.analysisId === base.analysisId) {
    return
  }
  const dims = cur.dimensions.map((d) => d.key)
  const baseScores = new Map(base.dimensions.map((d) => [d.key, d.score]))
  renderChart(compareEl, {
    tooltip: { trigger: 'axis' },
    legend: { data: [fmtDate(base.createdAt), fmtDate(cur.createdAt)], top: 0 },
    grid: { left: 44, right: 24, top: 32, bottom: 28 },
    xAxis: { type: 'category', data: dims.map((k) => DIM_LABEL[k] ?? k) },
    yAxis: { type: 'value', min: 0, max: 100 },
    series: [
      {
        name: fmtDate(base.createdAt),
        type: 'bar',
        data: dims.map((k) => baseScores.get(k) ?? null),
      },
      {
        name: fmtDate(cur.createdAt),
        type: 'bar',
        data: cur.dimensions.map((d) => d.score),
      },
    ],
  })
}

function fmtDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())}`
}

const riskDiff = computed(() => {
  const data = items.value
  if (data.length < 2) return { added: [], removed: [], kept: [] }
  const cur = data[0]
  const base = baseIndex.value < data.length ? data[baseIndex.value] : data[1]
  if (!cur || !base || cur.analysisId === base.analysisId) {
    return { added: [], removed: [], kept: [] }
  }
  const curTitles = new Set(cur.risks.map((r) => r.title))
  const baseTitles = new Set(base.risks.map((r) => r.title))
  const added = cur.risks.filter((r) => !baseTitles.has(r.title))
  const removed = base.risks.filter((r) => !curTitles.has(r.title))
  const kept = cur.risks.filter((r) => baseTitles.has(r.title))
  return { added, removed, kept }
})

async function load(): Promise<void> {
  if (!expanded.value || items.value.length) return
  loading.value = true
  errorMsg.value = ''
  try {
    items.value = await getReportHistory(props.projectId, 20)
    baseIndex.value = items.value.length ? Math.min(1, items.value.length - 1) : 0
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function onExpand(): void {
  expanded.value = !expanded.value
  if (expanded.value) void load()
}

watch(
  () => [items.value, baseIndex.value, expanded.value],
  () => {
    if (!expanded.value) return
    if (trendEl.value) renderTrend()
    if (compareEl.value) renderCompare()
  },
  { flush: 'post' },
)

onMounted(() => {
  if (expanded.value) void load()
})
onBeforeUnmount(() => {
  for (const [, c] of charts) c.dispose()
  charts.clear()
})
</script>

<template>
  <NCard size="small" class="report-history">
    <template #header>
      <div class="history-head">
        <span>历史趋势{{ items.length ? `（${items.length} 期）` : '' }}</span>
        <NButton size="tiny" quaternary @click="onExpand">
          {{ expanded ? '收起' : '展开' }}
        </NButton>
      </div>
    </template>

    <div v-if="expanded" class="history-body">
      <NSpin :show="loading">
        <NAlert v-if="errorMsg" type="error" :show-icon="true">{{ errorMsg }}</NAlert>
        <NEmpty
          v-else-if="!items.length"
          description="暂无历史报告（至少一次完整分析后显示趋势）"
        />
        <template v-else>
          <div class="trend">
            <h4>健康分趋势</h4>
            <div :ref="setTrendRef" class="chart-box"></div>
            <p class="hint">
              点击折线点切换对比基准期（当前基准：{{ fmtDate(items[baseIndex]?.createdAt ?? '') }}）
            </p>
          </div>

          <div v-if="items.length >= 2" class="compare">
            <h4>
              最近两期对比（{{ fmtDate(items[baseIndex]?.createdAt ?? '') }} →
              {{ fmtDate(items[0]?.createdAt ?? '') }}）
            </h4>
            <div :ref="setCompareRef" class="chart-box"></div>

            <div
              v-if="riskDiff.added.length || riskDiff.removed.length || riskDiff.kept.length"
              class="risk-diff"
            >
              <div v-if="riskDiff.added.length" class="rd-row">
                <span class="rd-label">新增</span>
                <NTag v-for="(r, i) in riskDiff.added" :key="i" size="small" type="error" bordered>
                  {{ r.title }}
                </NTag>
              </div>
              <div v-if="riskDiff.removed.length" class="rd-row">
                <span class="rd-label">已消失</span>
                <NTag v-for="(r, i) in riskDiff.removed" :key="i" size="small" bordered>
                  {{ r.title }}
                </NTag>
              </div>
              <div v-if="riskDiff.kept.length" class="rd-row">
                <span class="rd-label">持续</span>
                <NTag v-for="(r, i) in riskDiff.kept" :key="i" size="small" type="warning" bordered>
                  {{ r.title }}
                </NTag>
              </div>
            </div>
          </div>
        </template>
      </NSpin>
    </div>
  </NCard>
</template>

<style scoped>
.report-history {
  margin-bottom: 4px;
}
.history-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.history-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.trend h4,
.compare h4 {
  margin: 0 0 8px;
  font-size: 13.5px;
  font-weight: 600;
}
.chart-box {
  height: 220px;
}
.hint {
  margin: 6px 0 0;
  font-size: 12px;
  color: #8798ab;
}
.risk-diff {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}
.rd-row {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.rd-label {
  font-size: 12px;
  color: #55667a;
  width: 44px;
  flex-shrink: 0;
}
</style>
