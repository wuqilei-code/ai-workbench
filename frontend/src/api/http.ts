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
    const msg = err.response?.data?.message || err.message || 'network error'
    return Promise.reject(new Error(msg))
  },
)

/** 取 data 字段 */
export function unwrap<T>(res: { data: { data: T } }): T {
  return res.data.data
}
