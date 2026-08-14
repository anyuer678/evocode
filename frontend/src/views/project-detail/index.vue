<script setup lang="ts">
import { computed, h, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { DataTableColumns } from 'naive-ui'
import {
  NAlert,
  NButton,
  NCard,
  NDataTable,
  NEmpty,
  NInput,
  NModal,
  NProgress,
  NSpin,
  NTag,
} from 'naive-ui'
import { NLayout, NLayoutContent, NLayoutSider } from 'naive-ui'
import { NSpace } from 'naive-ui'
import { useRoute } from 'vue-router'
import ArchitectureView from './architecture-view.vue'
import DependencyView from './dependency-view.vue'
import DocView from './doc-view.vue'
import DoctorView from './doctor-view.vue'
import EvolutionView from './evolution-view.vue'
import TechDebtView from './tech-debt-view.vue'
import ReportHistoryView from './report-history-view.vue'
import { getProjectDetail } from '../../api/project'
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
// 审查修复：路由参数响应式（从 /projects/13 直接跳 /projects/14 时组件复用需刷新）
const projectId = computed(() => Number(route.params.id))

// 分区导航：当前激活的功能区（体检报告/质量/架构/演化/依赖/AI医生/技术债/文档/文件）
// 反 AI 味守则：导航用克制的序号/文字标识，不用 emoji
type SectionKey =
  | 'report'
  | 'quality'
  | 'architecture'
  | 'evolution'
  | 'dependency'
  | 'doctor'
  | 'debt'
  | 'doc'
  | 'files'
const activeSection = ref<SectionKey>('report')

const SECTIONS: { key: SectionKey; label: string; desc: string }[] = [
  { key: 'report', label: '体检报告', desc: '健康分 · 诊断 · 风险' },
  { key: 'quality', label: '质量分析', desc: 'Sonar 静态扫描' },
  { key: 'architecture', label: '架构分析', desc: '分层 · 调用关系' },
  { key: 'evolution', label: '演化分析', desc: '提交趋势 · 热点' },
  { key: 'dependency', label: '依赖分析', desc: '依赖清单 · EOL 风险' },
  { key: 'doctor', label: 'AI 医生', desc: '项目问答 · 引用溯源' },
  { key: 'debt', label: '技术债', desc: '债务登记 · 状态跟踪' },
  { key: 'doc', label: '项目文档', desc: 'README / 架构 / API' },
  { key: 'files', label: '文件地图', desc: '代码浏览 · 预览' },
]

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

const DIM_LABEL: Record<ReportDimension['key'], string> = {
  quality: '质量',
  structure: '结构',
  dependency: '依赖',
  scale: '规模',
}

// 审查修复：拆分轮询 timer——此前 pollIfRunning(3s 快扫) 与 pollAnalysis(2s 任务)
// 共用 timer 变量，任一 clearInterval 会停掉另一个轮询；SSE 与轮询双通道到终态
// 会各自触发一次刷新。现拆分 projectTimer/analysisTimer，并用 doneAnalysisId 去重
// （同一 analysisId 的终态只刷新一次，不同分析各自刷新）。
let projectTimer: number | undefined
let analysisTimer: number | undefined
let doneAnalysisId: number | null = null
let toastTimer: number | undefined

// ---- P9e：实时进度 SSE ----
let closeProgress: (() => void) | null = null
const liveProgress = ref<AnalysisProgressEvent | null>(null)
const toast = ref('')

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
  closeProgress = subscribeAnalysisProgress(projectId.value, {
    onEvent: (e) => {
      liveProgress.value = e
      if (e.status === 'SUCCEEDED' || e.status === 'FAILED' || e.status === 'CANCELLED') {
        // 终态：关 SSE、清两个轮询、Toast + 刷新（doneAnalysisId 去重，
        // 同一 analysisId 的 SSE 与轮询双通道只刷新一次）
        if (doneAnalysisId === e.analysisId) return
        doneAnalysisId = e.analysisId
        if (projectTimer) {
          window.clearInterval(projectTimer)
          projectTimer = undefined
        }
        if (analysisTimer) {
          window.clearInterval(analysisTimer)
          analysisTimer = undefined
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
    detail.value = await getProjectDetail(projectId.value)
    loadError.value = ''
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  }
}

/** 自动快扫轮询：项目 ANALYZING/CREATED 时每 3s 刷新详情，落定后刷新档案与文件 */
function pollIfRunning() {
  const st = detail.value?.status
  if (st === 'ANALYZING' || st === 'CREATED') {
    if (projectTimer) window.clearInterval(projectTimer)
    projectTimer = window.setInterval(async () => {
      await loadDetail()
      const s = detail.value?.status
      if (s === 'READY' || s === 'FAILED') {
        window.clearInterval(projectTimer)
        projectTimer = undefined
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
    // 审查修复：加大 size 以覆盖历史 SUCCEEDED 分析（最近 N 条可能全 FAILED 时
    // 默认 size=10 会漏掉有报告的旧分析，导致报告卡 404 空白）
    const data = await listAnalyses(projectId.value, 1, 50)
    analyses.value = data.items
    // 默认选中最近一条有报告的分析（否则最新一条）
    const withReport = data.items.find((a) => a.healthScore != null && a.status === 'SUCCEEDED')
    const target = withReport ?? data.items[0]
    if (target) {
      selectedId.value = target.id
      if (withReport) {
        await loadReport(target.id)
      } else {
        // 审查修复：无 SUCCEEDED 报告时置空（避免请求 FAILED 分析的报告 404）
        report.value = null
      }
    } else {
      report.value = null
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : String(e)
  }
}

// 审查修复：报告加载竞态守卫（loadSeq 递增，过期响应丢弃）
let reportLoadSeq = 0

async function loadReport(analysisId: number) {
  const seq = ++reportLoadSeq
  selectedId.value = analysisId
  reportLoading.value = true
  try {
    const r = await getReport(analysisId)
    if (seq !== reportLoadSeq) return // 已有更新的请求，丢弃过期响应
    report.value = r
  } catch {
    if (seq !== reportLoadSeq) return
    report.value = null
  } finally {
    if (seq === reportLoadSeq) reportLoading.value = false
  }
}

/** 发起 FULL 分析：202 后轮询任务状态，落定后刷新历史与报告 */
async function startAnalysis() {
  if (starting.value) return
  starting.value = true
  loadError.value = ''
  try {
    const created = await createAnalysis(projectId.value)
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
  if (analysisTimer) window.clearInterval(analysisTimer)
  analysisTimer = window.setInterval(async () => {
    try {
      const st = await getAnalysisStatus(analysisId)
      if (st.status === 'SUCCEEDED' || st.status === 'FAILED' || st.status === 'CANCELLED') {
        window.clearInterval(analysisTimer)
        analysisTimer = undefined
        // 审查修复：终态去重——SSE 通道已处理过该 analysisId 则轮询不再重复刷新
        if (doneAnalysisId === analysisId) return
        doneAnalysisId = analysisId
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
    quality.value = await getQualityIssues(projectId.value, { page: 1, size: 20 })
  } catch {
    quality.value = null
  } finally {
    qualityLoading.value = false
  }
}

// ---- 文件地图 ----

async function loadFiles() {
  fileLoading.value = true
  try {
    const data = await listFiles(projectId.value, {
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

async function openFile(item: FileNodeItem) {
  contentLoading.value = true
  content.value = null
  try {
    content.value = await getFileContent(projectId.value, item.path)
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

function scoreLevelClass(level: string | undefined | null): string {
  const lv = (level ?? '').toLowerCase()
  return lv === 'excellent' || lv === 'good' || lv === 'fair' || lv === 'poor' ? lv : ''
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

async function loadAll() {
  await loadDetail()
  const st = detail.value?.status
  // 审查修复：FAILED 状态也加载分析历史/报告（项目可能之前有 SUCCEEDED 分析）
  await loadHistory()
  if (st === 'READY') {
    loadFiles()
    await loadQuality()
  } else if (st === 'ANALYZING') {
    // P9e：仅 FULL/REGENERATE 分析中订阅 SSE（CREATED 快扫无事件流，避免空挂）
    connectProgress()
    pollIfRunning()
  } else {
    pollIfRunning()
  }
}

onMounted(loadAll)

// 审查修复：路由参数变化（同组件复用跳转其他项目）时重置状态并重载
watch(
  () => route.params.id,
  () => {
    activeSection.value = 'report'
    detail.value = null
    report.value = null
    analyses.value = []
    quality.value = null
    files.value = []
    content.value = null
    loadError.value = ''
    if (projectTimer) window.clearInterval(projectTimer)
    if (analysisTimer) window.clearInterval(analysisTimer)
    closeProgress?.()
    closeProgress = null
    void loadAll()
  },
)

onBeforeUnmount(() => {
  if (projectTimer) window.clearInterval(projectTimer)
  if (analysisTimer) window.clearInterval(analysisTimer)
  closeProgress?.()
  closeProgress = null
  if (toastTimer) window.clearTimeout(toastTimer)
})
const STATUS_TAG: Record<ProjectStatus, 'default' | 'info' | 'success' | 'error'> = {
  CREATED: 'default',
  ANALYZING: 'info',
  READY: 'success',
  FAILED: 'error',
}
const STATUS_LABEL: Record<ProjectStatus, string> = {
  CREATED: '已创建',
  ANALYZING: '分析中',
  READY: '就绪',
  FAILED: '失败',
}
function statusTagType(s: ProjectStatus): 'default' | 'info' | 'success' | 'error' {
  return STATUS_TAG[s] ?? 'default'
}
function statusLabel(s: ProjectStatus): string {
  return STATUS_LABEL[s] ?? s
}
const LEVEL_TAG: Record<string, 'success' | 'info' | 'warning' | 'error'> = {
  EXCELLENT: 'success',
  GOOD: 'success',
  FAIR: 'warning',
  POOR: 'error',
}
const LEVEL_LABEL: Record<string, string> = {
  EXCELLENT: '优秀',
  GOOD: '良好',
  FAIR: '一般',
  POOR: '较差',
}
function levelTagType(lv: string): 'success' | 'info' | 'warning' | 'error' {
  return LEVEL_TAG[lv] ?? 'default'
}
function levelLabel(lv: string): string {
  return LEVEL_LABEL[lv] ?? lv
}
const LEVEL_COLOR: Record<string, string> = {
  EXCELLENT: '#0f9d58',
  GOOD: '#3fae4f',
  FAIR: '#e8890c',
  POOR: '#d64545',
}
function levelColor(lv: string): string {
  return LEVEL_COLOR[lv] ?? '#3fae4f'
}
const ANA_STATUS_TAG: Record<string, 'default' | 'info' | 'success' | 'error'> = {
  PENDING: 'default',
  RUNNING: 'info',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
}
const ANA_STATUS_LABEL: Record<string, string> = {
  PENDING: '排队中',
  RUNNING: '分析中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
}
const historyColumns: DataTableColumns<AnalysisHistoryItem> = [
  { title: 'ID', key: 'id', width: 60, render: (r) => '#' + r.id },
  { title: '类型', key: 'type', width: 80 },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (r) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: ANA_STATUS_TAG[r.status] ?? 'default' },
        () => ANA_STATUS_LABEL[r.status] ?? r.status,
      ),
  },
  {
    title: '进度',
    key: 'progress',
    width: 110,
    render: (r) => `${r.progress}%${r.stage ? ' · ' + r.stage : ''}`,
  },
  { title: '健康分', key: 'healthScore', width: 80, render: (r) => r.healthScore ?? '—' },
  { title: '来源', key: 'source', width: 70, render: (r) => r.source ?? '-' },
  { title: '完成时间', key: 'finishedAt', render: (r) => fmtTime(r.finishedAt) },
]
const SEVERITY_TAG: Record<string, 'error' | 'warning' | 'info' | 'default'> = {
  BLOCKER: 'error',
  CRITICAL: 'error',
  MAJOR: 'warning',
  MINOR: 'info',
  INFO: 'default',
}
const SEVERITY_LABEL: Record<string, string> = {
  BLOCKER: '阻断',
  CRITICAL: '严重',
  MAJOR: '主要',
  MINOR: '次要',
  INFO: '提示',
}
const qualityColumns: DataTableColumns<QualityIssueItem> = [
  {
    title: '严重度',
    key: 'severity',
    width: 90,
    render: (r) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: SEVERITY_TAG[r.severity] ?? 'default' },
        () => SEVERITY_LABEL[r.severity] ?? r.severity,
      ),
  },
  {
    title: '类型',
    key: 'kind',
    width: 100,
    render: (r) => h(NTag, { size: 'small', bordered: false }, () => r.kind),
  },
  {
    title: '位置',
    key: 'filePath',
    render: (r) => `${r.filePath}${r.line != null ? ':' + r.line : ''}`,
  },
  { title: '问题', key: 'message', render: (r) => r.message },
  {
    title: 'AI 解释',
    key: 'aiStatus',
    width: 90,
    render: (r) =>
      r.aiStatus === 'DONE' && r.aiExplanation
        ? '已解释'
        : r.aiStatus === 'FAILED'
          ? '解释失败'
          : '待解释',
  },
]
const fileColumns: DataTableColumns<FileNodeItem> = [
  { title: '路径', key: 'path', render: (r) => r.path },
  {
    title: '语言',
    key: 'language',
    width: 110,
    render: (r) => h(NTag, { size: 'small', bordered: false }, () => r.language || '-'),
  },
  { title: 'LOC', key: 'loc', width: 70, render: (r) => r.loc },
  { title: '大小', key: 'sizeBytes', width: 90, render: (r) => fmtSize(r.sizeBytes) },
  {
    title: '操作',
    key: 'actions',
    width: 90,
    render: (r) =>
      h(
        NButton,
        { size: 'small', quaternary: true, type: 'primary', onClick: () => openFile(r) },
        () => '查看内容',
      ),
  },
]
</script>
<template>
  <div v-if="detail" class="detail">
    <!-- 项目身份条 -->
    <div class="detail-head">
      <div class="detail-head-main">
        <div class="detail-head-title-row">
          <h1 class="detail-name">{{ detail.name }}</h1>
          <NTag size="small" :bordered="false" :type="statusTagType(detail.status)">
            {{ statusLabel(detail.status) }}
          </NTag>
          <span v-if="detail.sourceType === 'GIT'" class="detail-repo">{{ detail.repoUrl }}</span>
        </div>
        <p v-if="detail.description" class="detail-desc">{{ detail.description }}</p>
      </div>
      <div class="detail-head-stats">
        <div class="stat">
          <span class="stat-num">{{ detail.locTotal.toLocaleString() }}</span
          ><span class="stat-key">LOC</span>
        </div>
        <div class="stat">
          <span class="stat-num">{{ detail.fileCount.toLocaleString() }}</span
          ><span class="stat-key">文件</span>
        </div>
        <div class="stat">
          <span class="stat-num" :class="scoreLevelClass(report?.report.level)">{{
            report?.report.healthScore ?? '—'
          }}</span>
          <span class="stat-key">健康分</span>
        </div>
      </div>
    </div>

    <NAlert v-if="detail.status === 'ANALYZING'" type="info" :show-icon="true" class="detail-alert">
      自动快扫进行中…档案与报告生成后自动刷新
    </NAlert>
    <NAlert
      v-else-if="detail.status === 'FAILED'"
      type="error"
      :show-icon="true"
      class="detail-alert"
    >
      快扫失败：档案不完整，可删除后重新导入。
    </NAlert>
    <NAlert v-if="loadError" type="error" :show-icon="true" class="detail-alert">{{
      loadError
    }}</NAlert>

    <!-- 分析操作 -->
    <div class="detail-ops">
      <NButton type="primary" :loading="starting" @click="startAnalysis">
        {{ starting ? '发起中…' : '发起完整分析' }}
      </NButton>
      <NButton v-if="report && selectedId != null" :loading="regenerating" @click="onRegenerate">
        {{ regenerating ? '重新生成中…' : '重新生成报告' }}
      </NButton>
      <span class="ops-hint">完整分析 = 重新扫描 + AI 健康报告（约 1-3 分钟）</span>
    </div>

    <!-- 实时进度 -->
    <NProgress
      v-if="liveProgress"
      type="line"
      :percentage="liveProgress.progress"
      :height="8"
      :show-indicator="true"
      class="detail-progress"
    />
    <p v-if="liveProgress?.message" class="progress-msg">{{ liveProgress.message }}</p>

    <!-- 分区工作台 -->
    <n-layout class="detail-workspace" has-sider>
      <n-layout-sider bordered width="200" :native-scrollbar="false">
        <nav class="detail-nav">
          <button
            v-for="s in SECTIONS"
            :key="s.key"
            type="button"
            class="detail-nav-item"
            :class="{ active: activeSection === s.key }"
            @click="activeSection = s.key"
          >
            <span class="detail-nav-label">{{ s.label }}</span>
            <span class="detail-nav-desc">{{ s.desc }}</span>
          </button>
        </nav>
      </n-layout-sider>
      <n-layout-content class="detail-content" :native-scrollbar="false">
        <!-- 体检报告 -->
        <div v-show="activeSection === 'report'" class="section-pane">
          <ReportHistoryView :project-id="projectId" />
          <NCard v-if="report" size="small" class="report-card" :bordered="true">
            <template #header>
              <div class="report-head">
                <span class="report-title">体检报告 #{{ report.analysisId }}</span>
                <NSpace size="small">
                  <NTag size="small" :bordered="false" :type="levelTagType(report.report.level)">
                    {{ levelLabel(report.report.level) }}
                  </NTag>
                  <NTag
                    size="small"
                    :bordered="false"
                    :type="report.source === 'LLM' ? 'info' : 'default'"
                  >
                    {{ report.source === 'LLM' ? 'AI 报告' : '规则报告' }}
                  </NTag>
                </NSpace>
                <span class="report-meta"
                  >{{ report.promptVersion }} · {{ fmtTime(report.generatedAt) }}</span
                >
              </div>
            </template>

            <div class="report-score">
              <div class="score-block">
                <span class="score-num" :class="scoreLevelClass(report.report.level)">{{
                  report.report.healthScore
                }}</span>
                <span class="score-label">健康分 / 100</span>
              </div>
              <div class="score-divider" />
              <p class="report-summary">{{ report.report.summary }}</p>
            </div>

            <div class="report-dims">
              <div v-for="d in report.report.dimensions" :key="d.key" class="dim-row">
                <div class="dim-head">
                  <span class="dim-name">{{ DIM_LABEL[d.key] }}</span>
                  <span class="dim-score">{{ d.score }}</span>
                </div>
                <NProgress
                  type="line"
                  :percentage="d.score"
                  :height="6"
                  :color="levelColor(report.report.level)"
                  :rail-color="'#e6ebf1'"
                  :show-indicator="false"
                />
                <div class="dim-summary">{{ d.summary }}</div>
              </div>
            </div>
          </NCard>
          <NCard v-else-if="reportLoading" size="small" class="report-card">
            <NSpin>报告加载中…</NSpin>
          </NCard>
          <NCard v-else-if="analyses.length" size="small" class="report-card">
            该分析暂无报告（仅快扫的项目没有报告，发起完整分析后生成）
          </NCard>

          <!-- 分析历史 -->
          <NCard v-if="analyses.length" size="small" title="分析历史" class="report-card">
            <NDataTable
              :columns="historyColumns"
              :data="analyses"
              :row-key="(r) => r.id"
              :bordered="false"
              :single-line="false"
              size="small"
            />
          </NCard>
        </div>

        <!-- 质量分析 -->
        <div v-show="activeSection === 'quality'" class="section-pane">
          <NCard v-if="quality" size="small" title="质量分析" class="report-card">
            <template #header-extra>
              <NTag
                size="small"
                :bordered="false"
                :type="quality.metrics.available ? 'success' : 'default'"
              >
                {{ quality.metrics.available ? 'Sonar 已接入' : 'Sonar 未启用' }}
              </NTag>
            </template>
            <div v-if="quality.metrics.available" class="q-metrics">
              <div class="q-metric">
                <span class="q-num fail">{{ quality.metrics.bugs }}</span
                ><span class="q-key">Bug</span>
              </div>
              <div class="q-metric">
                <span class="q-num critical">{{ quality.metrics.vulnerabilities }}</span
                ><span class="q-key">漏洞</span>
              </div>
              <div class="q-metric">
                <span class="q-num">{{ quality.metrics.codeSmells }}</span
                ><span class="q-key">异味</span>
              </div>
            </div>
            <NDataTable
              v-if="quality.items.length"
              :columns="qualityColumns"
              :data="quality.items"
              :row-key="(r) => r.id"
              :bordered="false"
              :single-line="false"
              size="small"
            />
            <NEmpty
              v-else
              :description="
                quality.metrics.available
                  ? 'Sonar 扫描完成，暂未发现问题。'
                  : '质量分析未启用：配置 Sonar（SONAR_HOST_URL / SONAR_TOKEN）后发起完整分析即可获得真实质量指标。'
              "
            />
          </NCard>
        </div>

        <!-- 架构 / 演化 / 依赖 / AI 医生 / 技术债 / 文档 -->
        <div v-if="activeSection === 'architecture'" class="section-pane">
          <ArchitectureView v-if="detail?.status === 'READY'" :project-id="projectId" />
        </div>
        <div v-if="activeSection === 'evolution'" class="section-pane">
          <EvolutionView v-if="detail?.status === 'READY'" :project-id="projectId" />
        </div>
        <div v-if="activeSection === 'dependency'" class="section-pane">
          <DependencyView v-if="detail?.status === 'READY'" :project-id="projectId" />
        </div>
        <div v-if="activeSection === 'doctor'" class="section-pane">
          <DoctorView v-if="detail?.status === 'READY'" :project-id="projectId" />
        </div>
        <div v-if="activeSection === 'debt'" class="section-pane">
          <TechDebtView v-if="detail?.status === 'READY'" :project-id="projectId" />
        </div>
        <div v-if="activeSection === 'doc'" class="section-pane">
          <DocView v-if="detail?.status === 'READY'" :project-id="projectId" />
        </div>

        <!-- 文件地图 -->
        <div v-show="activeSection === 'files'" class="section-pane">
          <NCard size="small" title="文件地图" class="report-card">
            <div class="file-toolbar">
              <NInput
                v-model:value="fileKeyword"
                placeholder="按路径搜索…"
                clearable
                style="width: 220px"
                @keyup.enter="onFileSearch"
                @clear="onFileSearch"
              />
              <NButton size="small" @click="onFileSearch">搜索</NButton>
            </div>
            <NDataTable
              :columns="fileColumns"
              :data="files"
              :row-key="(r) => r.path"
              :loading="fileLoading"
              :bordered="false"
              :single-line="false"
              size="small"
              :pagination="
                fileTotal > fileSize
                  ? {
                      page: filePage,
                      pageSize: fileSize,
                      itemCount: fileTotal,
                      onUpdatePage: (p: number) => {
                        filePage = p
                        loadFiles()
                      },
                    }
                  : false
              "
            />
            <NEmpty
              v-if="!fileLoading && fileTotal === 0"
              description="暂无文件（快扫完成后显示）"
            />
          </NCard>
        </div>
      </n-layout-content>
    </n-layout>

    <!-- 内容预览 -->
    <NModal
      :show="content != null"
      preset="card"
      style="width: 80%; max-width: 900px"
      :title="content?.path"
      @update:show="
        (v: boolean) => {
          if (!v) content = null
        }
      "
    >
      <pre class="code-block">{{ content?.content }}</pre>
    </NModal>
  </div>

  <div v-else class="detail-loading">
    <NSpin v-if="!loadError" />
    <NAlert v-else type="error" :show-icon="true">{{ loadError }}</NAlert>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.detail-head-title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.detail-name {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
}
.detail-repo {
  font-size: 12px;
  color: #8798ab;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 40%;
}
.detail-desc {
  margin: 4px 0 0;
  color: #8798ab;
  font-size: 13px;
}
.detail-head-stats {
  display: flex;
  gap: 28px;
  flex-shrink: 0;
}
.stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 56px;
}
.stat-num {
  font-size: 22px;
  font-weight: 700;
}
.stat-num.excellent {
  color: #0f9d58;
}
.stat-num.good {
  color: #3fae4f;
}
.stat-num.fair {
  color: #e8890c;
}
.stat-num.poor {
  color: #d64545;
}
.stat-key {
  font-size: 11px;
  color: #8798ab;
  letter-spacing: 0.04em;
}
.detail-alert {
  margin: 0;
}
.detail-ops {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ops-hint {
  font-size: 12px;
  color: #8798ab;
}
.detail-progress {
  max-width: 600px;
}
.progress-msg {
  margin: 4px 0 0;
  font-size: 12px;
  color: #8798ab;
}
.detail-workspace {
  background: transparent;
}
.detail-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 6px;
}
.detail-nav-item {
  display: flex;
  flex-direction: column;
  gap: 1px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  border-left: 2px solid transparent;
  border-radius: 4px;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition:
    background 150ms ease,
    color 150ms ease;
}
.detail-nav-item:hover {
  background: #f6f9fd;
}
.detail-nav-item.active {
  border-left-color: #1668dc;
  background: rgba(22, 104, 220, 0.08);
}
.detail-nav-label {
  font-size: 13.5px;
  font-weight: 600;
  color: #1b2633;
}
.detail-nav-desc {
  font-size: 11.5px;
  color: #8798ab;
}
.detail-nav-item.active .detail-nav-label {
  color: #1668dc;
}
.detail-content {
  padding: 0 0 0 16px;
  background: transparent;
}
.section-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.report-card {
  background: #fff;
}
.report-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.report-title {
  font-size: 15px;
  font-weight: 600;
}
.report-meta {
  margin-left: auto;
  font-size: 12px;
  color: #8798ab;
}
.report-score {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 8px 0 14px;
  border-bottom: 1px solid #eef1f5;
}
.score-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  min-width: 90px;
}
.score-num {
  font-size: 40px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
}
.score-num.excellent {
  color: #0f9d58;
}
.score-num.good {
  color: #3fae4f;
}
.score-num.fair {
  color: #e8890c;
}
.score-num.poor {
  color: #d64545;
}
.score-label {
  font-size: 11px;
  color: #8798ab;
  letter-spacing: 0.05em;
}
.score-divider {
  width: 1px;
  height: 56px;
  background: #e2e8f0;
}
.report-summary {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.7;
  color: #55667a;
  flex: 1;
}
.report-dims {
  margin-top: 14px;
}
.dim-row {
  padding: 10px 0;
  border-bottom: 1px dashed #e2e8f0;
}
.dim-row:last-child {
  border-bottom: none;
}
.dim-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
}
.dim-name {
  font-size: 13.5px;
  font-weight: 600;
}
.dim-score {
  font-size: 14px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.dim-summary {
  margin-top: 6px;
  font-size: 12.5px;
  color: #8798ab;
}
.q-metrics {
  display: flex;
  gap: 24px;
  padding: 8px 0 14px;
}
.q-metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.q-num {
  font-size: 22px;
  font-weight: 700;
}
.q-num.fail {
  color: #d64545;
}
.q-num.critical {
  color: #e8890c;
}
.q-key {
  font-size: 11px;
  color: #8798ab;
}
.file-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.code-block {
  margin: 0;
  padding: 14px;
  background: #f4f6f9;
  border-radius: 4px;
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  overflow: auto;
  max-height: 60vh;
  white-space: pre;
}
.detail-loading {
  padding: 60px 0;
  display: flex;
  justify-content: center;
}
</style>
