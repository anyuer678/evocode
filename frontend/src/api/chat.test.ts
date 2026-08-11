import { describe, expect, it, vi } from 'vitest'
import { sendChatMessage } from './chat'

/**
 * chat.ts SSE 流解析（docs/06-API契约.md §4.4）：
 * - 逐行 event:/data: 状态机，兼容 \n\n 与 \r\n\r\n 分块
 * - delta / citations / done / error 事件分发
 * - EOF 终止事件（合法 SSE 允许无空行结尾）
 * - 无 done → onError('CONNECTION_LOST')
 */

/** 构造一个返回 SSE 流的 Response 的 fetch mock */
function sseResponse(body: string, opts: { chunked?: boolean } = {}): void {
  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      if (opts.chunked) {
        // 模拟网络分块：把 body 切成多段（每段不保证事件边界）
        const bytes = encoder.encode(body)
        const half = Math.ceil(bytes.length / 2)
        controller.enqueue(bytes.slice(0, half))
        controller.enqueue(bytes.slice(half))
      } else {
        controller.enqueue(encoder.encode(body))
      }
      controller.close()
    },
  })
  vi.stubGlobal(
    'fetch',
    vi.fn(
      async () =>
        new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }),
    ),
  )
}

describe('sendChatMessage SSE 解析', () => {
  it('解析 delta / citations / done 事件', async () => {
    const body = [
      'event: delta',
      'data: {"content":"你好"}',
      '',
      'event: citations',
      'data: {"items":[{"file":"a.java","line":1,"excerpt":"..."}]}',
      '',
      'event: done',
      'data: {"messageId":7}',
      '',
    ].join('\n')
    sseResponse(body)

    const onDelta = vi.fn()
    const onCitations = vi.fn()
    const onDone = vi.fn()
    await sendChatMessage(1, 'hi', null, { onDelta, onCitations, onDone })

    expect(onDelta).toHaveBeenCalledWith('你好')
    expect(onCitations).toHaveBeenCalledWith([{ file: 'a.java', line: 1, excerpt: '...' }])
    expect(onDone).toHaveBeenCalledWith(7)
  })

  it('兼容 \\r\\n\\r\\n 分块', async () => {
    const body =
      'event: delta\r\ndata: {"content":"ok"}\r\n\r\nevent: done\r\ndata: {"messageId":1}\r\n\r\n'
    sseResponse(body)

    const onDelta = vi.fn()
    const onDone = vi.fn()
    await sendChatMessage(1, 'hi', null, { onDelta, onDone })

    expect(onDelta).toHaveBeenCalledWith('ok')
    expect(onDone).toHaveBeenCalledWith(1)
  })

  it('EOF 终止事件（尾部无空行）也能解析', async () => {
    const body = 'event: delta\ndata: {"content":"tail"}\nevent: done\ndata: {"messageId":9}'
    sseResponse(body)

    const onDelta = vi.fn()
    const onDone = vi.fn()
    await sendChatMessage(1, 'hi', null, { onDelta, onDone })

    expect(onDelta).toHaveBeenCalledWith('tail')
    expect(onDone).toHaveBeenCalledWith(9)
  })

  it('流式分块下仍能完整解析', async () => {
    const body = [
      'event: delta',
      'data: {"content":"分块"}',
      '',
      'event: done',
      'data: {"messageId":3}',
      '',
    ].join('\n')
    sseResponse(body, { chunked: true })

    const onDelta = vi.fn()
    const onDone = vi.fn()
    await sendChatMessage(1, 'hi', null, { onDelta, onDone })

    expect(onDelta).toHaveBeenCalledWith('分块')
    expect(onDone).toHaveBeenCalledWith(3)
  })

  it('error 事件转发 onError', async () => {
    const body = 'event: error\ndata: {"code":"LLM_FAILED","message":"回答生成失败"}\n\n'
    sseResponse(body)

    const onError = vi.fn()
    await sendChatMessage(1, 'hi', null, { onError })

    expect(onError).toHaveBeenCalledWith('LLM_FAILED', '回答生成失败')
  })

  it('流结束无 done → CONNECTION_LOST', async () => {
    sseResponse('event: delta\ndata: {"content":"半截"}\n\n')

    const onError = vi.fn()
    await sendChatMessage(1, 'hi', null, { onError })

    expect(onError).toHaveBeenCalledWith('CONNECTION_LOST', expect.any(String))
  })

  it('跳过无法解析的 data 行', async () => {
    const body = [
      'event: delta',
      'data: {broken json',
      '',
      'event: delta',
      'data: {"content":"ok"}',
      '',
      'event: done',
      'data: {"messageId":2}',
      '',
    ].join('\n')
    sseResponse(body)

    const onDelta = vi.fn()
    const onDone = vi.fn()
    await sendChatMessage(1, 'hi', null, { onDelta, onDone })

    expect(onDelta).toHaveBeenCalledTimes(1)
    expect(onDelta).toHaveBeenCalledWith('ok')
    expect(onDone).toHaveBeenCalledWith(2)
  })

  it('HTTP 错误 → onError 携带业务码', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ code: 3001, message: '会话不存在' }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )

    const onError = vi.fn()
    await sendChatMessage(1, 'hi', null, { onError })

    expect(onError).toHaveBeenCalledWith('3001', '会话不存在')
  })
})
