<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ECharts } from 'echarts/core'
import { NButton, NCard, NEmpty, NList, NListItem, NStatistic } from 'naive-ui'
import { listProjects } from '../../api/project'
import type { ProjectSummary } from '../../types/api'

echarts.use([
  BarChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

const router = useRouter()
const projects = ref<ProjectSummary[]>([])
const healthEl = ref<HTMLElement | null>(null)
const langEl = ref<HTMLElement | null>(null)
const statusEl = ref<HTMLElement | null>(null)
const charts: ECharts[] = []
let resizeRaf = 0

const avgHealth = computed(() => {
  const scored = projects.value.filter((p) => p.healthScore != null)
  if (!scored.length) return 0
  return scored.reduce((s, p) => s + (p.healthScore ?? 0), 0) / scored.length
})
const readyCount = computed(() => projects.value.filter((p) => p.status === 'READY').length)
const totalLoc = computed(() => projects.value.reduce((s, p) => s + (p.locTotal ?? 0), 0))
const recent = computed(() =>
  [...projects.value]
    .filter((p) => p.lastAnalyzedAt)
    .sort((a, b) => (b.lastAnalyzedAt ?? '').localeCompare(a.lastAnalyzedAt ?? ''))
    .slice(0, 8),
)

const statusLabel = (s: string): string =>
  ({ CREATED: '已创建', ANALYZING: '分析中', READY: '就绪', FAILED: '失败' })[s] ?? s

const healthClass = (score: number | null): string => {
  if (score == null) return 'dash__health--none'
  if (score >= 80) return 'dash__health--high'
  if (score >= 60) return 'dash__health--mid'
  return 'dash__health--low'
}

const formatTime = (t: string): string => new Date(t).toLocaleDateString()

function goDetail(id: number): void {
  void router.push(`/projects/${id}`)
}

function renderCharts(): void {
  if (!healthEl.value || !langEl.value || !statusEl.value) return
  charts.forEach((c) => c.dispose())
  charts.length = 0

  const titleStyle = { fontSize: 13, color: '#55667a' }
  const legendStyle = { color: '#8798ab' }

  // 健康分分布
  const buckets = { low: 0, mid: 0, high: 0, none: 0 }
  for (const p of projects.value) {
    if (p.healthScore == null) buckets.none += 1
    else if (p.healthScore >= 80) buckets.high += 1
    else if (p.healthScore >= 60) buckets.mid += 1
    else buckets.low += 1
  }
  charts.push(echarts.init(healthEl.value))
  charts[0].setOption({
    title: { text: '健康分分布', left: 'center', textStyle: titleStyle },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 16, top: 36, bottom: 8, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eef1f5' } } },
    yAxis: {
      type: 'category',
      data: ['优秀 (≥80)', '一般 (60-80)', '待改善 (<60)', '未分析'],
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#55667a', fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        data: [
          { value: buckets.high, itemStyle: { color: '#0f9d58', borderRadius: 2 } },
          { value: buckets.mid, itemStyle: { color: '#e8890c', borderRadius: 2 } },
          { value: buckets.low, itemStyle: { color: '#d64545', borderRadius: 2 } },
          { value: buckets.none, itemStyle: { color: '#c2ccd8', borderRadius: 2 } },
        ],
        barWidth: 18,
      },
    ],
  })

  // 语言构成
  const langCount = new Map<string, number>()
  for (const p of projects.value) {
    for (const [lang, pct] of Object.entries(p.langStats ?? {})) {
      langCount.set(lang, (langCount.get(lang) ?? 0) + (pct ?? 0))
    }
  }
  const langData = [...langCount.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([name, value]) => ({ name, value: Math.round(value) }))
  charts.push(echarts.init(langEl.value))
  charts[1].setOption({
    title: { text: '语言构成', left: 'center', textStyle: titleStyle },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, type: 'scroll', textStyle: legendStyle },
    series: [{ type: 'pie', radius: ['42%', '70%'], center: ['50%', '46%'], data: langData }],
  })

  // 项目状态分布（横向条形，对比清晰）
  const statusCount = new Map<string, number>()
  for (const p of projects.value) {
    statusCount.set(p.status, (statusCount.get(p.status) ?? 0) + 1)
  }
  const statusData = [...statusCount.entries()].map(([name, value]) => ({ name, value }))
  const statusColor = (n: string): string =>
    n === 'READY'
      ? '#0f9d58'
      : n === 'ANALYZING'
        ? '#e8890c'
        : n === 'FAILED'
          ? '#d64545'
          : '#c2ccd8'
  charts.push(echarts.init(statusEl.value))
  charts[2].setOption({
    title: { text: '项目状态', left: 'center', textStyle: titleStyle },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 16, top: 36, bottom: 8, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eef1f5' } } },
    yAxis: {
      type: 'category',
      data: statusData.map((d) => statusLabel(d.name)),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#55667a', fontSize: 12 },
    },
    series: [
      {
        type: 'bar',
        data: statusData.map((d) => ({
          value: d.value,
          itemStyle: { color: statusColor(d.name), borderRadius: 2 },
        })),
        barWidth: 18,
      },
    ],
  })
}

