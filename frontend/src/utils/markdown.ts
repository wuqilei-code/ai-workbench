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

/** 把内联文本中的预览地址 /api/preview/<port>(及子路径)识别为可点击链接。
 *  在 token 流层面处理,天然跳过代码块(fence)内容。 */
const PREVIEW_RE = /\/api\/preview\/\d{2,5}(?:\/[^\s`)\]}>]*)?/g

/** 预览链接带上当前登录 JWT(顶层导航新标签页无法携带 Authorization 头/cookie 跨域不保证,
 *  只能靠 URL 参数传递凭据)。JWT 24h 过期,泄露面可控。 */
function previewHref(path: string): string {
  const token = typeof localStorage !== 'undefined' ? localStorage.getItem('token') : null
  if (!token) return path
  return path.includes('?') ? `${path}&token=${encodeURIComponent(token)}` : `${path}?token=${encodeURIComponent(token)}`
}

function previewLinks(state: any) {
  for (const block of state.tokens) {
    if (block.type !== 'inline' || !block.children) continue
    const children = block.children
    for (let i = 0; i < children.length; i++) {
      const token = children[i]
      if (token.type !== 'text') continue
      const text: string = token.content
      if (!text.includes('/api/preview/')) continue
      let last = 0
      let m: RegExpExecArray | null
      PREVIEW_RE.lastIndex = 0
      const newChildren: any[] = []
      while ((m = PREVIEW_RE.exec(text)) !== null) {
        const before = text.slice(last, m.index)
        if (before) {
          const t = new state.Token('text', '', 0)
          t.content = before
          newChildren.push(t)
        }
        const link = new state.Token('link_open', 'a', 1)
        link.attrs = [
          ['href', previewHref(m[0])],
          ['target', '_blank'],
          ['rel', 'noreferrer noopener'],
          ['class', 'preview-link'],
        ]
        newChildren.push(link)
        const inner = new state.Token('text', '', 0)
        inner.content = '🖥 打开预览'
        newChildren.push(inner)
        const close = new state.Token('link_close', 'a', -1)
        newChildren.push(close)
        last = m.index + m[0].length
      }
      const tail = text.slice(last)
      if (tail) {
        const t = new state.Token('text', '', 0)
        t.content = tail
        newChildren.push(t)
      }
      if (newChildren.length) {
        children.splice(i, 1, ...newChildren)
        i += newChildren.length - 1
      }
    }
  }
}

md.core.ruler.push('preview_links', previewLinks)

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return md.render(text)
}
