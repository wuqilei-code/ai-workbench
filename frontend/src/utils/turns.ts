import type { MessageVo, Step, StepParts, ToolCall, Turn } from '@/types'

/**
 * 把扁平的消息列表按"连续分组"规则聚合成 Turn 列表。
 * <p>
 * 规则:一条 user 消息 + 其后连续的 assistant 消息(到下一条 user 之前)= 一个 Turn。
 * Turn 内:最后一条 assistant = finalAnswer(deepagents 一轮一条 assistant 消息,无中间步);
 * 若出现多条(极端/历史数据),最后一条作 finalAnswer,其余为 steps。
 * <p>
 * 只依赖 messages;流式增量文本由调用方经 props 注入到活跃 step。
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
        // 孤儿 turn(消息壳先于 user 消息出现时)
        current = {
          id: 'orphan-' + String(msg.id),
          userMessage: null,
          steps: [],
          finalAnswer: null,
        }
      }
      current.steps.push(...stepsFromParts(msg))
    }
    // tool / system / interrupted 角色忽略(不参与 turn 分组)
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
 * 把"轮 + 总结"按时间交错成有序渲染流。
 * deepagents 轨道 summaries 恒为空,本函数退化为纯轮次列表(保留形状兼容)。
 */
export function buildFlow(turns: Turn[], summaries: SummaryItem[]): FlowItem[] {
  const turnStart = (t: Turn): number => {
    const ts = t.userMessage?.createdAt
    return ts ? new Date(ts).getTime() : 0
  }
  const summaryAt = (s: SummaryItem): number => (s.at ? new Date(s.at).getTime() : 0)

  const items: FlowItem[] = []
  for (const s of summaries) {
    let insertAt = turns.length
    for (let k = 0; k < turns.length; k++) {
      if (turnStart(turns[k]) > summaryAt(s)) {
        insertAt = k
        break
      }
    }
    items.push({ kind: 'summary', summary: s, _insertBefore: insertAt })
  }
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

/** 把 turn 内最后一段"正文"步提为 finalAnswer(推理/工具步留在思考区按序展示) */
function finalizeTurn(turn: Turn) {
  if (turn.steps.length === 0) return
  let idx = -1
  for (let i = turn.steps.length - 1; i >= 0; i--) {
    if ((turn.steps[i].kind || 'text') === 'text') { idx = i; break }
  }
  if (idx < 0) idx = turn.steps.length - 1
  // 推理不是"答案":当最后可提升的步骤是纯推理段时不提升,让它保留在思考区,
  // 避免"思考"在流式期被当成正文渲染、结束后才跳进思考块。
  // 系统提示(会话压缩)同理:它是过程性提示,绝不能成为最终答案。
  if (turn.steps[idx].kind === 'reason' || turn.steps[idx].kind === 'system') return
  // 空步骤(发送时预建的占位壳)不提升:保留在 steps 里,使 turn 走多步模式
  // 立即渲染"AI Workbench + 思考中"头部,而非一个空白气泡。
  if (!(turn.steps[idx].narration || '').trim() && !turn.steps[idx].toolCalls?.length) return
  const fa = turn.steps[idx]
  turn.finalAnswer = fa
  turn.steps.splice(idx, 1)
  // 当末段"正文"整段只是交付文件声明(总结后又插入了 update_memory 等后台工具、单独输出
  // [结果文件] 行时,末段正文会只剩下标记行),此时把紧邻的上文正文并入 finalAnswer,
  // 避免真正的总结被孤立在折叠的思考区、只有文件下载块。
  // 修复:总结消失 bug→finalAnswer 合并紧邻正文。build=20260829-2
  if (fa.kind === 'text' && !stripDeliverableMarkers(fa.narration || '').trim()) {
    for (let i = turn.steps.length - 1; i >= 0; i--) {
      const s = turn.steps[i]
      if ((s.kind || 'text') === 'text' && (s.narration || '').trim()) {
        fa.narration = (s.narration || '') + '\n\n' + (fa.narration || '')
        turn.steps.splice(i, 1)
        break
      }
    }
  }
}

function toStep(msg: MessageVo): Step {
  const parts: StepParts | null | undefined = msg.parts
  return {
    message: msg,
    narration: msg.content || '',
    toolCalls: parts?.toolCalls ?? [],
  }
}

/**
 * 按落库时间线把一条 assistant 消息拆成有序步骤(文本段 ↔ 工具调用交错)。
 * 旧消息无 timeline 时回退为单步(narration=全文 + 全部工具)。
 */
function stepsFromParts(msg: MessageVo): Step[] {
  const base = toStep(msg)
  const tl = (msg.parts as any)?.timeline as
    | { k: string; v?: string; id?: string }[]
    | undefined
  if (!Array.isArray(tl) || !tl.length) return [base]
  const byId = new Map<string, ToolCall>((base.toolCalls ?? []).map((tc) => [tc.id || '', tc]))
  const steps: Step[] = []
  let cur: Step | null = null
  const flush = () => {
    if (cur && ((cur.narration || '').trim() || cur.toolCalls.length)) steps.push(cur)
    cur = null
  }
  for (const e of tl) {
    if (e.k === 'system') {
      // 系统提示(会话压缩等):独立成步,永不并入相邻文本段、也不参与 finalAnswer 提升
      flush()
      steps.push({ message: msg, narration: e.v || '', toolCalls: [], kind: 'system' })
    } else if (e.k === 'text' || e.k === 'reason') {
      const kind: 'text' | 'reason' = e.k === 'reason' ? 'reason' : 'text'
      if (cur && cur.kind === kind && !cur.toolCalls.length) {
        cur.narration += e.v || ''
      } else {
        flush()
        cur = { message: msg, narration: e.v || '', toolCalls: [], kind }
      }
    } else if (e.k === 'tool' && e.id) {
      const tc = byId.get(e.id)
      // 网关对同一调用可能在多个节点更新里重复追加时间线条目;
      // 无对应 toolCall 的条目(重复/数据缺失)直接跳过,避免生成"只有图标没有内容"的空步骤
      if (!tc) continue
      flush()
      cur = { message: msg, narration: '', toolCalls: [tc] }
      byId.delete(e.id)
    }
  }
  flush()
  // 兜底:toolCalls 里有而时间线漏掉的调用追加到末尾
  if (byId.size && steps.length === 0) return [base]
  for (const tc of byId.values()) {
    steps.push({ message: msg, narration: '', toolCalls: [tc] })
  }
  return steps.length ? steps : [base]
}

function turnIdForUser(msg: MessageVo): string {
  return 'u-' + String(msg.id) + (msg.agentMessageId ? '-' + msg.agentMessageId : '')
}

function firstStr(...vals: unknown[]): string | null {
  for (const v of vals) {
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return null
}

/** 路径摘要:/workspace 前缀剥离;/skills/<名>/... 显示为「技能 <名><子路径>」;其余保留末两级目录。 */
export function summarizePath(raw: string): string {
  let p = raw.replace(/\\/g, '/').trim()
  p = p.replace(/^\/workspace\/?/i, '')
  const skill = p.match(/^\/?skills\/([^/]+)(\/.+)?$/i)
  if (skill) return `技能 ${skill[1]}${skill[2] || ''}`
  const segs = p.split('/').filter(Boolean)
  return segs.slice(-2).join('/') || p
}

/**
 * 把工具调用摘要成短串(展示在 chip 上)。
 * 技能目录路径带技能名(如 "技能 note-summary/SKILL.md");命令单行截断;写路径保留末两级。
 */
export function summarizeToolCall(tc: ToolCall): string {
  const args = tc.args as Record<string, unknown> | null | undefined
  if (args == null) return ''
  if (tc.name === 'create_skill' && typeof args.name === 'string') return args.name
  if (tc.name === 'use_skill' && typeof args.skill === 'string') return args.skill
  const path = firstStr(args.filePath, args.path, args.file, args.filename)
  if (path) return summarizePath(path)
  const cmd = firstStr(args.command, args.cmd)
  if (cmd) {
    const oneLine = cmd.replace(/\s+/g, ' ')
    return oneLine.length > 64 ? oneLine.slice(0, 63) + '…' : oneLine
  }
  if (typeof args.query === 'string' && args.query.trim()) return args.query
  if (typeof args.url === 'string') return args.url
  if (typeof args.name === 'string') return args.name
  return ''
}

/** 从 tool call args 提取完整文件路径(保留目录,非 basename),用于下载。 */
export function extractFilePath(tc: ToolCall): string | null {
  const args = tc.args
  if (args == null) return null
  for (const key of ['filePath', 'path', 'file', 'filename']) {
    const v = (args as Record<string, unknown>)[key]
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return null
}

/** 判断 tool 是否为"产出/修改文件"类(用于决定是否在 chip 上显示下载按钮) */
const FILE_PRODUCING_RE = /^(write|edit|create|str_replace|apply_patch|multi_edit|save|patch)/i
export function isFileProducingTool(tc: ToolCall): boolean {
  return FILE_PRODUCING_RE.test(tc.name)
}

/** [结果文件] 标记行正则:整行匹配,path 至少 1 字符;多行模式(m)让 ^/$ 匹配每行首尾 */
const DELIVERABLE_LINE_RE = /^\[结果文件\]\s*(.+)$/gm

/** [WIDGET_FILE] 标记行:组件已由 toolCall.outputs 渲染为交互组件,正文里的标记行同样剥离 */
const WIDGET_FILE_LINE_RE = /^\[WIDGET_FILE\]\s*(\S+)\s*$/gm

/** 归一化交付路径:去掉 /workspace 前缀与首尾分隔符,得到相对项目根的路径(前端文件 API 以此为基准)。 */
export function normalizeDeliverablePath(path: string): string {
  if (!path) return path
  return path
    .trim()
    .replace(/^\/workspace\/?/i, '')
    .replace(/^[\\/]+/, '')
    .trim()
}

/** 从一个 turn 提取所有"结果文件"声明(跨 finalAnswer + 所有 steps,按归一化 path 去重)。
 *  <p>
 *  AI 在回复末尾用 `[结果文件] <相对路径>` 标记交付文件(见 chat.md "交付文件声明"规则,
 *  deepagents 系统提示词同样约定该格式)。扫描所有 step 文本 + finalAnswer,按 path 去重并保留首次出现顺序。 */
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
      const path = normalizeDeliverablePath(m[1])
      if (!path || seen.has(path)) continue
      seen.add(path)
      out.push({ path, name: path.split(/[\\/]/).filter(Boolean).pop() || path })
    }
  }
  return out
}

