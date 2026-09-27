<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { useChatStore } from '@/stores/chat'
import { useFavoritesStore } from '@/stores/favorites'
import { renderMarkdown } from '@/utils/markdown'
import { downloadMarkdown } from '@/utils/exportMarkdown'
import { isFileProducingTool, collectDeliverables, stripDeliverableMarkers } from '@/utils/turns'
import { fmtMessageTime } from '@/utils/time'
import { fileApi } from '@/api/files'
import JSZip from 'jszip'
import type { Step, Turn, ToolCall } from '@/types'
import ChatMessage from './ChatMessage.vue'
import DeliverableBar from './DeliverableBar.vue'
import FilePreview from './preview/FilePreview.vue'
import StreamMarkdown from './StreamMarkdown.vue'
import ToolCallItem from './ToolCallItem.vue'
import WidgetFrame from './WidgetFrame.vue'
import TypeReveal from './TypeReveal.vue'

const props = defineProps<{
  turn: Turn
  sessionId: number | null
}>()

const chat = useChatStore()
const msg = useMessage()
const fav = useFavoritesStore()
const { t } = useI18n()

// ---------- 流式 / 活跃判定 ----------

/** 当前正在接收 token 的消息 id(可能为 null,如步骤间的工具执行) */
const activeMessageId = computed(() => chat.streamingMessageId)

/** 本 turn 是否为最后一个 turn(只有最后一个 turn 可能正在生成) */
const isLastTurn = computed(() => {
  const list = chat.turns
  return list.length > 0 && list[list.length - 1].id === props.turn.id
})

/** 本 turn 是否仍在生成中(isStreaming 稳定,仅 session.idle 结束) */
const turnInProgress = computed(() => chat.isStreaming && isLastTurn.value)

/** 本轮生成已运行秒数:秒数在走 = AI 仍在工作,用于安抚"以为断线/暂停"的疑虑 */
const runningSeconds = computed(() => chat.streamingElapsedSeconds)

/** 友好提示:有工具在跑就展示(即使已有消息壳)。
 *  - 内容生成/写文件类工具 → "正在生成/写入内容…"
 *  - 其它工具 → "正在执行 xxx…"
 *  - 无工具无文本(纯思考)→ "AI 正在处理中…"
 * AI 是否已产出当前可见内容(正在流式输出的推理/正文,或已提交到活跃步骤的文本)。
 *  用于定位真正"等待"的窗口:收到首个 reason/delta 前显示等待动画,产出后转由时间线承载。 */
const hasVisibleOutput = computed(() => {
  if (chat.streamingText) return true
  if (chat.liveReasoning) return true
  const id = activeMessageId.value
  for (const s of props.turn.steps) {
    if (s.message.agentMessageId === id && (s.narration || '').trim()) return true
  }
  return false
})

const showWorkingHint = computed(() =>
  turnInProgress.value &&
  // 思考块头部已在转 spinner 时不再重复显示等待行
  props.turn.steps.length === 0 &&
  (chat.runningActivity?.kind === 'tool' || !hasVisibleOutput.value),
)

const pendingHint = computed(() => {
  const act = chat.runningActivity
  if (act?.kind === 'tool') {
    if (isFileProducingTool({ name: act.tool } as ToolCall)) {
      return act.arg ? t('turn.writing', { arg: act.arg }) : t('turn.writingNoArg')
    }
    return act.arg ? t('turn.running', { tool: act.tool, arg: act.arg }) : t('turn.runningNoArg', { tool: act.tool })
  }
  return t('turn.thinking')
})

/** 某个 step 是否正在流式接收文本(仅时间线最后一步算活跃) */
function isStepActive(step: Step | null): boolean {
  if (!step || !step.message.agentMessageId) return false
  if (!chat.isStreaming || step.message.agentMessageId !== activeMessageId.value) return false
  const steps = props.turn.steps
  const last = steps.length ? steps[steps.length - 1] : props.turn.finalAnswer
  return last === step || props.turn.finalAnswer === step
}

/** 取 step 的展示文本:统一用定稿/实时 narration(时间线已按序累积)。
 *  活跃推理段不经 buildTurns 重建,直接读 store.liveReasoning 保证逐块实时可见。 */
function displayContent(step: Step | null): string {
  if (!step) return ''
  if (step.kind === 'reason' && isStepActive(step)) {
    return chat.liveReasoning || step.narration || ''
  }
  return step.narration || ''
}

/** 步骤可见文本:正文类剥离 [结果文件]/[WIDGET_FILE] 标记行(标记已单独渲染为
 *  组件/交付卡),避免"只剩标记行"的步骤渲染出一个右侧无内容的孤立图标行。 */
function stepDisplayText(step: Step | null): string {
  if (!step) return ''
  const raw = displayContent(step)
  return step.kind === 'reason' ? raw : stripDeliverableMarkers(raw)
}

/** 流式期间最终答案的实时文本:非 simpleMode 下 displayContent 只读 step.narration,
 *  而 narration 是 reason 时间线的累计文本,不含 delta 正文;
 *  故当 finalAnswer 正在活跃时,直接使用 store.streamingText 保证增量可见。 */
const faStreamingText = computed(() => {
  const fa = props.turn.finalAnswer
  if (!fa || !isStepActive(fa)) return ''
  return chat.streamingText || ''
})

/** 流式中本 turn 的"正文"实时文本(finalAnswer 尚未成形、纯推理已是 step 时用)。
 *  推理留在思考块,正文在此独立流式;done 后由 finalAnswer 承接,避免推理被当正文。 */
const liveStreamText = computed(() => {
  if (!turnInProgress.value || props.turn.finalAnswer) return ''
  const id = activeMessageId.value
  if (!id) return ''
  const owns = props.turn.steps.some((s) => s.message.agentMessageId === id)
  if (!owns) return ''
  return chat.streamingText || ''
})

// ---------- 最终答案(markstream 流式渲染) ----------

/** 最终答案展示文本:流式期间用实时正文,定稿后读定稿内容(剥离交付标记行) */
const answerContent = computed(() =>
  stripDeliverableMarkers(
    liveStreamText.value || faStreamingText.value || displayContent(props.turn.finalAnswer),
  ),
)

/** 最终答案是否仍在流式输出(决定 markstream 按增量渲染还是定稿渲染) */
const answerStreaming = computed(() => {
  if (!turnInProgress.value) return false
  if (faStreamingText.value || liveStreamText.value) return true
  return isStepActive(props.turn.finalAnswer)
})

// ---------- 推理段包裹卡片 ----------

/** 推理段展开状态:流式期间本回合的推理段全部展开(思考随输出持续可见,不被新步骤收起);
 *  结束后自动折叠;用户点击后记入手动覆盖 */
const reasonOpen = ref<Record<string, boolean>>({})
const reasonKey = (i: number) => `${props.turn.id}-r${i}`
function isReasonOpen(step: Step, i: number): boolean {
  const manual = reasonOpen.value[reasonKey(i)]
  if (manual !== undefined) return manual
  // 流式中的本回合:所有推理段保持展开;完成后折叠
  if (turnInProgress.value && step.message.agentMessageId === activeMessageId.value) return true
  return false
}
function toggleReason(i: number) {
  const k = reasonKey(i)
  reasonOpen.value[k] = !isReasonOpen(props.turn.steps[i], i)
}

