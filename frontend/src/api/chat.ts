import { http, unwrap } from './http'
import type { SessionVo, MessageVo } from '@/types'

export const chatApi = {
  listSessions: () => http.get<SessionVo[]>('/chat/sessions').then(unwrap),
  createSession: (data: { title?: string; providerId?: string; modelId?: string }) =>
    http.post<SessionVo>('/chat/sessions', data).then(unwrap),
  getSession: (id: number) => http.get<SessionVo>(`/chat/sessions/${id}`).then(unwrap),
  updateSession: (id: number, data: Partial<{ title: string; providerId: string; modelId: string; archived: number }>) =>
    http.patch<SessionVo>(`/chat/sessions/${id}`, data).then(unwrap),
  deleteSession: (id: number) => http.delete(`/chat/sessions/${id}`),
  listMessages: (id: number, limit = 200) =>
    http.get<MessageVo[]>(`/chat/sessions/${id}/messages`, { params: { limit } }).then(unwrap),
  sendMessage: (id: number, data: { text: string; providerId?: string; modelId?: string }) =>
    http.post(`/chat/sessions/${id}/messages`, data),
  abort: (id: number) => http.post<boolean>(`/chat/sessions/${id}/abort`).then(unwrap),
  replyQuestion: (sessionId: number, requestId: string, answers: string[][]) =>
    http.post(`/chat/sessions/${sessionId}/question/${requestId}/reply`, { answers }),
  rejectQuestion: (sessionId: number, requestId: string) =>
    http.post(`/chat/sessions/${sessionId}/question/${requestId}/reject`),
  replyPermission: (sessionId: number, permissionId: string, reply: 'once' | 'always' | 'reject') =>
    http.post(`/chat/sessions/${sessionId}/permission/${permissionId}/reply`, { reply }),
}
