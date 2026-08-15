/**
 * 消息时间显示:DB 存北京时间,后端 LocalDateTime 序列化为无时区 ISO 字符串
 * (如 "2026-08-08T03:54:02.308")。浏览器按本地时区解析——中国用户(UTC+8)即正确。
 * 今天只显 HH:mm;跨天/跨年补日期。
 */
export function fmtMessageTime(dateStr?: string | null): string {
  if (!dateStr) return ''
  const d = new Date(dateStr)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n: number) => String(n).padStart(2, '0')
  const hm = `${p(d.getHours())}:${p(d.getMinutes())}`
  const now = new Date()
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  const sameYear = d.getFullYear() === now.getFullYear()
  if (sameDay) return hm
  return sameYear
    ? `${p(d.getMonth() + 1)}-${p(d.getDate())} ${hm}`
    : `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${hm}`
}