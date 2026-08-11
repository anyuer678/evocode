<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteProject, listProjects } from '../../api/project'
import type { ProjectStatus, ProjectSummary } from '../../types/api'

const router = useRouter()

const items = ref<ProjectSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const languageFilter = ref('')
const statusFilter = ref('')
const sortBy = ref('lastAnalyzedAt')
const orderDir = ref<'asc' | 'desc'>('desc')
const viewMode = ref<'card' | 'list'>(
  localStorage.getItem('evocode-view') === 'list' ? 'list' : 'card',
)
const loading = ref(false)
const error = ref('')

const VIEW_KEY = 'evocode-view'

const STATUS_META: Record<ProjectStatus, { label: string; cls: string }> = {
  CREATED: { label: '已创建', cls: 'st-created' },
  ANALYZING: { label: '分析中', cls: 'st-analyzing' },
  READY: { label: '就绪', cls: 'st-ready' },
  FAILED: { label: '失败', cls: 'st-failed' },
}

const SORT_OPTIONS = [
  { value: 'lastAnalyzedAt', label: '最近分析' },
  { value: 'locTotal', label: '代码量' },
  { value: 'createdAt', label: '创建时间' },
]
// 注：healthScore 排序需后端白名单补充（P9e 加入，见 p9-design.md §E2）

/** 固定语言色板（P9a）：浅色主色，深色用弱化变体 */
const LANG_COLORS: Record<string, string> = {
  Java: '#e76f00',
  JavaScript: '#d9a300',
  TypeScript: '#2f6fed',
  Python: '#2b6cb0',
  Go: '#0e7490',
  Vue: '#3aa675',
  HTML: '#d94f4f',
  CSS: '#7c3aed',
  XML: '#6b7280',
  Shell: '#4a8c3f',
}

// 语言筛选选项：从当前页项目 lang_stats 聚合（避免每次筛选请求，沿用 size 上限）
const langOptions = computed(() => {
  const map = new Map<string, number>()
  for (const item of items.value) {
    if (!item.langStats) continue
    for (const [lang, pct] of Object.entries(item.langStats)) {
      map.set(lang, (map.get(lang) ?? 0) + pct)
    }
  }
  return [...map.entries()].sort((a, b) => b[1] - a[1]).map(([name]) => name)
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listProjects({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      language: languageFilter.value || undefined,
      status: statusFilter.value || undefined,
      sort: sortBy.value as 'createdAt' | 'lastAnalyzedAt' | 'locTotal' | 'name' | undefined,
      order: orderDir.value,
    })
    items.value = data.items
    total.value = data.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

let debounceTimer = 0
function onSearch() {
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => {
    page.value = 1
    load()
  }, 300)
}

function onFilterChange() {
  page.value = 1
  load()
}

function resetFilters() {
  keyword.value = ''
  languageFilter.value = ''
  statusFilter.value = ''
  sortBy.value = 'lastAnalyzedAt'
  orderDir.value = 'desc'
  onFilterChange()
}

function onSortChange() {
  page.value = 1
  load()
}

function onPage(p: number) {
  page.value = p
  load()
}

function toggleView(mode: 'card' | 'list') {
  viewMode.value = mode
  localStorage.setItem(VIEW_KEY, mode)
}

function langTags(item: ProjectSummary): { lang: string; pct: number }[] {
  if (!item.langStats) return []
  return Object.entries(item.langStats)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([lang, pct]) => ({ lang, pct }))
}

function langColor(lang: string): string {
  return LANG_COLORS[lang] ?? '#6b7280'
}

/** 健康分环形小图（φ36，SVG stroke-dasharray 按分数着色） */
function ringStyle(score: number | null): { color: string; dash: string } {
  if (score == null) return { color: 'var(--bg-muted)', dash: '0 999' }
  const color =
    score >= 80 ? 'var(--ok-color)' : score >= 60 ? 'var(--warn-color)' : 'var(--fail-color)'
  const r = 15
  const c = 2 * Math.PI * r
  return { color, dash: `${(c * score) / 100} ${c}` }
}

function scoreClass(score: number | null): string {
  if (score == null) return ''
  return score >= 80 ? 'ok' : score >= 60 ? 'warn' : 'fail'
}

async function onDelete(item: ProjectSummary) {
  if (!window.confirm(`确定删除项目「${item.name}」？将同时清理磁盘目录与全部分析数据。`)) return
  try {
    await deleteProject(item.id)
    // 删掉本页最后一条时回退一页，避免空页
    if (items.value.length === 1 && page.value > 1) page.value -= 1
    load()
  } catch (e) {
    window.alert(e instanceof Error ? e.message : String(e))
  }
}

