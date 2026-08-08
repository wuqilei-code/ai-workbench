import { http, unwrap } from './http'
import type { SessionVo, MessageVo, CommandItem, AgentItem, SkillInfo } from '@/types'

export const chatApi = {
  listSessions: () => http.get<SessionVo[]>('/chat/sessions').then(unwrap),
  listArchivedSessions: () => http.get<SessionVo[]>('/chat/sessions/archived').then(unwrap),
  searchSessions: (q: string) => http.get<SessionVo[]>('/chat/sessions/search', { params: { q } }).then(unwrap),
  builtinSkills: () => http.get<SkillInfo[]>('/chat/skills/builtin').then(unwrap),
  createSession: (data: { title?: string; providerId?: string; modelId?: string; agent?: string }) =>
    http.post<SessionVo>('/chat/sessions', data).then(unwrap),
  listAgents: () => http.get<AgentItem[]>('/chat/sessions/agents').then(unwrap),
  getSession: (id: number) => http.get<SessionVo>(`/chat/sessions/${id}`).then(unwrap),
  updateSession: (id: number, data: Partial<{ title: string; providerId: string; modelId: string; archived: number; pinned: number; agent: string }>) =>
    http.patch<SessionVo>(`/chat/sessions/${id}`, data).then(unwrap),
  deleteSession: (id: number) => http.delete(`/chat/sessions/${id}`),
  warmup: (id: number) => http.post(`/chat/sessions/${id}/warmup`),
  listMessages: (id: number, limit = 200) =>
    http.get<MessageVo[]>(`/chat/sessions/${id}/messages`, { params: { limit } }).then(unwrap),
  clearMessages: (id: number) => http.delete(`/chat/sessions/${id}/messages`),
  exportSession: (id: number, format: 'md' | 'json' | 'pdf' | 'docx' = 'md') =>
    http.get<Blob>(`/chat/sessions/${id}/export`, { params: { format }, responseType: 'blob' }),
  sendMessage: (id: number, data: { text: string; providerId?: string; modelId?: string }) =>
    http.post(`/chat/sessions/${id}/messages`, data),
  abort: (id: number) => http.post<boolean>(`/chat/sessions/${id}/abort`).then(unwrap),
  replyQuestion: (sessionId: number, requestId: string, answers: string[][]) =>
    http.post(`/chat/sessions/${sessionId}/question/${requestId}/reply`, { answers }),
  rejectQuestion: (sessionId: number, requestId: string) =>
    http.post(`/chat/sessions/${sessionId}/question/${requestId}/reject`),
  replyPermission: (sessionId: number, permissionId: string, reply: 'once' | 'always' | 'reject') =>
    http.post(`/chat/sessions/${sessionId}/permission/${permissionId}/reply`, { reply }),
  listCommands: (sessionId: number) =>
    http.get<CommandItem[]>(`/chat/sessions/${sessionId}/commands`).then(unwrap),
  runCommand: (sessionId: number, data: { command: string; arguments?: string }) =>
    http.post(`/chat/sessions/${sessionId}/commands`, data),
}
