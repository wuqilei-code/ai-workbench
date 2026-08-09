import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

/** 渲染后的代码块原文登记表:按钮 data-idx → 源码文本。
 *  供全局事件委托(./codeCopy.ts)在点击复制按钮时取原文。 */
const codeBlocks = new Map<number, string>()
let _idx = 0

/** 注册一段代码原文,返回对应索引;索引会复用(渲染内容被替换后,旧索引自然失效) */
export function registerCodeBlock(raw: string): number {
  _idx = (_idx + 1) % 1_000_000
  codeBlocks.set(_idx, raw)
  // 防止长会话里 Map 无限增长:超过 2000 条时清一次(旧索引失效,不影响当前渲染)
  if (codeBlocks.size > 2000) {
    const keys = [...codeBlocks.keys()].slice(0, codeBlocks.size - 2000)
    for (const k of keys) codeBlocks.delete(k)
  }
  return _idx
}

export function getCodeBlock(idx: number): string | undefined {
  return codeBlocks.get(idx)
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(str: string, lang: string): string {
    const idx = registerCodeBlock(str)
    const langName = (lang && lang.trim()) || ''
    const body =
      langName && hljs.getLanguage(langName)
        ? (() => {
            try {
              return hljs.highlight(str, { language: langName }).value
            } catch {
              return md.utils.escapeHtml(str)
            }
          })()
        : md.utils.escapeHtml(str)
    const label = langName ? `复制 · ${langName}` : '复制'
    return [
      `<div class="code-block">`,
      `<button class="code-copy-btn" data-idx="${idx}" title="复制代码">${label}</button>`,
      `<pre class="hljs"><code>${body}</code></pre>`,
      `</div>`,
    ].join('')
  },
})

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return md.render(text)
}