/** 从 turn 的已完成工具调用里提取交付文件(即使 AI 没写 [结果文件] 标记,结果文件块也能展示)。
 *  优先用工具调用的结构化 outputs(deepagents 透传的确切路径,含 execute 的目录 diff 结果);
 *  没有 outputs 时,只对写文件类工具从 args 猜路径。路径均归一化为项目相对。 */
export function toolCallDeliverables(turn: Turn): { path: string; name: string }[] {
  if (!turn) return []
  const out: { path: string; name: string }[] = []
  const seen = new Set<string>()
  const pushTc = (tc: ToolCall) => {
    if (tc.status !== 'completed') return
    const candidates: string[] = (tc.outputs || []).map((o) => o && o.path).filter(Boolean)
    if (candidates.length === 0) {
      if (!isFileProducingTool(tc)) return
      const raw = extractFilePath(tc)
      if (raw) candidates.push(raw)
    }
    for (const raw of candidates) {
      const path = normalizeDeliverablePath(raw)
      if (!path || seen.has(path)) continue
      seen.add(path)
      out.push({ path, name: path.split(/[\\/]/).filter(Boolean).pop() || path })
    }
  }
  for (const s of turn.steps) (s.toolCalls || []).forEach(pushTc)
  if (turn.finalAnswer) (turn.finalAnswer.toolCalls || []).forEach(pushTc)
  return out
}

