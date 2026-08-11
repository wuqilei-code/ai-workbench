import type { MessageVo, Step, StepParts, ToolCall, Turn } from '@/types'

/**
 * 把扁平的消息列表按"连续分组"规则聚合成 Turn 列表。
 * <p>
 * 规则:一条 user 消息 + 其后连续的 assistant 消息(到下一条 user 之前)= 一个 Turn。
 * Turn 内:有 stepFinish.reason==='stop' 的 step 优先作 finalAnswer;
 * 否则最后一条 assistant = finalAnswer,其余 = steps。
 * <p>
 * 只依赖 messages(不接收流式参数),流式增量文本由调用方经 props 注入到活跃 step。
 */
export function buildTurns(messages: MessageVo[]): Turn[] {
  const turns: Turn[] = []
  let current: Turn | null = null

  const pushCurrent = () => {
    if (current) {
      finalizeTurn(current)
      turns.push(current)
      current = null
    }
  }

  for (const msg of messages) {
    if (msg.role === 'user') {
      pushCurrent()
      current = {
        id: turnIdForUser(msg),
        userMessage: msg,
        steps: [],
        finalAnswer: null,
      }
    } else if (msg.role === 'assistant') {
      if (!current) {
        // 孤儿 turn(meta 先到、user 乐观气泡尚未关联等):建一个无 userMessage 的 turn
        current = {
          id: 'orphan-' + String(msg.id),
          userMessage: null,
          steps: [],
          finalAnswer: null,
        }
      }
      current.steps.push(toStep(msg))
    }
    // tool / system 角色忽略(不参与 turn 分组)
  }
  pushCurrent()
  return turns
}

/** 渲染流条目:一轮对话(turn)或一张 AI 变更总结卡片(summary),按时间顺序交错。 */
export type FlowItem =
  | { kind: 'turn'; turn: Turn }
  | { kind: 'summary'; summary: {
      summary: string
      files: { status: string; path: string }[]
      at?: string
      sha?: string
      parentSha?: string
    }; _insertBefore?: number }

/** summary 条目形状(与 chat store 的 summaries 元素对齐) */
export interface SummaryItem {
  summary: string
  files: { status: string; path: string }[]
  at?: string
  sha?: string
  parentSha?: string
}

/**
 * 把"轮 + 总结"按时间交错成有序渲染流,让每张总结卡片紧跟它所属的那一轮。
 * <p>
 * 总结生成于对应轮 idle 之后、下一条 user 消息之前,故把每张总结插到
 * 「at 时间之后的第一条 user 消息」之前(时间上晚于该总结的下一轮开头之前)。
 * 用 user 消息时间定位(而非 turnEnd):流式期间新 user 乐观气泡的 createdAt 是本地 now,
 * 若用 turnEnd(可能缺/晚)会导致总结与新消息谁先谁后抖动。
 */
export function buildFlow(turns: Turn[], summaries: SummaryItem[]): FlowItem[] {
  // 每个 turn 的"起始时间" = 其 user 消息的 createdAt(无则取 0)
  const turnStart = (t: Turn): number => {
    const ts = t.userMessage?.createdAt
    return ts ? new Date(ts).getTime() : 0
  }
  const summaryAt = (s: SummaryItem): number => (s.at ? new Date(s.at).getTime() : 0)

  const items: FlowItem[] = []
  for (const s of summaries) {
    // 找到 at 时间之后的第一条 user 消息所属 turn 的索引
    let insertAt = turns.length
    for (let k = 0; k < turns.length; k++) {
      if (turnStart(turns[k]) > summaryAt(s)) {
        insertAt = k
        break
      }
    }
    // 若总结时间介于某个 turn 内部(不该发生),用时间戳交错兜底
    items.push({ kind: 'summary', summary: s, _insertBefore: insertAt })
  }
  // 先把 summary 按插入位置排好,再与 turns 归并
  items.sort((a, b) => (a._insertBefore ?? 0) - (b._insertBefore ?? 0))
  const merged: FlowItem[] = []
  let si = 0
  for (let t = 0; t < turns.length; t++) {
    while (si < items.length && (items[si] as any)._insertBefore === t) {
      merged.push(items[si++])
    }
    merged.push({ kind: 'turn', turn: turns[t] })
  }
  while (si < items.length) merged.push(items[si++])
  return merged
}

