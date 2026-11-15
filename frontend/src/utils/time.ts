/**
 * 消息时间显示。
 *
 * 后端 LocalDateTime 序列化后有两种形态:
 * - 落库时间(MySQL CURRENT_TIMESTAMP,容器默认 UTC):无时区,如 "2026-11-14T10:03:25.043";
 * - 前端乐观消息(发送时本地构造):带 Z 的 ISO,如 "2026-11-14T18:03:25.043Z"。
 *
 * 无时区的裸时间按 UTC 解析,再交给浏览器转成本地时区;带时区的直接解析。
 * 今天只显 HH:mm;跨天/跨年补日期。
 */
const NAIVE_DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?$/

export function fmtMessageTime(dateStr?: string | null): string {
  if (!dateStr) return ''
  const normalized = NAIVE_DATETIME_RE.test(dateStr) ? dateStr + 'Z' : dateStr
  const d = new Date(normalized)
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
