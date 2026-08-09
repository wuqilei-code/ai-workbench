/** 把文本作为 .md 文件下载到本地(带 UTF-8 BOM,避免 Windows 记事本乱码)。 */
export function downloadMarkdown(text: string, sessionId?: number | null): void {
  const blob = new Blob(['\uFEFF' + text], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  const name = sessionId ? `chat-${sessionId}-${Date.now()}` : `export-${Date.now()}`
  a.href = url
  a.download = `${name}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
