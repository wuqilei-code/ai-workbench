import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { favoriteApi } from '@/api/favorites'
import type { SessionVo } from '@/types'

/** 收藏项:一条消息(用户问题或 AI 回答)。以 sessionId+messageId 唯一。 */
export interface FavoriteItem {
  sessionId: number
  messageId: number
  /** user | assistant */
  role: 'user' | 'assistant'
  /** 消息正文(截断存储) */
  content: string
  /** 所在 turn 的 id(跳转定位用) */
  turnId: string
  /** 会话标题(冗余存储,跳转后刷新) */
  sessionTitle: string
  createdAt: number
}

export const useFavoritesStore = defineStore('favorites', () => {
  /** key = `${sessionId}:${messageId}`(后端按用户隔离,无需再本地命名空间) */
  const byKey = ref<Record<string, FavoriteItem>>({})
  let loaded = false
  let loading: Promise<void> | null = null

  const keyOf = (sessionId: number, messageId: number) => `${sessionId}:${messageId}`

  /** 从后端拉取收藏(幂等:仅首次真正加载)。 */
  function load(): Promise<void> {
    if (loaded) return Promise.resolve()
    if (loading) return loading
    loading = favoriteApi.list()
      .then((items) => {
        const map: Record<string, FavoriteItem> = {}
        for (const it of items) {
          map[keyOf(it.sessionId, it.messageId)] = {
            sessionId: it.sessionId,
            messageId: it.messageId,
            role: it.role,
            content: it.content || '',
            turnId: it.turnId || '',
            sessionTitle: it.sessionTitle || '',
            createdAt: it.createdAt || 0,
          }
        }
        byKey.value = map
        loaded = true
      })
      .catch((e) => {
        console.debug('[favorites] load failed', e)
      })
      .finally(() => { loading = null })
    return loading
  }

  const list = computed<FavoriteItem[]>(() =>
    Object.values(byKey.value).sort((a, b) => b.createdAt - a.createdAt),
  )

  function isFavorite(sessionId: number, messageId: number): boolean {
    return !!byKey.value[keyOf(sessionId, messageId)]
  }

  /** 乐观更新 + 后台同步后端;返回是否「已收藏」(true=新增,false=取消)。 */
  function toggle(item: Omit<FavoriteItem, 'createdAt'>): boolean {
    const k = keyOf(item.sessionId, item.messageId)
    const added = !byKey.value[k]
    if (added) {
      byKey.value[k] = { ...item, createdAt: Date.now() }
    } else {
      delete byKey.value[k]
    }
    favoriteApi.toggle({
      sessionId: item.sessionId,
      messageId: item.messageId,
      role: item.role,
      content: item.content,
      turnId: item.turnId,
      sessionTitle: item.sessionTitle,
    }).catch((e) => console.debug('[favorites] toggle failed', e))
    return added
  }

  function remove(sessionId: number, messageId: number) {
    const k = keyOf(sessionId, messageId)
    if (byKey.value[k]) delete byKey.value[k]
    favoriteApi.remove(sessionId, messageId).catch((e) => console.debug('[favorites] remove failed', e))
  }

  function refreshSessionTitles(sessions: SessionVo[]) {
    const map = new Map(sessions.map((s) => [s.id, s.title]))
    for (const it of Object.values(byKey.value)) {
      const title = map.get(it.sessionId)
      if (title && title !== it.sessionTitle) it.sessionTitle = title
    }
  }

  return { list, isFavorite, toggle, remove, refreshSessionTitles, load }
})
