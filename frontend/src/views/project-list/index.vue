<script setup lang="ts">
import { computed, ref, h, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { NButton, NDataTable, NInput, NProgress, NSelect, NSpace, NTag, useDialog } from 'naive-ui'
import { listProjects, deleteProject, exportReport } from '../../api/project'
import type { ProjectStatus, ProjectSummary } from '../../types/api'

const router = useRouter()
const message = useMessage()
const dialog = useDialog()

const loading = ref(false)
const items = ref<ProjectSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const statusFilter = ref<ProjectStatus | ''>('')
const languageFilter = ref('')
const sortBy = ref<'createdAt' | 'lastAnalyzedAt' | 'locTotal' | 'name' | 'healthScore'>(
  'lastAnalyzedAt',
)
const orderDir = ref<'asc' | 'desc'>('desc')

const STATUS_OPTIONS = [
  { label: '全部状态', value: '' },
  { label: '已创建', value: 'CREATED' },
  { label: '分析中', value: 'ANALYZING' },
  { label: '就绪', value: 'READY' },
  { label: '失败', value: 'FAILED' },
]

const STATUS_TAG: Record<
  ProjectStatus,
  { type: 'default' | 'info' | 'success' | 'error'; label: string }
> = {
  CREATED: { type: 'default', label: '已创建' },
  ANALYZING: { type: 'info', label: '分析中' },
  READY: { type: 'success', label: '就绪' },
  FAILED: { type: 'error', label: '失败' },
}

const LANG_COLORS: Record<string, string> = {
  Java: '#b07219',
  Python: '#3572A5',
  JavaScript: '#f1e05a',
  TypeScript: '#3178c6',
  Go: '#00ADD8',
  Vue: '#41b883',
  C: '#555555',
  'C++': '#f34b7d',
  'C#': '#178600',
  Rust: '#dea584',
  PHP: '#4F5D95',
  Ruby: '#701516',
  Swift: '#F05138',
  Kotlin: '#A97BFF',
  HTML: '#e34c26',
  CSS: '#563d7c',
  Shell: '#89e051',
  Dockerfile: '#384d54',
  SQL: '#e38c00',
}

const STATUS_COLOR: Record<string, string> = {
  EXCELLENT: '#0f9d58',
  GOOD: '#3fae4f',
  FAIR: '#e8890c',
  POOR: '#d64545',
}

function levelOf(score: number | null): 'EXCELLENT' | 'GOOD' | 'FAIR' | 'POOR' {
  if (score == null) return 'POOR'
  if (score >= 80) return 'EXCELLENT'
  if (score >= 60) return 'GOOD'
  if (score >= 40) return 'FAIR'
  return 'POOR'
}

let loadSeq = 0
async function load() {
  const seq = ++loadSeq
  loading.value = true
  try {
    const data = await listProjects({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      language: languageFilter.value || undefined,
      status: statusFilter.value || undefined,
      sort: sortBy.value,
      order: orderDir.value,
    })
    if (seq !== loadSeq) return
    items.value = data.items
    total.value = data.total
  } catch (e) {
    if (seq === loadSeq) message.error(e instanceof Error ? e.message : String(e))
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

function onPageChange(p: number) {
  page.value = p
  load()
}

function onSearch() {
  page.value = 1
  load()
}

function onFilterChange() {
  page.value = 1
  load()
}

function onSortChange(sorter: { columnKey?: string; order?: 'ascend' | 'descend' }) {
  const key = sorter.columnKey as typeof sortBy.value | undefined
  if (!key) return
  sortBy.value = key
  orderDir.value = sorter.order === 'ascend' ? 'asc' : 'desc'
  page.value = 1
  load()
}

async function onDelete(item: ProjectSummary) {
  dialog.warning({
    title: '删除项目',
    content: `确定删除「${item.name}」？将同时清理磁盘目录与全部分析数据。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteProject(item.id)
        if (items.value.length === 1 && page.value > 1) page.value -= 1
        load()
        message.success('已删除')
      } catch (e) {
        message.error(e instanceof Error ? e.message : String(e))
      }
    },
  })
}

async function onExport(item: ProjectSummary) {
  try {
    const blob = await exportReport(item.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `evocode-report-${item.id}.md`
    a.click()
    // 审查修复：立即 revoke 可能导致部分浏览器下载中断，延迟释放
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (e) {
    message.error(e instanceof Error ? e.message : String(e))
  }
}

const langOptions = computed(() => {
  const map = new Map<string, number>()
  for (const item of items.value) {
    if (!item.langStats) continue
    for (const [lang, pct] of Object.entries(item.langStats)) {
      map.set(lang, (map.get(lang) ?? 0) + pct)
    }
  }
  return [...map.entries()].sort((a, b) => b[1] - a[1]).map(([label, value]) => ({ label, value }))
})

function fmtTime(iso: string | null): string {
  if (!iso) return '-'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

const columns = computed<DataTableColumns<ProjectSummary>>(() => [
  {
    title: '项目',
    key: 'name',
    sorter: true,
    minWidth: 200,
    render(row) {
      return h('div', { class: 'cell-name' }, [
        h(
          'a',
          {
            class: 'cell-name-link',
            onClick: () => router.push(`/projects/${row.id}`),
          },
          row.name,
        ),
        h('div', { class: 'cell-name-sub' }, row.sourceType === 'GIT' ? 'GitHub 仓库' : 'zip 上传'),
      ])
    },
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render(row) {
      const meta = STATUS_TAG[row.status] ?? STATUS_TAG.CREATED
      return h(NTag, { size: 'small', type: meta.type, bordered: false }, () => meta.label)
    },
  },
  {
    title: '健康分',
    key: 'healthScore',
    sorter: true,
    width: 160,
    render(row) {
      const score = row.healthScore
      const lv = levelOf(score)
      return h('div', { class: 'cell-gauge' }, [
        h(
          NProgress,
          {
            type: 'line',
            percentage: score ?? 0,
            height: 8,
            railColor: '#e6ebf1',
            color: STATUS_COLOR[lv],
            showIndicator: false,
          },
          undefined,
        ),
        h(
          'span',
          {
            class: 'cell-gauge-score',
            style: { color: score == null ? '#8798ab' : STATUS_COLOR[lv] },
          },
          score ?? '—',
        ),
      ])
    },
  },
  {
    title: '语言分布',
    key: 'langStats',
    minWidth: 160,
    render(row) {
      if (!row.langStats) return h('span', { class: 'muted' }, '-')
      const top = Object.entries(row.langStats)
        .sort((a, b) => b[1] - a[1])
        .slice(0, 3)
      return h(NSpace, { size: 4 }, () =>
        top.map(([lang, pct]) =>
          h(
            NTag,
            {
              size: 'small',
              bordered: false,
              style: {
                color: LANG_COLORS[lang] ?? '#55667a',
                background: `color-mix(in srgb, ${LANG_COLORS[lang] ?? '#8899aa'} 14%, transparent)`,
              },
            },
            () => `${lang} ${Number(pct).toFixed(0)}%`,
          ),
        ),
      )
    },
  },
  {
    title: '规模',
    key: 'locTotal',
    sorter: true,
    width: 120,
    render(row) {
      return h('div', { class: 'tabular-nums' }, `${row.locTotal.toLocaleString()} 行`)
    },
  },
  {
    title: '最近分析',
    key: 'lastAnalyzedAt',
    sorter: true,
    width: 170,
    render(row) {
      return h('span', { class: 'muted tabular-nums' }, fmtTime(row.lastAnalyzedAt))
    },
  },
  {
    title: '操作',
    key: 'actions',
    width: 160,
    render(row) {
      return h(NSpace, { size: 4 }, () => [
        h(
          NButton,
          {
            size: 'small',
            quaternary: true,
            type: 'primary',
            onClick: () => router.push(`/projects/${row.id}`),
          },
          () => '查看',
        ),
        h(NButton, { size: 'small', quaternary: true, onClick: () => onExport(row) }, () => '导出'),
        h(
          NButton,
          { size: 'small', quaternary: true, type: 'error', onClick: () => onDelete(row) },
          () => '删除',
        ),
      ])
    },
  },
])

onMounted(load)
</script>

<template>
  <div class="page-list">
    <div class="page-head">
      <div>
        <h1 class="page-title">项目档案</h1>
        <p class="page-desc">导入项目（zip 或 GitHub）→ 自动快扫 → 生成软件健康档案</p>
      </div>
      <NButton type="primary" @click="router.push('/projects/create')">＋ 新建项目</NButton>
    </div>

    <div class="toolbar">
      <NInput
        v-model:value="keyword"
        placeholder="按项目名搜索…"
        clearable
        style="width: 220px"
        @keyup.enter="onSearch"
        @clear="onSearch"
      />
      <NSelect
        :value="languageFilter"
        :options="langOptions"
        placeholder="全部语言"
        clearable
        style="width: 160px"
        @update:value="
          (v) => {
            languageFilter = v ?? ''
            onFilterChange()
          }
        "
      />
      <NSelect
        :value="statusFilter"
        :options="STATUS_OPTIONS"
        style="width: 140px"
        @update:value="
          (v) => {
            statusFilter = v ?? ''
            onFilterChange()
          }
        "
      />
    </div>

    <NDataTable
      :columns="columns"
      :data="items"
      :loading="loading"
      :pagination="{
        page: page,
        pageSize: size,
        itemCount: total,
        pageSizes: [12, 24, 48],
        showSizePicker: true,
        onUpdatePage: onPageChange,
        onUpdatePageSize: (s: number) => {
          size = s
          page = 1
          load()
        },
      }"
      :row-key="(row: ProjectSummary) => row.id"
      :bordered="false"
      :single-line="false"
      @update:sorter="onSortChange"
    />

    <div v-if="!loading && total === 0" class="empty-state">
      <p>还没有项目，创建一个开始体检</p>
      <NButton type="primary" @click="router.push('/projects/create')">新建项目</NButton>
    </div>
  </div>
</template>

<style scoped>
.page-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}
.page-desc {
  margin: 4px 0 0;
  font-size: 13px;
  color: #8798ab;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
}
.cell-name {
  display: flex;
  flex-direction: column;
}
.cell-name-link {
  font-size: 14px;
  font-weight: 600;
  color: #1b2633;
  text-decoration: none;
  cursor: pointer;
}
.cell-name-link:hover {
  color: #1668dc;
}
.cell-name-sub {
  font-size: 12px;
  color: #8798ab;
  margin-top: 2px;
}
.cell-gauge {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 120px;
}
.cell-gauge-score {
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  width: 28px;
  text-align: right;
}
.muted {
  color: #8798ab;
}
.empty-state {
  padding: 60px 0;
  text-align: center;
  color: #8798ab;
}
.empty-state p {
  margin-bottom: 16px;
}
</style>
