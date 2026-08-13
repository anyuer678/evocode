<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NAlert,
  NButton,
  NCard,
  NEmpty,
  NInput,
  NModal,
  NSpace,
  NSpin,
  NTabPane,
  NTabs,
  NTag,
} from 'naive-ui'
import { editDoc, fetchDocs, generateDoc } from '../../api/doc'
import type { DocItem, DocType } from '../../types/api'

const props = defineProps<{ projectId: number }>()

const tabs: { value: DocType; label: string }[] = [
  { value: 'README', label: 'README' },
  { value: 'ARCH', label: '架构文档' },
  { value: 'API', label: 'API 文档' },
]

const docs = ref<DocItem[]>([])
const activeType = ref<DocType>('README')
const generating = ref(false)
const editing = ref(false)
const editContent = ref('')
const saving = ref(false)
const editError = ref('')

const activeLabel = computed(() => tabs.find((t) => t.value === activeType.value)?.label ?? '')
const doc = computed(() => docs.value.find((d) => d.docType === activeType.value) ?? null)

async function load() {
  try {
    docs.value = await fetchDocs(props.projectId)
  } catch (err) {
    console.error('加载文档失败', err)
  }
}

function switchTab(t: DocType) {
  if (generating.value) return
  if (editing.value && editContent.value !== doc.value?.content) {
    const ok = window.confirm('当前有未保存的编辑，切换将丢弃编辑内容。继续？')
    if (!ok) return
  }
  activeType.value = t
  editing.value = false
}

async function onGenerate(isRegenerate: boolean) {
  if (generating.value) return
  if (editing.value && editContent.value !== doc.value?.content) {
    const ok = window.confirm('当前有未保存的编辑，重新生成将丢弃编辑内容。继续？')
    if (!ok) return
  }
  editing.value = false
  if (isRegenerate && doc.value?.edited) {
    const ok = window.confirm('该文档已被人工编辑，重新生成将覆盖当前内容。继续？')
    if (!ok) return
  }
  generating.value = true
  try {
    const updated = await generateDoc(
      props.projectId,
      activeType.value,
      isRegenerate || !!doc.value?.edited, // 重新生成或已编辑 → force 覆盖
    )
    docs.value = docs.value.filter((d) => d.docType !== activeType.value)
    docs.value.push(updated)
  } catch (err) {
    window.alert(`生成失败：${err instanceof Error ? err.message : '未知错误'}`)
  } finally {
    generating.value = false
  }
}

function startEdit() {
  if (!doc.value) return
  editContent.value = doc.value.content
  editError.value = ''
  editing.value = true
}

async function saveEdit() {
  if (!doc.value) return
  saving.value = true
  editError.value = ''
  try {
    const updated = await editDoc(doc.value.id, editContent.value)
    docs.value = docs.value.map((d) => (d.id === updated.id ? updated : d))
    editing.value = false
  } catch (err) {
    editError.value = err instanceof Error ? err.message : '保存失败'
  } finally {
    saving.value = false
  }
}

// ---- 轻量安全 Markdown 渲染（先转义防 XSS，再处理标题/代码块/表格/列表/粗体/行内码） ----

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function renderMarkdown(text: string): string {
  const lines = text.split('\n')
  let html = ''
  let inCode = false
  let codeBuf: string[] = []
  let tableBuf: string[] = []
  const flushTable = () => {
    if (!tableBuf.length) return
    const rows = tableBuf
      .filter((r) => !/^\s*\|?[\s:|-]+\|?\s*$/.test(r)) // 过滤 |---| 分隔行
      .map((r) => {
        const cells = r.split('|').filter((c) => c.trim() !== '')
        return `<tr>${cells.map((c) => `<td>${inline(c.trim())}</td>`).join('')}</tr>`
      })
    if (rows.length) html += `<table>${rows.join('')}</table>`
    tableBuf = []
  }
  for (const raw of lines) {
    const line = raw.trimEnd()
    if (line.trimStart().startsWith('```')) {
      if (inCode) {
        html += `<pre class="docs__code">${codeBuf.map(escapeHtml).join('\n')}</pre>`
        codeBuf = []
      }
      inCode = !inCode
      continue
    }
    if (inCode) {
      codeBuf.push(line)
      continue
    }
    if (line.startsWith('|')) {
      tableBuf.push(line)
      continue
    }
    flushTable()
    const trimmed = line.trim()
    if (trimmed.startsWith('### ')) {
      html += `<h4>${inline(trimmed.slice(4))}</h4>`
    } else if (trimmed.startsWith('## ')) {
      html += `<h3>${inline(trimmed.slice(3))}</h3>`
    } else if (trimmed.startsWith('# ')) {
      html += `<h2>${inline(trimmed.slice(2))}</h2>`
    } else if (/^[-*] /.test(trimmed)) {
      html += `<li>${inline(trimmed.slice(2))}</li>`
    } else if (/^\d+\. /.test(trimmed)) {
      html += `<li>${inline(trimmed.replace(/^\d+\. /, ''))}</li>`
    } else if (trimmed === '') {
      html += '<div class="docs__gap"></div>'
    } else {
      html += `<p>${inline(trimmed)}</p>`
    }
  }
  if (inCode && codeBuf.length) {
    html += `<pre class="docs__code">${codeBuf.map(escapeHtml).join('\n')}</pre>`
  }
  flushTable()
  return html
}

