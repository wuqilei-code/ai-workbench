import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useAuthStore } from './auth'
import type { SessionVo } from '@/types'

/** 收藏项:一条消息(用户问题或 AI 回答)。以 sessionId+messageId 唯一。 */
export interface FavoriteItem {
  sessionId: number
  messageId: number
  /** user | assistant */
  role: 'user' | 'assistant'
  /** 消息正文(截断存储) */
  content: string
  /** 所在 turn 的 id(跳转定位用;后端消息 id 可能缺失时退化为内容定位) */
  turnId: string
  /** 会话标题(冗余存储,跳转后刷新) */
  sessionTitle: string
  createdAt: number
}

const LS_KEY = 'aiw.favorites'

function loadAll(): Record<string, FavoriteItem> {
  try {
    const raw = localStorage.getItem(LS_KEY)
    return raw ? (JSON.parse(raw) as Record<string, FavoriteItem>) : {}
  } catch {
    return {}
  }
}

export const useFavoritesStore = defineStore('favorites', () => {
  const auth = useAuthStore()
  /** key = `${sessionId}:${messageId}` */
  const byKey = ref<Record<string, FavoriteItem>>(loadAll())

  const uidKey = computed(() => {
    if (!auth.user) return ''
    return `${auth.user.username}@${auth.user.id}`
  })

  /** 单用户命名空间:避免多账号共用同一份收藏 */
  const scoped = computed<Record<string, FavoriteItem>>(() => {
    return Object.fromEntries(
      Object.entries(byKey.value).filter(([k]) => k.startsWith(uidKey.value + ':')),
    )
  })

  const list = computed<FavoriteItem[]>(() =>
    Object.values(scoped.value).sort((a, b) => b.createdAt - a.createdAt),
  )

  function persist() {
    localStorage.setItem(LS_KEY, JSON.stringify(byKey.value))
  }

  function isFavorite(sessionId: number, messageId: number): boolean {
    return !!byKey.value[`${uidKey.value}:${sessionId}:${messageId}`]
  }

  function toggle(item: Omit<FavoriteItem, 'createdAt'>): boolean {
    const k = `${uidKey.value}:${item.sessionId}:${item.messageId}`
    if (byKey.value[k]) {
      delete byKey.value[k]
      persist()
      return false
    }
    byKey.value[k] = { ...item, createdAt: Date.now() }
    persist()
    return true
  }

  function remove(sessionId: number, messageId: number) {
    const k = `${uidKey.value}:${sessionId}:${messageId}`
    if (byKey.value[k]) {
      delete byKey.value[k]
      persist()
    }
  }

  function refreshSessionTitles(sessions: SessionVo[]) {
    const map = new Map(sessions.map((s) => [s.id, s.title]))
    let changed = false
    for (const it of Object.values(scoped.value)) {
      const title = map.get(it.sessionId)
      if (title && title !== it.sessionTitle) {
        it.sessionTitle = title
        changed = true
      }
    }
    if (changed) persist()
  }

  return { list, isFavorite, toggle, remove, refreshSessionTitles }
})
