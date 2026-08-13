<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, type ComponentPublicInstance } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ComposeOption, ECharts } from 'echarts/core'
import type { BarSeriesOption, LineSeriesOption, PieSeriesOption } from 'echarts/charts'
import type {
  GridComponentOption,
  LegendComponentOption,
  TooltipComponentOption,
} from 'echarts/components'
import { NCard, NEmpty, NRadioButton, NRadioGroup, NSpin, NTag } from 'naive-ui'
import { fetchEvolution } from '../../api/evolution'
import type {
  EvolutionAuthor,
  EvolutionResult,
  EvolutionTopFile,
  EvolutionTrend,
} from '../../types/api'

echarts.use([
  LineChart,
  BarChart,
  PieChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  CanvasRenderer,
])

type ECOption = ComposeOption<
  | LineSeriesOption
  | BarSeriesOption
  | PieSeriesOption
  | GridComponentOption
  | TooltipComponentOption
  | LegendComponentOption
>

/** 审查 X1：ECharts tooltip 默认 HTML 渲染，作者名/文件路径来自被分析仓库，须转义 */
function escapeHtml(s: unknown): string {
  return String(s ?? '').replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] ?? c,
  )
}

const props = defineProps<{ projectId: number }>()

const RANGES = [
  { key: '30d', label: '近 30 天' },
  { key: '90d', label: '近 90 天' },
  { key: '180d', label: '近 180 天' },
  { key: 'all', label: '全部' },
] as const

const loading = ref(true)
const errorMsg = ref('')
const data = ref<EvolutionResult | null>(null)
const range = ref('30d')

// 三个图容器（回调 ref：P4c 实测字符串 ref 不回填 .value）
const trendEl = ref<HTMLDivElement | null>(null)
const filesEl = ref<HTMLDivElement | null>(null)
const authorsEl = ref<HTMLDivElement | null>(null)
function setTrendRef(el: Element | ComponentPublicInstance | null): void {
  trendEl.value = (el as HTMLDivElement | null) ?? null
}
function setFilesRef(el: Element | ComponentPublicInstance | null): void {
  filesEl.value = (el as HTMLDivElement | null) ?? null
}
function setAuthorsRef(el: Element | ComponentPublicInstance | null): void {
  authorsEl.value = (el as HTMLDivElement | null) ?? null
}

const charts = new Map<HTMLDivElement, ECharts>()

function renderChart(holder: typeof trendEl, option: ECOption): void {
  const el = holder.value
  if (!el) return
  // 防御：容器已不在 DOM（三态切换重建）时清理僵尸实例
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

function buildTrendOption(trend: EvolutionTrend[]): ECOption {
  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: unknown) =>
        escapeHtml((params as Array<{ axisValue?: unknown }>)[0]?.axisValue ?? ''),
    },
    legend: { data: ['提交数', '新增行数'], top: 0 },
    grid: { left: 50, right: 50, top: 32, bottom: 28 },
    xAxis: { type: 'category', data: trend.map((t) => t.week.slice(0, 7)) },
    yAxis: [
      { type: 'value', name: '提交', minInterval: 1 },
      { type: 'value', name: '行', splitLine: { show: false } },
    ],
    series: [
      {
        name: '提交数',
        type: 'line',
        smooth: true,
        data: trend.map((t) => t.commits),
        areaStyle: { opacity: 0.12 },
      },
      {
        name: '新增行数',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        data: trend.map((t) => t.linesAdded),
      },
    ],
  }
}

function buildFilesOption(files: EvolutionTopFile[]): ECOption {
  const top = files.slice(0, 8)
  return {
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: unknown) => {
        const ps = params as Array<{ axisValue?: unknown; value?: unknown; seriesName?: string }>
        const first = ps[0]
        if (first?.axisValue == null) return ''
        return `<b>${escapeHtml(first.axisValue)}</b><br/>${escapeHtml(first.seriesName ?? '')}：${escapeHtml(first.value ?? '')}`
      },
    },
    grid: { left: 10, right: 40, top: 10, bottom: 8, containLabel: true },
    xAxis: { type: 'value', minInterval: 1 },
    yAxis: {
      type: 'category',
      data: top.map((f) => f.filePath.split(/[\\/]/).pop() || f.filePath).reverse(),
      axisLabel: { width: 160, overflow: 'truncate' },
    },
    series: [
      {
        name: '变更次数',
        type: 'bar',
        data: top.map((f) => f.commitCount).reverse(),
        itemStyle: { color: '#3b82f6', borderRadius: [0, 4, 4, 0] },
      },
    ],
  }
}

function buildAuthorsOption(authors: EvolutionAuthor[]): ECOption {
  return {
    tooltip: {
      trigger: 'item',
      formatter: (p: unknown) => {
        const d = p as { name?: unknown; value?: unknown; percent?: unknown }
        return `${escapeHtml(d.name ?? '')}: ${String(d.value ?? 0)} 次提交 (${String(d.percent ?? 0)}%)`
      },
    },
    legend: { type: 'scroll', bottom: 0 },
    series: [
      {
        name: '作者',
        type: 'pie',
        radius: ['38%', '64%'],
        center: ['50%', '46%'],
        data: authors.map((a) => ({ name: a.authorName, value: a.commits })),
        label: { formatter: '{b}\n{c} 次' },
      },
    ],
  }
}

