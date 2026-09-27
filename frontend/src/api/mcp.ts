import { http, unwrap } from './http'
import type { McpVo } from '@/types'

export interface McpPayload {
  name: string
  type: 'remote' | 'openapi' | 'manual'
  url: string
  headers?: string
  config?: string
  enabled?: number
  sortOrder?: number
}

export const mcpApi = {
  list: () => http.get<McpVo[]>('/chat/mcp').then(unwrap),
  /** 会话勾选弹窗用:可加载的全部工具(公共+私有,含类型) */
  selectable: () => http.get<Array<{ name: string; type: string }>>('/chat/mcp/selectable').then(unwrap),
  create: (data: McpPayload) => http.post<McpVo>('/chat/mcp', data).then(unwrap),
  update: (id: number, data: Partial<McpPayload>) => http.put<McpVo>(`/chat/mcp/${id}`, data).then(unwrap),
  remove: (id: number) => http.delete(`/chat/mcp/${id}`),
  manualAssist: (description: string, serverName: string) =>
    http.post<any>(`/chat/mcp/manual/assist`, { description, server_name: serverName }).then(unwrap),
  manualAssistParams: (data: { method: string; path: string; name: string; description: string }) =>
    http.post<any>(`/chat/mcp/manual/assist/params`, data).then(unwrap),
}