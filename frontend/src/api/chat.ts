import { http, unwrap } from './http'
import type { SessionVo, MessageVo, SkillInfo, TemplateVo, MarketplaceSkillVo, SkillFileVo } from '@/types'

export const chatApi = {
  listSessions: (limit?: number, offset?: number) =>
    http.get<SessionVo[]>('/chat/sessions', { params: { limit, offset } }).then(unwrap),
  listArchivedSessions: (limit?: number, offset?: number) =>
    http.get<SessionVo[]>('/chat/sessions/archived', { params: { limit, offset } }).then(unwrap),
  searchSessions: (q: string) => http.get<SessionVo[]>('/chat/sessions/search', { params: { q } }).then(unwrap),
  builtinSkills: () => http.get<SkillInfo[]>('/chat/skills/builtin').then(unwrap),
  /** 用户自定义技能名(可勾选按会话加载) */
  customSkills: () => http.get<string[]>('/chat/skills/custom').then(unwrap),
  /** 我的技能(user_skill:自建/安装) */
  listMineSkills: () => http.get<any[]>('/chat/skills/mine').then(unwrap),
  createSkill: (data: { name: string; description?: string; content?: string; visibility?: number; files?: SkillFileVo[] }) =>
    http.post<any>('/chat/skills/mine', data).then(unwrap),
  updateSkill: (id: number, data: { name?: string; description?: string; content?: string; visibility?: number; files?: SkillFileVo[] }) =>
    http.put<any>(`/chat/skills/mine/${id}`, data).then(unwrap),
  deleteSkill: (id: number) => http.delete(`/chat/skills/mine/${id}`),
  createSession: (data: { title?: string; providerId?: string; modelId?: string; agent?: string; skills?: string[]; mcpServers?: string[] }) =>
    http.post<SessionVo>('/chat/sessions', data).then(unwrap),
  /** 导入会话:上传导出的 JSON,返回新建会话 */
  importSession: (json: string) => http.post<SessionVo>('/chat/sessions/import', { json }).then(unwrap),
  getSession: (id: number) => http.get<SessionVo>(`/chat/sessions/${id}`).then(unwrap),
  updateSession: (id: number, data: Partial<{ title: string; providerId: string; modelId: string; archived: number; pinned: number; agent: string; skills: string[]; mcpServers: string[]; resetMcpServers: boolean }>) =>
    http.patch<SessionVo>(`/chat/sessions/${id}`, data).then(unwrap),
  deleteSession: (id: number) => http.delete(`/chat/sessions/${id}`),
  /** 消息历史(游标分页):beforeId 向上翻页取更早,afterId 增量追平,都不传取最新 limit 条 */
  listMessages: (id: number, opts?: { limit?: number; beforeId?: number; afterId?: number }) =>
    http.get<MessageVo[]>(`/chat/sessions/${id}/messages`, {
      params: { limit: opts?.limit ?? 200, beforeId: opts?.beforeId, afterId: opts?.afterId },
    }).then(unwrap),
  clearMessages: (id: number) => http.delete(`/chat/sessions/${id}/messages`),
  exportSession: (id: number, format: 'md' | 'json' | 'pdf' | 'docx' = 'md') =>
    http.get<Blob>(`/chat/sessions/${id}/export`, { params: { format }, responseType: 'blob' }),
  sendMessage: (id: number, data: { text: string; providerId?: string; modelId?: string }) =>
    http.post(`/chat/sessions/${id}/messages`, data),
  abort: (id: number) => http.post<boolean>(`/chat/sessions/${id}/abort`).then(unwrap),
  /** 人工确认(HITL):允许/拒绝危险操作,或回答 agent 提问,续跑被中断的生成 */
  replyPermission: (sessionId: number, requestId: string, decision: 'approve' | 'reject' | 'reply', answer?: string) =>
    http.post<boolean>(`/chat/sessions/${sessionId}/permission/${requestId}/reply`, { decision, answer }).then(unwrap),
  /** 清除过期/失效的待确认请求(防止刷新后重现) */
  clearPermission: (sessionId: number, requestId: string) =>
    http.post<boolean>(`/chat/sessions/${sessionId}/permission/${requestId}/clear`).then(unwrap),
  listTemplates: () => http.get<TemplateVo[]>('/chat/templates').then(unwrap),
  /** 启用/停用模板 */
  setTemplateEnabled: (id: number, enabled: boolean) =>
    http.post(`/chat/templates/${id}/enabled`, { enabled }),
  /** 收藏/取消收藏模板(favorite 省略则取反) */
  setTemplateFavorite: (id: number, favorite?: boolean) =>
    http.post<{ favorited: boolean }>(`/chat/templates/${id}/favorite`, { favorite }).then(unwrap),
  /** 置顶/取消置顶(仅自己的模板) */
  setTemplatePin: (id: number, pinned: boolean) =>
    http.post<{ pinned: boolean }>(`/chat/templates/${id}/pin`, { pinned }).then(unwrap),
  createTemplate: (data: { title: string; category: string; content: string; icon?: string; description?: string; tags?: string; format?: string; skill?: string; thumb?: string }) =>
    http.post<TemplateVo>('/chat/templates', data).then(unwrap),
  updateTemplate: (id: number, data: { title: string; category: string; content: string; icon?: string; description?: string; tags?: string; format?: string; skill?: string; thumb?: string }) =>
    http.put<TemplateVo>(`/chat/templates/${id}`, data).then(unwrap),
  deleteTemplate: (id: number) => http.delete(`/chat/templates/${id}`),
  /** 保存当前用户模板的自定义顺序(id 数组按展示先后) */
  reorderTemplates: (ids: number[]) => http.post('/chat/templates/reorder', { ids }),

  // ===== 模板市场 =====
  /** 市场模板列表(installed/mine 标记) */
  templateMarketList: () => http.get<any[]>('/chat/templates/market').then(unwrap),
  /** 我发布的模板 */
  templateMarketMine: () => http.get<any[]>('/chat/templates/market/mine').then(unwrap),
  /** 把我创建的模板分享到市场 */
  templatePublish: (id: number) => http.post(`/chat/templates/${id}/publish`),
  /** 下架我发布的模板 */
  templateUnpublish: (id: number) => http.delete(`/chat/templates/market/${id}`),
  /** 从市场安装到我的模板库 */
  templateInstall: (id: number) => http.post(`/chat/templates/market/${id}/install`),
  listMarketplaceSkills: () => http.get<MarketplaceSkillVo[]>('/chat/skills/marketplace').then(unwrap),
  marketplaceSkillDetail: (id: number) => http.get<MarketplaceSkillVo>(`/chat/skills/marketplace/${id}`).then(unwrap),
  publishSkill: (data: { name: string; description: string; category?: string; icon?: string; content: string; files?: SkillFileVo[] }) =>
    http.post<MarketplaceSkillVo>('/chat/skills/marketplace', data).then(unwrap),
  /** 上传技能包(zip/.skill,内含根级 SKILL.md) */
  uploadSkill: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<MarketplaceSkillVo>('/chat/skills/marketplace/upload', fd).then(unwrap)
  },
  installSkill: (id: number) => http.post(`/chat/skills/marketplace/${id}/install`),
  deleteMarketplaceSkill: (id: number) => http.delete(`/chat/skills/marketplace/${id}`),
}