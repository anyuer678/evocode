<template>
  <section class="doctor">
    <!-- 左：会话列表 -->
    <aside class="doctor__sidebar">
      <button class="doctor__new" type="button" @click="createSession">＋ 新建会话</button>
      <ul class="doctor__sessions">
        <li
          v-for="s in sessions"
          :key="s.id"
          class="doctor__session"
          :class="{ 'doctor__session--active': s.id === activeId }"
          @click="selectSession(s.id)"
        >
          <span class="doctor__session-title">{{ s.title }}</span>
          <button
            class="doctor__session-del"
            type="button"
            title="删除会话"
            @click.stop="removeSession(s.id)"
          >
            ×
          </button>
        </li>
        <li v-if="!sessions.length" class="doctor__session-empty">暂无会话</li>
      </ul>
    </aside>

    <!-- 右：对话区 -->
    <div class="doctor__main">
      <div ref="msgBox" class="doctor__messages">
        <div v-if="!activeId" class="doctor__empty">
          选择或新建一个会话，向 AI 医生提问项目问题（支持 @ 文件）
        </div>
        <template v-else>
          <div
            v-for="m in messages"
            :key="m._localId"
            class="doctor__msg"
            :class="'doctor__msg--' + m.role.toLowerCase()"
          >
            <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 内已 escapeHtml 转义（doctor-view L341） -->
            <div class="doctor__msg-bubble" v-html="renderMarkdown(m.content)" />
            <div v-if="m.role === 'ASSISTANT' && m.citations?.length" class="doctor__cites">
              <button
                v-for="(c, i) in m.citations"
                :key="i"
                class="doctor__cite"
                type="button"
                :title="c.excerpt"
                @click="previewFile(c.file, c.line)"
              >
                {{ c.file }}:{{ c.line }}
              </button>
            </div>
          </div>
          <div v-if="streaming" class="doctor__msg doctor__msg--assistant">
            <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 内已 escapeHtml 转义（doctor-view L341） -->
            <div class="doctor__msg-bubble" v-html="renderMarkdown(streamText)" />
            <span class="doctor__cursor" />
          </div>
          <div v-if="streamError" class="doctor__error">{{ streamError }}</div>
        </template>
      </div>

      <div class="doctor__input">
        <input
          v-model="fileRef"
          class="doctor__fileref"
          placeholder="文件路径（可选，@ 后医生将结合文件内容回答）"
          :disabled="streaming"
        />
        <div class="doctor__input-row">
          <textarea
            v-model="input"
            rows="2"
            placeholder="问 AI 医生……（Enter 发送，Shift+Enter 换行）"
            :disabled="streaming"
            @keydown.enter.exact.prevent="send"
          />
          <button
            type="button"
            class="doctor__send"
            :disabled="streaming || !input.trim()"
            @click="send"
          >
            {{ streaming ? '生成中…' : '发送' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Monaco 预览弹层（按需加载） -->
    <Teleport to="body">
      <div v-if="preview" class="doctor__preview" @click.self="preview = null">
        <div class="doctor__preview-head">
          <span class="doctor__preview-path">{{ preview.path }}:{{ preview.line }}</span>
          <button type="button" @click="preview = null">关闭</button>
        </div>
        <div ref="editorEl" class="doctor__editor" />
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type * as monacoNs from 'monaco-editor'
import { getFileContent } from '../../api/file'
import {
  createChatSession,
  deleteChatSession,
  fetchChatMessages,
  fetchChatSessions,
  sendChatMessage,
} from '../../api/chat'
import type { ChatCitation, ChatMessageItem, ChatSessionItem } from '../../types/api'

interface LocalMessage {
  _localId: number
  id: number | null
  role: 'USER' | 'ASSISTANT'
  content: string
  citations: ChatCitation[] | null
}

interface PreviewState {
  path: string
  line: number
}

const props = defineProps<{ projectId: number }>()

const sessions = ref<ChatSessionItem[]>([])
const activeId = ref<number | null>(null)
const messages = ref<LocalMessage[]>([])
const input = ref('')
const fileRef = ref('')
const streaming = ref(false)
const streamText = ref('')
const streamError = ref('')
const streamCitations = ref<ChatCitation[]>([])
const msgBox = ref<HTMLElement | null>(null)
const preview = ref<PreviewState | null>(null)
const editorEl = ref<HTMLElement | null>(null)
const loading = ref(false)
let localSeq = 0
let editor: monacoNs.editor.IStandaloneCodeEditor | null = null
// 审查修复：当前流式请求的取消控制器——组件卸载/切换会话时 abort，停止 fetch 流
let streamAbort: AbortController | null = null

const scrollToBottom = () => {
  if (msgBox.value) msgBox.value.scrollTop = msgBox.value.scrollHeight
}

watch([streamText, messages], scrollToBottom, { flush: 'post' })

onBeforeUnmount(() => {
  // 审查修复：卸载时取消进行中的 AI 医生流（此前流继续读，回调写已卸载组件）
  streamAbort?.abort()
  streamAbort = null
  editor?.dispose()
  editor = null
})

async function loadSessions() {
  try {
    sessions.value = (await fetchChatSessions(props.projectId)).items
  } catch (err) {
    console.error('加载会话失败', err)
  }
}

async function createSession() {
  try {
    const s = await createChatSession(props.projectId)
    sessions.value.unshift(s)
    activeId.value = s.id
    messages.value = []
    input.value = ''
  } catch (err) {
    console.error('建会话失败', err)
  }
}

async function selectSession(id: number) {
  if (loading.value || streaming.value || id === activeId.value) return
  activeId.value = id
  messages.value = []
  loading.value = true
  // 审查修复：会话切换竞态守卫——fetchChatMessages 挂起期间用户切走/新建会话，
  // 旧响应返回时校验 activeId 仍为本会话，否则丢弃
  const targetId = id
  try {
    const page = await fetchChatMessages(targetId, 1, 100)
    if (targetId !== activeId.value) return
    messages.value = page.items.map((m: ChatMessageItem) => ({
      _localId: ++localSeq,
      id: m.id,
      role: m.role,
      content: m.content,
      citations: m.citations,
    }))
    await nextTick()
    scrollToBottom()
  } catch (err) {
    console.error('加载消息失败', err)
  } finally {
    loading.value = false
  }
}

async function removeSession(id: number) {
  if (streaming.value) return
  try {
    await deleteChatSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (activeId.value === id) {
      activeId.value = null
      messages.value = []
    }
  } catch (err) {
    console.error('删会话失败', err)
  }
}

async function send() {
  const content = input.value.trim()
  if (!content || streaming.value) return
  let sessionId = activeId.value
  if (sessionId == null) {
    try {
      const s = await createChatSession(props.projectId)
      sessionId = s.id
    } catch (e) {
      // 审查 M8：建会话失败不再静默丢失输入，展示错误
      streamError.value = `会话创建失败：${e instanceof Error ? e.message : String(e)}`
      return
    }
  }
  const refPath = fileRef.value.trim() || null
  messages.value.push({ _localId: ++localSeq, id: null, role: 'USER', content, citations: null })
  input.value = ''
  streaming.value = true
  streamText.value = ''
  streamError.value = ''
  streamCitations.value = []
  // 审查修复：本次流的取消控制器（卸载/切换会话时 abort）
  streamAbort = new AbortController()
  await nextTick()
  scrollToBottom()
  try {
    await sendChatMessage(
      sessionId,
      content,
      refPath,
      {
        onDelta: (delta: string) => {
          if (sessionId !== activeId.value) return
          streamText.value += delta
        },
        onCitations: (items: ChatCitation[]) => {
          if (sessionId !== activeId.value) return
          streamCitations.value = items
        },
        onDone: () => {
          if (sessionId !== activeId.value) return
          const text = streamText.value
          const cites = streamCitations.value
          messages.value.push({
            _localId: ++localSeq,
            id: null,
            role: 'ASSISTANT',
            content: text || '（空回复）',
            citations: cites.length ? cites : null,
          })
          streaming.value = false
          streamText.value = ''
          void loadSessions()
        },
        onError: (code: string, message: string) => {
          if (sessionId !== activeId.value) return
          streamError.value = `回答失败（${code}）：${message}`
          messages.value.push({
            _localId: ++localSeq,
            id: null,
            role: 'ASSISTANT',
            content: `回答失败（${code}）：${message}`,
            citations: null,
          })
          streaming.value = false
          streamText.value = ''
        },
      },
      streamAbort.signal,
    )
  } finally {
    // 防御：任何未捕获异常都复位流式状态（审查 M1）
    streamAbort = null
    if (streaming.value) {
      streaming.value = false
      streamText.value = ''
    }
  }
}

async function previewFile(file: string, line: number) {
  preview.value = { path: file, line }
  await nextTick()
  if (!editorEl.value) return
  let data
  try {
    data = await getFileContent(props.projectId, file)
  } catch (err) {
    console.error('读取文件失败', err)
    preview.value = null
    return
  }
  const monaco = await import('monaco-editor')
  try {
    editor?.dispose()
    editor = monaco.editor.create(editorEl.value, {
      value: data.content,
      language: mapLanguage(data.language),
      readOnly: true,
      automaticLayout: true,
      fontSize: 12,
      lineNumbers: 'on',
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
    })
    if (line > 0 && line <= (data.loc || 1)) {
      editor.revealLineInCenter(line)
      editor.deltaDecorations(
        [],
        [
          {
            range: new monaco.Range(line, 1, line, 1),
            options: { isWholeLine: true, className: 'doc-line-hl' },
          },
        ],
      )
    }
  } catch (err) {
    // Monaco 加载/创建失败：关闭弹层并提示（审查 M5）
    console.error('Monaco 预览失败', err)
    preview.value = null
  }
}

function mapLanguage(lang: string): string {
  const l = lang.toLowerCase()
  if (l === 'java') return 'java'
  if (l.includes('python')) return 'python'
  if (l === 'javascript' || l === 'typescript' || l === 'vue') return l
  return 'plaintext'
}

/** 轻量安全 Markdown：仅粗体/行内代码/引用/代码块，先转义防 XSS */
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
  for (const line of lines) {
    if (line.trimStart().startsWith('```')) {
      inCode = !inCode
      html += inCode ? '<pre class="doc-code">' : '</pre>'
      continue
    }
    if (inCode) {
      html += escapeHtml(line) + '\n'
      continue
    }
    let l = escapeHtml(line)
    l = l.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    l = l.replace(/`([^`]+)`/g, '<code>$1</code>')
    l = l.replace(/\[([^\]]+):(\d+)\]/g, '<span class="doc-ref">$1:$2</span>')
    html += `<div>${l || '&nbsp;'}</div>`
  }
  return html
}

void loadSessions()
</script>

<style scoped>
.doctor {
  display: flex;
  gap: 16px;
  min-height: 480px;
  max-height: 720px;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
  background: var(--bg-card, #fff);
}

/* 会话列表 */
.doctor__sidebar {
  width: 220px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-color, #e5e7eb);
  display: flex;
  flex-direction: column;
  padding: 12px;
  gap: 10px;
  background: var(--bg-muted, #fafafa);
}
.doctor__new {
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: var(--ok-color, #16a34a);
  color: var(--bg-card);
  cursor: pointer;
  font-size: 13px;
}
.doctor__sessions {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.doctor__session {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  background: var(--bg-card);
  border: 1px solid var(--border-color, #e5e7eb);
}
.doctor__session--active {
  border-color: var(--ok-color);
  background: var(--ok-weak);
}
.doctor__session-title {
  flex: 1;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doctor__session-del {
  border: none;
  background: none;
  color: var(--text-secondary, #6b7280);
  cursor: pointer;
  font-size: 14px;
  padding: 0 2px;
}
.doctor__session-empty {
  font-size: 13px;
  color: var(--text-secondary, #6b7280);
  text-align: center;
  padding: 12px 0;
}

/* 对话区 */
.doctor__main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.doctor__messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.doctor__empty {
  margin: auto;
  color: var(--text-secondary, #6b7280);
  font-size: 14px;
}
.doctor__msg {
  max-width: 86%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.doctor__msg--user {
  align-self: flex-end;
}
.doctor__msg--assistant {
  align-self: flex-start;
}
.doctor__msg-bubble {
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.65;
  word-break: break-word;
  background: var(--bg-muted, #f3f4f6);
  border: 1px solid var(--border-color, #e5e7eb);
}
.doctor__msg--user .doctor__msg-bubble {
  background: var(--ok-color, #16a34a);
  color: var(--bg-card);
  border: none;
}
.doctor__msg--user .doc-ref {
  color: #eafff0;
  border-color: rgba(255, 255, 255, 0.5);
}
.doc-code {
  background: #1e293b;
  color: #e2e8f0;
  padding: 8px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 12px;
  margin: 4px 0;
}
.doc-ref {
  display: inline-block;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  color: var(--ok-color, #16a34a);
  border: 1px solid currentColor;
  border-radius: 4px;
  padding: 0 4px;
}
.doctor__cites {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.doctor__cite {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  color: var(--info-color);
  background: var(--info-weak);
  border: 1px solid var(--info-color);
  border-radius: 4px;
  padding: 2px 6px;
  cursor: pointer;
}
.doctor__cursor {
  display: inline-block;
  width: 8px;
  height: 14px;
  background: var(--ok-color, #16a34a);
  animation: blink 1s step-end infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}
.doctor__error {
  color: var(--fail-color, #dc2626);
  font-size: 13px;
}

/* 输入区 */
.doctor__input {
  border-top: 1px solid var(--border-color, #e5e7eb);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.doctor__fileref {
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
  background: var(--bg-muted, #fafafa);
}
.doctor__input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.doctor__input-row textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--border-color, #e5e7eb);
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.5;
}
.doctor__send {
  padding: 8px 18px;
  border: none;
  border-radius: 6px;
  background: var(--ok-color, #16a34a);
  color: var(--bg-card);
  cursor: pointer;
  font-size: 13px;
}
.doctor__send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Monaco 预览 */
.doctor__preview {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.55);
  display: flex;
  flex-direction: column;
  padding: 48px;
  z-index: 100;
}
.doctor__preview-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #0f172a;
  color: #e2e8f0;
  padding: 8px 12px;
  border-radius: 8px 8px 0 0;
  font-size: 12px;
  font-family: ui-monospace, Consolas, monospace;
}
.doctor__preview-head button {
  border: none;
  background: #334155;
  color: #e2e8f0;
  border-radius: 4px;
  padding: 4px 10px;
  cursor: pointer;
}
.doctor__editor {
  flex: 1;
  border-radius: 0 0 8px 8px;
  overflow: hidden;
  background: #1e1e1e;
}
:deep(.doc-line-hl) {
  background: rgba(250, 204, 21, 0.25) !important;
}
</style>
