import { fetchEventSource } from '@microsoft/fetch-event-source'
import { http } from './http'

/** 浏览器面板实时流(SSE:frame=截图 base64,state=状态/错误)。断开自动重连;401/403 停止。 */
export function browserLive(h: {
  onFrame: (image: string) => void
  onState: (state: string, message?: string) => void
}): { close: () => void } {
  const ctrl = new AbortController()
  let fatal = false
  fetchEventSource('/api/agent/browser/live', {
    headers: { Authorization: `Bearer ${localStorage.getItem('token')}`, Accept: 'text/event-stream' },
    signal: ctrl.signal,
    onopen: async (res) => {
      if (res.status === 401 || res.status === 403) {
        fatal = true
        throw new Error(`SSE ${res.status}`)
      }
      if (!res.ok) throw new Error(`SSE ${res.status}`)
    },
    onmessage: (e) => {
      let data: any = {}
      try { data = e.data ? JSON.parse(e.data) : {} } catch { data = {} }
      if (e.event === 'frame' && data.image) h.onFrame(data.image)
      else if (e.event === 'state') h.onState(data.state || '', data.message || '')
    },
    onerror: (e) => {
      if (fatal) throw e
      h.onState('error', '实时连接断开,重连中…')
    },
  })
  return { close: () => ctrl.abort() }
}

/** 用户接管动作:调用 agent 共享浏览器上的 playwright-mcp 工具。返回 MCP result({content:[...]})。 */
export async function browserAction(tool: string, args: Record<string, unknown>): Promise<any> {
  const res = await http.post('/agent/browser/action', { tool, args })
  return res.data?.data
}

/** 关闭整个浏览器(停止沙箱容器,立即释放资源;下次使用自动重建)。 */
export async function browserStop(): Promise<void> {
  await http.post('/agent/sandbox/stop')
}
