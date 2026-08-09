import { http, unwrap } from './http'
import type { AdminUserVo, ProviderVo, UsageStats } from '@/types'

export const modelApi = {
  available: () => http.get<ProviderVo[]>('/models').then(unwrap),
}

export const adminApi = {
  listProviders: () => http.get<ProviderVo[]>('/admin/providers').then(unwrap),
  createProvider: (data: any) => http.post<ProviderVo>('/admin/providers', data).then(unwrap),
  updateProvider: (id: number, data: any) => http.put<ProviderVo>(`/admin/providers/${id}`, data).then(unwrap),
  deleteProvider: (id: number) => http.delete(`/admin/providers/${id}`),
  createModel: (data: { providerId: string; modelId: string; name: string }) =>
    http.post('/admin/models', data).then(unwrap),
  deleteModel: (id: number) => http.delete(`/admin/models/${id}`),
  apply: () => http.post('/admin/providers/apply'),
  usageStats: (days = 14) => http.get<UsageStats>('/admin/stats/usage', { params: { days } }).then(unwrap),
  listUsers: (keyword?: string) => http.get<AdminUserVo[]>('/admin/users', { params: { keyword } }).then(unwrap),
  updateUserStatus: (id: number, status: number) => http.put(`/admin/users/${id}/status`, { status }),
  updateUserRole: (id: number, role: 'user' | 'admin') => http.put(`/admin/users/${id}/role`, { role }),
  resetUserPassword: (id: number, password: string) => http.put(`/admin/users/${id}/password`, { password }),
}
