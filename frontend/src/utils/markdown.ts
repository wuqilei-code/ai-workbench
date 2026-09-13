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

/** 把内联文本中的预览地址识别为可点击链接,在 token 流层面处理,天然跳过代码块(fence)。
 *  匹配两种形式:
 *  - 标准代理地址 /api/preview/<port>(chat.md 约定)
 *  - agent 可能输出 http://localhost:<port> / http://127.0.0.1:<port> / localhost:<port>
 *    这些在用户浏览器里指向用户本机,必须重写为代理地址才能访问。 */
const PREVIEW_RE = /(?:\/api\/preview\/\d{2,5}(?:\/[^\s`)\]}>]*)?|(?:https?:\/\/)?(?:localhost|127\.0\.0\.1):\d{2,5}(?:\/[^\s`)\]}>]*)?)/g

/** 预览地址重写为代理路径,依赖同源 HttpOnly cookie(aiwb_token)鉴权,不把 JWT 暴露进 URL。
 *  localhost/127.0.0.1 形式重写为标准代理地址 /api/preview/<port>。 */
function previewHref(raw: string): string {
  let path = raw
  const localMatch = raw.match(/(?:https?:\/\/)?(?:localhost|127\.0\.0\.1):(\d{2,5})(.*)/)
  if (localMatch) {
    path = '/api/preview/' + localMatch[1] + (localMatch[2] || '/')
  }
  return path
}

/** 兼容流式解析器与 markdown-it 两种核心 state 的 Token 构造(两者都在 state.Token 上暴露) */
function newToken(state: any, type: string, tag: string, nesting: number): any {
  return new state.Token(type, tag, nesting)
}

/** preview_links 核心规则:把内联文本中的预览地址识别为可点击链接。
 *  同时注册于 markdown-it(legacy 渲染)与 stream-markdown-parser(markstream-vue),
 *  两者 token 结构兼容;流式解析器对部分 linkify 裸链接直接生成结构化 link 节点,一并处理。 */
export function previewLinks(state: any) {
  for (const block of state.tokens) {
    if (block.type !== 'inline' || !block.children) continue
    const children = block.children
    // 第零遍:结构化 link 节点(带 href),直接改写节点字段
    for (let i = 0; i < children.length; i++) {
      const token = children[i]
      if (token.type !== 'link' || typeof token.href !== 'string') continue
      if (!/^(?:https?:\/\/)?(?:localhost|127\.0\.0\.1):\d{2,5}/.test(token.href)) continue
      token.href = previewHref(token.href)
      token.attrs = [['target', '_blank'], ['rel', 'noreferrer noopener'], ['class', 'preview-link']]
      if (token.children && token.children.length === 1 && token.children[0].type === 'text') {
        token.children[0].content = '🖥 打开预览'
        token.children[0].raw = '🖥 打开预览'
      }
    }
    // 第一遍:重写 linkify 已生成的 localhost 链接(<a href="http://localhost:8012">…</a>)
    for (let i = 0; i < children.length; i++) {
      const token = children[i]
      if (token.type !== 'link_open') continue
      const hrefAttr = (token.attrs || []).find((a: any[]) => a[0] === 'href')
      if (!hrefAttr) continue
      const href: string = String(hrefAttr[1] || '')
      if (!/^(?:https?:\/\/)?(?:localhost|127\.0\.0\.1):\d{2,5}/.test(href)) continue
      hrefAttr[1] = previewHref(href)
      token.attrs = [...(token.attrs || []).filter((a: any[]) => a[0] !== 'target' && a[0] !== 'class'),
        ['target', '_blank'], ['rel', 'noreferrer noopener'], ['class', 'preview-link']]
      // 把链接内文本改成"打开预览"(其后通常是 text → link_close)
      for (let j = i + 1; j < children.length; j++) {
        const inner = children[j]
        if (inner.type === 'text' || inner.type === 'code_inline') {
          inner.content = '🖥 打开预览'
          break
        }
        if (inner.type === 'link_close') break
      }
    }
    // 第二遍:裸文本(含 /api/preview/ 或 localhost:端口)转成代理链接;
    // 跳过已在链接内部(level > 0)的文本,避免嵌套 <a>
    for (let i = 0; i < children.length; i++) {
      const token = children[i]
      if (token.type !== 'text' && token.type !== 'code_inline') continue
      if (token.level > 0) continue
      const text: string = token.content
      if (!text.includes('/api/preview/') && !/localhost:\d|127\.0\.0\.1:\d/.test(text)) continue
      let last = 0
      let m: RegExpExecArray | null
      PREVIEW_RE.lastIndex = 0
      const newChildren: any[] = []
      while ((m = PREVIEW_RE.exec(text)) !== null) {
        const before = text.slice(last, m.index)
        if (before) {
          const t = newToken(state, 'text', '', 0)
          t.content = before
          newChildren.push(t)
        }
        const link = newToken(state, 'link_open', 'a', 1)
        link.attrs = [
          ['href', previewHref(m[0])],
          ['target', '_blank'],
          ['rel', 'noreferrer noopener'],
          ['class', 'preview-link'],
        ]
        newChildren.push(link)
        const inner = newToken(state, 'text', '', 0)
        inner.content = '🖥 打开预览'
        newChildren.push(inner)
        const close = newToken(state, 'link_close', 'a', -1)
        newChildren.push(close)
        last = m.index + m[0].length
      }
      const tail = text.slice(last)
      if (tail) {
        const t = newToken(state, 'text', '', 0)
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