function onResize(): void {
  cancelAnimationFrame(resizeRaf)
  resizeRaf = requestAnimationFrame(() => {
    charts.forEach((c) => c.resize())
  })
}

onMounted(async () => {
  try {
    const page = await listProjects({ page: 1, size: 100 })
    projects.value = page.items
  } catch (err) {
    console.error('加载项目列表失败', err)
  }
  await Promise.resolve()
  renderCharts()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  cancelAnimationFrame(resizeRaf)
  charts.forEach((c) => c.dispose())
  charts.length = 0
})
</script>

<template>
  <div class="dash">
    <h2 class="dash__title">全局总览</h2>

    <div class="dash__stats">
      <NCard size="small">
        <NStatistic label="项目总数" :value="projects.length" />
      </NCard>
      <NCard size="small">
        <NStatistic label="平均健康分" :value="Number(avgHealth.toFixed(1))" />
      </NCard>
      <NCard size="small">
        <NStatistic label="已分析项目" :value="readyCount" />
      </NCard>
      <NCard size="small">
        <NStatistic label="总代码行数" :value="totalLoc" />
      </NCard>
    </div>

    <div v-if="projects.length" class="dash__charts">
      <NCard size="small" class="dash__chart-card">
        <div ref="healthEl" class="dash__chart" />
      </NCard>
      <NCard size="small" class="dash__chart-card">
        <div ref="langEl" class="dash__chart" />
      </NCard>
      <NCard size="small" class="dash__chart-card">
        <div ref="statusEl" class="dash__chart" />
      </NCard>
    </div>
    <NCard v-else size="small" class="dash__empty">
      <NEmpty description="还没有项目，去「项目档案」页创建并分析，这里会展示全局健康分布">
        <template #extra>
          <NButton size="small" type="primary" @click="router.push('/projects/create')">
            新建项目
          </NButton>
        </template>
      </NEmpty>
    </NCard>

    <h3 class="dash__sub">最近分析</h3>
    <NCard v-if="recent.length" size="small" :bordered="false">
      <NList hoverable>
        <NListItem v-for="p in recent" :key="p.id" class="dash__item" @click="goDetail(p.id)">
          <div class="dash__item-inner">
            <span class="dash__item-name">{{ p.name }}</span>
            <span class="dash__health" :class="healthClass(p.healthScore)">
              {{ p.healthScore ?? '—' }}
            </span>
            <span class="dash__item-meta">{{ statusLabel(p.status) }}</span>
            <span class="dash__item-meta">{{ p.locTotal?.toLocaleString() ?? 0 }} 行</span>
            <span class="dash__item-meta">{{
              p.lastAnalyzedAt ? formatTime(p.lastAnalyzedAt) : '未分析'
            }}</span>
          </div>
        </NListItem>
      </NList>
    </NCard>
    <NCard v-else size="small" :bordered="false">
      <NEmpty description="暂无项目" />
    </NCard>
  </div>
</template>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.dash__title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}
.dash__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
}
.dash__charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}
.dash__chart {
  height: 240px;
}
.dash__sub {
  margin: 8px 0 0;
  font-size: 15px;
  font-weight: 600;
}
.dash__item-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dash__item-name {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dash__health {
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  width: 32px;
  text-align: right;
}
.dash__health--high {
  color: #0f9d58;
}
.dash__health--mid {
  color: #e8890c;
}
.dash__health--low {
  color: #d64545;
}
.dash__health--none {
  color: #c2ccd8;
}
.dash__item-meta {
  font-size: 12px;
  color: #8798ab;
  white-space: nowrap;
}
</style>
