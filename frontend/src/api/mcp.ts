import { http, unwrap } from './http'
import type { McpVo } from '@/types'

/** 用户私有 MCP Server 配置(/api/mcps,自动绑定当前登录用户) */
export const mcpApi = {
  listPublic: () => http.get<McpVo[]>('/mcps/public').then(unwrap),
  listMine: () => http.get<McpVo[]>('/mcps').then(unwrap),
  createMine: (data: any) => http.post<McpVo>('/mcps', data).then(unwrap),
  updateMine: (id: number, data: any) => http.put<McpVo>(`/mcps/${id}`, data).then(unwrap),
  deleteMine: (id: number) => http.delete(`/mcps/${id}`),
}
