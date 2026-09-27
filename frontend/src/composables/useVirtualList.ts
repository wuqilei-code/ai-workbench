import { computed } from 'vue'
import type { useChatStore } from '@/stores/chat'
import type { Turn } from '@/types'

type ChatStore = ReturnType<typeof useChatStore>

/** 虚拟列表每项:turn 为消息组,其余为占位/分割线/流式占位 */
export type VirtualListItem =
  | { key: string; kind: 'turn'; turn: Turn }
  | { key: string; kind: 'streaming'; text: string }
  | { key: string; kind: 'thinking' }
  | { key: string; kind: 'connecting' }
  | { key: string; kind: 'older-loader' }
  | { key: string; kind: 'top-space' }
  | { key: string; kind: 'compact-divider' }
  | { key: string; kind: 'compact-progress' }
  | { key: string; kind: 'interrupted-divider' }

/**
 * 消息列表的虚拟化项计算(把 messages + flow 归并成渲染序列)。
 * 从 ChatView 抽出;纯派生逻辑,无副作用。
 */
export function useVirtualList(chat: ChatStore) {
  /** 当前流式目标消息是否已落入某个 turn(step/finalAnswer) */
  const activeTurnExists = computed(() => {
    const id = chat.streamingMessageId
    if (!id) return false
    return chat.turns.some(
      (t) =>
        t.steps.some((s) => s.message.agentMessageId === id) ||
        t.finalAnswer?.message.agentMessageId === id,
    )
  })

  /** 最后一个 turn 是否已有 assistant 内容(避免与"思考中"兜底重复) */
  const lastTurnHasContent = computed(() => {
    const list = chat.turns
    if (list.length === 0) return false
    const last = list[list.length - 1]
    return last.steps.length > 0 || !!last.finalAnswer
  })

  /** 末尾显示独立的流式气泡(流式文本尚未并入 turn 时) */
  const showStreamingBubble = computed(
    () => chat.isStreaming && !!chat.streamingText && !activeTurnExists.value,
  )
  /** 末尾显示"思考中"占位(有输出请求但既无文本也无 turn 内容) */
  const showThinkingFallback = computed(
    () =>
      chat.isStreaming &&
      !chat.streamingText &&
      !activeTurnExists.value &&
      !lastTurnHasContent.value,
  )

  const virtualItems = computed<VirtualListItem[]>(() => {
    const items: VirtualListItem[] = []
    // 历史分页:还有更早消息或正在加载时,顶部固定显示提示条(滚动到顶触发加载)
    if (chat.hasOlder || chat.loadingOlder) {
      items.push({ key: '__older__', kind: 'older-loader' })
    } else if (chat.messages.length > 0) {
      // 已到最早:插入空占位,避免第一条消息紧贴对话框顶部
      items.push({ key: '__top-space__', kind: 'top-space' })
    }
    const msgs = chat.messages
    // 压缩点(遗留兼容):deepagents 不再落 compaction 记录,该分支恒空。
    const compactionMarks = msgs
      .filter((m) => m.role === 'compaction')
      .map((m) => ({ id: m.id, status: m.status }))
    // 中断点:用户中断生成时后端落库 role=interrupted 记录,前端渲染为"已中断"分割线。
    const interruptedMarks = msgs
      .filter((m) => m.role === 'interrupted')
      .map((m) => ({ id: m.id }))
    // 统一归并:压缩/中断标记都按 id 插到对应 flow 项之前。
    const dividerMarks = [
      ...compactionMarks.map((m) => ({
        id: m.id,
        kind: 'compact-divider' as VirtualListItem['kind'],
        key: '__compact__-' + m.id,
      })),
      ...interruptedMarks.map((m) => ({
        id: m.id,
        kind: 'interrupted-divider' as VirtualListItem['kind'],
        key: '__interrupted__-' + m.id,
      })),
    ].sort((a, b) => (a.id ?? 0) - (b.id ?? 0))
    // flow 每项的锚点消息 id:turn 用其 user 消息 id(或首个 step 消息 id)。
    const flowKeyById = chat.flow.map(
      (it) => it.turn.userMessage?.id ?? it.turn.steps[0]?.message?.id ?? -1,
    )
    const flowItems: VirtualListItem[] = chat.flow.map((it) => ({
      key: 't-' + it.turn.id,
      kind: 'turn',
      turn: it.turn,
    }))
    // 双指针归并:压缩/中断标记按 id 插到对应 flow 项之前。
    // 本地乐观消息(id 为负,发送中的用户消息/未刷新定稿)必定是最新的,
    // 任何已落库的标记记录都在它之前 → 负 id 一律判定"标记在前",否则按 id 比较。
    const dividerBeforeFlow = (divId: number, flowId: number): boolean => {
      if (flowId < 0) return true
      return divId < flowId
    }
    let fi = 0
    let ci = 0
    while (fi < flowItems.length || ci < dividerMarks.length) {
      const cm = dividerMarks[ci]
      const nextFlowId = flowKeyById[fi] ?? Number.MAX_SAFE_INTEGER
      if (cm && (fi >= flowItems.length || dividerBeforeFlow(cm.id ?? 0, nextFlowId))) {
        items.push({ key: cm.key, kind: cm.kind } as VirtualListItem)
        ci++
      } else {
        items.push(flowItems[fi])
        fi++
      }
    }
    // 流式进行中:末尾显示流式气泡或思考占位(deepagents 无压缩/冷启动连接态)
    if (showStreamingBubble.value) {
      items.push({ key: '__streaming__', kind: 'streaming', text: chat.streamingText })
    } else if (showThinkingFallback.value) {
      items.push({ key: '__thinking__', kind: 'thinking' })
    }
    return items
  })

  return { virtualItems, showStreamingBubble, showThinkingFallback }
}
