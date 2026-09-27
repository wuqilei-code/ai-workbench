import axios from 'axios'

export const http = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => {
    // 后端统一 R<T>:{code,message,data};code!=0 视为业务错误
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new Error(body.message || 'request failed'))
    }
    return res
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      if (location.pathname !== '/login') location.href = '/login'
    }
    const data = err.response?.data
    // blob 响应(文件下载/预览):错误以 R 包裹的 JSON Blob 返回,读取文本解析 message
    if (data instanceof Blob) {
      return data.text().then((t) => {
        let msg = 'request failed'
        try {
          const j = JSON.parse(t)
          if (j?.message) msg = j.message
        } catch { /* 非 JSON 的错误正文,保留默认文案 */ }
        return Promise.reject(new Error(msg))
      })
    }
    const msg = data?.message || err.message || 'network error'
    return Promise.reject(new Error(msg))
  },
)

/** 取后端统一响应体 R<T> 的 data 字段。
 *  调用方 `http.get<T>(...)` 的泛型是「业务数据类型 T」,而运行时 `res.data` 实际是
 *  `{ code, message, data: T }` 信封(AxiosResponse 的 data 泛型与之一致),故这里从
 *  res.data.data 取出 T;若后端返回的不是信封(如 blob),不会走到这里。 */
export function unwrap<T>(res: { data: T }): T {
  return (res.data as unknown as { data: T }).data
}
