import { computed, nextTick, ref } from 'vue'
import type { Turn } from '@/types'

export interface FindMatch {
  turnId: string
  role: 'user' | 'assistant'
}

/**
 * 会话内查找(Ctrl+F):从 turns 里匹配关键字,定位并短暂高亮对应 turn。
 * 抽离自 ChatView,减少巨型组件体积;仅依赖传入的 turns 访问器与 DOM 定位。
 *
 * @param getTurns  turns 访问器
 * @param locate    可选的虚拟列表定位器。消息列表已窗口化,目标行可能未渲染,
 *                  此时 DOM 里没有对应元素,需由列表按偏移量直接滚动;返回 true 表示已处理。
 */
export function useSessionFind(getTurns: () => Turn[], locate?: (turnId: string) => boolean) {
  const findOpen = ref(false)
  const findQuery = ref('')
  const findIndex = ref(0)
  const findInputRef = ref<any>(null)
  const flashedTurnId = ref<string | null>(null)
  let flashTimer: ReturnType<typeof setTimeout> | null = null

  const findMatches = computed<FindMatch[]>(() => {
    const q = findQuery.value.trim().toLowerCase()
    if (!q) return []
    const list: FindMatch[] = []
    for (const t of getTurns()) {
      const cands: { content: string; role: 'user' | 'assistant' }[] = []
      if (t.userMessage) cands.push({ content: t.userMessage.content || '', role: 'user' })
      for (const s of t.steps) cands.push({ content: s.message?.content || '', role: 'assistant' })
      if (t.finalAnswer) cands.push({ content: t.finalAnswer.message?.content || '', role: 'assistant' })
      const hit = cands.find((c) => c.content.toLowerCase().includes(q))
      if (hit) list.push({ turnId: t.id, role: hit.role })
    }
    return list
  })

  function openFind() {
    findOpen.value = true
    findIndex.value = 0
    nextTick(() => findInputRef.value?.focus())
  }

  function closeFind() {
    findOpen.value = false
    findQuery.value = ''
    findIndex.value = 0
  }

  /** 滚动到指定 turn 并短暂高亮:优先用虚拟列表按偏移定位(目标行可能尚未渲染),否则回退 DOM 定位。 */
  function locateTurn(turnId: string) {
    if (!locate?.(turnId)) {
      const el = document.getElementById('find-turn-' + turnId)
      el?.scrollIntoView({ block: 'start' })
    }
    flashedTurnId.value = turnId
    if (flashTimer) clearTimeout(flashTimer)
    flashTimer = setTimeout(() => { flashedTurnId.value = null }, 1600)
  }

  function navigateFind(dir: number) {
    const total = findMatches.value.length
    if (!total) return
    findIndex.value = (findIndex.value + dir + total) % total
    locateTurn(findMatches.value[findIndex.value].turnId)
  }

  return { findOpen, findQuery, findIndex, findInputRef, flashedTurnId, findMatches, openFind, closeFind, locateTurn, navigateFind }
}
