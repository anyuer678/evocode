<template>
  <section class="docs">
    <div class="docs__head">
      <h3>项目文档</h3>
      <div class="docs__tabs">
        <button
          v-for="t in tabs"
          :key="t.value"
          type="button"
          class="docs__tab"
          :class="{ 'docs__tab--active': activeType === t.value }"
          @click="switchTab(t.value)"
        >
          {{ t.label }}
        </button>
      </div>
    </div>

    <div v-if="generating" class="docs__state">AI 正在生成文档，请稍候（约 10-30 秒）…</div>
    <template v-else>
      <div v-if="!doc" class="docs__state">
        尚未生成{{ activeLabel }}文档
        <button type="button" class="docs__btn docs__btn--ok" @click="onGenerate(false)">
          生成
        </button>
      </div>
      <div v-else class="docs__view">
        <div class="docs__toolbar">
          <span class="docs__meta">
            {{ doc.title }} · v{{ doc.version }}{{ doc.edited ? '（已人工编辑）' : '' }}
          </span>
          <div class="docs__actions">
            <button type="button" class="docs__btn" @click="startEdit">编辑</button>
            <button type="button" class="docs__btn docs__btn--ok" @click="onGenerate(true)">
              重新生成
            </button>
          </div>
        </div>
        <div class="docs__content" v-html="renderMarkdown(doc.content)" />
      </div>
    </template>

    <!-- 编辑模式 -->
    <div v-if="editing" class="docs__edit">
      <textarea v-model="editContent" class="docs__edit-area" rows="16" />
      <div class="docs__actions">
        <button type="button" class="docs__btn" @click="editing = false">取消</button>
        <button type="button" class="docs__btn docs__btn--ok" :disabled="saving" @click="saveEdit">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
      <p v-if="editError" class="docs__error">{{ editError }}</p>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
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

<style scoped>
.docs {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  padding: 16px;
  background: var(--bg-card, #fff);
}
.docs__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.docs__head h3 {
  margin: 0;
  font-size: 15px;
}
.docs__tabs {
  display: flex;
  gap: 6px;
}
.docs__tab {
  border: 1px solid var(--border-color, #e5e7eb);
  background: var(--bg-card);
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
  color: var(--text-secondary, #6b7280);
}
.docs__tab--active {
  border-color: var(--ok-color, #16a34a);
  color: var(--ok-color, #16a34a);
  background: rgba(22, 163, 74, 0.08);
}
.docs__state {
  color: var(--text-secondary, #6b7280);
  font-size: 13px;
  padding: 16px 0;
  display: flex;
  align-items: center;
  gap: 10px;
}
.docs__view {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  overflow: hidden;
}
.docs__toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 12px;
  background: var(--bg-muted, #fafafa);
  border-bottom: 1px solid var(--border-color, #e5e7eb);
}
.docs__meta {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}
.docs__actions {
  display: flex;
  gap: 8px;
}
.docs__btn {
  border: 1px solid var(--border-color, #e5e7eb);
  background: var(--bg-card);
  border-radius: 6px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
}
.docs__btn--ok {
  background: var(--ok-color, #16a34a);
  border-color: var(--ok-color, #16a34a);
  color: var(--bg-card);
}
.docs__content {
  padding: 12px 16px;
  font-size: 13px;
  line-height: 1.7;
  overflow-x: auto;
}
.docs__content :deep(h2),
.docs__content :deep(h3),
.docs__content :deep(h4) {
  margin: 14px 0 6px;
}
.docs__content :deep(p) {
  margin: 6px 0;
}
.docs__content :deep(li) {
  margin: 3px 0 3px 18px;
}
.docs__content :deep(code) {
  background: var(--code-bg);
  border-radius: 4px;
  padding: 0 4px;
  font-size: 12px;
}
.docs__code {
  background: #1e293b;
  color: #e2e8f0;
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 12px;
}
.docs__content :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
}
.docs__content :deep(td) {
  border: 1px solid var(--border-color, #e5e7eb);
  padding: 4px 8px;
  font-size: 12px;
}
.docs__gap {
  height: 4px;
}
.docs__edit {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.docs__edit-area {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 8px;
  padding: 10px;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  resize: vertical;
}
.docs__error {
  color: var(--fail-color, #dc2626);
  font-size: 12px;
  margin: 0;
}
</style>
