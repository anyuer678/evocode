<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { NAlert, NButton, NCard, NEmpty, NInput, NList, NListItem, NModal, NTag } from 'naive-ui'
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
<template>
  <NCard size="small" class="doctor">
    <div class="doctor-body">
      <!-- 左：会话列表 -->
      <aside class="doctor-sidebar">
        <NButton size="small" type="primary" block @click="createSession">＋ 新建会话</NButton>
        <NList class="doctor-sessions" hoverable>
          <NListItem
            v-for="s in sessions"
            :key="s.id"
            class="doctor-session"
            :class="{ 'doctor-session--active': s.id === activeId }"
            @click="selectSession(s.id)"
          >
            <div class="doctor-session-row">
              <span class="doctor-session-title">{{ s.title }}</span>
              <NButton size="tiny" quaternary type="error" @click.stop="removeSession(s.id)"
                >×</NButton
              >
            </div>
          </NListItem>
        </NList>
        <NEmpty v-if="!sessions.length" description="暂无会话" size="small" />
      </aside>

      <!-- 右：对话区 -->
      <div class="doctor-main">
        <div ref="msgBox" class="doctor-messages">
          <NEmpty
            v-if="!activeId"
            description="选择或新建一个会话，向 AI 医生提问项目问题（支持 @ 文件）"
          />
          <template v-else>
            <div
              v-for="m in messages"
              :key="m._localId"
              class="doctor-msg"
              :class="'doctor-msg--' + m.role.toLowerCase()"
            >
              <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 内已 escapeHtml 转义 -->
              <div class="doctor-msg-bubble" v-html="renderMarkdown(m.content)" />
              <div v-if="m.role === 'ASSISTANT' && m.citations?.length" class="doctor-cites">
                <NTag
                  v-for="(c, i) in m.citations"
                  :key="i"
                  size="small"
                  bordered
                  :title="c.excerpt"
                  @click="previewFile(c.file, c.line)"
                >
                  {{ c.file }}:{{ c.line }}
                </NTag>
              </div>
            </div>
            <div v-if="streaming" class="doctor-msg doctor-msg--assistant">
              <!-- eslint-disable-next-line vue/no-v-html -- renderMarkdown 内已 escapeHtml 转义 -->
              <div class="doctor-msg-bubble" v-html="renderMarkdown(streamText)" />
              <span class="doctor-cursor" />
            </div>
            <NAlert v-if="streamError" type="error" :show-icon="true" class="doctor-error">
              {{ streamError }}
            </NAlert>
          </template>
        </div>

        <div class="doctor-input">
          <NInput
            v-model:value="fileRef"
            placeholder="文件路径（可选，@ 后医生将结合文件内容回答）"
            :disabled="streaming"
            size="small"
          />
          <div class="doctor-input-row">
            <NInput
              v-model:value="input"
              type="textarea"
              :rows="2"
              placeholder="问 AI 医生……（Enter 发送，Shift+Enter 换行）"
              :disabled="streaming"
              @keydown.enter.exact.prevent="send"
            />
            <NButton
              type="primary"
              :disabled="streaming || !input.trim()"
              :loading="streaming"
              @click="send"
            >
              {{ streaming ? '生成中…' : '发送' }}
            </NButton>
          </div>
        </div>
      </div>
    </div>

    <!-- Monaco 预览弹层（按需加载） -->
    <NModal
      :show="preview != null"
      preset="card"
      style="width: 80%; max-width: 960px"
      :title="preview ? preview.path + ':' + preview.line : ''"
      @update:show="
        (v: boolean) => {
          if (!v) preview = null
        }
      "
    >
      <div ref="editorEl" class="doctor-editor" />
    </NModal>
  </NCard>
</template>
<style scoped>
.doctor {
  background: #fff;
}
.doctor-body {
  display: flex;
  gap: 14px;
  min-height: 480px;
  max-height: 720px;
}
.doctor-sidebar {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-right: 1px solid #eef1f5;
  padding-right: 12px;
  overflow-y: auto;
}
.doctor-sessions {
  flex: 1;
}
.doctor-session {
  cursor: pointer;
}
.doctor-session--active {
  background: #f0f6ff;
}
.doctor-session-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.doctor-session-title {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doctor-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.doctor-messages {
  flex: 1;
  overflow-y: auto;
  padding: 4px 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.doctor-msg {
  display: flex;
  flex-direction: column;
  max-width: 85%;
}
.doctor-msg--user {
  align-self: flex-end;
}
.doctor-msg--assistant {
  align-self: flex-start;
}
.doctor-msg-bubble {
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 13.5px;
  line-height: 1.7;
  word-break: break-word;
}
.doctor-msg--user .doctor-msg-bubble {
  background: #1668dc;
  color: #fff;
}
.doctor-msg--assistant .doctor-msg-bubble {
  background: #f4f6f9;
  color: #1b2633;
}
.doctor-cites {
  display: flex;
  gap: 6px;
  margin-top: 4px;
  flex-wrap: wrap;
}
.doctor-cursor {
  display: inline-block;
  width: 8px;
  height: 14px;
  background: #1668dc;
  animation: evo-pulse 1s infinite;
  margin-left: 2px;
  vertical-align: text-bottom;
}
.doctor-error {
  margin-top: 6px;
}
.doctor-input {
  border-top: 1px solid #eef1f5;
  padding-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.doctor-input-row {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.doctor-editor {
  height: 480px;
  border: 1px solid #e2e8f0;
}

@media (max-width: 720px) {
  .doctor-body {
    flex-direction: column;
    max-height: none;
  }
  .doctor-sidebar {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #eef1f5;
    padding-right: 0;
    padding-bottom: 10px;
    max-height: 200px;
  }
}
</style>
