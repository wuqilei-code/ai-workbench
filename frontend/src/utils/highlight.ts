/** 工具输出轻量高亮:JSON 结构化着色 / 日志行级关键词着色。
 *  返回 HTML 转义后的字符串(可直接 v-html);超大输出跳过着色,仅转义保性能。 */

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** JSON 着色(输入已转义;key=主题强调色,string=ok 绿,num=蓝,bool/null=主题色)。
 *  逐 token 扫描而不是正则嵌套替换,避免 span 标签自身的引号被误匹配。 */
function jsonHighlight(s: string): string {
  const tokenRe = /"(?:\\.|[^"\\])*"|\b-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b|\b(?:true|false|null)\b|./g
  let out = ''
  let m: RegExpExecArray | null
  while ((m = tokenRe.exec(s)) !== null) {
    const t = m[0]
    const c = t[0]
    if (c === '"') {
      // key 与 value 的区分:字符串后紧跟(跳过空白)冒号即为 key
      const isKey = /^\s*:/.test(s.slice(tokenRe.lastIndex))
      out += isKey ? `<span class="hk j-key">${t}</span>` : `<span class="hk j-str">${t}</span>`
    } else if (c === '-' ? /\d/.test(t[1] ?? '') : /\d/.test(c)) {
      out += `<span class="hk j-num">${t}</span>`
    } else if (t === 'true' || t === 'false') {
      out += `<span class="hk j-bool">${t}</span>`
    } else if (t === 'null') {
      out += `<span class="hk j-null">${t}</span>`
    } else {
      out += t
    }
  }
  return out
}

/** 日志行级着色:含错误/异常关键词的行标红,警告行标黄(输入未转义,逐行转义后着色) */
function logHighlight(s: string): string {
  const errRe = /(error|exception|traceback|failed|fatal|panic|denied|失败|错误|异常)/i
  const warnRe = /(warning|warn|deprecated|警告)/i
  return s
    .split('\n')
    .map((line) => {
      const el = escapeHtml(line)
      if (errRe.test(line)) return `<span class="hk lg-err">${el}</span>`
      if (warnRe.test(line)) return `<span class="hk lg-warn">${el}</span>`
      return el
    })
    .join('\n')
}

/** 工具输出高亮入口:JSON(对象/数组)走结构化着色,否则走日志关键词着色 */
export function highlightToolOutput(text: string): string {
  const raw = text ?? ''
  if (!raw.trim()) return ''
  if (raw.length > 100000) return escapeHtml(raw)
  const trimmed = raw.trim()
  if (/^[[{]/.test(trimmed)) {
    try {
      const value = JSON.parse(trimmed)
      const formatted = JSON.stringify(value, null, 2)
      if (typeof formatted === 'string' && formatted) return jsonHighlight(escapeHtml(formatted))
    } catch {
      /* 非 JSON,走日志行级着色 */
    }
  }
  return logHighlight(raw)
}
