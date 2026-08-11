<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { deleteProject, listProjects } from '../../api/project'
import type { ProjectStatus, ProjectSummary } from '../../types/api'

const router = useRouter()

const items = ref<ProjectSummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const statusFilter = ref('')
const loading = ref(false)
const error = ref('')

const STATUS_META: Record<ProjectStatus, { label: string; cls: string }> = {
  CREATED: { label: '已创建', cls: 'st-created' },
  ANALYZING: { label: '分析中', cls: 'st-analyzing' },
  READY: { label: '就绪', cls: 'st-ready' },
  FAILED: { label: '失败', cls: 'st-failed' },
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listProjects({
      page: page.value,
      size: size.value,
      keyword: keyword.value.trim() || undefined,
      status: statusFilter.value || undefined,
    })
    items.value = data.items
    total.value = data.total
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function onSearch() {
  page.value = 1
  load()
}

function onPage(p: number) {
  page.value = p
  load()
}

function langTags(item: ProjectSummary): string[] {
  if (!item.langStats) return []
  return Object.entries(item.langStats)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 3)
    .map(([lang, pct]) => `${lang} ${pct.toFixed(1)}%`)
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
      <input v-model="keyword" class="input" placeholder="按项目名搜索…" @keyup.enter="onSearch" />
      <select v-model="statusFilter" class="input select" @change="onSearch">
        <option value="">全部状态</option>
        <option value="CREATED">已创建</option>
        <option value="ANALYZING">分析中</option>
        <option value="READY">就绪</option>
        <option value="FAILED">失败</option>
      </select>
      <button class="btn" type="button" @click="onSearch">搜索</button>
    </div>

    <div v-if="error" class="alert-fail">加载失败：{{ error }}</div>
    <p v-else-if="!loading && total === 0" class="empty">
      暂无项目，点击右上角「新建项目」导入第一个仓库。
    </p>

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
              <span v-for="t in langTags(item)" :key="t" class="tag">{{ t }}</span>
            </span>
            <span v-else class="muted">-</span>
          </td>
          <td>{{ item.locTotal.toLocaleString() }}</td>
          <td>{{ item.fileCount.toLocaleString() }}</td>
          <td>
            <span
              v-if="item.healthScore != null"
              class="score"
              :class="item.healthScore >= 80 ? 'ok' : item.healthScore >= 60 ? 'warn' : 'fail'"
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
  background: var(--bg-page);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 24px 28px;
}
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.desc {
  margin-top: 4px;
  color: var(--text-secondary);
  font-size: 13px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin: 16px 0;
}
.input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
}
.input:focus {
  outline: 2px solid rgba(47, 111, 237, 0.25);
  border-color: var(--primary-color);
}
.select {
  min-width: 120px;
}
.btn {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--border-color);
  border-radius: 6px;
  background: #fff;
  font-size: 13px;
  cursor: pointer;
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
.btn-primary {
  height: 36px;
  padding: 0 18px;
  border: none;
  border-radius: 6px;
  background: var(--primary-color);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
.btn-primary:hover {
  filter: brightness(1.08);
}
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
  background: rgba(47, 111, 237, 0.04);
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
  color: #6b7280;
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
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.tag {
  padding: 1px 6px;
  border-radius: 4px;
  background: #eef2f7;
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
  color: #d97706;
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
  border-radius: 6px;
  background: rgba(220, 38, 38, 0.08);
  color: var(--fail-color);
  font-size: 13px;
}
.empty {
  padding: 40px 0;
  text-align: center;
  color: var(--text-secondary);
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 16px;
}
.page-info {
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
