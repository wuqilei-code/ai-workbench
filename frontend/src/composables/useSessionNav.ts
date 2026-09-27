import { ref, type Ref } from 'vue'
import type { Router } from 'vue-router'
import type { useChatStore } from '@/stores/chat'

type ChatStore = ReturnType<typeof useChatStore>

/** 轮询等待消息加载的最大次数与间隔(任务面板/收藏跳转目标可能是未加载的旧消息) */
const LOCATE_MAX_TRIES = 40
const LOCATE_INTERVAL_MS = 200

/**
 * 会话跳转与消息定位:从任务面板/收藏面板跳到某个会话的某条消息,
 * 等待消息异步加载后滚动并高亮对应 turn。从 ChatView 抽出。
 *
 * @param deps.chat        chat store
 * @param deps.router      vue-router
 * @param deps.sessionId   当前路由会话 id
 * @param deps.locateTurn  实际滚动+高亮(来自 useSessionFind)
 * @param deps.onBeforeNav 跳转前副作用(如收起工作区面板)
 */
export function useSessionNav(deps: {
  chat: ChatStore
  router: Router
  sessionId: Ref<number | null>
  locateTurn: (turnId: string) => void
  onBeforeNav?: () => void
}) {
  /** 消息定位轮询中(定位期间显示遮罩/提示) */
  const locating = ref(false)

  /** 轮询等待 turns 出现目标(取不到则超时放弃) */
  async function waitForTurn(predicate: () => string | undefined): Promise<void> {
    for (let i = 0; i < LOCATE_MAX_TRIES; i++) {
      const turnId = predicate()
      if (turnId) {
        deps.locateTurn(turnId)
        return
      }
      await new Promise((r) => setTimeout(r, LOCATE_INTERVAL_MS))
    }
  }

  /** 任务面板「来源会话」:关面板、切会话并高亮触发该任务的那条用户消息。 */
  async function openSessionFromTask(target: { sessionId: number; messageId?: number }) {
    deps.onBeforeNav?.()
    if (deps.sessionId.value !== target.sessionId) {
      await deps.router.push({ name: 'session', params: { id: target.sessionId } })
    }
    await flashToMessage(target.messageId)
  }

  /** 收藏面板点击:切会话并定位到收藏项所在 turn。 */
  async function openSessionFromFavorite(target: { sessionId: number; turnId?: string }) {
    deps.onBeforeNav?.()
    if (deps.sessionId.value !== target.sessionId) {
      await deps.router.push({ name: 'session', params: { id: target.sessionId } })
    }
    if (!target.turnId) return
    await waitForTurn(() => {
      const turn = deps.chat.turns.find((t) => t.id === target.turnId)
      return turn?.id
    })
  }

  /** 定位到指定用户消息所在 turn 并闪亮(消息加载是异步的,轮询等待)。 */
  async function flashToMessage(messageId?: number) {
    if (!messageId) return
    locating.value = true
    try {
      await waitForTurn(() => {
        const turn = deps.chat.turns.find((t) => t.userMessage?.id === messageId)
        return turn?.id
      })
    } finally {
      locating.value = false
    }
  }

  return { locating, openSessionFromTask, openSessionFromFavorite, flashToMessage }
}
