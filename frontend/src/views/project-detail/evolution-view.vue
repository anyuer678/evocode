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
    tooltip: { trigger: 'axis' },
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
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
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
    tooltip: { trigger: 'item', formatter: '{b}: {c} 次提交 ({d}%)' },
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
  <section class="ev">
    <div class="ev-head">
      <span class="ev-title">演化分析</span>
      <span v-if="data?.available" class="ev-badge ok">git 历史</span>
      <div class="ev-range">
        <button
          v-for="r in RANGES"
          :key="r.key"
          class="ev-range-btn"
          :class="{ active: range === r.key }"
          :aria-pressed="range === r.key"
          @click="range = r.key"
        >
          {{ r.label }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="ev-state" role="status">演化统计加载中…</div>
    <div v-else-if="errorMsg" class="ev-state fail" role="status">{{ errorMsg }}</div>
    <div v-else-if="!data || !data.available" class="ev-state" role="status">
      该项目非 Git 来源或无提交历史，暂无演化数据。
    </div>
    <!-- 图表容器常驻（v-show 而非 v-if），避免三态切换重建 DOM 导致 ECharts 实例泄漏 -->
    <div v-show="!loading && !errorMsg && data?.available" class="ev-grid">
      <div class="ev-card ev-card-wide">
        <h4 class="ev-card-title">提交趋势</h4>
        <div :ref="setTrendRef" class="ev-canvas"></div>
      </div>
      <div class="ev-card">
        <h4 class="ev-card-title">TOP 变更文件</h4>
        <div :ref="setFilesRef" class="ev-canvas"></div>
      </div>
      <div class="ev-card">
        <h4 class="ev-card-title">作者提交占比</h4>
        <div :ref="setAuthorsRef" class="ev-canvas"></div>
      </div>
      <div class="ev-card ev-card-wide">
        <h4 class="ev-card-title">风险中心</h4>
        <div v-if="data?.hotspots.length" class="ev-hotspots">
          <div
            v-for="h in data?.hotspots ?? []"
            :key="h.module"
            class="ev-hotspot"
            :class="[
              'lv-' + h.riskLevel.toLowerCase(),
              !['high', 'medium'].includes(h.riskLevel.toLowerCase()) ? 'lv-medium' : '',
            ]"
          >
            <div class="ev-hotspot-head">
              <span class="ev-hotspot-module">{{ h.module }}</span>
              <span class="ev-hotspot-badge">{{ h.riskLevel }}</span>
            </div>
            <ul class="ev-hotspot-evidence">
              <li v-for="(e, i) in h.evidence" :key="i">{{ e }}</li>
            </ul>
            <p v-if="h.aiConclusion" class="ev-hotspot-ai">{{ h.aiConclusion }}</p>
          </div>
        </div>
        <div v-else class="ev-state small">当前范围内未发现风险热点。</div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.ev {
  margin-top: 20px;
  padding: 16px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  background: var(--bg-card, #fff);
}

.ev-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.ev-title {
  font-weight: 600;
  font-size: 15px;
}

.ev-badge.ok {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  background: rgba(16, 185, 129, 0.12);
  color: #10b981;
}

.ev-range {
  margin-left: auto;
  display: flex;
  gap: 6px;
}

.ev-range-btn {
  padding: 3px 10px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 12px;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
  color: var(--text-secondary, #6b7280);
}

.ev-range-btn.active {
  background: var(--ok-color, #10b981);
  border-color: var(--ok-color, #10b981);
  color: #fff;
}

.ev-state {
  padding: 28px 12px;
  text-align: center;
  color: var(--text-secondary, #6b7280);
  font-size: 13px;
}

.ev-state.fail {
  color: var(--fail-color, #ef4444);
}

.ev-state.small {
  padding: 16px 8px;
}

.ev-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.ev-card {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  padding: 10px 12px;
  min-width: 0;
}

.ev-card-wide {
  grid-column: 1 / -1;
}

.ev-card-title {
  margin: 0 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary, #6b7280);
}

.ev-canvas {
  width: 100%;
  height: 240px;
}

.ev-hotspots {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
}

.ev-hotspot {
  border: 1px solid var(--border-color, #e5e7eb);
  border-left-width: 4px;
  border-radius: 8px;
  padding: 10px 12px;
}

.ev-hotspot.lv-high {
  border-left-color: #ef4444;
}

.ev-hotspot.lv-medium {
  border-left-color: #f59e0b;
}

.ev-hotspot-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ev-hotspot-module {
  font-weight: 600;
  font-size: 13px;
  word-break: break-all;
}

.ev-hotspot-badge {
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 11px;
  color: #fff;
}

.ev-hotspot.lv-high .ev-hotspot-badge {
  background: #ef4444;
}

.ev-hotspot.lv-medium .ev-hotspot-badge {
  background: #f59e0b;
}

.ev-hotspot-evidence {
  margin: 8px 0 0;
  padding-left: 16px;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.ev-hotspot-evidence li {
  margin: 2px 0;
}

.ev-hotspot-ai {
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: rgba(59, 130, 246, 0.08);
  font-size: 12px;
  line-height: 1.5;
}

@media (max-width: 900px) {
  .ev-grid {
    grid-template-columns: 1fr;
  }
}
</style>