/** 步骤时间线容器(.timeline,限高 420px 内部滚动):流式期间若用户未上翻则吸底跟随最新步骤 */
const timelineEl = ref<HTMLElement | null>(null)
let timelineStuck = true
function onTimelineScroll() {
  const el = timelineEl.value
  if (!el) return
  timelineStuck = el.scrollTop + el.clientHeight >= el.scrollHeight - 60
}
function scrollTimelineToBottom() {
  const el = timelineEl.value
  if (!el || !timelineStuck) return
  if (el.scrollHeight > el.clientHeight) el.scrollTop = el.scrollHeight
}
/** 活跃推理段内容增长时,时间线吸底(读 displayContent,以感知 liveReasoning) */
const activeReasonLen = computed(() => {
  const s = props.turn.steps.find((x) => x.kind === 'reason' && isStepActive(x))
  return s ? displayContent(s).length : 0
})
watch(activeReasonLen, async () => {
  await nextTick()
  scrollTimelineToBottom()
})
// 新步骤(工具/更多推理段)追加进时间线时也吸底,保证流式中最新输出无需手动滚动
watch(() => props.turn.steps.length, () => {
  if (!turnInProgress.value) return
  nextTick(scrollTimelineToBottom)
})

// ---------- 简单模式(单条 assistant 无 tool)----------

const simpleMode = computed(
  () =>
    props.turn.steps.length === 0 &&
    !!props.turn.finalAnswer &&
    props.turn.finalAnswer.toolCalls.length === 0,
)

const simpleMessage = computed(() => {
  const fa = props.turn.finalAnswer
  if (!fa) return null
  // 剥离 [结果文件] 标记行,避免 simpleMode 正文里显示标记(标记只在 DeliverableBar 展示)
  // 活跃时段:优先正文(streamingText);无正文且为活跃推理段时读 liveReasoning,
  // 保证首个 reason 一到即开始流式输出(而非憋到最后一次性出现)
  const raw = isStepActive(fa)
    ? (chat.streamingText ||
        (fa.kind === 'reason' ? (chat.liveReasoning || '') : ''))
    : (fa.message.content || '')
  return { ...fa.message, content: stripDeliverableMarkers(raw) }
})

async function copyFinalAnswer() {
  const fa = props.turn.finalAnswer
  if (!fa) return
  const text = (fa.message.content || '').trim()
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    msg.success(t('turn.copied'))
  } catch {
    msg.error(t('turn.copyFailed'))
  }
}

/** 导出为 .md 文件:文件名取会话标题或时间戳 */
function exportFinalAnswerAsMarkdown() {
  const fa = props.turn.finalAnswer
  if (!fa) return
  const text = stripDeliverableMarkers(fa.message.content || '').trim()
  if (!text) return
  downloadMarkdown(text, props.sessionId ?? undefined)
}

// ---------- 收藏最终答案 ----------

const faMessageId = computed(() => props.turn.finalAnswer?.message.id ?? 0)

/** 最终答案消息时间(多步气泡顶部) */
const faTime = computed(() => props.turn.finalAnswer?.message.createdAt || '')

/** 本轮全部工具调用(各步骤 + 最终答案),按调用 id 去重避免重复计数 */
const allToolCalls = computed(() => {
  const seen = new Set<string>()
  const out: any[] = []
  const add = (list: any[]) => {
    for (const tc of list || []) {
      const key = String(tc?.id || `${tc?.name}|${JSON.stringify(tc?.args || {})}`)
      if (seen.has(key)) continue
      seen.add(key)
      out.push(tc)
    }
  }
  for (const s of props.turn.steps) add(s.message.parts?.toolCalls || [])
  if (props.turn.finalAnswer) add(props.turn.finalAnswer.toolCalls || [])
  return out
})

/** 工具分类定义:按前缀/名称归入不同类别,未知归入「其它」 */
const TOOL_CATEGORIES: { key: string; label: () => string; icon: string; match: (n: string) => boolean }[] = [
  { key: 'file', label: () => t('turn.catFile'), icon: 'file', match: (n) => /^(ls|read_file|read|write_file|write|edit_file|edit|delete|glob|grep|execute|bash)$/.test(n) },
  { key: 'browser', label: () => t('turn.catBrowser'), icon: 'globe', match: (n) => /browser|chrom|cdp|aiwb/i.test(n) },
  { key: 'viz', label: () => t('turn.catViz'), icon: 'chart', match: (n) => /widget|mermaid|excalidraw|docx|pptx|xlsx|pdf|html|chart|report|doc/i.test(n) },
  { key: 'skill', label: () => t('turn.catSkill'), icon: 'skill', match: (n) => /skill|todo|plan|ask_user|memory/i.test(n) },
]

/** 工具调用按类别聚合(每类数量 + 代表工具名),仅保留非空类别 */
const toolSummary = computed(() => {
  const byKey = new Map<string, { key: string; label: string; icon: string; calls: any[] }>()
  for (const tc of allToolCalls.value) {
    const name = String(tc?.name || 'tool')
    const cat = TOOL_CATEGORIES.find((c) => c.match(name)) || { key: 'other', label: () => t('turn.catOther'), icon: 'tool' }
    if (!byKey.has(cat.key)) byKey.set(cat.key, { key: cat.key, label: cat.label(), icon: cat.icon, calls: [] })
    byKey.get(cat.key)!.calls.push(tc)
  }
  return Array.from(byKey.values()).map((g) => {
    const counter = new Map<string, number>()
    for (const c of g.calls) {
      const n = String(c?.name || 'tool')
      counter.set(n, (counter.get(n) || 0) + 1)
    }
    const running = g.calls.some((c) => c?.status === 'running')
    const error = g.calls.some((c) => c?.status === 'error' || c?.status === 'failed')
    return {
      key: g.key,
      label: g.label,
      icon: g.icon,
      total: g.calls.length,
      tools: Array.from(counter.entries()).map(([name, count]) => ({ name, count })),
      running,
      error,
    }
  })
})

/** 本轮生成耗时(秒)。
 *  1) 当前会话内:store 在流式结束时按 assistant 消息 id 记录(从发送时刻精确计时)
 *  2) 刷新后(内存态丢失):读后端落库的 parts.elapsedSec(流开始→定稿的真实耗时)。
 *     注意不能用 createdAt 差值——assistant 消息在流式期间即已首次落库,其时间戳≈流开始 */
const turnElapsedSec = computed(() => {
  const msgs = [...props.turn.steps.map((s) => s.message)]
  if (props.turn.finalAnswer) msgs.push(props.turn.finalAnswer.message)
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    const id = m.agentMessageId
    if (id && chat.turnElapsed[id]) return chat.turnElapsed[id]
    const p = m.parts as { elapsedSec?: number } | null
    if (p && typeof p.elapsedSec === 'number' && p.elapsedSec > 0) return p.elapsedSec
  }
  return 0
})
const elapsedText = computed(() => {
  const s = turnElapsedSec.value
  if (!s) return ''
  return s >= 60 ? `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s` : `${s}s`
})

const faStarred = computed(() =>
  props.sessionId != null && faMessageId.value > 0
    ? fav.isFavorite(props.sessionId, faMessageId.value)
    : false,
)

function toggleFaStar() {
  if (props.sessionId == null || faMessageId.value <= 0) return
  const added = fav.toggle({
    sessionId: props.sessionId,
    messageId: faMessageId.value,
    role: 'assistant',
    content: stripDeliverableMarkers(props.turn.finalAnswer?.message.content || '').slice(0, 500),
    turnId: props.turn.id,
    sessionTitle: '',
  })
  msg.success(added ? t('turn.favorited') : t('turn.unfavorited'))
}

// ---------- 建议追问(回答定稿后展示可点击的继续提问) ----------/** 基于用户原始问题 + 回答内容,启发式生成 2-3 个继续提问的示例。 */
const followUpQuestions = computed<string[]>(() => {
  if (turnInProgress.value) return []
  const fa = props.turn.finalAnswer
  if (!fa || !(fa.message.content || '').trim()) return []
  const userQ = props.turn.userMessage?.content || ''
  const q = userQ.trim()
  const asksWhy = /为什么|为何|原因/.test(q)
  const asksHow = /怎么|如何|怎么做|步骤/.test(q)
  const asksWhat = /是什么|区别|对比|哪个/.test(q)
  const out: string[] = []
  if (asksWhy) out.push(t('turn.followWhy'))
  else if (asksHow) out.push(t('turn.followHow'))
  if (asksWhat) out.push(t('turn.followWhat'))
  out.push(t('turn.followExample'))
  out.push(t('turn.followSummary'))
  return out.slice(0, 3)
})

