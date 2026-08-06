import { fetchEventSource } from '@microsoft/fetch-event-source'

export interface SseHandlers {
  onOpen: () => void
  onMessage: (type: string, data: any) => void
  onError: (e: unknown) => void
}

/**
 * 订阅某会话的实时事件。EventSource 不能带 header(无法携带 JWT),
 * 故用 fetch-event-source(基于 fetch+ReadableStream)。
 * 库默认在 onerror 后自动重连;不要在 onerror 里 throw,否则停止重连。
 */
export function subscribeSession(sessionId: number, token: string, h: SseHandlers): { close: () => void } {
  const ctrl = new AbortController()
  fetchEventSource(`/api/chat/sessions/${sessionId}/events`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: ctrl.signal,
    onopen: async (res) => {
      if (!res.ok) throw new Error(`SSE ${res.status}`)
      h.onOpen()
    },
    onmessage: (e) => {
      let data: any = {}
      try { data = e.data ? JSON.parse(e.data) : {} } catch { data = {} }
      h.onMessage(e.event || '', data)
    },
    onerror: (e) => {
      h.onError(e)
      // 不 throw:让库自动重连(指数退避)
    },
  })
  return { close: () => ctrl.abort() }
}
