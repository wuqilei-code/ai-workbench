<script setup lang="ts">
/**
 * 右上角消息铃铛:展示系统消息(定时任务完成等)。
 * 点击消息 → 标记已读 + 打开详情弹窗(展示完整内容,link 为报告时渲染报告 markdown)。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage } from 'naive-ui'
import MarkdownIt from 'markdown-it'
import { notificationApi } from '@/api/notifications'
import { fileApi } from '@/api/files'
import type { NotificationInfo } from '@/types'

const { t } = useI18n()
const msg = useMessage()
const list = ref<NotificationInfo[]>([])
const unread = ref(0)
const loading = ref(false)
const popoverShow = ref(false)
/** 是否已展开全部消息 */
const showAll = ref(false)

/** 详情弹窗 */
const detailShow = ref(false)
const detail = ref<NotificationInfo | null>(null)
const detailRenderedHtml = ref('')
const detailLoading = ref(false)

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

let timer: ReturnType<typeof setInterval> | null = null

async function refresh() {
  try {
    const [items, count] = await Promise.all([notificationApi.list(showAll.value ? 200 : 5), notificationApi.unreadCount()])
    list.value = items
    unread.value = count
  } catch (e: any) {
    // 静默:接口不可用不打扰
  }
}

/** 展开全部消息。 */
async function onShowAll() {
  showAll.value = true
  await refresh()
}

function startPolling() {
  stopPolling()
  timer = setInterval(refresh, 20000)
}
function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function onShow() {
  if (popoverShow.value) refresh()
}

async function openDetail(n: NotificationInfo) {
  const wasUnread = !n.read
  if (wasUnread) {
    try {
      await notificationApi.markRead(n.id)
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
      await refresh()
      // 默认只展示 5 条且未读优先:刚读的这条可能被挤出列表,补回末尾(已读区),避免"读完就消失"
      if (!showAll.value && !list.value.some((x) => x.id === n.id)) {
        list.value = [...list.value, { ...n, read: true }]
      }
    } catch {
      /* 忽略 */
    }
  }
  popoverShow.value = false
  detail.value = n
  detailRenderedHtml.value = n.content ? md.render(n.content) : ''
  detailShow.value = true
  // 若 link 指向工作区文件(任务执行报告),读取并渲染
  if (n.link && n.type === 'task') {
    detailLoading.value = true
    try {
      const report = await fileApi.content(n.link)
      if (report) detailRenderedHtml.value = md.render(report)
    } catch {
      /* 保留 content 渲染 */
    } finally {
      detailLoading.value = false
    }
  }
}

async function onReadAll() {
  try {
    await notificationApi.markAllRead()
    list.value.forEach((n) => (n.read = true))
    unread.value = 0
    await refresh()
    msg.success(t('notif.allRead'))
  } catch (e: any) {
    msg.error(e?.message || t('notif.actionFailed'))
  }
}

