<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ArchitectureView from './architecture-view.vue'
import DependencyView from './dependency-view.vue'
import DocView from './doc-view.vue'
import DoctorView from './doctor-view.vue'
import EvolutionView from './evolution-view.vue'
import TechDebtView from './tech-debt-view.vue'
import ReportHistoryView from './report-history-view.vue'
import { deleteProject, getProjectDetail } from '../../api/project'
import { getFileContent, listFiles } from '../../api/file'
import {
  createAnalysis,
  getAnalysisStatus,
  getReport,
  listAnalyses,
  regenerateReport,
  subscribeAnalysisProgress,
  type AnalysisProgressEvent,
} from '../../api/analysis'
import { getQualityIssues } from '../../api/quality'
import type {
  AnalysisHistoryItem,
  AnalysisStatusValue,
  FileContent,
  FileNodeItem,
  ProjectDetail,
  ProjectStatus,
  QualityIssueItem,
  QualityIssuesResult,
  ReportDetail,
  ReportDimension,
} from '../../types/api'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

const detail = ref<ProjectDetail | null>(null)
const loadError = ref('')

// ---- 分析历史与报告 ----
const analyses = ref<AnalysisHistoryItem[]>([])
const selectedId = ref<number | null>(null)
const report = ref<ReportDetail | null>(null)
const reportLoading = ref(false)
const starting = ref(false)
const regenerating = ref(false)

// ---- 质量 issues ----
const quality = ref<QualityIssuesResult | null>(null)
const qualityLoading = ref(false)

// ---- 文件地图 ----
const files = ref<FileNodeItem[]>([])
const fileTotal = ref(0)
const filePage = ref(1)
const fileSize = ref(20)
const langFilter = ref('')
const fileKeyword = ref('')
const fileSort = ref<'path' | 'loc' | 'sizeBytes'>('path')
const fileOrder = ref<'asc' | 'desc'>('asc')
const fileLoading = ref(false)

const content = ref<FileContent | null>(null)
const contentLoading = ref(false)

const STATUS_META: Record<ProjectStatus, { label: string; cls: string }> = {
  CREATED: { label: '已创建', cls: 'st-created' },
  ANALYZING: { label: '分析中', cls: 'st-analyzing' },
  READY: { label: '就绪', cls: 'st-ready' },
  FAILED: { label: '失败', cls: 'st-failed' },
}

const ANA_STATUS_META: Record<AnalysisStatusValue, { label: string; cls: string }> = {
  PENDING: { label: '排队中', cls: 'st-created' },
  RUNNING: { label: '分析中', cls: 'st-analyzing' },
  SUCCEEDED: { label: '成功', cls: 'st-ready' },
  FAILED: { label: '失败', cls: 'st-failed' },
  CANCELLED: { label: '已取消', cls: 'st-created' },
}

const DIM_LABEL: Record<ReportDimension['key'], string> = {
  quality: '质量',
  structure: '结构',
  dependency: '依赖',
  scale: '规模',
}

const LEVEL_META: Record<string, { label: string; cls: string }> = {
  EXCELLENT: { label: '优秀', cls: 'lv-excellent' },
  GOOD: { label: '良好', cls: 'lv-good' },
  FAIR: { label: '一般', cls: 'lv-fair' },
  POOR: { label: '较差', cls: 'lv-poor' },
}

const langOptions = computed(() => {
  const stats = detail.value?.langStats
  if (!stats) return []
  return Object.entries(stats)
    .sort((a, b) => b[1] - a[1])
    .map(([lang]) => lang)
})

const langBars = computed(() => {
  const stats = detail.value?.langStats
  if (!stats) return []
  return Object.entries(stats)
    .sort((a, b) => b[1] - a[1])
    .map(([lang, pct]) => ({ lang, pct: Number(pct) }))
})

let timer: number | undefined

// ---- P9e：实时进度 SSE ----
let closeProgress: (() => void) | null = null
const liveProgress = ref<AnalysisProgressEvent | null>(null)
const toast = ref('')
let toastTimer: number | undefined

function showToast(msg: string): void {
  toast.value = msg
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
  }, 4000)
}

