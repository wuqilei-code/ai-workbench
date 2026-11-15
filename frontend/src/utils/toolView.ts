/** 工具调用的"视图模型":把各工具千差万别的 args/output 归一成
 *  卡片需要的结构化数据(diff / 命令 / 路径列表 / 链接)。
 *
 *  只做纯数据推导,不依赖 Vue / i18n,便于单测。
 *  工具名以 deepagents 内置工具为准(read_file / write_file / edit_file /
 *  ls / glob / grep / execute),同时兼容常见别名与 MCP 工具的命名。 */

import type { ToolCall } from '@/types'
import { contentAsDiff, diffLines, parseUnifiedPatch, type LineDiff } from './diff'

export type ToolKind = 'diff' | 'shell' | 'read' | 'search' | 'link' | 'generic'

/** 卡片动作(决定标题文案与图标) */
export type ToolAction =
  | 'edit'
  | 'write'
  | 'patch'
  | 'shell'
  | 'read'
  | 'list'
  | 'search'
  | 'glob'
  | 'fetch'
  | 'websearch'

export type DiffMode = 'edit' | 'write' | 'patch'

export interface DiffView {
  path: string
  mode: DiffMode
  diff: LineDiff
  /** edit_file 的 replace_all:改动可能命中多处 */
  replaceAll: boolean
}

export interface ShellView {
  command: string
  timeout?: number
}

export interface ReadView {
  path: string
  /** 0-indexed 起始行(read_file 参数) */
  offset?: number
  limit?: number
  /** 输出实际行数(截断时含提示行) */
  lineCount: number
}

export interface SearchItem {
  path: string
  /** grep 命中行号 */
  line?: number
  /** grep 命中行内容 */
  detail?: string
}

export interface SearchView {
  query: string
  /** 搜索范围目录 */
  scope?: string
  glob?: string
  items: SearchItem[]
  /** 输出里解析出的条目总数(可能大于 items.length) */
  itemCount: number
}

export interface LinkView {
  urls: string[]
  query?: string
}

const DIFF_TOOLS =
  /^(edit|edit_file|editfile|write|write_file|writefile|apply_patch|patch|multi_edit|str_replace|str_replace_editor|create_file|append|append_file)$/i
const SHELL_TOOLS = /^(execute|bash|shell|run|terminal|shell_command)$/i
const READ_TOOLS = /^(read|read_file|readfile|view|open_file)$/i
const LIST_TOOLS = /^(ls|list|list_dir|listdir|list_files)$/i
const SEARCH_TOOLS = /^(grep|search|find|rg|search_files)$/i
const GLOB_TOOLS = /^(glob|glob_files)$/i
const LINK_TOOLS = /^(webfetch|web_fetch|fetch_url|fetch|websearch|web_search|search_web)$/i

function input(tc: ToolCall): Record<string, unknown> {
  const a = tc.args
  return a && typeof a === 'object' ? (a as Record<string, unknown>) : {}
}