function formatTime(s?: string | null): string {
  if (!s) return ''
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function formatTimeFull(s?: string | null): string {
  if (!s) return ''
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

onMounted(() => {
  refresh()
  startPolling()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <n-popover v-model:show="popoverShow" trigger="click" placement="bottom-end" :width="380" @update:show="onShow">
    <template #trigger>
      <n-badge :value="unread" :max="99" :offset="[-2, 2]">
      <n-button size="small" quaternary circle :title="t('notif.center')">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
            <path d="M13.73 21a2 2 0 0 1-3.46 0" />
          </svg>
        </n-button>
      </n-badge>
    </template>

    <div class="notif-panel">
      <div class="notif-head">
      <span class="notif-title">{{ t('notif.center') }}</span>
      <n-button v-if="unread > 0" size="tiny" quaternary @click="onReadAll">{{ t('notif.readAll') }}</n-button>
      </div>
      <div v-if="loading" class="notif-loading">{{ t('notif.loading') }}</div>
      <div v-else-if="!list.length" class="notif-empty">{{ t('notif.empty') }}</div>
      <div v-else class="notif-list">
        <div
          v-for="n in list"
          :key="n.id"
          class="notif-item"
          :class="{ unread: !n.read }"
          @click="openDetail(n)"
        >
          <div class="notif-line">
            <span class="notif-dot" :class="{ read: n.read }"></span>
            <span class="notif-item-title">{{ n.title }}</span>
            <span class="notif-time">{{ formatTime(n.createdAt) }}</span>
          </div>
          <div v-if="n.content" class="notif-content">{{ n.content }}</div>
        </div>
        <div v-if="!showAll" class="notif-more">
      <n-button size="tiny" quaternary @click="onShowAll">{{ t('notif.showAll') }}</n-button>
        </div>
      </div>
    </div>
  </n-popover>

    <n-modal v-model:show="detailShow" preset="card" :title="detail?.title || t('notif.detailFallback')" style="width: 720px; max-width: 94vw" :bordered="false">
    <div class="detail-panel">
      <div class="detail-meta">
        <span>{{ t('notif.type') }}: {{ detail?.type === 'task' ? t('notif.typeTask') : t('notif.typeSystem') }}</span>
        <span>{{ t('notif.time') }}: {{ formatTimeFull(detail?.createdAt) }}</span>
        <span v-if="detail?.link">{{ t('notif.link') }}: {{ detail.link }}</span>
      </div>
      <div v-if="detailLoading" class="detail-loading">{{ t('notif.detailLoading') }}</div>
      <div v-else-if="detailRenderedHtml" class="detail-body markdown-body" v-html="detailRenderedHtml"></div>
      <div v-else class="detail-empty">({{ t('notif.noContent') }})</div>
    </div>
  </n-modal>
</template>

<style scoped>
.notif-panel { display: flex; flex-direction: column; width: 100%; }
.notif-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 2px 2px 8px; border-bottom: 1px solid var(--border-soft);
}
.notif-title { font-size: 13px; font-weight: 600; color: var(--text); }
.notif-loading, .notif-empty { color: var(--text-3); font-size: 12px; padding: 16px 0; text-align: center; }
.notif-list { display: flex; flex-direction: column; max-height: 60vh; overflow-y: auto; }
.notif-item {
  display: flex; flex-direction: column; gap: 4px;
  padding: 8px 6px; border-radius: var(--radius-sm); cursor: pointer;
  border-bottom: 1px solid var(--border-soft);
}
.notif-item:hover { background: var(--surface-hover); }
.notif-item.unread { background: var(--accent-soft); }
.notif-item.unread:hover { background: var(--accent-soft); opacity: .9; }
.notif-line { display: flex; align-items: center; gap: 6px; min-width: 0; }
.notif-dot {
  width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0;
  background: var(--accent);
}
.notif-dot.read { background: transparent; }
.notif-item-title {
  font-size: 12px; font-weight: 600; color: var(--text-1); flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.notif-time { font-size: 11px; color: var(--text-3); flex-shrink: 0; }
.notif-content {
  font-size: 12px; color: var(--text-3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap;
}
.notif-more { display: flex; justify-content: center; padding-top: 6px; }

.detail-panel { display: flex; flex-direction: column; gap: 10px; }
.detail-meta { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 12px; color: var(--text-3); }
.detail-loading, .detail-empty { color: var(--text-3); font-size: 13px; padding: 24px 0; text-align: center; }
.detail-plain {
  font-size: 13px; color: var(--text-2); line-height: 1.7; white-space: pre-wrap;
  max-height: 60vh; overflow-y: auto;
}
.detail-body { max-height: 62vh; overflow-y: auto; }
.detail-body :deep(p) { margin: 0 0 8px; }
.detail-body :deep(pre) { background: var(--bg); border: 1px solid var(--border-soft); border-radius: var(--radius-sm); padding: 10px; overflow-x: auto; }
.detail-body :deep(code) { font-family: Consolas, Menlo, monospace; }
.detail-body :deep(ul), .detail-body :deep(ol) { margin: 0 0 8px; padding-left: 20px; }
.detail-body :deep(h1), .detail-body :deep(h2), .detail-body :deep(h3) { margin: 10px 0 6px; }
</style>
