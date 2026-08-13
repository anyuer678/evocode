<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ECharts } from 'echarts/core'
import type { ECBasicOption } from 'echarts/types/dist/shared'
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
// 对比基准：默认最新两期（index 0 = 最新），点击折线点切换
const baseIndex = ref(1)

// 图表容器（回调 ref，避免字符串 ref 不回填）
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
  // 防御：容器已不在 DOM（组件重挂载）时清理僵尸实例
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
  // 后端已按 id 倒序，展示时正序（旧→新）
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
              lineStyle: { color: '#4caf50', type: 'dashed' },
              label: { formatter: '优良线 80' },
            },
            {
              yAxis: 60,
              lineStyle: { color: '#ff9800', type: 'dashed' },
              label: { formatter: '及格线 60' },
            },
          ],
        },
        itemStyle: {
          // 基准期高亮
          color: (p: { dataIndex: number }) =>
            baseId != null && asc[p.dataIndex].analysisId === baseId ? '#f57c00' : '#5b8def',
        },
      },
    ],
  })
  // 折线点点击 → 切换对比基准期
  const chart = trendEl.value ? charts.get(trendEl.value) : undefined
  chart?.off('click')
  chart?.on('click', (p: { dataIndex?: number }) => {
    if (p.dataIndex == null) return
    const idx = data.length - 1 - p.dataIndex // asc → items 索引
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
  // 维度：以当前期为准（缺基准期维度显示 null，条形留空）
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

/** 风险 diff：两期按 title 比较 → added/removed/kept */
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
    // 至少两期才展示对比；基准默认第二新（审查 L5：空列表时 baseIndex=0 避免 -1）
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
  <section class="report-history">
    <button class="history-toggle" type="button" @click="onExpand">
      <span class="caret" :class="{ open: expanded }">▶</span>
      历史趋势{{ items.length ? `（${items.length} 期）` : '' }}
    </button>

    <div v-if="expanded" class="history-body">
      <p v-if="loading" class="muted">历史报告加载中…</p>
      <p v-else-if="errorMsg" class="err">{{ errorMsg }}</p>
      <template v-else-if="items.length">
        <!-- 折线（含基准高亮） -->
        <div class="trend">
          <h4>健康分趋势</h4>
          <div :ref="setTrendRef" class="chart-box"></div>
          <p class="hint">
            点击折线点切换对比基准期（当前基准：{{ fmtDate(items[baseIndex]?.createdAt ?? '') }}）
          </p>
        </div>

        <!-- 两期对比 -->
        <div v-if="items.length >= 2" class="compare">
          <h4>
            最近两期对比（{{ fmtDate(items[baseIndex]?.createdAt ?? '') }} →
            {{ fmtDate(items[0]?.createdAt ?? '') }}）
          </h4>
          <div :ref="setCompareRef" class="chart-box"></div>

          <!-- 风险 diff -->
          <div
            v-if="riskDiff.added.length || riskDiff.removed.length || riskDiff.kept.length"
            class="risk-diff"
          >
            <div v-if="riskDiff.added.length" class="rd-added">
              <span class="rd-label">新增</span>
              <span v-for="(r, i) in riskDiff.added" :key="i" class="rd-item">{{ r.title }}</span>
            </div>
            <div v-if="riskDiff.removed.length" class="rd-removed">
              <span class="rd-label">已消失</span>
              <span v-for="(r, i) in riskDiff.removed" :key="i" class="rd-item">{{ r.title }}</span>
            </div>
            <div v-if="riskDiff.kept.length" class="rd-kept">
              <span class="rd-label">持续</span>
              <span v-for="(r, i) in riskDiff.kept" :key="i" class="rd-item">{{ r.title }}</span>
            </div>
          </div>
        </div>
      </template>
      <p v-else class="muted">暂无历史报告（至少一次完整分析后显示趋势）</p>
    </div>
  </section>
</template>

<style scoped>
.report-history {
  margin-bottom: 16px;
}
.history-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-sm, 6px);
  background: var(--bg-sub, #f8fafc);
  cursor: pointer;
  font-size: 13px;
  color: var(--text-primary, #1e293b);
}
.history-toggle:hover {
  border-color: var(--accent, #5b8def);
  color: var(--accent, #5b8def);
}
.caret {
  display: inline-block;
  transition: transform 0.2s;
  font-size: 10px;
}
.caret.open {
  transform: rotate(90deg);
}
.history-body {
  margin-top: 12px;
  padding: 14px;
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-md, 10px);
  background: var(--bg-card, #fff);
}
.trend,
.compare {
  margin-bottom: 16px;
}
.chart-box {
  width: 100%;
  height: 220px;
}
.hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-muted, #64748b);
}
.risk-diff {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
}
.risk-diff > div {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
}
.rd-label {
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 600;
}
.rd-item {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
}
.rd-added .rd-label {
  color: #d32f2f;
}
.rd-added .rd-item {
  background: #fdecea;
  color: #d32f2f;
}
.rd-removed .rd-label {
  color: #2e7d32;
}
.rd-removed .rd-item {
  background: #e8f5e9;
  color: #2e7d32;
}
.rd-kept .rd-label {
  color: #b26a00;
}
.rd-kept .rd-item {
  background: #fff8e1;
  color: #b26a00;
}
.err {
  color: #d32f2f;
  font-size: 13px;
}
</style>
