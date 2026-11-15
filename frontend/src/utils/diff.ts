/** 行级 diff:给工具卡片渲染"编辑前/后"的最小变更行。
 *
 *  纯前端、无依赖;编辑片段通常很小,用 LCS 动态规划求最短编辑脚本。
 *  超大文本(整文件写入/补丁)降级为"整块替换",避免 O(n·m) 爆内存;
 *  展示时按 2 行上下文折叠未改动区段,长 diff 只保留前 N 行并标记截断。 */

export type DiffLineType = 'ctx' | 'add' | 'del'

/** 一行内容(带变更前后的行号;删除行无新行号,新增行无旧行号) */
export interface DiffLine {
  type: DiffLineType
  text: string
  oldNo?: number
  newNo?: number
}

/** 折叠掉的未改动区段 */
export interface DiffGap {
  type: 'gap'
  count: number
}

export type DiffRow = DiffLine | DiffGap

export interface LineDiff {
  rows: DiffRow[]
  additions: number
  deletions: number
  /** 行数超限,只渲染了前面一部分 */
  truncated: boolean
  /** 被截断未渲染的行数(不含折叠区段) */
  hiddenLines: number
}

type Op = { type: DiffLineType; text: string }

/** 每处改动保留的上下文行数 */
const CONTEXT = 2
/** 单次渲染的最大行数(超出折叠为"还有 N 行") */
const MAX_DISPLAY_LINES = 600
/** LCS 规模上限(超出走整块替换) */
const MAX_CELLS = 250_000

/** 按行切分;去掉末尾换行产生的空行,统一 CRLF。 */
function splitLines(s: string): string[] {
  if (!s) return []
  const out = s.replace(/\r\n?/g, '\n').split('\n')
  if (out.length && out[out.length - 1] === '') out.pop()
  return out
}

/** LCS 动态规划(从后往前填表),再正序回溯出编辑脚本。 */
function lcsOps(a: string[], b: string[]): Op[] {
  const n = a.length
  const m = b.length
  const w = m + 1
  const dp = new Int32Array((n + 1) * w)
  for (let i = n - 1; i >= 0; i--) {
    const row = i * w
    const next = (i + 1) * w
    for (let j = m - 1; j >= 0; j--) {
      dp[row + j] =
        a[i] === b[j]
          ? dp[next + j + 1] + 1
          : Math.max(dp[next + j], dp[row + j + 1])
    }
  }
  const ops: Op[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push({ type: 'ctx', text: a[i] })
      i++
      j++
    } else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) {
      ops.push({ type: 'del', text: a[i] })
      i++
    } else {
      ops.push({ type: 'add', text: b[j] })
      j++
    }
  }
  while (i < n) ops.push({ type: 'del', text: a[i++] })
  while (j < m) ops.push({ type: 'add', text: b[j++] })
  return ops
}

/** 整块替换:先删后加,用于超大规模或纯文本写入。 */
function blockOps(a: string[], b: string[]): Op[] {
  const ops: Op[] = a.map((text) => ({ type: 'del', text }))
  for (const text of b) ops.push({ type: 'add', text })
  return ops
}

function pickOps(a: string[], b: string[]): Op[] {
  if (!a.length) return b.map((text) => ({ type: 'add', text }))
  if (!b.length) return a.map((text) => ({ type: 'del', text }))
  // 剪掉公共前缀/后缀,把 DP 规模压到"真正变化的那一段"
  let start = 0
  while (start < a.length && start < b.length && a[start] === b[start]) start++
  let endA = a.length
  let endB = b.length
  while (endA > start && endB > start && a[endA - 1] === b[endB - 1]) {
    endA--
    endB--
  }
  const midA = a.slice(start, endA)
  const midB = b.slice(start, endB)
  const head: Op[] = a.slice(0, start).map((text) => ({ type: 'ctx', text }))
  const tail: Op[] = a.slice(endA).map((text) => ({ type: 'ctx', text }))
  let mid: Op[]
  if (!midA.length) mid = midB.map((text) => ({ type: 'add', text }))
  else if (!midB.length) mid = midA.map((text) => ({ type: 'del', text }))
  else if (midA.length * midB.length > MAX_CELLS) mid = blockOps(midA, midB)
  else mid = lcsOps(midA, midB)
  return head.concat(mid, tail)
}

