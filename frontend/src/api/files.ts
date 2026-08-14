import { http, unwrap } from './http'
import type { FileNode, FileHistoryEntry, FileDiffResult } from '@/types'

export const fileApi = {
  list: (path = '') => http.get<FileNode[]>('/files', { params: { path } }).then(unwrap),
  /** 递归搜索文件名(root 限定范围;技能模式传 .opencode/skills) */
  search: (root: string, q: string) =>
    http.get<FileNode[]>('/files/search', { params: { root, q } }).then(unwrap),
  content: (path: string) => http.get<string>('/files/content', { params: { path } }).then(unwrap),
  write: (path: string, content: string) => http.post('/files', { path, content }),
  delete: (path: string) => http.delete('/files', { params: { path } }),

  /** 获取文件 Blob(带 JWT),不触发下载,供预览组件使用。后端异常以 JSON Blob 返回,response interceptor 解析后抛出。 */
  fetchBlob: async (path: string): Promise<Blob> => {
    const res = await http.get('/files/download', { params: { path }, responseType: 'blob' })
    return res.data as Blob
  },

  /** 下载文件(带 JWT),经 Blob URL 触发浏览器下载 */
  download: async (path: string) => {
    const blob = await fileApi.fetchBlob(path)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = path.split(/[\\/]/).pop() || 'download'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  },

  /** 上传文件(multipart) */
  upload: (dir: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    form.append('dir', dir)
    return http.post('/files/upload', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },

  /** 上传 ZIP 项目包并解压到指定目录(默认 uploads/);返回解压文件数。
   *  大文件公网上传较慢,放宽超时到 5 分钟。 */
  uploadZip: (dir: string, file: File, onProgress?: (pct: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    form.append('dir', dir)
    return http.post<{ data: number }>('/files/upload-zip', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 5 * 60 * 1000,
      onUploadProgress: onProgress
        ? (e) => {
            if (e.total) onProgress(Math.round((e.loaded / e.total) * 100))
          }
        : undefined,
    }).then((res) => res.data.data as number)
  },

  /** 新建目录 */
  mkdir: (path: string) => http.post('/files/mkdir', null, { params: { path } }),

  /** 文件历史(版本列表) */
  history: (path: string) => http.get<FileHistoryEntry[]>('/files/history', { params: { path } }).then(unwrap),

  /** 两版 diff;to 省略=对比当前工作区版本 */
  diff: (path: string, from: string, to?: string) =>
    http.get<FileDiffResult>('/files/diff', { params: { path, from, to } }).then(unwrap),

  /** 回滚到某版本 */
  restore: (path: string, sha: string) => http.post('/files/restore', null, { params: { path, sha } }),
}
