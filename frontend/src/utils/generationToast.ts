/**
 * 全局节流:内容生成类 toast 提醒。
 *  - 同一工具(同 key)60s 内只提示一次(去重);
 *  - 不同工具 10s 内只提示一次(防突发连弹)。
 * 模块级状态,组件重挂载 / 多 turn 实例共用同一个节流器。 */
let lastKey = ''
let lastAt = 0

export function shouldShowGenerationToast(key: string, intervalMs = 10_000): boolean {
  const now = Date.now()
  if (key === lastKey && now - lastAt < 60_000) return false
  if (now - lastAt < intervalMs) return false
  lastKey = key
  lastAt = now
  return true
}
