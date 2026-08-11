<template>
  <section class="dash">
    <h2 class="dash__title">全局总览</h2>

    <div class="dash__stats">
      <div class="dash__stat">
        <span class="dash__stat-num">{{ projects.length }}</span>
        <span class="dash__stat-label">项目总数</span>
      </div>
      <div class="dash__stat">
        <span class="dash__stat-num">{{ avgHealth.toFixed(1) }}</span>
        <span class="dash__stat-label">平均健康分</span>
      </div>
      <div class="dash__stat">
        <span class="dash__stat-num">{{ readyCount }}</span>
        <span class="dash__stat-label">已分析项目</span>
      </div>
      <div class="dash__stat">
        <span class="dash__stat-num">{{ totalLoc.toLocaleString() }}</span>
        <span class="dash__stat-label">总代码行数</span>
      </div>
    </div>

    <div class="dash__charts">
      <div ref="healthEl" class="dash__chart" />
      <div ref="langEl" class="dash__chart" />
      <div ref="statusEl" class="dash__chart" />
    </div>

    <h3 class="dash__sub">最近分析</h3>
    <div v-if="!recent.length" class="dash__empty">暂无项目，去「项目」页创建并分析</div>
    <ul v-else class="dash__list">
      <li v-for="p in recent" :key="p.id" class="dash__item" @click="goDetail(p.id)">
        <span class="dash__item-name">{{ p.name }}</span>
        <span class="dash__health" :class="healthClass(p.healthScore)">
          {{ p.healthScore ?? '—' }}
        </span>
        <span class="dash__item-meta">{{ statusLabel(p.status) }}</span>
        <span class="dash__item-meta">{{ p.locTotal?.toLocaleString() ?? 0 }} 行</span>
        <span class="dash__item-meta">{{
          p.lastAnalyzedAt ? formatTime(p.lastAnalyzedAt) : '未分析'
        }}</span>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts/core'
import { PieChart } from 'echarts/charts'
import { LegendComponent, TitleComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ECharts } from 'echarts/core'
import { listProjects } from '../../api/project'
import type { ProjectSummary } from '../../types/api'

echarts.use([PieChart, LegendComponent, TitleComponent, TooltipComponent, CanvasRenderer])

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

  // 审查 M6：从 CSS 变量取主题色，深色下标题/图例可读
  const cssVar = (name: string): string =>
    getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const textColor = cssVar('--text-primary') || '#1f2329'
  const textSecondary = cssVar('--text-secondary') || '#6b7280'
  const titleStyle = { fontSize: 13, color: textColor }
  const legendStyle = { color: textSecondary }

  // 健康分分布（环形：<60 / 60-80 / ≥80）
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
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: legendStyle },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        data: [
          { name: '优秀 (≥80)', value: buckets.high, itemStyle: { color: '#16a34a' } },
          { name: '一般 (60-80)', value: buckets.mid, itemStyle: { color: '#d97706' } },
          { name: '待改善 (<60)', value: buckets.low, itemStyle: { color: '#dc2626' } },
          { name: '未分析', value: buckets.none, itemStyle: { color: '#9ca3af' } },
        ],
      },
    ],
  })

  // 语言构成（跨项目聚合 top 8）
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
    series: [{ type: 'pie', radius: ['40%', '68%'], data: langData }],
  })

  // 项目状态分布
  const statusCount = new Map<string, number>()
  for (const p of projects.value) {
    statusCount.set(p.status, (statusCount.get(p.status) ?? 0) + 1)
  }
  const statusData = [...statusCount.entries()].map(([name, value]) => ({ name, value }))
  charts.push(echarts.init(statusEl.value))
  charts[2].setOption({
    title: { text: '项目状态', left: 'center', textStyle: titleStyle },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0, textStyle: legendStyle },
    series: [
      {
        type: 'pie',
        radius: ['40%', '68%'],
        data: statusData.map((d) => ({
          ...d,
          name: statusLabel(d.name),
          itemStyle: {
            color:
              d.name === 'READY'
                ? '#16a34a'
                : d.name === 'ANALYZING'
                  ? '#d97706'
                  : d.name === 'FAILED'
                    ? '#dc2626'
                    : '#9ca3af',
          },
        })),
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

<style scoped>
.dash__title {
  margin: 0 0 16px;
  font-size: 18px;
}
.dash__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.dash__stat {
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dash__stat-num {
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}
.dash__stat-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.dash__charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
  margin-bottom: 20px;
}
.dash__chart {
  height: 240px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  background: var(--bg-card);
  padding: 8px;
}
.dash__sub {
  font-size: 15px;
  margin: 0 0 10px;
}
.dash__empty {
  color: var(--text-secondary);
  font-size: 13px;
  padding: 16px 0;
}
.dash__list {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--bg-card);
}
.dash__item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--border-color);
  cursor: pointer;
  font-size: 13px;
}
.dash__item:last-child {
  border-bottom: none;
}
.dash__item:hover {
  background: var(--bg-muted);
}
.dash__item-name {
  flex: 1;
  font-weight: 500;
}
.dash__health {
  font-weight: 700;
  border-radius: var(--radius-sm);
  padding: 1px 8px;
  min-width: 40px;
  text-align: center;
}
.dash__health--high {
  background: var(--ok-weak);
  color: var(--ok-color);
}
.dash__health--mid {
  background: var(--warn-weak);
  color: var(--warn-color);
}
.dash__health--low {
  background: var(--fail-weak);
  color: var(--fail-color);
}
.dash__health--none {
  background: var(--bg-muted);
  color: var(--text-secondary);
}
.dash__item-meta {
  color: var(--text-secondary);
  font-size: 12px;
}
</style>
