import { http, unwrap } from './http'
import type { FileNode } from '@/types'

export const fileApi = {
  list: (path = '') => http.get<FileNode[]>('/files', { params: { path } }).then(unwrap),
  /** 递归搜索文件名(root 限定范围;技能模式传 .opencode/skills) */
  search: (root: string, q: string) =>
    http.get<FileNode[]>('/files/search', { params: { root, q } }).then(unwrap),
  content: (path: string) => http.get<string>('/files/content', { params: { path } }).then(unwrap),
  write: (path: string, content: string) => http.post('/files', { path, content }),
  delete: (path: string) => http.delete('/files', { params: { path } }),

  /** 获取文件 Blob(带 JWT),不触发下载,供预览组件使用。后端异常以 JSON Blob 返回,解析后抛出。 */
  fetchBlob: async (path: string): Promise<Blob> => {
    const res = await http.get('/files/download', { params: { path }, responseType: 'blob' })
    const blob: Blob = res.data
    if (blob.type && blob.type.includes('application/json')) {
      let text = ''
      try {
        text = await blob.text()
      } catch {
        /* ignore */
      }
      try {
        const j = JSON.parse(text)
        throw new Error(j.message || 'download failed')
      } catch (e) {
        throw e instanceof Error ? e : new Error('download failed')
      }
    }
    return blob
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

  /** 新建目录 */
  mkdir: (path: string) => http.post('/files/mkdir', null, { params: { path } }),
}
