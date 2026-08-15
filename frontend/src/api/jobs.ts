import { http, unwrap } from './http'
import type { JobTaskInfo, JobTaskRunInfo } from '@/types'

/** 定时任务接口:CRUD + 手动触发 + 取消 + 运行日志 + 运行历史。 */
export const jobApi = {
  list: () => http.get<JobTaskInfo[]>('/jobs').then(unwrap),
  create: (data: { name: string; prompt: string; cron?: string | null; agent?: string; autoApprove?: boolean }) =>
    http.post<JobTaskInfo>('/jobs', data).then(unwrap),
  update: (
    id: number,
    data: Partial<{ name: string; prompt: string; cron: string | null; agent: string; autoApprove: boolean; enabled: boolean }>,
  ) => http.patch<JobTaskInfo>(`/jobs/${id}`, data).then(unwrap),
  remove: (id: number) => http.delete(`/jobs/${id}`),
  run: (id: number) => http.post<boolean>(`/jobs/${id}/run`).then(unwrap),
  cancel: (id: number) => http.post<boolean>(`/jobs/${id}/cancel`).then(unwrap),
  log: (id: number, tail = 12000) =>
    http.get<string>(`/jobs/${id}/log`, { params: { tail } }).then(unwrap),
  runs: (id: number) => http.get<JobTaskRunInfo[]>(`/jobs/${id}/runs`).then(unwrap),
  runLog: (id: number, runId: number, tail = 12000) =>
    http.get<string>(`/jobs/${id}/runs/${runId}/log`, { params: { tail } }).then(unwrap),
}