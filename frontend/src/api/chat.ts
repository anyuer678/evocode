import request from './request'
import type {
  ApiResponse,
  ChatCitation,
  ChatMessageItem,
  ChatSessionItem,
  ChatSessionsResult,
  PageResult,
} from '../types/api'

/** 会话列表（06 §3.15） */
export async function fetchChatSessions(projectId: number): Promise<ChatSessionsResult> {
  const resp = await request.get<ApiResponse<ChatSessionsResult>>(`/projects/${projectId}/chats`)
  return resp.data.data
}

/** 建会话（title 占位"新会话"，首条消息后自动生成） */
export async function createChatSession(projectId: number): Promise<ChatSessionItem> {
  const resp = await request.post<ApiResponse<ChatSessionItem>>(`/projects/${projectId}/chats`)
  return resp.data.data
}

/** 删会话（级联删消息） */
export async function deleteChatSession(id: number): Promise<void> {
  await request.delete<ApiResponse<null>>(`/chats/${id}`)
}

/** 消息分页（按 id asc） */
export async function fetchChatMessages(
  sessionId: number,
  page = 1,
  size = 50,
): Promise<PageResult<ChatMessageItem>> {
  const resp = await request.get<ApiResponse<PageResult<ChatMessageItem>>>(
    `/chats/${sessionId}/messages`,
    { params: { page, size } },
  )
  return resp.data.data
}

export interface SseChatHandlers {
  onDelta?: (content: string) => void
  onCitations?: (items: ChatCitation[]) => void
  onDone?: (messageId: number) => void
  onError?: (code: string, message: string) => void
}

/**
 * 发消息（06 §4.1）：SSE 流式。用 fetch + ReadableStream 逐行解析
 * event:/data:（契约 §4.4 明确不用 EventSource——需自定义 headers）。
 * 断线/无 done → onError('CONNECTION_LOST', ...)。
 */
export async function sendChatMessage(
  sessionId: number,
  content: string,
  fileRef: string | null,
  handlers: SseChatHandlers = {},
): Promise<void> {
  let resp: Response
  try {
    resp = await fetch(`/api/v1/chats/${sessionId}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ content, fileRef }),
    })
  } catch {
    handlers.onError?.('CONNECTION_LOST', '网络错误，请重试')
    return
  }
  if (!resp.ok || !resp.body) {
    let code = `HTTP_${resp.status}`
    let message = `请求失败（${resp.status}）`
    try {
      const body = (await resp.json()) as { code?: number; message?: string }
      if (typeof body.code === 'number') code = String(body.code)
      if (body.message) message = body.message
    } catch {
      /* 非 JSON 响应体 */
    }
    handlers.onError?.(code, message)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let currentEvent = ''
  let sawDone = false
  const dispatch = (event: string, data: unknown) => {
    const obj = data as Record<string, unknown>
    if (event === 'delta') {
      handlers.onDelta?.(String(obj.content ?? ''))
    } else if (event === 'citations') {
      handlers.onCitations?.((obj.items as ChatCitation[]) ?? [])
    } else if (event === 'done') {
      sawDone = true
      handlers.onDone?.(Number(obj.messageId))
    } else if (event === 'error') {
      handlers.onError?.(String(obj.code ?? 'LLM_FAILED'), String(obj.message ?? '回答生成失败'))
    }
  }
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let sep: number
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      for (const line of block.split('\n')) {
        const trimmed = line.trim()
        if (trimmed.startsWith('event: ')) {
          currentEvent = trimmed.slice(7)
        } else if (trimmed.startsWith('data: ')) {
          try {
            dispatch(currentEvent, JSON.parse(trimmed.slice(6)))
          } catch {
            /* 跳过无法解析的 data 行 */
          }
        }
      }
    }
  }
  if (!sawDone) {
    handlers.onError?.('CONNECTION_LOST', '连接中断，回复可能不完整')
  }
}