/** 订阅项目分析进度；SUCCEEDED/FAILED → Toast + 刷新数据并关闭连接。 */
function connectProgress(): void {
  closeProgress?.()
  closeProgress = subscribeAnalysisProgress(projectId, {
    onEvent: (e) => {
      liveProgress.value = e
      if (e.status === 'SUCCEEDED' || e.status === 'FAILED' || e.status === 'CANCELLED') {
        // 终态：关 SSE、清轮询、Toast + 刷新（避免 ≤2s 后轮询重复刷新）
        if (timer) {
          window.clearInterval(timer)
          timer = undefined
        }
        closeProgress?.()
        closeProgress = null
        liveProgress.value = null
        showToast(e.message ?? (e.status === 'SUCCEEDED' ? '分析完成' : '分析失败'))
        void loadDetail()
        void loadHistory()
        void loadQuality()
      }
    },
    onError: () => {
      // 断线：关闭 SSE，回退现有 2s 轮询（pollAnalysis 已有），并清除实时进度提示
      closeProgress = null
      liveProgress.value = null
    },
  })
}

async function loadDetail() {
  try {
    detail.value = await getProjectDetail(projectId)
    loadError.value = ''
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  }
}

/** 自动快扫轮询：项目 ANALYZING/CREATED 时每 3s 刷新详情，落定后刷新档案与文件 */
function pollIfRunning() {
  const st = detail.value?.status
  if (st === 'ANALYZING' || st === 'CREATED') {
    timer = window.setInterval(async () => {
      await loadDetail()
      const s = detail.value?.status
      if (s === 'READY' || s === 'FAILED') {
        window.clearInterval(timer)
        timer = undefined
        if (s === 'READY') {
          loadFiles()
          await loadHistory()
          await loadQuality()
        }
      }
    }, 3000)
  }
}

// ---- 分析历史与报告 ----