function askFollowUp(text: string) {
  chat.fillInput(text)
}

// ---------- 重新生成回答 ----------

/** 重新生成:用本 turn 的用户问题再次触发 AI 回答(追加新一轮)。 */
async function regenerateAnswer() {
  const userText = props.turn.userMessage?.content
  if (!userText || !chat.currentSessionId) {
    msg.warning(t('turn.regenNoQuestion'))
    return
  }
  if (chat.isStreaming) {
    msg.info(t('turn.regenBusy'))
    return
  }
  try {
    await chat.sendMessage(userText)
  } catch (e: any) {
    msg.error(e?.message || t('turn.regenFailed'))
  }
}

const canRegenerate = computed(
  () =>
    !!props.turn.userMessage &&
    !!props.turn.finalAnswer &&
    !turnInProgress.value &&
    chat.currentSessionId != null,
)

/** 本 turn 的结果文件清单(仅 turn 定稿后展示)。
 *  流式期间(turnInProgress)返回空,避免半截标记 `[结果` 闪烁。
 *  来源:[结果文件] 标记行 + 已完成的写文件类工具调用(双重兜底,见 collectDeliverables)。 */
const deliverables = computed(() =>
  turnInProgress.value ? [] : collectDeliverables(props.turn),
)

const fileDeliverables = computed(() =>
  deliverables.value.filter((d) => !/^widgets\/[^/]+\.html$/i.test(d.path)),
)

/** 存在性校验过滤:AI 清理中间产物(或用户删除)后,`[结果文件]` 标记与工具目录
 *  diff 里声明的路径可能已不存在,不能继续展示。先乐观展示,随后批量校验剔除。
 *  校验失败(网络等)时保留原列表——宁可多显示也不吞掉真实文件。 */
const verifiedDeliverables = ref<{ path: string; name: string }[]>([])

let verifySeq = 0
watch(
  () => fileDeliverables.value.map((d) => d.path).join('\n'),
  async () => {
    const items = fileDeliverables.value
    const seq = ++verifySeq
    if (!items.length) {
      verifiedDeliverables.value = []
      return
    }
    verifiedDeliverables.value = items
    try {
      const existing = new Set(await fileApi.existsBatch(items.map((d) => d.path)))
      if (seq !== verifySeq) return
      verifiedDeliverables.value = items.filter((d) => existing.has(d.path))
    } catch {
      if (seq === verifySeq) verifiedDeliverables.value = items
    }
  },
  { immediate: true },
)

/** 按时间线穿插:某一步里 pure_show_widget 已产出的组件路径(流式期间工具一完成即出现) */
function stepWidgetPaths(step: Step | null): string[] {
  if (!step) return []
  return (step.toolCalls ?? [])
    .filter((tc) => tc.name === 'pure_show_widget' && tc.outputs?.length)
    .flatMap((tc) => tc.outputs!.map((o) => o.path))
    .filter((p) => /^widgets\/[^/]+\.html$/i.test(p))
}

/** 已完成/出错且「完全没有可展示内容」的工具 → 时间线不展示。
 *  判定比原先宽:除 output 外,`args`(会以 chip-arg 摘要展示)与 `outputs` 也算内容。
 *  原规则只看 output,把「生成式 UI」类工具整条隐藏了 —— 例如 baidu_chatCompletions
 *  的结果不在 output 里(恒为空串),但 args 里有 model/query/instruction,是真实操作。 */
function isBlankToolOutput(tc: ToolCall): boolean {
  if (tc.status !== 'completed' && tc.status !== 'error') return false
  if ((tc.output ?? '').trim()) return false
  if (tc.outputs && tc.outputs.length) return false
  const a = tc.args as Record<string, unknown> | undefined
  return !(a && Object.keys(a).length > 0)
}

/** chip 展示过滤:pure_show_widget 已完成的不再显示原始 chip(避免 [WIDGET_FILE] 噪声);
 *  write_todos 的清单在**流式期间**由任务规划卡片承载,那时不重复展示;
 *  但卡片只在生成中存在,turn 结束后就没了 —— 所以定稿后必须在时间线里把记录还回来;
 *  空输出的已定稿工具也不展示(时间线里一堆没内容的命令行记录) */
function displayToolCalls(step: Step | null): ToolCall[] {
  if (!step) return []
  return (step.toolCalls ?? []).filter(
    (tc) =>
      !(tc.name === 'pure_show_widget' && tc.outputs?.length) &&
      !(tc.name === 'write_todos' && turnInProgress.value) &&
      !isBlankToolOutput(tc),
  )
}

/** 步骤是否值得展示:无可见解说(标记行已剥离)、无可显示工具(如 pure_show_widget 已转组件区/输出为空被过滤)的步骤整行隐藏 */
function stepVisible(step: Step): boolean {
  if (stepDisplayText(step).trim()) return true
  return displayToolCalls(step).length > 0
}

// ---------- 时间线分组收纳:连续同名纯工具步骤合并为一行"工具 × N",点击展开逐条 ----------

type TimelineBlock =
  | { type: 'step'; step: Step; index: number }
  | { type: 'group'; name: string; items: { step: Step; index: number }[] }

const displayBlocks = computed<TimelineBlock[]>(() => {
  const raw: TimelineBlock[] = []
  for (let i = 0; i < props.turn.steps.length; i++) {
    const step = props.turn.steps[i]
    if (!stepVisible(step)) continue
    const tools = displayToolCalls(step)
    const isPureTool = !stepDisplayText(step).trim() && tools.length > 0
    if (!isPureTool) {
      raw.push({ type: 'step', step, index: i })
      continue
    }
    const name = tools[0]?.name || ''
    const last = raw[raw.length - 1]
    if (last && last.type === 'group' && last.name === name) {
      last.items.push({ step, index: i })
    } else {
      raw.push({ type: 'group', name, items: [{ step, index: i }] })
    }
  }
  // 单项目组退化为普通步骤行("× 1"没有意义)
  return raw.map((b) =>
    b.type === 'group' && b.items.length === 1
      ? { type: 'step', step: b.items[0].step, index: b.items[0].index }
      : b,
  )
})

const openGroups = ref<Record<number, boolean>>({})
function toggleGroup(i: number) {
  openGroups.value[i] = !openGroups.value[i]
}
function groupStatus(items: { step: Step }[]): 'run' | 'ok' | 'err' {
  for (const it of items) {
    for (const tc of it.step.toolCalls ?? []) {
      if (tc.status === 'running') return 'run'
      if (tc.status === 'error') return 'err'
    }
  }
  return 'ok'
}

/** 组件内「追问」按钮(window.sendPrompt):直接作为新消息发送 */
function onWidgetPrompt(text: string) {
  void chat.sendMessage(text)
}

// ---------- HITL 人工确认(危险操作) ----------

/** 本 turn 内待用户允许/拒绝的权限请求(挂在其 assistant 消息的 parts.pendingPermission 上) */
const turnPending = computed(() => {
  const steps: Step[] = [...props.turn.steps]
  if (props.turn.finalAnswer) steps.push(props.turn.finalAnswer)
  for (const s of steps) {
    const pp = s.message.parts?.pendingPermission
    if (pp && !pp._replied) return pp
  }
  return null
})

