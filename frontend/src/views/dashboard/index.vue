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
import { NButton, NCard, NEmpty, NList, NListItem, NProgress } from 'naive-ui'
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
// 审查修复：展示全部项目（含未分析）——此前 filter(lastAnalyzedAt) 导致
// 有项目但无分析时间时列表空、页面大片空白。按 分析时间>创建时间 倒序。
const recent = computed(() =>
  [...projects.value]
    .sort((a, b) =>
      (b.lastAnalyzedAt ?? b.createdAt ?? '').localeCompare(a.lastAnalyzedAt ?? a.createdAt ?? ''),
    )
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

/** 健康分进度条着色（与 healthClass 档位一致） */
const healthColor = (score: number | null): string => {
  if (score == null) return '#c2ccd8'
  if (score >= 80) return '#0f9d58'
  if (score >= 60) return '#e8890c'
  return '#d64545'
}

const formatTime = (t: string): string => new Date(t).toLocaleDateString()

function goDetail(id: number): void {
  void router.push(`/projects/${id}`)
}

/** 功能导览：EvoCode 全部分析能力（点击进入项目详情对应分区） */
const FEATURES = [
  {
    key: 'report',
    icon: '🏥',
    label: '体检报告',
    desc: '健康评分 · 诊断 · 风险',
    color: '#1668dc',
  },
  {
    key: 'quality',
    icon: '🔬',
    label: '质量分析',
    desc: 'Sonar 静态扫描 · Bug/漏洞',
    color: '#0f9d58',
  },
  {
    key: 'architecture',
    icon: '🕸',
    label: '架构分析',
    desc: '分层结构 · 调用关系',
    color: '#e8890c',
  },
  {
    key: 'evolution',
    icon: '📈',
    label: '演化分析',
    desc: '提交趋势 · 风险热点',
    color: '#d64545',
  },
  {
    key: 'dependency',
    icon: '🧩',
    label: '依赖分析',
    desc: '依赖清单 · EOL 风险',
    color: '#7c5cd6',
  },
  { key: 'doctor', icon: '💬', label: 'AI 医生', desc: '项目问答 · 引用溯源', color: '#0e7490' },
  { key: 'debt', icon: '🧾', label: '技术债', desc: '债务登记 · 状态跟踪', color: '#b45309' },
  { key: 'doc', icon: '📄', label: '项目文档', desc: 'README / 架构 / API', color: '#1668dc' },
  { key: 'files', icon: '🗂', label: '文件地图', desc: '代码浏览 · 内容预览', color: '#55667a' },
]

/** 进入项目详情对应功能分区（有项目时） */
function goFeature(key: string): void {
  const pid = projects.value[0]?.id
  if (!pid) {
    void router.push('/projects/create')
    return
  }
  void router.push({ path: `/projects/${pid}`, query: { section: key } })
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
    <div class="dash-head">
      <div>
        <h2 class="dash__title">全局总览</h2>
        <p class="dash__subtitle">跨项目健康分布 · 语言构成 · 状态总览</p>
      </div>
      <NButton size="small" type="primary" @click="router.push('/projects/create')"
        >＋ 新建项目</NButton
      >
    </div>

    <div class="dash__stats">
      <NCard size="small" class="dash__stat-card">
        <div class="dash__stat-inner">
          <div class="dash__stat-icon icon-blue">▤</div>
          <div class="dash__stat-meta">
            <span class="dash__stat-num">{{ projects.length }}</span>
            <span class="dash__stat-label">项目总数</span>
          </div>
        </div>
      </NCard>
      <NCard size="small" class="dash__stat-card">
        <div class="dash__stat-inner">
          <div class="dash__stat-icon icon-green">♥</div>
          <div class="dash__stat-meta">
            <span class="dash__stat-num">{{ Number(avgHealth.toFixed(1)) }}</span>
            <span class="dash__stat-label">平均健康分</span>
          </div>
        </div>
      </NCard>
      <NCard size="small" class="dash__stat-card">
        <div class="dash__stat-inner">
          <div class="dash__stat-icon icon-amber">✓</div>
          <div class="dash__stat-meta">
            <span class="dash__stat-num">{{ readyCount }}</span>
            <span class="dash__stat-label">已分析项目</span>
          </div>
        </div>
      </NCard>
      <NCard size="small" class="dash__stat-card">
        <div class="dash__stat-inner">
          <div class="dash__stat-icon icon-slate">≡</div>
          <div class="dash__stat-meta">
            <span class="dash__stat-num">{{ totalLoc.toLocaleString() }}</span>
            <span class="dash__stat-label">总代码行数</span>
          </div>
        </div>
      </NCard>
    </div>

    <!-- 功能导览：全部分析能力入口 -->
    <div class="dash__features">
      <div class="dash__features-head">
        <h3 class="dash__sub">能力导览</h3>
        <span class="dash__features-hint">点击进入项目对应分析分区</span>
      </div>
      <div class="dash__feature-grid">
        <button
          v-for="f in FEATURES"
          :key="f.key"
          type="button"
          class="dash__feature"
          @click="goFeature(f.key)"
        >
          <span
            class="dash__feature-icon"
            :style="{ background: f.color + '1a', color: f.color }"
            >{{ f.icon }}</span
          >
          <span class="dash__feature-text">
            <span class="dash__feature-label">{{ f.label }}</span>
            <span class="dash__feature-desc">{{ f.desc }}</span>
          </span>
        </button>
      </div>
    </div>

    <div v-if="projects.length" class="dash__charts">
      <NCard size="small" class="dash__chart-card" title="健康分分布">
        <div ref="healthEl" class="dash__chart" />
      </NCard>
      <NCard size="small" class="dash__chart-card" title="语言构成">
        <div ref="langEl" class="dash__chart" />
      </NCard>
      <NCard size="small" class="dash__chart-card" title="项目状态">
        <div ref="statusEl" class="dash__chart" />
      </NCard>
    </div>

    <h3 class="dash__sub">最近分析</h3>
    <NCard v-if="recent.length" size="small" :bordered="false">
      <NList hoverable>
        <NListItem v-for="p in recent" :key="p.id" class="dash__item" @click="goDetail(p.id)">
          <div class="dash__item-inner">
            <span class="dash__item-name">{{ p.name }}</span>
            <span class="dash__item-gauge">
              <NProgress
                type="line"
                :percentage="p.healthScore ?? 0"
                :height="6"
                :color="healthColor(p.healthScore)"
                :rail-color="'#e6ebf1'"
                :show-indicator="false"
              />
            </span>
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
.dash-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
}
.dash__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}
.dash__subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8798ab;
}
.dash__stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
}
.dash__stat-card {
  position: relative;
  overflow: hidden;
  transition:
    transform 150ms ease,
    box-shadow 150ms ease;
}
.dash__stat-card::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: #1668dc;
  opacity: 0;
  transition: opacity 150ms ease;
}
.dash__stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 14px rgba(15, 23, 42, 0.08);
}
.dash__stat-card:hover::before {
  opacity: 1;
}
.dash__stat-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dash__stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 10px;
  font-size: 20px;
  flex-shrink: 0;
}
.icon-blue {
  background: rgba(22, 104, 220, 0.14);
  color: #1668dc;
}
.icon-green {
  background: rgba(15, 157, 88, 0.14);
  color: #0f9d58;
}
.icon-amber {
  background: rgba(232, 137, 12, 0.14);
  color: #e8890c;
}
.icon-slate {
  background: rgba(85, 102, 122, 0.14);
  color: #55667a;
}
.dash__stat-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dash__stat-num {
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.dash__stat-label {
  font-size: 12px;
  color: #8798ab;
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
.dash__features {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.dash__features-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.dash__features-hint {
  font-size: 12px;
  color: #8798ab;
}
.dash__feature-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
}
.dash__feature {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  transition:
    box-shadow 150ms ease,
    transform 150ms ease,
    border-color 150ms ease;
}
.dash__feature:hover {
  box-shadow: 0 3px 10px rgba(15, 23, 42, 0.08);
  transform: translateY(-2px);
  border-color: #cbd6e2;
}
.dash__feature-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  font-size: 18px;
  flex-shrink: 0;
}
.dash__feature-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.dash__feature-label {
  font-size: 13.5px;
  font-weight: 600;
  color: #1b2633;
}
.dash__feature-desc {
  font-size: 11.5px;
  color: #8798ab;
}
.dash__item-inner {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dash__item-name {
  width: 180px;
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dash__item-gauge {
  width: 140px;
  flex-shrink: 0;
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