function fmtTime(iso: string | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

function fmtDate(iso: string | null): string {
  if (!iso) return '未分析'
  return new Date(iso).toLocaleDateString('zh-CN')
}

onMounted(load)
</script>

<template>
  <section class="page">
    <div class="page-head">
      <div>
        <h1>项目列表</h1>
        <p class="desc">导入项目（zip 或 GitHub）→ 自动快扫 → 生成软件健康档案</p>
      </div>
      <button class="btn-primary" type="button" @click="router.push('/projects/create')">
        + 新建项目
      </button>
    </div>

    <div class="toolbar">
      <input v-model="keyword" class="input grow" placeholder="按项目名搜索…" @input="onSearch" />
      <select v-model="languageFilter" class="input select" @change="onFilterChange">
        <option value="">全部语言</option>
        <option v-for="lang in langOptions" :key="lang" :value="lang">{{ lang }}</option>
      </select>
      <select v-model="statusFilter" class="input select" @change="onFilterChange">
        <option value="">全部状态</option>
        <option value="CREATED">已创建</option>
        <option value="ANALYZING">分析中</option>
        <option value="READY">就绪</option>
        <option value="FAILED">失败</option>
      </select>
      <select v-model="sortBy" class="input select" @change="onSortChange">
        <option v-for="opt in SORT_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </option>
      </select>
      <button
        class="btn small icon-btn"
        type="button"
        :title="viewMode === 'card' ? '切换到列表' : '切换到卡片'"
        @click="toggleView(viewMode === 'card' ? 'list' : 'card')"
      >
        {{ viewMode === 'card' ? '☰' : '▦' }}
      </button>
    </div>

    <div v-if="error" class="alert-fail">
      <p>加载失败：{{ error }}</p>
      <button class="btn small" type="button" @click="load">重试</button>
    </div>

    <!-- 骨架屏（加载中占位，与真实卡片同尺寸） -->
    <div v-else-if="loading" class="card-grid" data-test="skeleton">
      <div v-for="n in 6" :key="n" class="skeleton-card">
        <div class="sk sk-title" />
        <div class="sk sk-line" />
        <div class="sk sk-line short" />
      </div>
    </div>

    <!-- 空态一：无项目 -->
    <div
      v-else-if="!loading && total === 0 && !keyword && !statusFilter && !languageFilter"
      class="empty-state"
    >
      <svg viewBox="0 0 64 64" width="64" height="64" aria-hidden="true">
        <rect
          x="8"
          y="14"
          width="48"
          height="38"
          rx="4"
          fill="var(--primary-weak)"
          stroke="var(--primary-color)"
          stroke-width="2"
        />
        <path d="M8 22h48" stroke="var(--primary-color)" stroke-width="2" />
        <path d="M24 14v8" stroke="var(--primary-color)" stroke-width="2" />
        <circle
          cx="44"
          cy="42"
          r="8"
          fill="var(--bg-card)"
          stroke="var(--primary-color)"
          stroke-width="2"
        />
        <path d="M44 38v8M40 42h8" stroke="var(--primary-color)" stroke-width="2" />
      </svg>
      <p class="empty-title">还没有项目，创建一个开始体检</p>
      <button class="btn-primary" type="button" @click="router.push('/projects/create')">
        创建项目
      </button>
    </div>

    <!-- 空态二：筛选无结果 -->
    <div v-else-if="total === 0" class="empty-state">
      <svg viewBox="0 0 64 64" width="64" height="64" aria-hidden="true">
        <circle
          cx="26"
          cy="26"
          r="16"
          fill="none"
          stroke="var(--text-secondary)"
          stroke-width="3"
        />
        <path
          d="M38 38l12 12"
          stroke="var(--text-secondary)"
          stroke-width="3"
          stroke-linecap="round"
        />
        <path d="M26 20v12M20 26h12" stroke="var(--text-secondary)" stroke-width="2" />
      </svg>
      <p class="empty-title">没有匹配的项目，调整筛选条件</p>
      <button class="btn" type="button" @click="resetFilters">重置筛选</button>
    </div>

    <!-- 卡片视图 -->
    <div v-else-if="viewMode === 'card'" class="card-grid">
      <article
        v-for="(item, idx) in items"
        :key="item.id"
        class="project-card"
        :style="{ transitionDelay: `${idx * 30}ms` }"
        @click="router.push(`/projects/${item.id}`)"
      >
        <div class="card-top">
          <div class="ring" :style="{ borderColor: ringStyle(item.healthScore).color }">
            <svg viewBox="0 0 40 40" width="36" height="36">
              <circle
                cx="20"
                cy="20"
                r="15"
                fill="none"
                stroke="var(--bg-muted)"
                stroke-width="4"
              />
              <circle
                cx="20"
                cy="20"
                r="15"
                fill="none"
                :stroke="ringStyle(item.healthScore).color"
                stroke-width="4"
                stroke-linecap="round"
                :stroke-dasharray="ringStyle(item.healthScore).dash"
                transform="rotate(-90 20 20)"
              />
            </svg>
            <span class="ring-num" :class="scoreClass(item.healthScore)">
              {{ item.healthScore ?? '—' }}
            </span>
          </div>
          <div class="card-title-wrap">
            <h3 class="card-title">{{ item.name }}</h3>
            <span class="card-source">
              {{ item.sourceType === 'GIT' ? 'GitHub 仓库' : 'zip 上传' }}
            </span>
          </div>
          <button class="btn small icon-btn ops" type="button" title="操作" @click.stop="void 0">
            ⋮
          </button>
        </div>

        <div class="card-meta">
          <span class="status-dot" :class="STATUS_META[item.status].cls">
            {{ STATUS_META[item.status].label }}
          </span>
        </div>

        <div class="card-langs">
          <span
            v-for="t in langTags(item)"
            :key="t.lang"
            class="lang-badge"
            :style="{
              color: langColor(t.lang),
              background: `color-mix(in srgb, ${langColor(t.lang)} 12%, transparent)`,
            }"
          >
            {{ t.lang }} {{ t.pct.toFixed(1) }}%
          </span>
          <span v-if="!langTags(item).length" class="muted">-</span>
        </div>

        <div class="card-stats">
          <span>{{ item.locTotal.toLocaleString() }} 行</span>
          <span>{{ item.fileCount.toLocaleString() }} 文件</span>
          <span>{{ fmtDate(item.lastAnalyzedAt) }} 分析</span>
        </div>
      </article>
    </div>

    <!-- 列表视图（保留原有表格，查看/删除操作） -->
    <table v-else class="table">
      <thead>
        <tr>
          <th>项目</th>
          <th>状态</th>
          <th>语言分布</th>
          <th>LOC</th>
          <th>文件数</th>
          <th>健康分</th>
          <th>创建时间</th>
          <th class="col-ops">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="item in items" :key="item.id">
          <td>
            <a class="link" @click="router.push(`/projects/${item.id}`)">{{ item.name }}</a>
            <div class="sub">
              {{ item.sourceType === 'GIT' ? 'GitHub 仓库' : 'zip 上传' }}
            </div>
          </td>
          <td>
            <span class="badge" :class="STATUS_META[item.status].cls">
              {{ STATUS_META[item.status].label }}
            </span>
          </td>
          <td>
            <span v-if="langTags(item).length" class="tags">
              <span v-for="t in langTags(item)" :key="t.lang" class="tag"
                >{{ t.lang }} {{ t.pct.toFixed(1) }}%</span
              >
            </span>
            <span v-else class="muted">-</span>
          </td>
          <td>{{ item.locTotal.toLocaleString() }}</td>
          <td>{{ item.fileCount.toLocaleString() }}</td>
          <td>
            <span
              v-if="item.healthScore != null"
              class="score"
              :class="scoreClass(item.healthScore)"
            >
              {{ item.healthScore }}
            </span>
            <span v-else class="muted">-</span>
          </td>
          <td class="muted">{{ fmtTime(item.createdAt) }}</td>
          <td class="col-ops">
            <button class="btn small" type="button" @click="router.push(`/projects/${item.id}`)">
              查看
            </button>
            <button class="btn small danger" type="button" @click="onDelete(item)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>

    <div v-if="total > size" class="pager">
      <button class="btn small" type="button" :disabled="page <= 1" @click="onPage(page - 1)">
        上一页
      </button>
      <span class="page-info"
        >第 {{ page }} / {{ Math.max(1, Math.ceil(total / size)) }} 页 · 共 {{ total }} 条</span
      >
      <button
        class="btn small"
        type="button"
        :disabled="page >= Math.ceil(total / size)"
        @click="onPage(page + 1)"
      >
        下一页
      </button>
    </div>
  </section>