const permSubmitting = ref(false)
const questionAnswer = ref('')
const selectedOptions = ref<string[]>([])
const OTHER_LABEL = computed(() => t('turn.otherOption'))
/** 「其他」判定:AI 可能直接给出"其他 xx"中文原样选项,前端追加的标签则随语言变化 */
function isOtherOption(o: string): boolean {
  return o === OTHER_LABEL.value || o.startsWith('其他')
}
const rawOptions = computed<string[]>(() => {
  const raw = turnPending.value?.tool?.args?.options
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string') return raw.split('|').map(s => s.trim()).filter(Boolean)
  return []
})
const questionOptions = computed<string[]>(() => {
  if (!rawOptions.value.length) return []
  if (rawOptions.value.some(isOtherOption)) return rawOptions.value
  return [...rawOptions.value, OTHER_LABEL.value]
})
const questionMultiple = computed(() => {
  const v = turnPending.value?.tool?.args?.multiple
  if (v == null || v === '' || v === false || v === 'false' || v === 0 || v === '0') return false
  return v === true || v === 1 || v === 'true' || v === '1' || Boolean(v)
})
// 是否命中"其他"自由输入:AI 可能直接传"其他 xx"原样选项(此时 questionOptions 不再追加标签),
// 也可能用前端追加的"其他(自由输入)"标签,统一按 isOtherOption 判定
const isOtherSelected = computed(() => selectedOptions.value.some(isOtherOption))

function toggleOption(opt: string) {
  if (questionMultiple.value) {
    const idx = selectedOptions.value.indexOf(opt)
    if (idx >= 0) selectedOptions.value.splice(idx, 1)
    else selectedOptions.value.push(opt)
  } else {
    selectedOptions.value = [opt]
  }
}

function buildAnswer(): string {
  if (questionOptions.value.length) {
    const opts = selectedOptions.value.filter(o => !isOtherOption(o))
    if (isOtherSelected.value && questionAnswer.value.trim()) opts.push(questionAnswer.value.trim())
    return opts.join(',')
  }
  return questionAnswer.value
}

async function onPerm(decision: 'approve' | 'reject' | 'reply', answer?: string) {
  const pp = turnPending.value
  if (!pp || permSubmitting.value) return
  if (!props.sessionId) {
    msg.error(t('turn.notReady'))
    return
  }
  const finalAnswer = answer ?? buildAnswer()
  permSubmitting.value = true
  try {
    const ok = await chat.replyPermission(pp.requestId, decision, finalAnswer)
    if (!ok) msg.warning(t('turn.permissionStale'))
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || t('turn.permissionFailed'))
  } finally {
    permSubmitting.value = false
    questionAnswer.value = ''
  }
}

/** 权限请求的展示用参数摘要(截断,单行) */
const permArgsText = computed(() => {
  const args = turnPending.value?.tool?.args
  if (args == null) return ''
  try {
    return JSON.stringify(args).slice(0, 240)
  } catch {
    return String(args).slice(0, 240)
  }
})

/** 是否为 agent 提问(ask_user 工具) */
const isQuestion = computed(() => turnPending.value?.tool?.name === 'ask_user')

// ---------- 折叠控制(受控 expanded-names)----------

/** 本 turn 是否含内联组件(含则保持思考区展开,组件不能被折叠藏掉) */
const hasStepWidgets = computed(
  () =>
    props.turn.steps.some((s) => stepWidgetPaths(s).length > 0) ||
    stepWidgetPaths(props.turn.finalAnswer).length > 0,
)

/** 全部组件路径(跨 steps 与 finalAnswer 去重,保持顺序)。集中渲染在时间线下方,避免组件在思考链中跳动/分散 */
const allWidgetPaths = computed<string[]>(() => {
  const seen = new Set<string>()
  const out: string[] = []
  for (const s of props.turn.steps) {
    for (const wp of stepWidgetPaths(s)) {
      if (!seen.has(wp)) { seen.add(wp); out.push(wp) }
    }
  }
  const fa = props.turn.finalAnswer
  if (fa) {
    for (const wp of stepWidgetPaths(fa)) {
      if (!seen.has(wp)) { seen.add(wp); out.push(wp) }
    }
  }
  return out
})

/** 流式 live 组件是否还应显示:最近一个 pure_show_widget 工具若已定稿(文件版接管),
 *  隐藏 live,避免与文件版叠加成双图;新工具开始流式时(状态 running)恢复显示。 */
const liveWidgetActive = computed(() => {
  const sw = chat.streamingWidgetCode
  if (!sw?.code) return false
  const widgets = [
    ...props.turn.steps.flatMap((s) => s.toolCalls ?? []),
    ...(props.turn.finalAnswer?.toolCalls ?? []),
  ].filter((t) => t.name === 'pure_show_widget')
  const last = widgets[widgets.length - 1]
  if (last && (last.status === 'completed' || last.status === 'error')) return false
  return true
})

const stepsOpen = computed<boolean>(() => {
  const ov = chat.manualCollapseOverride[props.turn.id]
  if (ov !== undefined) return ov
  // 流式期间展开(推理/步骤按序滚动),完成后折叠;用户可点头部手动展开/收起。
  return turnInProgress.value
})

function toggleSteps() {
  chat.setCollapse(props.turn.id, !stepsOpen.value)
}

// ---------- 工具 chip 状态 ----------

/** 下载工具产出的文件(chip 上的 📥 按钮) */
const downloadingPath = ref<string | null>(null)
async function downloadFile(path: string) {
  if (downloadingPath.value !== null) return
  downloadingPath.value = path
  try {
    await fileApi.download(path)
  } catch (e: any) {
    msg.error(e?.message || t('turn.downloadFailed'))
  } finally {
    downloadingPath.value = null
  }
}

// ---------- 文件预览 ----------
const previewPath = ref<string | null>(null)
function previewFile(path: string) {
  previewPath.value = path
}
function closePreview() {
  previewPath.value = null
}

/** 一键下载本 turn 全部结果文件:打包成 zip 一次下载,绕开浏览器多文件下载拦截。
 *  复用 fileApi.fetchBlob 拿每个文件 blob → JSZip 内存打包 → 单次 a.click() 触发下载。 */
const downloadingAll = ref(false)
async function downloadAllFiles() {
  if (downloadingAll.value) return
  const items = verifiedDeliverables.value
  if (!items.length) return
  downloadingAll.value = true
  try {
    const zip = new JSZip()
    let failed = 0
    for (const item of items) {
      try {
        const blob = await fileApi.fetchBlob(item.path)
        // 用相对路径作为 zip 内路径,保留子目录结构,避免同名冲突;去掉前导 / \
        const zipPath = item.path.replace(/^[/\\]+/, '')
        zip.file(zipPath, blob)
      } catch (e: any) {
        failed++
        msg.error(t('turn.readFailed', { name: item.name, msg: e?.message || '' }))
      }
    }
    if (failed === items.length) {
      msg.error(t('turn.readAllFailed'))
      return
    }
    const zipBlob = await zip.generateAsync({ type: 'blob' })
    const url = URL.createObjectURL(zipBlob)
    const a = document.createElement('a')
    a.href = url
    const ts = new Date().toISOString().slice(0, 16).replace(/[-T:]/g, '')
    a.download = t('turn.zipName', { ts })
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    if (failed === 0) {
      msg.success(t('turn.exported', { n: items.length }))
    } else {
      msg.warning(t('turn.exportedPartial', { ok: items.length - failed, total: items.length, failed }))
    }
  } catch (e: any) {
    msg.error(t('turn.exportFailed', { msg: e?.message || '' }))
  } finally {
    downloadingAll.value = false
  }
}

/**
 * 步数 = 本轮**去重后的工具调用次数**,与上方工具分类卡各卡片的合计数严格相等。
 *
 * 原实现有两个口径不一致的来源,导致标题里的「N 步操作」和卡片合计对不上:
 * 1. 额外把「有解说的思考段」也算作一步(解说是文字,不是操作,也不出现在任何卡片里);
 * 2. 直接累加各 step 的 toolCalls.length,没有像 allToolCalls 那样按调用 id 去重
 *    (deepagents 一轮一条消息挂全部工具,流式重放时同一调用会出现在多个 step)。
 * 现统一取 allToolCalls 的长度,保证「卡片合计 = 标题步数」。
 */
const thinkingStepCount = computed(() => allToolCalls.value.length)

