import { fetchEventSource } from '@microsoft/fetch-event-source'

export interface SseHandlers {
  onOpen: () => void
  onMessage: (type: string, data: any) => void
  onError: (e: unknown) => void
  /** 登录失效(SSE 返回 401/403):调用方应停止重连、提示重新登录。 */
  onAuthError?: () => void
}

/**
 * 订阅某会话的实时事件。EventSource 不能带 header(无法携带 JWT),
 * 故用 fetch-event-source(基于 fetch+ReadableStream)。
 * 库默认在 onerror 后自动重连;不要在 onerror 里 throw,否则停止重连。
 * 例外:收到 401/403 说明 token 失效,重连无意义且会无限循环 → 标记 fatal 并在
 * onerror 中抛出以终止自动重连,同时回调 onAuthError 让上层提示「登录过期」。
 */
export function subscribeSession(sessionId: number, token: string, h: SseHandlers): { close: () => void } {
  const ctrl = new AbortController()
  let fatal = false
  fetchEventSource(`/api/chat/sessions/${sessionId}/events`, {
    headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
    signal: ctrl.signal,
    onopen: async (res) => {
      if (res.status === 401 || res.status === 403) {
        fatal = true
        h.onAuthError?.()
        throw new Error(`SSE ${res.status}`)
      }
      if (!res.ok) throw new Error(`SSE ${res.status}`)
      h.onOpen()
    },
    onmessage: (e) => {
      let data: any = {}
      try { data = e.data ? JSON.parse(e.data) : {} } catch { data = {} }
      h.onMessage(e.event || '', data)
    },
    onerror: (e) => {
      if (fatal) {
        // throw:让库停止自动重连(其余情况不 throw,保留指数退避重连)
        throw e
      }
      h.onError(e)
    },
  })
  return { close: () => ctrl.abort() }
}