</template>

<style scoped>
.page {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-lg);
  padding: var(--space-5) var(--space-6);
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--space-4);
}
.desc {
  margin-top: var(--space-1);
  color: var(--text-secondary);
  font-size: 13px;
}
.toolbar {
  display: flex;
  gap: var(--space-2);
  margin: var(--space-4) 0;
  flex-wrap: wrap;
}
.input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-card);
  font-size: 13px;
  font-family: var(--font-family);
  transition:
    border-color var(--transition),
    box-shadow var(--transition);
}
.input:focus {
  outline: var(--focus-ring);
  outline-offset: 1px;
  border-color: var(--primary-color);
}
.input.grow {
  flex: 1;
  min-width: 180px;
}
.select {
  min-width: 110px;
}
.btn {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  background: var(--bg-card);
  font-size: 13px;
  font-family: var(--font-family);
  cursor: pointer;
  transition:
    border-color var(--transition),
    color var(--transition),
    transform var(--transition);
}
.btn:hover {
  border-color: var(--primary-color);
  color: var(--primary-color);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.small {
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
}
.btn.danger:hover {
  border-color: var(--fail-color);
  color: var(--fail-color);
}
.icon-btn {
  font-size: 14px;
  line-height: 1;
}
.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--primary-color);
  color: var(--bg-card);
  font-size: 14px;
  font-family: var(--font-family);
  cursor: pointer;
  transition: filter var(--transition);
}
.btn-primary:hover {
  filter: brightness(1.08);
}