/**
 * 时间线上「不是工具调用」的额外行数(解说文字 / 推理段 / 系统提示)。
 * 折叠头只报工具操作数(与卡片合计一致),这些行会在下方时间线里出现,
 * 因此额外标一句「含 N 段解说」,避免用户以为漏算了。
 */
const extraTimelineRows = computed(() => {
  let n = 0
  for (const s of props.turn.steps) {
    if (s.kind === 'system') continue // 系统提示不是解说
    if ((s.narration || '').trim()) n++
    else if (s.kind === 'reason' && (s.message?.content || '').trim()) n++
  }
  return n
})

const thinkingHeader = computed(() => {
  const n = thinkingStepCount.value
  const extra = extraTimelineRows.value
  if (turnInProgress.value) {
    // 占位壳阶段(尚无任何真实思考/工具内容):只显示"思考中…"
    const hasContent = n > 0 || props.turn.steps.some((s) => stepDisplayText(s).trim())
    if (!hasContent) return t('turn.thinkingOnly')
    return n > 0 ? t('turn.thinkingRunning', { n }) : t('turn.thinkingOnly')
  }
  // 定稿:耗时 + 工具步数(与分类卡合计相等)+ 解说段数,合并为一行
  // (不再用"已折叠"字样——展开后文案会与状态矛盾;展开/收起由右侧 chevron 表达)
  const parts: string[] = []
  if (elapsedText.value) parts.push(t('turn.elapsedDone', { t: elapsedText.value }))
  if (n > 0) parts.push(t('turn.stepCount', { n }))
  if (extra > 0) parts.push(t('turn.narrationCount', { n: extra }))
  return parts.join(' · ') || t('turn.process')
})

function stepHtml(step: Step): string {
  return renderMarkdown(stepDisplayText(step))
}

// (deepagents 无提问/权限环节:原 pending question/permission 收集与提交逻辑已移除)
</script>