function renderAll(): void {
  const d = data.value
  if (!d || !d.available) return
  renderChart(trendEl, buildTrendOption(d.trend))
  renderChart(filesEl, buildFilesOption(d.topFiles))
  renderChart(authorsEl, buildAuthorsOption(d.authors))
}

// 数据到位后统一渲染（flush:'post' 保证 DOM 已更新、回调 ref 已回填）
watch(
  () => data.value,
  () => renderAll(),
  { flush: 'post' },
)

// resize 用 rAF 防抖，避免高频触发时对三个图各 resize 一次；跳过隐藏（v-show=false）容器防缩零
let resizeRaf = 0
function onResize(): void {
  cancelAnimationFrame(resizeRaf)
  resizeRaf = requestAnimationFrame(() => {
    charts.forEach((c) => {
      const el = c.getDom()
      if (el.isConnected && el.offsetParent !== null) c.resize()
    })
  })
}

// 请求序号守卫：快速切换 range 时丢弃过期响应，避免旧数据覆盖新数据
let loadSeq = 0
async function load(): Promise<void> {
  const seq = ++loadSeq
  loading.value = true
  errorMsg.value = ''
  try {
    const d = await fetchEvolution(props.projectId, range.value)
    if (seq !== loadSeq) return
    data.value = d
  } catch (e) {
    if (seq !== loadSeq) return
    errorMsg.value = e instanceof Error ? e.message : '演化统计加载失败'
    data.value = null
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

watch(range, () => void load())

onMounted(() => {
  window.addEventListener('resize', onResize)
  void load()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  charts.forEach((c) => c.dispose())
  charts.clear()
})
</script>
<template>
  <NCard size="small" class="ev">
    <template #header>
      <div class="ev-head">
        <span class="ev-title">演化分析</span>
        <NTag v-if="data?.available" size="small" type="success" bordered>git 历史</NTag>
        <NRadioGroup v-model:value="range" size="small" class="ev-range">
          <NRadioButton v-for="r in RANGES" :key="r.key" :value="r.key" :label="r.label" />
        </NRadioGroup>
      </div>
    </template>

    <NSpin :show="loading">
      <NAlert v-if="errorMsg" type="error" :show-icon="true">{{ errorMsg }}</NAlert>
      <NEmpty
        v-else-if="!data || !data.available"
        description="该项目非 Git 来源或无提交历史，暂无演化数据。"
      />
      <div v-show="!loading && !errorMsg && data?.available" class="ev-grid">
        <NCard size="small" class="ev-card ev-card-wide">
          <template #header><span class="ev-card-title">提交趋势</span></template>
          <div :ref="setTrendRef" class="ev-canvas"></div>
        </NCard>
        <NCard size="small" class="ev-card">
          <template #header><span class="ev-card-title">TOP 变更文件</span></template>
          <div :ref="setFilesRef" class="ev-canvas"></div>
        </NCard>
        <NCard size="small" class="ev-card">
          <template #header><span class="ev-card-title">作者提交占比</span></template>
          <div :ref="setAuthorsRef" class="ev-canvas"></div>
        </NCard>
        <NCard size="small" class="ev-card ev-card-wide">
          <template #header><span class="ev-card-title">风险中心</span></template>
          <div v-if="data?.hotspots.length" class="ev-hotspots">
            <div
              v-for="h in data?.hotspots ?? []"
              :key="h.module"
              class="ev-hotspot"
              :class="'lv-' + h.riskLevel.toLowerCase()"
            >
              <div class="ev-hotspot-head">
                <span class="ev-hotspot-module">{{ h.module }}</span>
                <NTag
                  size="small"
                  bordered
                  :type="
                    h.riskLevel === 'HIGH'
                      ? 'error'
                      : h.riskLevel === 'MEDIUM'
                        ? 'warning'
                        : 'default'
                  "
                >
                  {{ h.riskLevel }}
                </NTag>
              </div>
              <ul class="ev-hotspot-evidence">
                <li v-for="(e, i) in h.evidence" :key="i">{{ e }}</li>
              </ul>
              <p v-if="h.aiConclusion" class="ev-hotspot-ai">{{ h.aiConclusion }}</p>
            </div>
          </div>
          <NEmpty v-else description="当前范围内未发现风险热点。" />
        </NCard>
      </div>
    </NSpin>
  </NCard>
</template>
<style scoped>
.ev {
  background: #fff;
}
.ev-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.ev-title {
  font-size: 15px;
  font-weight: 600;
}
.ev-range {
  margin-left: auto;
}
.ev-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.ev-card {
  background: #fff;
}
.ev-card-wide {
  grid-column: 1 / -1;
}
.ev-card-title {
  font-size: 13px;
  font-weight: 600;
}
.ev-canvas {
  height: 240px;
}
.ev-hotspots {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ev-hotspot {
  padding: 10px 12px;
  border: 1px solid #eef1f5;
  border-left: 3px solid #c2ccd8;
  border-radius: 4px;
}
.ev-hotspot.lv-high {
  border-left-color: #d64545;
}
.ev-hotspot.lv-medium {
  border-left-color: #e8890c;
}
.ev-hotspot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.ev-hotspot-module {
  font-size: 13px;
  font-weight: 600;
}
.ev-hotspot-evidence {
  margin: 6px 0 0;
  padding-left: 18px;
  font-size: 12.5px;
  color: #55667a;
  line-height: 1.7;
}
.ev-hotspot-ai {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #1668dc;
}

@media (max-width: 900px) {
  .ev-grid {
    grid-template-columns: 1fr;
  }
}
</style>