function str(...vals: unknown[]): string | null {
  for (const v of vals) {
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return null
}

function num(...vals: unknown[]): number | undefined {
  for (const v of vals) {
    if (typeof v === 'number' && Number.isFinite(v)) return v
    if (typeof v === 'string' && v.trim() && Number.isFinite(Number(v))) return Number(v)
  }
  return undefined
}

/** 工具类别(决定用哪种卡片) */
export function toolKind(tc: ToolCall): ToolKind {
  const name = (tc.name || '').trim()
  if (!name) return 'generic'
  if (DIFF_TOOLS.test(name)) return 'diff'
  if (SHELL_TOOLS.test(name)) return 'shell'
  if (SEARCH_TOOLS.test(name) || GLOB_TOOLS.test(name)) return 'search'
  if (READ_TOOLS.test(name) || LIST_TOOLS.test(name)) return 'read'
  if (LINK_TOOLS.test(name)) return 'link'
  return 'generic'
}

/** 卡片动作(标题文案);未知工具返回 null,由调用方回退到原始工具名。 */
export function toolAction(tc: ToolCall): ToolAction | null {
  const name = (tc.name || '').trim()
  switch (toolKind(tc)) {
    case 'diff': {
      if (/edit|str_replace|multi_edit/i.test(name)) return 'edit'
      if (/patch/i.test(name)) return 'patch'
      if (/write|create|append/i.test(name)) return 'write'
      const v = toolDiff(tc)
      return v ? v.mode : 'write'
    }
    case 'shell':
      return 'shell'
    case 'read':
      return LIST_TOOLS.test(name) ? 'list' : 'read'
    case 'search':
      return GLOB_TOOLS.test(name) ? 'glob' : 'search'
    case 'link':
      return /web_?search|search_web/i.test(name) ? 'websearch' : 'fetch'
    default:
      return null
  }
}

/** 文件路径参数(兼容 snake_case / camelCase / 各种别名) */
export function toolFilePath(tc: ToolCall): string | null {
  const a = input(tc)
  return str(a.file_path, a.filePath, a.filepath, a.path, a.file, a.filename)
}

/** 文件 diff 视图;非编辑类或取不到内容时返回 null */
export function toolDiff(tc: ToolCall): DiffView | null {
  const a = input(tc)
  const path = toolFilePath(tc) || ''
  const oldS = str(a.old_string, a.oldString, a.old_str, a.oldStr, a.search)
  const newS = str(a.new_string, a.newString, a.new_str, a.newStr, a.replace, a.replacement)
  if (oldS != null || newS != null) {
    return {
      path,
      mode: 'edit',
      diff: diffLines(oldS ?? '', newS ?? ''),
      replaceAll: a.replace_all === true || a.replaceAll === true,
    }
  }
  const patch = str(a.patch, a.patch_text, a.patchText, a.diff, a.input)
  if (patch) {
    const parsed = parseUnifiedPatch(patch)
    if (parsed) return { path, mode: 'patch', diff: parsed, replaceAll: false }
  }
  const content = str(a.content, a.file_text, a.fileText, a.text)
  if (content != null) {
    return { path, mode: 'write', diff: contentAsDiff(content), replaceAll: false }
  }
  return null
}

/** 命令视图(execute / bash) */
export function toolShell(tc: ToolCall): ShellView | null {
  const a = input(tc)
  const command = str(a.command, a.cmd, a.script, a.shell_command)
  if (!command) return null
  return { command, timeout: num(a.timeout, a.timeout_sec, a.timeoutSec) }
}

/** 读取/列目录视图 */
export function toolRead(tc: ToolCall): ReadView {
  const a = input(tc)
  const output = (tc.output ?? '').replace(/\n+$/, '')
  return {
    path: toolFilePath(tc) || '',
    offset: num(a.offset, a.start_line, a.startLine),
    limit: num(a.limit, a.max_lines, a.maxLines),
    lineCount: output ? output.split('\n').length : 0,
  }
}

function stripAnsi(s: string): string {
  // eslint-disable-next-line no-control-regex
  return s.replace(/\u001b\[[0-9;]*m/g, '')
}

function looksLikePath(p: string): boolean {
  if (!p || /\s{3,}/.test(p)) return false
  return /[/\\]/.test(p) || /\.[A-Za-z0-9]{1,8}$/.test(p)
}

const MAX_SEARCH_ITEMS = 300

/** 从 grep / glob / ls 的文本输出里解析路径列表。
 *  兼容三种形态:每行一个路径、`路径:行号:内容`(grep content 模式)、带列表符号。 */
export function parseSearchItems(output: string): SearchItem[] {
  const items: SearchItem[] = []
  for (const raw of (output || '').split('\n')) {
    const line = stripAnsi(raw).trim()
    if (!line) continue
    if (/^(found|no matches|no files|0 matches|error|warning)\b/i.test(line)) continue
    if (/^\[[^\]]*\]$/.test(line)) continue
    const m = /^(.+?):(\d+):(.*)$/.exec(line)
    if (m && looksLikePath(m[1])) {
      items.push({ path: m[1].trim(), line: Number(m[2]), detail: m[3].trim() })
      continue
    }
    const p = line.replace(/^[-*•]\s+/, '').replace(/\s+\(\d+\)$/, '')
    if (looksLikePath(p)) items.push({ path: p })
    if (items.length >= MAX_SEARCH_ITEMS) break
  }
  return items
}

/** 搜索视图(grep / glob) */
export function toolSearch(tc: ToolCall): SearchView {
  const a = input(tc)
  const items = parseSearchItems(tc.output ?? '')
  return {
    query: str(a.pattern, a.query, a.search, a.term) || '',
    scope: str(a.path, a.dir, a.directory, a.cwd) || undefined,
    glob: str(a.glob, a.include) || undefined,
    items,
    itemCount: items.length,
  }
}

const URL_RE = /https?:\/\/[^\s<>"'`)\]]+/g
const MAX_LINKS = 12

/** 从工具入参与输出里提取可点击链接(webfetch / websearch) */
export function toolLinks(tc: ToolCall): LinkView {
  const a = input(tc)
  const found: string[] = []
  const push = (raw: string) => {
    const url = raw.replace(/[.,;:!?]+$/, '')
    if (!/^https?:\/\//i.test(url)) return
    if (found.includes(url)) return
    found.push(url)
  }
  for (const v of [a.url, a.href, a.link]) {
    if (typeof v === 'string' && v.trim()) push(v.trim())
  }
  for (const m of (tc.output ?? '').matchAll(URL_RE)) push(m[0])
  return {
    urls: found.slice(0, MAX_LINKS),
    query: str(a.query, a.q, a.search, a.term) || undefined,
  }
}

/** 压成单行并截断(命令、参数摘要用) */
export function oneLine(text: string, max = 120): string {
  const s = (text || '').replace(/\s+/g, ' ').trim()
  return s.length > max ? s.slice(0, max - 1) + '…' : s
}

/** 路径拆成"目录 + 文件名",便于列表里对齐展示 */
export function splitPath(path: string): { dir: string; base: string } {
  const p = (path || '').replace(/\\/g, '/')
  const i = p.lastIndexOf('/')
  if (i < 0) return { dir: '', base: p }
  return { dir: p.slice(0, i + 1), base: p.slice(i + 1) }
}