<template>
  <!-- 简单模式:复用普通气泡 -->
  <template v-if="simpleMode && simpleMessage">
    <ChatMessage
      :message="simpleMessage"
      :streaming="isStepActive(turn.finalAnswer)"
      :session-id="props.sessionId"
      :turn-id="props.turn.id"
    />
    <DeliverableBar
      v-if="verifiedDeliverables.length"
      :items="verifiedDeliverables"
      :busy="downloadingAll"
      :downloading-path="downloadingPath"
      @preview="previewFile"
      @download="downloadFile"
      @downloadAll="downloadAllFiles"
    />
  </template>
  <!-- 多步模式:时间线步骤流(Trae 风格)= 头部 + 可折叠步骤时间线 + 最终答案 -->
  <div v-else class="turn">
    <div class="turn-head">
      <span class="turn-logo">
        <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M12 2.5l1.9 5.9 6.1 1.9-5 4.4 1.5 6.3L12 17.5l-4.5 3.5 1.5-6.3-5-4.4 6.1-1.9L12 2.5z"/></svg>
      </span>
      <span class="turn-name">AI Workbench</span>
      <span v-if="faTime" class="msg-time">{{ fmtMessageTime(faTime) }}</span>
    </div>
    <div class="turn-body">
      <!-- 工具分类卡:本轮调用的工具按类聚合为卡片(参考主视图设计) -->
      <div v-if="toolSummary.length" class="tool-cats">
        <div v-for="g in toolSummary" :key="g.key" class="tool-cat">
          <div class="cat-head">
            <span class="c-ic" :class="g.key">
              <svg v-if="g.icon === 'file'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>
              <svg v-else-if="g.icon === 'globe'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a15 15 0 0 1 4 9 15 15 0 0 1-4 9 15 15 0 0 1-4-9 15 15 0 0 1 4-9z"/></svg>
              <svg v-else-if="g.icon === 'chart'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"/><rect x="7" y="12" width="3" height="6"/><rect x="12" y="8" width="3" height="10"/><rect x="17" y="5" width="3" height="13"/></svg>
              <svg v-else-if="g.icon === 'skill'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>
              <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a4.5 4.5 0 0 0-6.4 6.4l-5 5a2 2 0 0 0 2.8 2.8l5-5a4.5 4.5 0 0 0 6.4-6.4L14 11.4 12.6 10z"/></svg>
            </span>
            <span class="cat-title">{{ g.label }}</span>
            <span class="cat-count">{{ g.total }}</span>
            <span v-if="g.running" class="cat-status run">{{ t('turn.statusRunning') }}</span>
            <span v-else-if="g.error" class="cat-status err">{{ t('turn.statusError') }}</span>
          </div>
          <div class="tools">
            <div v-for="t in g.tools" :key="t.name" class="tool-row">
              <code class="t-name">{{ t.name }}</code>
              <span v-if="t.count > 1" class="t-count">×{{ t.count }}</span>
            </div>
          </div>
        </div>
      </div>
      <!-- 思考过程:自定义折叠头 + v-show 时间线(常驻 DOM,无挂载延迟,思考内容即时可见) -->
      <div v-if="turn.steps.length > 0 || (turn.finalAnswer && turn.finalAnswer.toolCalls.length)">
        <div class="think-header" :class="{ active: turnInProgress }" @click="toggleSteps">
          <span v-if="turnInProgress" class="think-spinner"><span class="think-ring" /></span>
          <span class="think-label">{{ thinkingHeader }}</span>
          <svg class="rc-chev" :class="{ open: stepsOpen }" viewBox="0 0 24 24" width="12" height="12"><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
        </div>
        <div v-show="stepsOpen" class="timeline" :ref="(el) => (timelineEl = (el as HTMLElement))" @scroll="onTimelineScroll">
          <template v-for="(b, bi) in displayBlocks" :key="b.type + '-' + bi">
          <!-- 单步骤块:思考/解说/含解说的工具步骤;节点统一为小圆点,含义由内容自表达 -->
          <div v-if="b.type === 'step'" class="tstep">
            <span class="tstep-dot" :class="{ active: isStepActive(b.step) }" />
            <div class="tstep-body">
              <!-- 系统提示(会话压缩等):独立样式行,与思考/正文区分 -->
              <div v-if="b.step.kind === 'system'" class="system-note">
                <span class="system-tag">{{ t('turn.tagSystem') }}</span>
                <span class="system-text">{{ stepDisplayText(b.step) }}</span>
              </div>
              <!-- 推理段:可折叠"思考"行,流式时限高滚动吸底,结束自动折叠,可点开回看 -->
              <div v-else-if="b.step.kind === 'reason' && displayContent(b.step)" class="reason-box">
                <div class="reason-head" @click.stop="toggleReason(b.index)">
                  <span class="rc-tag">{{ t('turn.tagReasoning') }}</span>
                  <svg
                    class="rc-chev"
                    :class="{ open: isReasonOpen(b.step, b.index) }"
                    viewBox="0 0 24 24"
                    width="12"
                    height="12"
                  ><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
                </div>
                <div
                  v-show="isReasonOpen(b.step, b.index)"
                  class="reason-body"
                ><TypeReveal v-if="isStepActive(b.step)" :text="displayContent(b.step)" :dim-tail="0" :max-chars="1200" thinking active /><template v-else>{{ displayContent(b.step) }}</template></div>
              </div>
              <div
                v-else-if="stepDisplayText(b.step)"
                class="step-narration"
                :class="{ 'step-narration--active': isStepActive(b.step) }"
              ><TypeReveal v-if="isStepActive(b.step)" :text="stepDisplayText(b.step)" :dim-tail="0" thinking active /><span v-else class="narration-md" v-html="stepHtml(b.step)" /></div>
              <div v-if="displayToolCalls(b.step).length" class="tool-calls">
                <ToolCallItem
                  v-for="tc in displayToolCalls(b.step)"
                  :key="tc.id || tc.name"
                  :tc="tc"
                  @download="downloadFile"
                />
              </div>
            </div>
          </div>
          <!-- 同名工具组块:连续同类操作收纳为一行,点击展开逐条 -->
          <div v-else class="tstep">
            <span class="tstep-dot" />
            <div class="tstep-body">
              <div class="tool-group-head" @click="toggleGroup(bi)">
                <span class="group-name">{{ b.name }}</span>
                <span class="group-count">{{ b.items.length }}</span>
                <span class="group-status" :class="groupStatus(b.items)">{{ groupStatus(b.items) === 'run' ? '…' : groupStatus(b.items) === 'err' ? '✗' : '✓' }}</span>
                <svg class="rc-chev" :class="{ open: openGroups[bi] }" viewBox="0 0 24 24" width="12" height="12"><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
              </div>
              <div v-show="openGroups[bi]" class="tool-group-items">
                <template v-for="it in b.items" :key="it.index">
                  <div class="tool-calls">
                    <ToolCallItem
                      v-for="tc in displayToolCalls(it.step)"
                      :key="tc.id || tc.name"
                      :tc="tc"
                      @download="downloadFile"
                    />
                  </div>
                </template>
              </div>
            </div>
          </div>
          </template>
          <div
            v-if="turn.finalAnswer && displayToolCalls(turn.finalAnswer).length"
            class="tstep"
          >
            <span class="tstep-dot" />
            <div class="tstep-body">
              <div class="tool-calls" v-if="displayToolCalls(turn.finalAnswer).length">
                <ToolCallItem
                  v-for="tc in displayToolCalls(turn.finalAnswer)"
                  :key="tc.id || tc.name"
                  :tc="tc"
                  @download="downloadFile"
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 内联组件集中区:位于时间线下方、最终答案之前,稳定渲染不随步骤跳动 -->
      <div
        v-if="allWidgetPaths.length || (turnInProgress && liveWidgetActive)"
        class="turn-widgets"
      >
        <WidgetFrame
          v-for="wp in allWidgetPaths"
          :key="wp"
          :path="wp"
          @prompt="onWidgetPrompt"
        />
        <WidgetFrame
          v-if="turnInProgress && liveWidgetActive"
          :live-code="chat.streamingWidgetCode!.code"
          @prompt="onWidgetPrompt"
        />
      </div>

      <!-- 最终答案 -->
      <div v-if="turn.finalAnswer || liveStreamText" class="fa-wrap" :class="{ 'md-tail-dim': answerStreaming }">
        <div v-if="turn.finalAnswer" class="fa-tools">
          <button
            class="copy-btn star-btn"
            :class="{ on: faStarred }"
            :title="faStarred ? t('turn.unfavoriteTitle') : t('turn.favoriteTitle')"
            @click="toggleFaStar"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" :fill="faStarred ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
          </button>
          <button
            class="copy-btn"
            :title="t('turn.copyAnswer')"
            @click="copyFinalAnswer"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2-2v1"/></svg>
          </button>
          <button
            class="copy-btn export-btn"
            :title="t('turn.exportMd')"
            @click="exportFinalAnswerAsMarkdown"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
          <button
            v-if="canRegenerate"
            class="copy-btn regen-btn"
            :title="t('turn.regenTitle')"
            @click="regenerateAnswer"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
          </button>
        </div>
        <StreamMarkdown v-if="answerContent" :content="answerContent" :final="!answerStreaming" />
      </div>
      <div v-if="followUpQuestions.length" class="followups">
        <span class="fu-label">{{ t('turn.followLabel') }}</span>
        <span
          v-for="(fu, i) in followUpQuestions"
          :key="i"
          class="fu-chip"
          @click="askFollowUp(fu)"
        >{{ fu }}</span>
      </div>
      <div v-else-if="showWorkingHint" class="pending">
        <n-spin size="small" />
        <span v-if="chat.streamingStuckSeconds > 60">{{ t('turn.stuckHint') }}</span>
        <template v-else>
          <span>{{ pendingHint }}</span>
          <span v-if="runningSeconds > 0" class="pending-elapsed">{{ t('turn.runningFor', { s: runningSeconds }) }}</span>
        </template>
      </div>

      <!-- 人工确认:危险操作需用户允许/拒绝,或回答 agent 提问,生成在此暂停 -->
      <div v-if="turnPending" class="perm-block" :class="{ 'question-block': isQuestion }">
        <div class="perm-head">
          <svg v-if="!isQuestion" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22v-5"/><path d="M9 8V2"/><path d="M15 8V2"/><path d="M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"/></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/><path d="M12 17h.01"/></svg>
          <span class="perm-title">{{ isQuestion ? t('turn.permAskTitle') : t('turn.permNeedTitle') }}</span>
        </div>
        <div class="perm-body">
          <div class="perm-tool">
            <span v-if="isQuestion" class="perm-question">{{ turnPending.tool?.args?.question || t('turn.permQuestionFallback') }}</span>
            <template v-else>
              <span class="perm-tool-name">{{ turnPending.tool?.name || 'tool' }}</span>
              <span v-if="turnPending.tool?.description" class="perm-desc">{{ turnPending.tool.description }}</span>
            </template>
          </div>
          <pre v-if="!isQuestion && permArgsText" class="perm-args">{{ permArgsText }}</pre>
          <div v-if="isQuestion" class="perm-question-input">
            <template v-if="questionOptions.length">
              <div class="perm-options">
                <div
                  v-for="opt in questionOptions"
                  :key="opt"
                  class="perm-option-card"
                  :class="{ selected: selectedOptions.includes(opt) }"
                  @click="toggleOption(opt)"
                >
                  <div class="perm-option-check">
                    <div v-if="questionMultiple" class="perm-checkbox" :class="{ checked: selectedOptions.includes(opt) }">
                      <svg v-if="selectedOptions.includes(opt)" viewBox="0 0 16 16" fill="currentColor"><path d="M13.78 4.22a.75.75 0 010 1.06l-7.25 7.25a.75.75 0 01-1.06 0L2.22 9.28a.75.75 0 011.06-1.06L6 10.94l6.72-6.72a.75.75 0 011.06 0z"/></svg>
                    </div>
                    <div v-else class="perm-radio" :class="{ checked: selectedOptions.includes(opt) }">
                      <div v-if="selectedOptions.includes(opt)" class="perm-radio-dot"/>
                    </div>
                  </div>
                  <span class="perm-option-text">{{ opt }}</span>
                </div>
              </div>
              <div v-if="isOtherSelected" class="perm-other-input">
                <n-input
                  v-model:value="questionAnswer"
                  type="textarea"
                  :placeholder="t('turn.permAnswerPlaceholder')"
                  :autosize="{ minRows: 2, maxRows: 6 }"
                  @keydown.enter.ctrl="onPerm('reply')"
                />
              </div>
            </template>
            <template v-else>
              <n-input
                v-model:value="questionAnswer"
                type="textarea"
                :placeholder="t('turn.permAnswerPlaceholder')"
                :autosize="{ minRows: 2, maxRows: 6 }"
                @keydown.enter.ctrl="onPerm('reply', questionAnswer)"
              />
            </template>
          </div>
          <div class="perm-actions">
            <template v-if="isQuestion">
              <n-button size="small" :loading="permSubmitting" :disabled="permSubmitting || (!questionOptions.length && !questionAnswer.trim()) || (questionOptions.length && !selectedOptions.length) || (isOtherSelected && !questionAnswer.trim())" type="primary" @click="onPerm('reply')">
                {{ t('turn.permSubmit') }}
              </n-button>
            </template>
            <template v-else>
              <n-button size="small" :loading="permSubmitting" :disabled="permSubmitting" @click="onPerm('reject')">
                {{ t('turn.permReject') }}
              </n-button>
              <n-button size="small" type="primary" :loading="permSubmitting" :disabled="permSubmitting" @click="onPerm('approve')">
                {{ t('turn.permApprove') }}
              </n-button>
            </template>
          </div>
        </div>
      </div>

      <!-- 结果文件区:AI 声明的交付文件(存在性校验后),点击预览 -->
      <DeliverableBar
        v-if="verifiedDeliverables.length"
        :items="verifiedDeliverables"
        :busy="downloadingAll"
        @preview="previewFile"
        @download="downloadFile"
        @downloadAll="downloadAllFiles"
      />
    </div>
  </div>

  <!-- 文件预览弹窗 -->
  <n-modal
    :show="!!previewPath"
    preset="card"
    :title="previewPath ? previewPath.split(/[\\/]/).pop() : t('turn.previewTitle')"
    :style="{ width: '86vw', maxWidth: '1280px' }"
    :mask-closable="true"
    :bordered="false"
            @update:show="(v: boolean) => { if (!v) closePreview() }"
  >
    <FilePreview
      v-if="previewPath"
      :path="previewPath"
    />
  </n-modal>