/** 把 turn 内最后一条 step 提为 finalAnswer;若有 stop 标记则优先它 */
function finalizeTurn(turn: Turn) {
  if (turn.steps.length === 0) return
  let finalIdx = -1
  for (let i = 0; i < turn.steps.length; i++) {
    if (turn.steps[i].finishReason === 'stop') {
      finalIdx = i
      break
    }
  }
  if (finalIdx < 0) finalIdx = turn.steps.length - 1
  turn.finalAnswer = turn.steps[finalIdx]
  turn.steps.splice(finalIdx, 1)
}

function toStep(msg: MessageVo): Step {
  const parts: StepParts | null | undefined = msg.parts
  return {
    message: msg,
    narration: msg.content || '',
    toolCalls: parts?.toolCalls ?? [],
    finishReason: parts?.stepFinish?.reason,
    reasoning: parts?.reasoning?.text || '',
  }
}

function turnIdForUser(msg: MessageVo): string {
  // 乐观气泡 id 为负数(-Date.now());用 opencodeMessageId 兜底保证唯一
  return 'u-' + String(msg.id) + (msg.opencodeMessageId ? '-' + msg.opencodeMessageId : '')
}

/**
 * 把工具调用摘要成短串(展示在 chip 上,如 "hello.txt")。
 * 从 input 取常见路径/命令字段,只保留 basename;无则返回空串。
 */
export function summarizeToolCall(tc: ToolCall): string {
  const input = tc.input
  if (input == null) return ''
  const candidates = [
    input.filePath,
    input.path,
    input.file,
    input.filename,
    input.command,
    input.cmd,
  ]
  for (const c of candidates) {
    if (typeof c === 'string' && c.trim()) {
      // 取 basename(兼容 / 与 \)
      const base = c.split(/[\\/]/).filter(Boolean).pop()
      return base || c
    }
  }
  // 兜底:若有 url/name 等字段
  if (typeof input.url === 'string') return input.url
  if (typeof input.name === 'string') return input.name
  return ''
}

/**
 * 从 tool call input 提取完整文件路径(保留目录,非 basename),用于下载。
 * 优先取常见路径字段;均为空则返回 null。
 */
export function extractFilePath(tc: ToolCall): string | null {
  const input = tc.input
  if (input == null) return null
  for (const key of ['filePath', 'path', 'file', 'filename']) {
    const v = (input as Record<string, unknown>)[key]
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return null
}

/** 判断 tool 是否为"产出/修改文件"类(用于决定是否在 chip 上显示下载按钮) */
const FILE_PRODUCING_RE = /^(write|edit|create|str_replace|apply_patch|multi_edit|save|patch)/i
export function isFileProducingTool(tc: ToolCall): boolean {
  return FILE_PRODUCING_RE.test(tc.tool)
}

/** [结果文件] 标记行正则:整行匹配,path 至少 1 字符;多行模式(m)让 ^/$ 匹配每行首尾 */
const DELIVERABLE_LINE_RE = /^\[结果文件\]\s*(.+)$/gm

/** 从一个 turn 提取所有"结果文件"声明(跨 finalAnswer + 所有 steps,按 path 去重)。
 *  <p>
 *  AI 在回复末尾用 `[结果文件] <相对路径>` 标记交付文件(见 chat.md "交付文件声明"规则)。
 *  扫描所有 step 文本 + finalAnswer,按 path 去重并保留首次出现顺序。 */
export function extractDeliverables(turn: Turn): { path: string; name: string }[] {
  if (!turn) return []
  const sources: string[] = []
  for (const s of turn.steps) sources.push(s.narration || '')
  if (turn.finalAnswer) sources.push(turn.finalAnswer.narration || '')
  const seen = new Set<string>()
  const out: { path: string; name: string }[] = []
  for (const text of sources) {
    if (!text) continue
    for (const m of text.matchAll(DELIVERABLE_LINE_RE)) {
      const path = m[1].trim()
      if (!path || seen.has(path)) continue
      seen.add(path)
      out.push({ path, name: path.split(/[\\/]/).filter(Boolean).pop() || path })
    }
  }
  return out
}

/** 渲染时剥离 [结果文件] 标记行,并清理多余空行,避免正文出现空段。
 *  标记本身只在"结果文件"卡片区展示,不应出现在回复正文里。 */
export function stripDeliverableMarkers(text: string): string {
  if (!text) return ''
  return text
    .replace(DELIVERABLE_LINE_RE, '')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/^\s*\n/, '')
    .trimEnd()
}