async function loadHistory() {
  try {
    const data = await listAnalyses(projectId)
    analyses.value = data.items
    // 默认选中最近一条有报告的分析（否则最新一条）
    const withReport = data.items.find((a) => a.healthScore != null && a.status === 'SUCCEEDED')
    const target = withReport ?? data.items[0]
    if (target) {
      selectedId.value = target.id
      await loadReport(target.id)
    } else {
      report.value = null
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  }
}

async function loadReport(analysisId: number) {
  selectedId.value = analysisId
  reportLoading.value = true
  try {
    report.value = await getReport(analysisId)
  } catch {
    report.value = null
  } finally {
    reportLoading.value = false
  }
}

async function selectHistory(item: AnalysisHistoryItem) {
  await loadReport(item.id)
  if (item.status === 'RUNNING' || item.status === 'PENDING') {
    pollAnalysis(item.id)
  }
}

/** 发起 FULL 分析：202 后轮询任务状态，落定后刷新历史与报告 */
async function startAnalysis() {
  if (starting.value) return
  starting.value = true
  loadError.value = ''
  try {
    const created = await createAnalysis(projectId)
    await loadDetail()
    connectProgress()
    pollAnalysis(created.id)
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    starting.value = false
  }
}

async function onRegenerate() {
  if (regenerating.value || selectedId.value == null) return
  regenerating.value = true
  loadError.value = ''
  try {
    await regenerateReport(selectedId.value)
    pollAnalysis(selectedId.value)
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    regenerating.value = false
  }
}

/** 分析任务轮询（2s）：RUNNING/PENDING 持续刷新，落定后停并刷新历史 */
function pollAnalysis(analysisId: number) {
  if (timer) window.clearInterval(timer)
  timer = window.setInterval(async () => {
    try {
      const st = await getAnalysisStatus(analysisId)
      if (st.status === 'SUCCEEDED' || st.status === 'FAILED' || st.status === 'CANCELLED') {
        window.clearInterval(timer)
        timer = undefined
        await loadDetail()
        await loadHistory()
        await loadQuality()
      }
    } catch {
      // 网络抖动：下一轮再试
    }
  }, 2000)
}

// ---- 质量 issues ----

async function loadQuality() {
  qualityLoading.value = true
  try {
    quality.value = await getQualityIssues(projectId, { page: 1, size: 20 })
  } catch {
    quality.value = null
  } finally {
    qualityLoading.value = false
  }
}

const SEVERITY_META: Record<QualityIssueItem['severity'], { label: string; cls: string }> = {
  BLOCKER: { label: '阻断', cls: 'sv-blocker' },
  CRITICAL: { label: '严重', cls: 'sv-critical' },
  MAJOR: { label: '主要', cls: 'sv-major' },
  MINOR: { label: '次要', cls: 'sv-minor' },
  INFO: { label: '提示', cls: 'sv-info' },
}

// ---- 文件地图 ----

async function loadFiles() {
  fileLoading.value = true
  try {
    const data = await listFiles(projectId, {
      page: filePage.value,
      size: fileSize.value,
      language: langFilter.value || undefined,
      keyword: fileKeyword.value.trim() || undefined,
      sort: fileSort.value,
      order: fileOrder.value,
    })
    files.value = data.items
    fileTotal.value = data.total
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  } finally {
    fileLoading.value = false
  }
}

function onFileSearch() {
  filePage.value = 1
  loadFiles()
}

function onFileSort(sort: 'path' | 'loc' | 'sizeBytes') {
  if (fileSort.value === sort) {
    fileOrder.value = fileOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    fileSort.value = sort
    fileOrder.value = 'asc'
  }
  loadFiles()
}

function prevFilePage() {
  if (filePage.value > 1) {
    filePage.value -= 1
    loadFiles()
  }
}

function nextFilePage() {
  if (filePage.value < Math.ceil(fileTotal.value / fileSize.value)) {
    filePage.value += 1
    loadFiles()
  }
}

async function openFile(item: FileNodeItem) {
  contentLoading.value = true
  content.value = null
  try {
    content.value = await getFileContent(projectId, item.path)
  } catch (e) {
    content.value = {
      path: item.path,
      language: item.language,
      loc: item.loc,
      content: `读取失败：${e instanceof Error ? e.message : String(e)}`,
      truncated: false,
    }
  } finally {
    contentLoading.value = false
  }
}

async function onDelete() {
  if (!detail.value) return
  if (!window.confirm(`确定删除项目「${detail.value.name}」？`)) return
  try {
    await deleteProject(projectId)
    router.push('/projects')
  } catch (e) {
    window.alert(e instanceof Error ? e.message : String(e))
  }
}

function stars(n: number): string {
  return '★'.repeat(Math.max(0, Math.min(5, n))) + '☆'.repeat(Math.max(0, 5 - Math.min(5, n)))
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function fmtTime(iso: string | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  await loadDetail()
  const st = detail.value?.status
  if (st === 'READY') {
    loadFiles()
    await loadHistory()
    await loadQuality()
  } else if (st === 'ANALYZING') {
    // P9e：仅 FULL/REGENERATE 分析中订阅 SSE（CREATED 快扫无事件流，避免空挂）
    connectProgress()
    pollIfRunning()
  } else {
    pollIfRunning()
  }
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
  closeProgress?.()
  closeProgress = null
  if (toastTimer) window.clearTimeout(toastTimer)
})
</script>

<template>
  <section v-if="detail" class="page">
    <div class="head">
      <a class="back" @click="router.push('/projects')">← 返回项目列表</a>
      <div class="head-right">
        <button class="btn danger" type="button" @click="onDelete">删除项目</button>
      </div>
    </div>

    <div class="title-row">
      <h1>{{ detail.name }}</h1>
      <span class="badge" :class="STATUS_META[detail.status].cls">
        {{ STATUS_META[detail.status].label }}
      </span>
      <span v-if="detail.sourceType === 'GIT'" class="repo-url">{{ detail.repoUrl }}</span>
    </div>
    <p v-if="detail.description" class="desc">{{ detail.description }}</p>

    <div v-if="detail.status === 'ANALYZING'" class="notice">
      自动快扫进行中…档案与报告生成后自动刷新
    </div>
    <div v-else-if="detail.status === 'FAILED'" class="alert-fail">
      快扫失败：档案不完整，可删除后重新导入。
    </div>
    <div v-if="loadError" class="alert-fail">{{ loadError }}</div>

    <!-- 档案区 -->
    <div class="cards">
      <div class="card">
        <h3>规模</h3>
        <div class="metrics">
          <div class="metric">
            <div class="value">{{ detail.locTotal.toLocaleString() }}</div>
            <div class="key">代码行数 LOC</div>
          </div>
          <div class="metric">
            <div class="value">{{ detail.fileCount.toLocaleString() }}</div>
            <div class="key">文件数</div>
          </div>
          <div class="metric">
            <div class="value">{{ detail.ignoredCount ?? '-' }}</div>
            <div class="key">忽略文件</div>
          </div>
        </div>
      </div>

      <div class="card">
        <h3>技术栈</h3>
        <div v-if="detail.frameworkTags.length" class="tags">
          <span v-for="t in detail.frameworkTags" :key="t" class="tag">{{ t }}</span>
        </div>
        <span v-else class="muted">尚未识别（快扫完成后显示）</span>
      </div>

      <div class="card">
        <h3>语言分布</h3>
        <div v-if="langBars.length" class="langs">
          <div v-for="bar in langBars" :key="bar.lang" class="lang-row">
            <span class="lang-name">{{ bar.lang }}</span>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: Math.min(100, bar.pct) + '%' }" />
            </div>
            <span class="lang-pct">{{ bar.pct.toFixed(1) }}%</span>
          </div>
        </div>
        <span v-else class="muted">快扫完成后显示</span>
      </div>

      <div class="card">
        <h3>最近分析</h3>
        <dl class="kv">
          <dt>状态</dt>
          <dd>{{ detail.latestAnalysis ? detail.latestAnalysis.status : '未开始' }}</dd>
          <dt>阶段</dt>
          <dd>{{ detail.latestAnalysis?.stage ?? '-' }}</dd>
          <dt>进度</dt>
          <dd>
            {{
              detail.latestAnalysis?.progress != null ? detail.latestAnalysis.progress + '%' : '-'
            }}
          </dd>
          <dt>最近扫描</dt>
          <dd>{{ fmtTime(detail.lastAnalyzedAt) }}</dd>
          <dt>创建时间</dt>
          <dd>{{ fmtTime(detail.createdAt) }}</dd>
        </dl>
      </div>
    </div>

    <!-- 分析操作 -->
    <div class="analysis-ops">
      <button class="btn-primary" type="button" :disabled="starting" @click="startAnalysis">
        {{ starting ? '发起中…' : '发起完整分析' }}
      </button>
      <button
        v-if="report && selectedId != null"
        class="btn"
        type="button"
        :disabled="regenerating"
        @click="onRegenerate"
      >
        {{ regenerating ? '重新生成中…' : '重新生成报告' }}
      </button>
      <span class="hint">完整分析 = 重新扫描 + AI 健康报告（约 1-3 分钟）</span>
    </div>

    <!-- P9e：实时进度（SSE 驱动；断线回退轮询后此块消失） -->
    <div v-if="liveProgress" class="live-progress">
      <div class="live-row">
        <span class="live-label">分析中 · {{ liveProgress.stage ?? '' }}</span>
        <span class="live-pct">{{ liveProgress.progress }}%</span>
      </div>
      <div class="progress-track">
        <div class="progress-bar" :style="{ width: liveProgress.progress + '%' }"></div>
      </div>
      <p v-if="liveProgress.message" class="hint">{{ liveProgress.message }}</p>
    </div>

    <!-- P9e：Toast（完成/失败提示） -->
    <Transition name="toast">
      <div v-if="toast" class="toast">{{ toast }}</div>
    </Transition>

    <!-- P9c：历史趋势折叠区（默认收起） -->
    <ReportHistoryView :project-id="projectId" />

    <!-- 体检报告 -->
    <div v-if="reportLoading" class="report loading">报告加载中…</div>
    <section v-else-if="report" class="report">
      <div class="report-head">
        <h2>体检报告 #{{ report.analysisId }}</h2>
        <span class="badge" :class="LEVEL_META[report.report.level].cls">
          {{ LEVEL_META[report.report.level].label }}
        </span>
        <span class="badge source" :class="report.source === 'LLM' ? 'src-llm' : 'src-rules'">
          {{ report.source === 'LLM' ? 'AI 报告' : '规则报告' }}
        </span>
        <span class="muted prompt">
          {{ report.promptVersion }} · {{ fmtTime(report.generatedAt) }}
        </span>
      </div>

      <div class="report-body">
        <div class="score-panel">
          <div class="score-num" :class="'lv-' + report.report.level.toLowerCase()">
            {{ report.report.healthScore }}
          </div>
          <div class="score-label">健康分（满分 100）</div>
          <p class="summary">{{ report.report.summary }}</p>
        </div>

        <div class="dims">
          <div v-for="d in report.report.dimensions" :key="d.key" class="dim">
            <div class="dim-head">
              <span class="dim-name">{{ DIM_LABEL[d.key] }}</span>
              <span class="dim-score">{{ d.score }}</span>
            </div>
            <div class="stars">{{ stars(d.stars) }}</div>
            <div class="dim-summary">{{ d.summary }}</div>
          </div>
        </div>
      </div>

      <div v-if="report.report.risks.length" class="report-section">
        <h3>风险</h3>
        <div
          v-for="(r, i) in report.report.risks"
          :key="i"
          class="risk"
          :class="'rk-' + r.level.toLowerCase()"
        >
          <div class="risk-head">
            <span class="risk-level">{{ r.level }}</span>
            <span class="risk-title">{{ r.title }}</span>
          </div>
          <p class="risk-detail">{{ r.detail }}</p>
          <p class="risk-suggestion">建议：{{ r.suggestion }}</p>
        </div>
      </div>

      <div v-if="report.report.recommendations.length" class="report-section">
        <h3>演化建议</h3>
        <div v-for="(rec, i) in report.report.recommendations" :key="i" class="rec">
          <div class="rec-phase">{{ rec.phase }}</div>
          <ul>
            <li v-for="item in rec.items" :key="item">{{ item }}</li>
          </ul>
        </div>
      </div>
    </section>
    <section v-else-if="analyses.length" class="report empty-report">
      该分析暂无报告（仅快扫的项目没有报告，发起完整分析后生成）
    </section>

    <!-- 分析历史 -->
    <div v-if="analyses.length" class="history">
      <h2>分析历史</h2>
      <table class="table">
        <thead>
          <tr>
            <th>ID</th>
            <th>类型</th>
            <th>状态</th>
            <th>进度</th>
            <th>健康分</th>
            <th>来源</th>
            <th>完成时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="a in analyses"
            :key="a.id"
            :class="{ selected: a.id === selectedId }"
            @click="selectHistory(a)"
          >
            <td>#{{ a.id }}</td>
            <td>{{ a.type }}</td>
            <td>
              <span class="badge" :class="ANA_STATUS_META[a.status].cls">
                {{ ANA_STATUS_META[a.status].label }}
              </span>
            </td>
            <td>{{ a.progress }}%{{ a.stage ? ' · ' + a.stage : '' }}</td>
            <td>
              <span v-if="a.healthScore != null" class="score">{{ a.healthScore }}</span>
              <span v-else class="muted">-</span>
            </td>
            <td>{{ a.source ?? '-' }}</td>
            <td class="muted">{{ fmtTime(a.finishedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 质量分析 -->
    <div v-if="quality" class="quality">
      <div class="quality-head">
        <h2>质量分析</h2>
        <span v-if="quality.metrics.available" class="quality-badge on"> Sonar 已接入 </span>
        <span v-else class="quality-badge off">Sonar 未启用</span>
      </div>

      <div v-if="quality.metrics.available" class="q-metrics">
        <div class="q-metric">
          <div class="value fail">{{ quality.metrics.bugs }}</div>
          <div class="key">Bug</div>
        </div>
        <div class="q-metric">
          <div class="value critical">{{ quality.metrics.vulnerabilities }}</div>
          <div class="key">漏洞</div>
        </div>
        <div class="q-metric">
          <div class="value">{{ quality.metrics.codeSmells }}</div>
          <div class="key">异味</div>
        </div>
      </div>

      <table v-if="quality.items.length" class="table">
        <thead>
          <tr>
            <th>严重度</th>
            <th>类型</th>
            <th>位置</th>
            <th>问题</th>
            <th>AI 解释</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in quality.items" :key="item.id">
            <td>
              <span class="sev" :class="SEVERITY_META[item.severity].cls">
                {{ SEVERITY_META[item.severity].label }}
              </span>
            </td>
            <td>
              <span class="tag">{{ item.kind }}</span>
            </td>
            <td class="path">
              {{ item.filePath }}<span v-if="item.line != null">:{{ item.line }}</span>
            </td>
            <td class="q-msg">{{ item.message }}</td>
            <td class="muted">
              <span v-if="item.aiStatus === 'DONE' && item.aiExplanation">已解释</span>
              <span v-else-if="item.aiStatus === 'FAILED'" class="fail-text">解释失败</span>
              <span v-else>待解释</span>
            </td>
          </tr>
        </tbody>
      </table>

      <p v-else class="empty">
        {{
          quality.metrics.available
            ? 'Sonar 扫描完成，暂未发现问题。'
            : '质量分析未启用：配置 Sonar（SONAR_HOST_URL / SONAR_TOKEN）后发起完整分析即可获得真实质量指标。'
        }}
      </p>
    </div>

    <!-- 依赖分析（P9d） -->
    <DependencyView v-if="detail?.status === 'READY'" :project-id="projectId" />
    <!-- 架构分析（P4c） -->
    <ArchitectureView v-if="detail?.status === 'READY'" :project-id="projectId" />
    <!-- 演化分析（P5c） -->
    <EvolutionView v-if="detail?.status === 'READY'" :project-id="projectId" />
    <!-- AI 医生（P6c） -->
    <DoctorView v-if="detail?.status === 'READY'" :project-id="projectId" />
    <!-- 技术债（P7a） -->
    <TechDebtView v-if="detail?.status === 'READY'" :project-id="projectId" />
    <!-- 文档（P7b） -->
    <DocView v-if="detail?.status === 'READY'" :project-id="projectId" />

    <!-- 文件地图 -->
    <div class="files">
      <h2>文件地图</h2>
      <div class="toolbar">
        <select v-model="langFilter" class="input select" @change="onFileSearch">
          <option value="">全部语言</option>
          <option v-for="l in langOptions" :key="l" :value="l">{{ l }}</option>
        </select>
        <input
          v-model="fileKeyword"
          class="input"
          placeholder="按路径搜索…"
          @keyup.enter="onFileSearch"
        />
        <button class="btn" type="button" @click="onFileSearch">搜索</button>
      </div>

      <table class="table">
        <thead>
          <tr>
            <th class="sortable" @click="onFileSort('path')">路径</th>
            <th>语言</th>
            <th class="sortable" @click="onFileSort('loc')">LOC</th>
            <th class="sortable" @click="onFileSort('sizeBytes')">大小</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.path">
            <td class="path">{{ f.path }}</td>
            <td>
              <span class="tag">{{ f.language || '-' }}</span>
            </td>
            <td>{{ f.loc }}</td>
            <td>{{ fmtSize(f.sizeBytes) }}</td>
            <td>
              <button class="btn small" type="button" @click="openFile(f)">查看内容</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="!fileLoading && fileTotal === 0" class="empty">暂无文件（快扫完成后显示）</p>
      <div v-if="fileTotal > fileSize" class="pager">
        <button class="btn small" type="button" :disabled="filePage <= 1" @click="prevFilePage">
          上一页
        </button>
        <span class="page-info">
          第 {{ filePage }} / {{ Math.max(1, Math.ceil(fileTotal / fileSize)) }} 页 · 共
          {{ fileTotal }} 条
        </span>
        <button
          class="btn small"
          type="button"
          :disabled="filePage >= Math.ceil(fileTotal / fileSize)"
          @click="nextFilePage"
        >
          下一页
        </button>
      </div>
    </div>

    <!-- 内容预览 -->
    <div v-if="content" class="content-panel">
      <div class="content-head">
        <span class="content-path">{{ content.path }}</span>
        <span v-if="content.truncated" class="muted">（已截断）</span>
        <button class="btn small" type="button" @click="content = null">关闭</button>
      </div>
      <pre class="code" :class="{ loading: contentLoading }">{{ content.content }}</pre>
    </div>
  </section>

  <section v-else class="page loading-page">
    <p v-if="loadError" class="alert-fail">{{ loadError }}</p>
    <p v-else class="muted">加载中…</p>
  </section>
</template>

<style scoped>
.page {
  background: var(--bg-page);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: 24px 28px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.back {
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
}
.back:hover {
  color: var(--primary-color);
}
.head-right .btn {
  height: 30px;
  padding: 0 12px;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.title-row h1 {
  margin: 0;
}
.repo-url {
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 40%;
}
.desc {
  margin: 6px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: var(--radius-lg);
  font-size: 12px;
  white-space: nowrap;
}
.st-created {
  color: var(--text-secondary);
  background: rgba(107, 114, 128, 0.12);
}
.st-analyzing {
  color: var(--primary-color);
  background: rgba(47, 111, 237, 0.1);
}
.st-ready {
  color: var(--ok-color);
  background: rgba(22, 163, 74, 0.1);
}
.st-failed {
  color: var(--fail-color);
  background: rgba(220, 38, 38, 0.1);
}
.notice {
  margin: 14px 0;
  padding: 10px 14px;
  border-radius: 6px;
  background: rgba(47, 111, 237, 0.08);
  color: var(--primary-color);
  font-size: 13px;
}
.alert-fail {
  margin: 14px 0;
  padding: 10px 14px;
  border-radius: 6px;
  background: rgba(220, 38, 38, 0.08);
  color: var(--fail-color);
  font-size: 13px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
  margin-top: 18px;
}
.card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: 16px;
}
.card h3 {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--text-secondary);
  font-weight: 600;
}
.metrics {
  display: flex;
  gap: 24px;
}
.metric .value {
  font-size: 22px;
  font-weight: 700;
}
.metric .key {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-secondary);
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag {
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--bg-muted);
  font-size: 12px;
}
.langs {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.lang-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
}
.lang-name {
  width: 90px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.bar-track {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: var(--bg-muted);
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 3px;
  background: var(--primary-color);
}
.lang-pct {
  width: 48px;
  text-align: right;
  color: var(--text-secondary);
}
.kv {
  margin: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 6px 14px;
  font-size: 13px;
}
.kv dt {
  color: var(--text-secondary);
}
.kv dd {
  margin: 0;
  text-align: right;
}
/* ---- 分析操作 ---- */
.analysis-ops {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 20px;
}
.hint {
  font-size: 12px;
  color: var(--text-secondary);
}

/* ---- P9e：实时进度 + Toast ---- */
.live-progress {
  margin-top: 14px;
  padding: 10px 14px;
  border: 1px solid var(--border-color, #e2e8f0);
  border-radius: var(--radius-sm, 6px);
  background: var(--bg-sub, #f8fafc);
}
.live-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
}
.live-label {
  color: var(--text-primary, #1e293b);
}
.live-pct {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--accent, #5b8def);
}
.progress-track {
  height: 6px;
  margin-top: 8px;
  border-radius: 3px;
  background: var(--bg-muted, #eef2f7);
  overflow: hidden;
}
.progress-bar {
  height: 100%;
  border-radius: 3px;
  background: var(--accent, #5b8def);
  transition: width 0.3s ease;
}
.toast {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1000;
  padding: 10px 18px;
  border-radius: var(--radius-sm, 6px);
  background: #1e293b;
  color: #fff;
  font-size: 13px;
  box-shadow: 0 4px 14px rgb(0 0 0 / 0.18);
}
.toast-enter-active,
.toast-leave-active {
  transition:
    opacity 0.25s,
    transform 0.25s;
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-8px);
}
/* ---- 体检报告 ---- */
.report {
  margin-top: 20px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: 20px;
}
.report.loading,
.empty-report {
  color: var(--text-secondary);
  font-size: 13px;
}
.report-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.report-head h2 {
  margin: 0;
  font-size: 16px;
}
.prompt {
  margin-left: auto;
  font-size: 12px;
}
.lv-excellent {
  color: #0e9f6e;
  background: rgba(14, 159, 110, 0.12);
}
.lv-good {
  color: var(--ok-color);
  background: rgba(22, 163, 74, 0.12);
}
.lv-fair {
  color: #d97706;
  background: rgba(217, 119, 6, 0.12);
}
.lv-poor {
  color: var(--fail-color);
  background: rgba(220, 38, 38, 0.12);
}
.source {
  font-weight: 600;
}
.src-llm {
  color: var(--primary-color);
  background: rgba(47, 111, 237, 0.12);
}
.src-rules {
  color: var(--text-secondary);
  background: rgba(107, 114, 128, 0.12);
}
.report-body {
  display: flex;
  gap: 28px;
  margin-top: 18px;
}
.score-panel {
  flex: 0 0 240px;
}
.score-num {
  font-size: 56px;
  font-weight: 800;
  line-height: 1;
}
.score-label {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-secondary);
}
.summary {
  margin-top: 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-primary);
}
.dims {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
}
.dim {
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: 12px;
}
.dim-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.dim-name {
  font-size: 13px;
  font-weight: 600;
}
.dim-score {
  font-size: 20px;
  font-weight: 700;
}
.stars {
  margin: 6px 0;
  color: #f59e0b;
  letter-spacing: 2px;
}
.dim-summary {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}
.report-section {
  margin-top: 20px;
}
.report-section h3 {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--text-secondary);
}
.risk {
  border-left: 3px solid var(--text-secondary);
  padding: 8px 12px;
  margin-bottom: 10px;
  background: var(--bg-page);
  border-radius: 0 6px 6px 0;
}
.risk.rk-high {
  border-left-color: var(--fail-color);
}
.risk.rk-medium {
  border-left-color: #d97706;
}
.risk.rk-low {
  border-left-color: var(--text-secondary);
}
.risk-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.risk-level {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(107, 114, 128, 0.15);
}
.rk-high .risk-level {
  background: rgba(220, 38, 38, 0.12);
  color: var(--fail-color);
}
.rk-medium .risk-level {
  background: rgba(217, 119, 6, 0.12);
  color: #d97706;
}
.risk-title {
  font-size: 13px;
  font-weight: 600;
}
.risk-detail {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
}
.risk-suggestion {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--primary-color);
}
.rec {
  margin-bottom: 10px;
}
.rec-phase {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 4px;
}
.rec ul {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--text-secondary);
}
/* ---- 分析历史 ---- */
.history {
  margin-top: 28px;
}
.history h2 {
  font-size: 16px;
  margin: 0 0 12px;
}
.history tbody tr {
  cursor: pointer;
}
.history tbody tr.selected {
  background: rgba(47, 111, 237, 0.06);
}
.score {
  font-weight: 700;
  color: var(--ok-color);
}
/* ---- 质量分析 ---- */
.quality {
  margin-top: 28px;
}
.quality-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.quality-head h2 {
  font-size: 16px;
  margin: 0;
}
.quality-badge {
  padding: 2px 10px;
  border-radius: var(--radius-lg);
  font-size: 12px;
}
.quality-badge.on {
  color: var(--ok-color);
  background: rgba(22, 163, 74, 0.1);
}
.quality-badge.off {
  color: var(--text-secondary);
  background: rgba(107, 114, 128, 0.12);
}
.q-metrics {
  display: flex;
  gap: 24px;
  margin: 14px 0;
}
.q-metric .value {
  font-size: 24px;
  font-weight: 700;
}
.q-metric .value.fail {
  color: var(--fail-color);
}
.q-metric .value.critical {
  color: #7c3aed;
}
.q-metric .key {
  font-size: 12px;
  color: var(--text-secondary);
}
.sev {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 600;
}
.sv-blocker {
  color: #7c2d12;
  background: rgba(127, 29, 29, 0.12);
}
.sv-critical {
  color: var(--fail-color);
  background: rgba(220, 38, 38, 0.12);
}
.sv-major {
  color: #d97706;
  background: rgba(217, 119, 6, 0.12);
}
.sv-minor {
  color: #2563eb;
  background: rgba(37, 99, 235, 0.12);
}
.sv-info {
  color: var(--text-secondary);
  background: rgba(107, 114, 128, 0.12);
}
.q-msg {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fail-text {
  color: var(--fail-color);
}
/* ---- 文件地图 ---- */
.files {
  margin-top: 28px;
}
.files h2 {
  font-size: 16px;
  margin: 0 0 12px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-card);
  font-size: 13px;
}
.input:focus {
  outline: 2px solid rgba(47, 111, 237, 0.25);
  border-color: var(--primary-color);
}
.select {
  min-width: 130px;
}
.btn {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: var(--bg-card);
  font-size: 13px;
  cursor: pointer;
}
.btn:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
}
.btn.danger:hover {
  border-color: var(--fail-color);
  color: var(--fail-color);
}
.btn.small {
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 6px;
  background: var(--primary-color);
  color: var(--bg-card);
  font-size: 14px;
  cursor: pointer;
}
.btn-primary:hover {
  filter: brightness(1.08);
}
.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.table th,
.table td {
  padding: 8px 12px;
  text-align: left;
  border-bottom: 1px solid var(--border-color);
}
.table th {
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
.sortable {
  cursor: pointer;
  user-select: none;
}
.sortable:hover {
  color: var(--primary-color);
}
.path {
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  font-size: 12px;
}
.empty {
  padding: 24px 0;
  text-align: center;
  color: var(--text-secondary);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 12px;
}
.page-info {
  font-size: 13px;
  color: var(--text-secondary);
}
.content-panel {
  margin-top: 20px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  overflow: hidden;
}
.content-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border-color);
  background: var(--bg-page);
}
.content-path {
  flex: 1;
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  font-size: 12px;
}
.code {
  margin: 0;
  padding: 14px 16px;
  max-height: 480px;
  overflow: auto;
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
}
.code.loading {
  opacity: 0.5;
}
.muted {
  color: var(--text-secondary);
}
.loading-page {
  padding: 40px;
  text-align: center;
}
</style>