</template>

<style scoped>
/* 时间线步骤流(Trae 风格):头部 + 竖线时间线 + 无气泡答案 */
.turn { margin-bottom: 26px; padding: 0 4px; }
.turn-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.turn-logo {
  width: 22px; height: 22px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent); color: var(--accent-contrast);
}
.turn-name { font-size: 13px; font-weight: 600; color: var(--text); }
.msg-time { font-size: 11px; color: var(--text-3); }
.followups { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
.fu-label { font-size: 12px; color: var(--text-3); }
.fu-chip {
  font-size: 12px; color: var(--text-2);
  background: var(--surface); border: 1px solid var(--border-soft);
  border-radius: 6px; padding: 4px 10px; cursor: pointer;
  transition: border-color .15s, color .15s;
}
.fu-chip:hover { border-color: var(--accent); color: var(--accent); }

/* 人工确认卡:危险操作待用户允许/拒绝 */
.perm-block {
  max-width: 80%;
  margin-top: 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-elev);
  overflow: hidden;
}
.perm-head {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 13px;
  background: var(--fill-0);
  color: var(--text-2);
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--border-soft);
}
.perm-head svg { color: var(--accent); flex-shrink: 0; }
.perm-title { display: inline-flex; align-items: center; gap: 6px; }
.perm-body { padding: 10px 13px; }
.perm-tool { display: flex; flex-direction: column; gap: 3px; font-size: 13px; color: var(--text); }
.perm-tool-name { font-family: ui-monospace, monospace; font-weight: 600; color: var(--accent-text); }
.perm-desc { color: var(--text-2); font-size: 12px; }
.perm-args {
  margin: 6px 0 0;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow: auto;
}
.perm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.author { font-size: 13px; font-weight: 600; color: var(--text); }
.fa-wrap { position: relative; margin-top: 10px; font-size: 15px; line-height: 1.75; letter-spacing: 0.01em; color: var(--text); }
/* 流式中的答案:markstream 增量渲染,尾部一行渐隐(隐藏光效),随输出推进自然固化 */
.md-tail-dim {
  -webkit-mask-image: linear-gradient(to bottom, #000 calc(100% - 2.2em), rgba(0, 0, 0, 0.25) calc(100% - 0.5em));
  mask-image: linear-gradient(to bottom, #000 calc(100% - 2.2em), rgba(0, 0, 0, 0.25) calc(100% - 0.5em));
}
/* 最终答案工具条:水平排列,悬停显示 */
.fa-tools {
  position: absolute; top: -6px; right: -6px; z-index: 2;
  display: flex; align-items: center; gap: 2px;
  padding: 2px; border-radius: 6px;
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow);
  opacity: 0; transition: opacity .15s;
}
.fa-wrap:hover .fa-tools { opacity: 1; }
/* 触屏:最终答案工具条常显 */
@media (hover: none) {
  .fa-tools { opacity: 1; }
}
.copy-btn {
  background: transparent; border: none; cursor: pointer;
  padding: 4px; border-radius: 4px; line-height: 1;
  color: var(--text-3); display: inline-flex; align-items: center; justify-content: center;
}
.copy-btn:hover { background: var(--surface-hover); color: var(--text-2); }
.copy-btn.star-btn.on { color: #f0b429; }
.copy-btn.regen-btn:hover { color: var(--accent); }

/* 思考区折叠头:朴素可点击文字行 + 右 chevron */
.think-header {
  display: inline-flex; align-items: center; gap: 8px;
  font-size: 13px; color: var(--text-3);
  cursor: pointer; user-select: none;
  padding: 4px 6px;
  margin-left: -6px;
  border-radius: 6px;
  transition: background .15s;
}
.think-header:hover { background: var(--surface-hover); }
.think-header .rc-chev { opacity: .6; }
.think-spinner {
  position: relative; width: 13px; height: 13px; flex-shrink: 0;
  display: inline-flex;
}
.think-ring {
  position: absolute; inset: 0; border-radius: 50%;
  border: 2px solid var(--accent-soft);
  border-top-color: var(--accent);
  animation: think-spin .8s linear infinite;
}
@keyframes think-spin { to { transform: rotate(360deg); } }
.think-label {
  font-size: 13px; font-weight: 500; color: var(--text-3);
  transition: color .2s;
}
.think-header.active .think-label {
  color: var(--accent-text);
  animation: think-pulse 1.4s ease-in-out infinite;
}
@keyframes think-pulse { 50% { opacity: .6; } }

/* 时间线:左侧竖向连接线 + 节点图标 + 内容;限高内部滚动 */
.timeline {
  position: relative;
  margin: 4px 0 8px 9px;
  max-height: 420px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 2px 6px 2px 0;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}
.timeline:hover { scrollbar-color: var(--border) transparent; }
.timeline::-webkit-scrollbar { width: 4px; }
.timeline::-webkit-scrollbar-thumb { background: transparent; border-radius: 2px; }
.timeline:hover::-webkit-scrollbar-thumb { background: var(--border); }
/* 连接线逐步骤自绘(而非容器级 ::before):时间线限高内滚动时,
   容器级绝对线只覆盖可视框高度,长内容滚动后中后段会断线 */
.tstep {
  position: relative;
  display: flex;
  gap: 11px;
  padding: 4px 0;
}
.tstep::before {
  content: '';
  position: absolute;
  left: 7px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--border);
}
/* 线从首个圆点中心(约 16px)起,到末个圆点中心止 */
.tstep:first-child::before { top: 16px; }
.tstep:last-child::before { bottom: auto; top: 0; height: 16px; }
/* 时间线节点:统一小圆点(含义由内容自表达:思考行/工具 chip/解说文本),
   不再按步骤类型混用星号/终端等图标,视觉更整齐 */
.tstep-dot {
  width: 15px; height: 15px;
  margin-top: 2.5px;
  flex-shrink: 0;
  display: flex; align-items: flex-start; justify-content: center;
  position: relative; z-index: 1;
}
.tstep-dot::after {
  content: '';
  width: 7px; height: 7px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--border-strong, #9aa0aa);
  box-shadow: 0 0 0 3px var(--bg);
}
.tstep-dot.active::after {
  background: var(--accent);
  animation: chip-pulse 1.6s ease-in-out infinite;
}
.tstep-body { flex: 1; min-width: 0; padding-top: 1px; }
/* 同名工具组:一行收纳,点击展开逐条 */
.tool-group-head {
  display: inline-flex; align-items: center; gap: 7px;
  cursor: pointer; user-select: none;
  padding: 3px 8px;
  margin-left: -8px;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-2);
  transition: background .15s;
}
.tool-group-head:hover { background: var(--surface-hover); }
.group-name { font-weight: 500; }
.group-count {
  min-width: 17px; text-align: center;
  font-size: 11px; line-height: 16px;
  color: var(--text-3);
  background: var(--surface-hover);
  border-radius: 8px; padding: 0 5px;
}
.group-status { font-size: 11px; font-weight: 600; }
.group-status.ok { color: var(--ok); }
.group-status.err { color: var(--err); }
.group-status.run { color: var(--text-3); animation: chip-pulse 1.6s ease-in-out infinite; }
.tool-group-head .rc-chev { color: var(--text-3); opacity: .7; }
.tool-group-items { margin: 2px 0 2px 2px; display: flex; flex-direction: column; gap: 1px; }
/* 内联组件集中区:位于时间线下方,组件依次竖排 */
.turn-widgets {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 12px 0 4px;
}
.turn-widgets :deep(.widget-frame) { width: 100%; }
.step-narration {
  font-size: 13px; color: var(--text-2); opacity: .85;
}
.step-narration--active { font-style: italic; opacity: 1; }
.step-narration :deep(p) { margin: 4px 0; }
/* 推理段:朴素"思考 ⌄"行,展开内容为圆角浅底卡片 */
.reason-box {
  margin: 2px 0;
  background: transparent;
}
.reason-head {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 6px; font-size: 12px;
  color: var(--text-3); cursor: pointer; user-select: none;
  border-radius: 6px;
}
.reason-head:hover { color: var(--text-2); background: var(--surface-hover); }
.rc-chev { transition: transform .15s ease; opacity: .7; }
.rc-chev.open { transform: rotate(180deg); }
.reason-body {
  margin: 4px 0 2px;
  padding: 8px 12px;
  font-size: 12px; line-height: 1.6;
  color: var(--text-2); font-style: italic;
  white-space: pre-wrap; word-break: break-word;
  /* 不再内嵌滚动:滚动统一交给外层 .timeline,避免双滚动条 */
  background: var(--surface);
  border-radius: 8px;
}
.reason-body .cursor { margin-left: 2px; }
.rc-tag {
  font-size: 12px; font-style: normal; font-weight: 500;
  color: var(--text-3);
}
.step-narration :deep(pre.hljs) { background: var(--code-bg); border-radius: var(--radius-sm); padding: 8px; overflow-x: auto; }
.step-narration :deep(code) { font-family: ui-monospace, monospace; font-size: 12px; }
/* 系统提示(会话压缩等):浅底提示条,与思考/正文区分 */
.system-note {
  display: inline-flex; align-items: center; gap: 8px;
  margin: 2px 0; padding: 4px 10px;
  font-size: 12px; color: var(--text-2);
  background: var(--accent-soft);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
}
.system-tag {
  font-size: 11px; font-weight: 600;
  color: var(--accent-text);
  padding: 0 6px; border-radius: 4px;
  background: var(--surface);
}
.system-text { white-space: pre-wrap; word-break: break-word; }