/* ---- 卡片网格 ---- */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--space-4);
}
.project-card {
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-xs);
  cursor: pointer;
  transition:
    box-shadow var(--transition),
    transform var(--transition),
    opacity var(--transition);
  animation: card-in 250ms ease both;
}
.project-card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
}
.project-card:hover .card-title {
  color: var(--primary-color);
}
@keyframes card-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.card-top {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}
.ring {
  position: relative;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 50%;
  border: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: center;
}
.ring-num {
  position: absolute;
  font-size: 10px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.ring-num.ok {
  color: var(--ok-color);
}
.ring-num.warn {
  color: var(--warn-color);
}
.ring-num.fail {
  color: var(--fail-color);
}
.card-title-wrap {
  flex: 1;
  min-width: 0;
}
.card-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color var(--transition);
}
.card-source {
  font-size: 12px;
  color: var(--text-secondary);
}
.card-meta {
  margin-top: var(--space-3);
}
.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}
.status-dot::before {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.status-dot.st-ready::before {
  background: var(--ok-color);
}
.status-dot.st-analyzing::before {
  background: var(--primary-color);
  animation: evo-pulse 1.2s ease-in-out infinite;
}
.status-dot.st-failed::before {
  background: var(--fail-color);
}
.status-dot.st-created::before {
  background: var(--text-secondary);
}
.card-langs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
  margin-top: var(--space-3);
}
.lang-badge {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
}
.card-stats {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
  margin-top: var(--space-3);
  padding-top: var(--space-2);
  border-top: 1px solid var(--border-color);
  font-size: 12px;
  color: var(--text-secondary);
}

/* ---- 骨架屏 ---- */
.skeleton-card {
  height: 148px;
  background: var(--bg-card);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  padding: var(--space-4);
  box-shadow: var(--shadow-xs);
}
.sk {
  background: var(--skeleton-bg);
  border-radius: 4px;
  animation: evo-pulse 1.4s ease-in-out infinite;
}
.sk-title {
  width: 55%;
  height: 14px;
  margin-bottom: var(--space-3);
}
.sk-line {
  width: 100%;
  height: 10px;
  margin-top: var(--space-2);
}
.sk-line.short {
  width: 40%;
}

/* ---- 空态 ---- */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-3);
  padding: 56px 0;
  text-align: center;
}
.empty-title {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
}

/* ---- 列表视图 ---- */
.table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.table th,
.table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--border-color);
}
.table th {
  color: var(--text-secondary);
  font-weight: 600;
  white-space: nowrap;
}
.table tbody tr:hover {
  background: var(--primary-weak);
}
.link {
  color: var(--primary-color);
  cursor: pointer;
  font-weight: 600;
}
.sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--text-secondary);
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 12px;
  white-space: nowrap;
}
.st-created {
  color: var(--text-secondary);
  background: var(--bg-muted);
}
.st-analyzing {
  color: var(--primary-color);
  background: var(--primary-weak);
}
.st-ready {
  color: var(--ok-color);
  background: var(--ok-weak);
}
.st-failed {
  color: var(--fail-color);
  background: var(--fail-weak);
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}
.tag {
  padding: 1px 6px;
  border-radius: var(--radius-sm);
  background: var(--bg-muted);
  font-size: 12px;
  color: var(--text-primary);
}
.score {
  font-weight: 700;
}
.score.ok {
  color: var(--ok-color);
}
.score.warn {
  color: var(--warn-color);
}
.score.fail {
  color: var(--fail-color);
}
.muted {
  color: var(--text-secondary);
}
.col-ops {
  white-space: nowrap;
}
.col-ops .btn + .btn {
  margin-left: 6px;
}
.alert-fail {
  padding: 10px 14px;
  margin: 12px 0;
  border-radius: var(--radius-sm);
  background: var(--fail-weak);
  color: var(--fail-color);
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-2);
}
.alert-fail p {
  margin: 0;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-3);
  margin-top: var(--space-4);
}
.page-info {
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