function inline(s: string): string {
  let l = escapeHtml(s)
  l = l.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  l = l.replace(/`([^`]+)`/g, '<code>$1</code>')
  return l
}

onMounted(load)
</script>
<template>
  <NCard size="small" class="docs">
    <NTabs v-model:value="activeType" type="line" @update:value="switchTab">
      <NTabPane v-for="t in tabs" :key="t.value" :name="t.value" :tab="t.label" />
    </NTabs>

    <NSpin :show="generating">
      <div v-if="generating" class="docs-state">AI 正在生成文档，请稍候（约 10-30 秒）…</div>
      <NEmpty v-else-if="!doc" :description="'尚未生成' + activeLabel + '文档'">
        <template #extra>
          <NButton size="small" type="primary" @click="onGenerate(false)">生成</NButton>
        </template>
      </NEmpty>
      <div v-else class="docs-view">
        <div class="docs-toolbar">
          <span class="docs-meta">
            {{ doc.title }} · v{{ doc.version }}
            <NTag v-if="doc.edited" size="small" bordered type="warning">已人工编辑</NTag>
          </span>
          <NSpace size="small">
            <NButton size="small" @click="startEdit">编辑</NButton>
            <NButton size="small" type="primary" @click="onGenerate(true)">重新生成</NButton>
          </NSpace>
        </div>
        <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 内已 escapeHtml 转义（doc-view） -->
        <div class="docs-content" v-html="renderMarkdown(doc.content)" />
      </div>
    </NSpin>

    <!-- 编辑模式 -->
    <NModal
      :show="editing"
      preset="card"
      title="编辑文档"
      style="width: 80%; max-width: 900px"
      @update:show="
        (v: boolean) => {
          if (!v) editing = false
        }
      "
    >
      <NInput v-model:value="editContent" type="textarea" :rows="16" class="docs-edit-area" />
      <template #footer>
        <NSpace>
          <NButton :loading="saving" type="primary" @click="saveEdit">{{
            saving ? '保存中…' : '保存'
          }}</NButton>
          <NButton @click="editing = false">取消</NButton>
        </NSpace>
      </template>
      <NAlert v-if="editError" type="error" :show-icon="true" class="docs-edit-error">{{
        editError
      }}</NAlert>
    </NModal>
  </NCard>
</template>
<style scoped>
.docs {
  background: #fff;
}
.docs-state {
  padding: 40px 0;
  text-align: center;
  color: #8798ab;
  font-size: 13.5px;
}
.docs-view {
  margin-top: 12px;
}
.docs-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid #eef1f5;
}
.docs-meta {
  font-size: 13px;
  color: #55667a;
  display: flex;
  align-items: center;
  gap: 8px;
}
.docs-content {
  font-size: 13.5px;
  line-height: 1.8;
  color: #1b2633;
}
.docs-content h2 {
  font-size: 18px;
  margin: 18px 0 8px;
}
.docs-content h3 {
  font-size: 15px;
  margin: 14px 0 6px;
}
.docs-content h4 {
  font-size: 13.5px;
  margin: 10px 0 4px;
}
.docs-content pre {
  background: #f4f6f9;
  padding: 12px;
  border-radius: 4px;
  overflow: auto;
  font-size: 12.5px;
}
.docs-content table {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}
.docs-content td {
  border: 1px solid #e2e8f0;
  padding: 6px 10px;
  font-size: 12.5px;
}
.docs-content li {
  margin-left: 20px;
}
.docs-edit-area {
  font-family: ui-monospace, Consolas, 'Courier New', monospace;
  font-size: 13px;
}
.docs-edit-error {
  margin-top: 10px;
}
</style>