.tool-calls { display: flex; flex-direction: column; gap: 6px; margin-top: 6px; }

/* 浏览器截图(步骤/最终答案内嵌,点击放大) */
.step-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.step-img {
  max-width: 320px; max-height: 400px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-soft);
  background: #fff;
  cursor: zoom-in;
  object-fit: contain;
}
.zoom-img { width: 100%; border-radius: 8px; background: #fff; }

.pending { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 13px; padding: 4px 0; }
.pending-elapsed { color: var(--text-2); font-size: 12px; }

.question-block .perm-head { color: var(--text-2); }
.perm-question { font-size: 15px; line-height: 1.6; color: var(--text); white-space: pre-wrap; }
.perm-question-input { margin-top: 10px; }
.perm-options { display: flex; flex-wrap: wrap; gap: 8px; }
.perm-option-card {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface); cursor: pointer;
  transition: border-color var(--motion-fast) var(--ease-standard), background-color var(--motion-fast) var(--ease-standard);
  flex: 1 1 calc(50% - 4px); min-width: 140px;
}
.perm-option-card:hover { border-color: var(--accent); background: var(--fill-0); }
.perm-option-card.selected { border-color: var(--accent); background: var(--accent-soft); }
.perm-option-check { flex-shrink: 0; }
.perm-checkbox {
  width: 16px; height: 16px; border-radius: 4px;
  border: 1.5px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  transition: all 0.15s ease; color: var(--accent-contrast);
}
.perm-checkbox.checked { background: var(--accent); border-color: var(--accent); }
.perm-checkbox svg { width: 11px; height: 11px; }
.perm-radio {
  width: 16px; height: 16px; border-radius: 50%;
  border: 1.5px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  transition: all 0.15s ease;
}
.perm-radio.checked { border-color: var(--accent); }
.perm-radio-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--accent); }
.perm-option-text { font-size: 14px; color: var(--text); line-height: 1.4; }
.perm-other-input { margin-top: 8px; width: 100%; }

.question-item {
  margin-bottom: 14px;
  width: 100%;
  overflow: hidden;
  box-sizing: border-box;
}
.question-item:last-child {
  margin-bottom: 10px;
}

.question-title {
  font-size: 14px;
  margin-bottom: 8px;
  line-height: 1.6;
  color: var(--text);
  word-break: break-word;
}

.question-type-tag {
  color: var(--text-3);
  font-size: 12px;
  margin-left: 6px;
}

.option-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.opt-desc {
  color: var(--text-2);
  font-size: 12px;
}

.custom-input-wrap {
  margin-top: 8px;
  width: 100%;
}

.perm-list {
  margin: 6px 0 8px 20px;
  padding: 0;
  font-size: 13px;
  color: var(--text-2);
}

.perm-list li {
  margin: 2px 0;
  font-family: ui-monospace, monospace;
  word-break: break-all;
  overflow-wrap: anywhere;
}

.pending-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
  width: 100%;
  box-sizing: border-box;
}

/* 移动端:消息布局收紧 */
@media (max-width: 768px) {
  /* 触摸设备:悬浮工具条常态显示,按钮放大到可点尺寸(无 hover 态依赖) */
  .fa-tools { opacity: 1; }
  .fa-tools .copy-btn { width: 34px; height: 34px; }
  /* 授权/追问选项:窄屏改为单列,避免两张卡片挤成两列不可读 */
  .perm-options { flex-direction: column; }
  .perm-option-card { flex: 1 1 auto; min-width: 0; width: 100%; }
  /* 时间线在小屏上更矮,把纵向空间留给正文 */
  .timeline { max-height: 300px; }
  .turn { margin-bottom: 18px; }
  .turn-widgets { gap: 8px; }
}

/* ===== 工具分类卡(本轮工具调用聚合) ===== */
.tool-cats { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; margin-bottom: 12px; }
.tool-cat { border: 1px solid var(--border-soft); border-radius: 12px; background: var(--surface); overflow: hidden; }
.cat-head { display: flex; align-items: center; gap: 9px; padding: 10px 14px; border-bottom: 1px solid var(--border-soft); }
.c-ic {
  width: 28px; height: 28px; border-radius: 8px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
}
.c-ic.file { background: var(--accent-soft); color: var(--accent-text); }
.c-ic.browser { background: rgba(59, 130, 246, .14); color: var(--accent-text); }
.c-ic.viz { background: rgba(88, 196, 155, .16); color: var(--ok); }
.c-ic.skill { background: rgba(232, 178, 107, .16); color: var(--warn); }
.c-ic.other, .c-ic.tool { background: var(--fill-1); color: var(--text-3); }
.cat-title { font-size: 13.5px; font-weight: 600; color: var(--text); }
.cat-count { font-size: 11px; font-weight: 600; color: var(--text-3); background: var(--fill-1); padding: 0 7px; border-radius: 999px; line-height: 1.7; }
.cat-status { font-size: 11px; margin-left: auto; }
.cat-status.run { color: var(--accent-text); }
.cat-status.err { color: var(--err); }
.tools { padding: 6px 8px; }
.tool-row { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border-bottom: 1px solid var(--border-soft); }
.tool-row:last-child { border-bottom: none; }
.t-name { font-family: var(--font-mono, ui-monospace, monospace); font-size: 12.5px; font-weight: 600; color: var(--accent-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.t-count { font-size: 11px; color: var(--text-3); }
</style>
