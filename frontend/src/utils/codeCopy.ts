import { getCodeBlock } from './markdown'

/** 全局事件委托:点击 markdown 代码块的复制按钮时复制源码。
 *  在 main.ts 注册一次即可(所有 v-html 渲染的代码块共用)。 */
export function handleCodeCopyClick(e: MouseEvent): void {
  const btn = (e.target as HTMLElement).closest?.('.code-copy-btn')
  if (!btn || !(btn instanceof HTMLElement)) return
  const idx = Number(btn.dataset.idx)
  if (Number.isNaN(idx)) return
  const code = getCodeBlock(idx)
  if (code == null) return
  e.preventDefault()
  e.stopPropagation()
  navigator.clipboard
    .writeText(code)
    .then(() => {
      const old = btn.textContent
      btn.textContent = '已复制 ✓'
      setTimeout(() => {
        if (btn.isConnected) btn.textContent = old
      }, 1200)
    })
    .catch(() => {
      btn.textContent = '复制失败'
      setTimeout(() => {
        if (btn.isConnected) btn.textContent = '复制'
      }, 1200)
    })
}

export function initCodeCopy(): void {
  document.addEventListener('click', handleCodeCopyClick)
}