/** 折叠大的未改动区段:只保留改动前后各 CONTEXT 行。 */
function collapseContext(lines: DiffLine[]): DiffRow[] {
  const keep = new Array<boolean>(lines.length).fill(false)
  let hasChange = false
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].type === 'ctx') continue
    hasChange = true
    const from = Math.max(0, i - CONTEXT)
    const to = Math.min(lines.length - 1, i + CONTEXT)
    for (let j = from; j <= to; j++) keep[j] = true
  }
  if (!hasChange) {
    return lines.length ? [{ type: 'gap', count: lines.length }] : []
  }
  const rows: DiffRow[] = []
  let i = 0
  while (i < lines.length) {
    if (keep[i]) {
      rows.push(lines[i])
      i++
      continue
    }
    let j = i
    while (j < lines.length && !keep[j]) j++
    rows.push({ type: 'gap', count: j - i })
    i = j
  }
  // 首尾的连续上下文直接丢弃(信息量低,且让卡片更紧凑)
  if (rows.length && rows[0].type === 'gap') rows.shift()
  if (rows.length && rows[rows.length - 1].type === 'gap') rows.pop()
  return rows
}

function finalize(lines: DiffLine[], additions: number, deletions: number): LineDiff {
  const rows = collapseContext(lines)
  const truncated = rows.length > MAX_DISPLAY_LINES
  const shown = truncated ? rows.slice(0, MAX_DISPLAY_LINES) : rows
  const hiddenLines = truncated
    ? rows.slice(MAX_DISPLAY_LINES).reduce((n, r) => n + (r.type === 'gap' ? r.count : 1), 0)
    : 0
  return { rows: shown, additions, deletions, truncated, hiddenLines }
}

function numberOps(ops: Op[]): LineDiff {
  const lines: DiffLine[] = []
  let additions = 0
  let deletions = 0
  let oldNo = 1
  let newNo = 1
  for (const op of ops) {
    if (op.type === 'ctx') {
      lines.push({ type: 'ctx', text: op.text, oldNo: oldNo++, newNo: newNo++ })
    } else if (op.type === 'del') {
      deletions++
      lines.push({ type: 'del', text: op.text, oldNo: oldNo++ })
    } else {
      additions++
      lines.push({ type: 'add', text: op.text, newNo: newNo++ })
    }
  }
  return finalize(lines, additions, deletions)
}

/** 计算两段文本的行级 diff。 */
export function diffLines(before: string, after: string): LineDiff {
  return numberOps(pickOps(splitLines(before), splitLines(after)))
}

/** 整段内容作为"新增行"(write_file / 新建文件)。只构建前 MAX_DISPLAY_LINES 行,避免大文件占用内存。 */
export function contentAsDiff(content: string): LineDiff {
  const all = splitLines(content)
  const shown = Math.min(all.length, MAX_DISPLAY_LINES)
  const lines: DiffLine[] = []
  for (let i = 0; i < shown; i++) lines.push({ type: 'add', text: all[i], newNo: i + 1 })
  return {
    rows: lines,
    additions: all.length,
    deletions: 0,
    truncated: all.length > shown,
    hiddenLines: all.length - shown,
  }
}

const HUNK_RE = /^@@\s*-(\d+)(?:,\d+)?\s*\+(\d+)(?:,\d+)?\s*@@/

/** 解析 unified patch(apply_patch / 手工补丁),失败返回 null。 */
export function parseUnifiedPatch(patch: string): LineDiff | null {
  const raw = splitLines(patch)
  const lines: DiffLine[] = []
  let additions = 0
  let deletions = 0
  let oldNo = 1
  let newNo = 1
  let changed = false
  for (const line of raw) {
    if (
      line.startsWith('*** ') ||
      line.startsWith('diff --git') ||
      line.startsWith('index ') ||
      line.startsWith('--- ') ||
      line.startsWith('+++ ') ||
      line.startsWith('\\ No newline')
    ) {
      continue
    }
    const hunk = HUNK_RE.exec(line)
    if (hunk) {
      oldNo = Number(hunk[1])
      newNo = Number(hunk[2])
      continue
    }
    if (line.startsWith('+')) {
      additions++
      changed = true
      lines.push({ type: 'add', text: line.slice(1), newNo: newNo++ })
    } else if (line.startsWith('-')) {
      deletions++
      changed = true
      lines.push({ type: 'del', text: line.slice(1), oldNo: oldNo++ })
    } else if (line.startsWith(' ') || line === '') {
      lines.push({ type: 'ctx', text: line.slice(1), oldNo: oldNo++, newNo: newNo++ })
    }
  }
  if (!changed) return null
  return finalize(lines, additions, deletions)
}