/** 合并两种来源的交付文件(标记优先,工具调用兜底),按归一化 path 去重,保持首次出现顺序。 */
export function collectDeliverables(turn: Turn): { path: string; name: string }[] {
  const markers = extractDeliverables(turn)
  const fromTools = toolCallDeliverables(turn)
  const seen = new Set<string>()
  const merged: { path: string; name: string }[] = []
  for (const item of [...markers, ...fromTools]) {
    if (seen.has(item.path)) continue
    seen.add(item.path)
    merged.push(item)
  }
  return merged
}

/** 渲染时剥离 [结果文件] / [WIDGET_FILE] 标记行,并清理多余空行,避免正文出现空段。 */
export function stripDeliverableMarkers(text: string): string {
  if (!text) return ''
  return text
    .replace(DELIVERABLE_LINE_RE, '')
    .replace(WIDGET_FILE_LINE_RE, '')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/^\s*\n/, '')
    .trimEnd()
}

// ---------- 任务规划(write_todos) ----------

/** 单个规划任务项(write_todos 工具参数,整表替换式) */
export interface PlanTodo {
  content: string
  status: 'pending' | 'in_progress' | 'completed'
  /** 进行中的现在进行时描述(如"正在渲染图表"),展示优先于 content */
  active_form?: string
}

/** 从 write_todos 工具入参解析任务清单(容错:模型可能传数组/嵌套/JSON 字符串)。 */
function parsePlanArgs(args: any): PlanTodo[] | null {
  if (!args) return null
  let raw: any = args
  if (!Array.isArray(raw)) raw = raw.todos ?? raw.items ?? raw.plan
  if (typeof raw === 'string') {
    try {
      raw = JSON.parse(raw)
    } catch {
      return null
    }
  }
  if (!Array.isArray(raw) || raw.length === 0) return null
  const out: PlanTodo[] = []
  for (const it of raw) {
    if (!it || typeof it !== 'object') continue
    const content = String(it.content ?? it.task ?? it.title ?? '').trim()
    if (!content) continue
    const st = String(it.status ?? 'pending').toLowerCase()
    const status: PlanTodo['status'] =
      st === 'completed' || st === 'done' ? 'completed'
        : st === 'in_progress' || st === 'active' || st === 'running' ? 'in_progress'
          : 'pending'
    out.push({ content, status, active_form: it.active_form ? String(it.active_form) : undefined })
  }
  return out.length ? out : null
}

/** 提取 turn 当前的任务规划:按时间序扫描所有工具调用,最后一次 write_todos 的
 *  整表替换生效(流式期间随工具事件实时更新;历史消息从落库 parts 恢复)。 */
export function extractPlan(turn: Turn): PlanTodo[] {
  if (!turn) return []
  let latest: PlanTodo[] | null = null
  const scan = (tcs: ToolCall[] | undefined) => {
    for (const tc of tcs || []) {
      if (tc.name !== 'write_todos') continue
      const parsed = parsePlanArgs(tc.args)
      if (parsed) latest = parsed
    }
  }
  for (const s of turn.steps) scan(s.toolCalls)
  if (turn.finalAnswer) scan(turn.finalAnswer.toolCalls)
  return latest || []
}