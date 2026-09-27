<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useFavoritesStore } from '@/stores/favorites'
import { useChatStore } from '@/stores/chat'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const emit = defineEmits<{
  (e: 'open-session', target: { sessionId: number; turnId?: string }): void
}>()

const fav = useFavoritesStore()
const chat = useChatStore()
const msg = useMessage()
const router = useRouter()

const list = computed(() => fav.list)

/** 点击收藏项:跳转到对应会话并定位到该 turn(等待消息异步加载) */
async function openFavorite(item: (typeof fav.list)[number]) {
  if (!item.sessionId) return
  if (chat.currentSessionId !== item.sessionId) {
    await router.push({ name: 'session', params: { id: item.sessionId } })
  }
  emit('open-session', { sessionId: item.sessionId, turnId: item.turnId })
}

function remove(item: (typeof fav.list)[number]) {
  fav.remove(item.sessionId, item.messageId)
  msg.success(t('favPanel2.removed'))
}

function fmtTime(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
</script>

<template>
  <div class="fav-panel">
    <div v-if="!list.length" class="fav-empty">
      <div class="fav-empty-icon">☆</div>
      <div>{{ t('favPanel2.empty') }}</div>
      <div class="fav-empty-sub">{{ t('favPanel2.emptySub') }}</div>
    </div>
    <div v-else class="fav-list">
      <div v-for="it in list" :key="`${it.sessionId}:${it.messageId}`" class="fav-item">
        <div class="fav-main" :title="it.content" @click="openFavorite(it)">
          <div class="fav-row">
            <span class="fav-role" :class="it.role">{{ it.role === 'user' ? t('favPanel2.me') : 'AI' }}</span>
            <span class="fav-session">{{ it.sessionTitle || t('favPanel2.sessionNo', { id: it.sessionId }) }}</span>
            <span class="fav-time">{{ fmtTime(it.createdAt) }}</span>
          </div>
          <div class="fav-content">{{ it.content }}</div>
        </div>
        <div class="fav-actions">
          <span class="fav-remove" :title="t('favPanel2.cancelTitle')" @click="remove(it)">✕</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fav-panel { padding: 4px 0; }
.fav-empty { text-align: center; color: var(--text-3); padding: 40px 12px; font-size: 13px; }
.fav-empty-icon { font-size: 30px; margin-bottom: 8px; color: var(--text-4, #5a6478); }
.fav-empty-sub { font-size: 12px; margin-top: 4px; opacity: .8; }
.fav-list { display: flex; flex-direction: column; gap: 6px; }
.fav-item {
  display: flex; align-items: flex-start; gap: 6px;
  padding: 8px 10px; border: 1px solid var(--border-soft);
  border-radius: var(--radius-sm); background: var(--surface);
  cursor: pointer; transition: border-color .15s;
}
.fav-item:hover { border-color: var(--accent-text); }
.fav-main { flex: 1; min-width: 0; }
.fav-row { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; }
.fav-role {
  font-size: 11px; padding: 1px 6px; border-radius: 8px; flex-shrink: 0;
  background: var(--accent-soft); color: var(--accent-text);
}
.fav-role.user { background: var(--surface-hover); color: var(--text-2); }
.fav-session {
  font-size: 11px; color: var(--text-3); flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fav-time { font-size: 11px; color: var(--text-4, #5a6478); flex-shrink: 0; }
.fav-content {
  font-size: 12px; color: var(--text-2); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  word-break: break-word;
}
.fav-actions { flex-shrink: 0; opacity: 0; transition: opacity .15s; }
.fav-item:hover .fav-actions { opacity: 1; }
@media (hover: none) {
  .fav-actions { opacity: 1; }
}
.fav-remove {
  color: var(--text-3); cursor: pointer; font-size: 12px; padding: 2px;
}
.fav-remove:hover { color: var(--err); }
</style>
