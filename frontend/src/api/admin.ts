import { http, unwrap } from './http'
import type { ProviderVo } from '@/types'

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
}
