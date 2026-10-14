import { http } from './http'

/** 沙箱浏览器/工具连接信息(VNC / VSCode / 终端 URL)。 */
export interface SandboxInfo {
  mode?: string
  vnc_url?: string | null
  code_server_url?: string | null
  terminal_url?: string | null
  cdp_url?: string
  base_url?: string
  error?: string
}

/** 获取当前用户的 AIO 沙箱连接信息(网关代理,自动按 JWT 定位用户)。
 *  首次调用可能需要等待沙箱容器启动,超时放宽到 60s。 */
export async function sandboxInfo(): Promise<SandboxInfo> {
  const res = await http.get('/agent/browser/sandbox-info', { timeout: 60000 })
  const body = res.data?.data ?? res.data
  return (body || {}) as SandboxInfo
}

/** 关闭整个浏览器(停止沙箱容器,立即释放资源;下次使用自动重建)。 */
export async function browserStop(): Promise<void> {
  await http.post('/agent/sandbox/stop')
}

/** 导入本地导出的 cookie 到沙箱浏览器(登录态),使其免登录访问对应站点。 */
export interface CookieImportResult {
  status?: string
  ok?: number
  failed?: number
  total?: number
  error?: string
}

export async function importCookies(cookies: unknown[]): Promise<CookieImportResult> {
  const res = await http.post('/agent/browser/cookies', { cookies }, { timeout: 60000 })
  const body = res.data?.data ?? res.data
  return (body || {}) as CookieImportResult
}
