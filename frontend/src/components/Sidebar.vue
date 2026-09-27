<script setup lang="ts">
import { computed, ref, inject, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import type { Ref } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useDialog, useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import type { SessionVo } from '@/types'

const props = withDefaults(defineProps<{ collapsed?: boolean; narrow?: boolean }>(), {
  collapsed: false,
  narrow: false,
})
const emit = defineEmits<{
  'update:collapsed': [v: boolean]
  'open-plugin-market': []
  'open-template-lib': []
  'open-mcp': []
  'open-my-files': []
  'open-automation': []
  'open-find': []
  'open-dashboard': []
  'close-manage': []
}>()

const router = useRouter()
const chat = useChatStore()
const dialog = useDialog()
const msg = useMessage()
const { t } = useI18n()

/** 当前打开的整页管理视图(技能/工具/模板/驾驶舱/自动化):用于导航项高亮 */
const sidebarEvents = inject<Ref<{ managePage: string }> | undefined>('sidebarEvents', undefined)
const managePage = computed(() => sidebarEvents?.value?.managePage || '')

/** 汉堡按钮:窄屏可手动收起/展开侧边栏 */
function toggleCollapsed() {
  emit('update:collapsed', !props.collapsed)
}

/** 归档视图开关:false=主列表, true=已归档列表 */
const viewArchived = ref(false)
const sessions = computed(() => viewArchived.value ? chat.archivedSessions : chat.sessions)
/** 耗时操作进行中:会话导出/导入等,显示轻量遮罩防止重复点击 */
const busy = ref('')
function withBusy<T>(label: string, fn: () => Promise<T>): Promise<T> {
  busy.value = label
  return fn().finally(() => { busy.value = '' })
}

/** 会话搜索:输入即搜(标题/消息内容),命中含归档;非空时展示搜索结果视图 */
const searchQuery = ref('')
const searching = computed(() => chat.searching)
const searchActive = computed(() => searchQuery.value.trim().length > 0)
const displayed = computed<SessionVo[]>(() =>
  searchActive.value ? chat.searchResults : sessions.value,
)

/** 相对时间:刚刚 / N 分钟前 / N 小时前 / 昨天 / N 天前 / 日期 */
function fmtRelativeTime(ts?: string): string {
  if (!ts) return ''
  const d = new Date(ts).getTime()
  if (Number.isNaN(d)) return ''
  const diff = Date.now() - d
  const min = 60_000, hour = 3_600_000, day = 86_400_000
  if (diff < min) return t('sidebar2.justNow')
  if (diff < hour) return t('sidebar2.minutesAgo', { n: Math.floor(diff / min) })
  if (diff < day) return t('sidebar2.hoursAgo', { n: Math.floor(diff / hour) })
  const dayDiff = Math.floor(diff / day)
  if (dayDiff === 1) return t('sidebar2.yesterday')
  if (dayDiff < 7) return t('sidebar2.daysAgo', { n: dayDiff })
  const date = new Date(d)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 会话时间分组:今天/昨天/7 天内/更早 */
function groupOf(ts?: string): string {
  if (!ts) return t('sidebar2.prevThread')
  const d = new Date(ts).getTime()
  if (Number.isNaN(d)) return t('sidebar2.prevThread')
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const day = 86_400_000
  if (d >= todayStart) return t('sidebar2.gToday')
  if (d >= todayStart - day) return t('sidebar2.gYesterday')
  if (d >= todayStart - 7 * day) return t('sidebar2.g7')
  return t('sidebar2.gEarlier')
}

interface GroupedSessions { label: string; items: SessionVo[] }
/** 分组后的会话列表:搜索时平铺;否则 置顶 → 今天 → 昨天 → 7 天内 → 更早 */
const grouped = computed<GroupedSessions[]>(() => {
  if (searchActive.value) {
  return displayed.value.length ? [{ label: t('sidebar2.searchGroup'), items: displayed.value }] : []
  }
  const groups: GroupedSessions[] = []
  const pinned = displayed.value.filter((s) => !viewArchived.value && s.pinned)
  const rest = displayed.value.filter((s) => viewArchived.value || !s.pinned)
  if (pinned.length) groups.push({ label: t('sidebar2.pinned'), items: pinned })
  for (const label of [t('sidebar2.gToday'), t('sidebar2.gYesterday'), t('sidebar2.g7'), t('sidebar2.gEarlier')]) {
    const items = rest.filter((s) => groupOf(s.updatedAt) === label)
    if (items.length) groups.push({ label, items })
  }
  return groups
})

function onSearchInput(v: string) {
  searchQuery.value = v
  chat.searchSessions(v)
}
function clearSearch() {
  searchQuery.value = ''
  chat.searchSessions('')
}
async function onOpenSearchResult(s: { id: number }) {
  openSession(s.id)
}
async function onViewArchived(v: boolean) {
  viewArchived.value = v
    if (v) await withBusy(t('sidebar2.loadingArchived'), () => chat.loadArchivedSessions())
}

// ---------- 会话列表滚动分页 ----------

/** 哨兵元素(列表末尾);进入视口即续一页 */
const pagerSentinel = ref<HTMLElement | null>(null)
/** 是否还有下一页:会话页看 sessionsHasMore,归档页看 archivedHasMore */
const hasMore = computed(() =>
  viewArchived.value ? chat.archivedHasMore : chat.sessionsHasMore,
)
const loadingMore = computed(() =>
  viewArchived.value ? chat.archivedLoadingMore : chat.sessionsLoadingMore,
)

let pagerObserver: IntersectionObserver | null = null

function tryLoadMore() {
  if (searchActive.value || !hasMore.value || loadingMore.value) return
  if (viewArchived.value) chat.loadMoreArchived()
  else chat.loadMoreSessions()
}

function observePager() {
  pagerObserver?.disconnect()
  pagerObserver = null
  const el = pagerSentinel.value
  if (!el || typeof IntersectionObserver === 'undefined') return
  // rootMargin 提前 240px:用户快滚到底时就开始预取,避免出现"停一下才加载"
  pagerObserver = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) tryLoadMore()
    },
    { rootMargin: '0px 0px 240px 0px' },
  )
  pagerObserver.observe(el)
}

onMounted(() => nextTick(observePager))
// 切换 会话/归档 或列表结构变化后,哨兵位置变了,重新绑定观察
watch([viewArchived, searchActive, () => displayed.value.length], () => nextTick(observePager))
onBeforeUnmount(() => {
  pagerObserver?.disconnect()
  pagerObserver = null
})

async function newChat() {
  if (chat.isStreaming) {
  const go = await confirmSwitch(t('sidebar2.newSession'))
    if (!go) return
  }
  // 只进入草稿态(欢迎页),不立即创建会话;首次发送消息时再创建
  chat.startDraft()
  emit('close-manage')
  router.push('/')
}

/** 顶部模式切换:work=主工作区 */
async function openSession(id: number) {
  // 点击会话(含当前会话)时退出整页管理视图,切回聊天
  emit('close-manage')
  if (id === chat.currentSessionId) {
    router.push(`/session/${id}`)
    return
  }
  if (chat.isStreaming) {
  const go = await confirmSwitch(t('sidebar2.switchSession'))
    if (!go) return
  }
  router.push(`/session/${id}`)
  await chat.selectSession(id)
}

/** 流式中切换会话的统一确认:提示正在生成,切换会中断本会话的实时推送(服务端继续运行,可再切回查看)。 */
function confirmSwitch(action: string): Promise<boolean> {
  return new Promise((resolve) => {
    dialog.warning({
      title: action,
    content: t('sidebar2.switchContent'),
    positiveText: t('sidebar2.confirm'),
    negativeText: t('sidebar2.cancel'),
      positiveButtonProps: { type: 'primary' },
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
    })
  })
}

async function remove(id: number) {
  dialog.warning({
    title: t('sidebar2.delTitle'),
    content: t('sidebar2.delContent'),
    positiveText: t('sidebar2.del'),
    negativeText: t('sidebar2.cancel'),
    positiveButtonProps: { type: 'primary' },
    onPositiveClick: async () => {
      try {
    await withBusy(t('sidebar2.deleting'), () => chat.deleteSession(id))
    msg.success(t('sidebar2.deleted'))
        if (chat.currentSessionId === null) router.push('/')
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

async function onPin(s: { id: number; pinned: number }) {
  try {
    await chat.pinSession(s.id, !s.pinned)
  msg.success(s.pinned ? t('sidebar2.pinned_off') : t('sidebar2.pinned_on'))
  } catch (e: any) { msg.error(e.message) }
}

async function onArchive(s: { id: number; title: string }) {
  dialog.warning({
    title: t('sidebar2.archTitle'),
    content: t('sidebar2.archContent', { title: s.title }),
    positiveText: t('sidebar2.archive'),
    negativeText: t('sidebar2.cancel'),
    positiveButtonProps: { type: 'primary' },
    onPositiveClick: async () => {
      try {
    await withBusy(t('sidebar2.archiving'), () => chat.archiveSession(s.id))
    msg.success(t('sidebar2.archived'))
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

async function onRestore(s: { id: number; title: string }) {
  try {
    await withBusy(t('sidebar2.unarchiving'), () => chat.unarchiveSession(s.id))
    msg.success(t('sidebar2.unarchived'))
  } catch (e: any) { msg.error(e.message) }
}

/** 重命名:弹出输入框 */
const renameVisible = ref(false)
const renameTarget = ref<{ id: number; title: string } | null>(null)
const renameValue = ref('')

function onRename(s: { id: number; title: string }) {
  renameTarget.value = s
  renameValue.value = s.title
  renameVisible.value = true
}

async function submitRename() {
  const v = renameValue.value.trim()
  if (!v) { msg.warning(t('sidebar2.nameEmpty')); return }
  if (!renameTarget.value) return
  try {
    await withBusy(t('sidebar2.renaming'), () => chat.renameSession(renameTarget.value!.id, v))
    msg.success(t('sidebar2.renamed'))
    renameVisible.value = false
  } catch (e: any) { msg.error(e.message) }
}

function onExport(s: { id: number; title: string }, format: 'md' | 'json' | 'pdf' | 'docx') {
  const labels: Record<string, string> = { md: 'Markdown', json: 'JSON', pdf: 'PDF', docx: 'Word' }
    withBusy(t('sidebar2.exporting', { label: labels[format] || format }), () => chat.exportSession(s.id, format)).catch((e: any) => msg.error(e.message))
}

async function onClear(s: { id: number; title: string }) {
  dialog.warning({
    title: t('sidebar2.clearTitle'),
    content: t('sidebar2.clearContent', { title: s.title }),
    positiveText: t('sidebar2.clear'),
    negativeText: t('sidebar2.cancel'),
    positiveButtonProps: { type: 'primary' },
    onPositiveClick: async () => {
      try {
    await withBusy(t('sidebar2.clearing'), () => chat.clearSession(s.id))
    msg.success(t('sidebar2.cleared'))
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

/** 会话项操作菜单选项(主列表 vs 归档列表不同) */
function menuOptions(s: { id: number; pinned?: number; title: string }) {
  if (viewArchived.value) {
    return [
    { label: t('sidebar2.actSummarize'), key: 'summarize' },
    { label: t('sidebar2.actRestore'), key: 'restore' },
    { label: t('sidebar2.actRename'), key: 'rename' },
    { label: t('sidebar2.actExportMd'), key: 'export-md' },
    { label: t('sidebar2.actExportJson'), key: 'export-json' },
    { label: t('sidebar2.actExportPdf'), key: 'export-pdf' },
    { label: t('sidebar2.actExportDocx'), key: 'export-docx' },
      { type: 'divider' as const, key: 'd1' },
    { label: t('sidebar2.actDelete'), key: 'delete' },
    ]
  }
  return [
    { label: t('sidebar2.actSummarize'), key: 'summarize' },
    { label: s.pinned ? t('sidebar2.actUnpin') : t('sidebar2.actPin'), key: 'pin' },
    { label: t('sidebar2.actRename'), key: 'rename' },
    { label: t('sidebar2.actExportMd'), key: 'export-md' },
    { label: t('sidebar2.actExportJson'), key: 'export-json' },
    { label: t('sidebar2.actExportPdf'), key: 'export-pdf' },
    { label: t('sidebar2.actExportDocx'), key: 'export-docx' },
    { type: 'divider' as const, key: 'd1' },
    { label: t('sidebar2.actArchive'), key: 'archive' },
    { label: t('sidebar2.actClear'), key: 'clear' },
    { label: t('sidebar2.actDelete'), key: 'delete' },
  ]
}

async function onSummarize(s: { id: number; title: string }) {
  try {
    msg.info(t('sidebar2.summarizing'))
    const s2 = await chat.summarizeSession(s.id, s.title)
    router.push(`/session/${s2.id}`)
  } catch (e: any) {
    msg.error(e?.message || t('sidebar2.summarizeFailed'))
  }
}

function onMenuSelect(key: string, s: { id: number; pinned?: number; title: string }) {
  switch (key) {
    case 'summarize': onSummarize(s); break
    case 'pin': onPin(s as { id: number; pinned: number }); break
    case 'rename': onRename(s); break
    case 'export-md': onExport(s, 'md'); break
    case 'export-json': onExport(s, 'json'); break
    case 'export-pdf': onExport(s, 'pdf'); break
    case 'export-docx': onExport(s, 'docx'); break
    case 'archive': onArchive(s); break
    case 'restore': onRestore(s); break
    case 'clear': onClear(s); break
    case 'delete': remove(s.id); break
  }
}
</script>

<template>
  <n-layout-sider :width="260" :collapsed-width="0" collapsible :collapsed="props.collapsed" :show-trigger="false" collapse-mode="width" content-style="display:flex; flex-direction:column; background: var(--sidebar-bg); overflow:hidden" @update:collapsed="emit('update:collapsed', $event)">
    <!-- 品牌区(参考主视图设计) -->
    <div class="brand">
      <div class="brand-logo">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>
      </div>
      <div class="brand-text">
        <div class="brand-name">{{ t('app.name') }}</div>
        <div class="brand-sub">{{ t('app.sub') }}</div>
      </div>
      <span class="brand-collapse" role="button" tabindex="0" :title="t('sidebar2.collapseTitle')" :aria-label="t('sidebar2.collapseTitle')" @click="toggleCollapsed" @keydown.enter.prevent="toggleCollapsed" @keydown.space.prevent="toggleCollapsed">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/><path d="m16 15-3-3 3-3"/></svg>
      </span>
    </div>

    <div class="nav-section">
      <div class="nav-item" :class="{ active: managePage === 'dashboard' }" @click="emit('open-dashboard')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg>
        <span>{{ t('nav.dashboard') }}</span>
      </div>
      <div class="nav-item" @click="newChat">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        <span>{{ t('nav.newChat') }}</span>
      </div>
      <div class="nav-item" :class="{ active: managePage === 'skills' }" @click="emit('open-plugin-market')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="9" y1="3" x2="9" y2="21"/></svg>
        <span>{{ t('nav.pluginMarket') }}</span>
      </div>
      <div class="nav-item" :class="{ active: managePage === 'automation' }" @click="emit('open-automation')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/></svg>
        <span>{{ t('nav.automation') }}</span>
      </div>
      <div class="nav-item" :class="{ active: managePage === 'templates' }" @click="emit('open-template-lib')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        <span>{{ t('nav.templateLib') }}</span>
      </div>
      <div class="nav-item" :class="{ active: managePage === 'tools' }" @click="emit('open-mcp')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
        <span>{{ t('nav.tools') }}</span>
      </div>
    </div>

    <div class="session-section">
      <div class="section-header">
        <span class="section-title">{{ t('sidebar.tasks') }}</span>
      </div>

      <div class="search-box">
        <n-input
          v-model:value="searchQuery"
          size="small"
          clearable
          :placeholder="t('sidebar.searchPlaceholder')"
          @update:value="onSearchInput"
          @clear="clearSearch"
        >
          <template #prefix>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          </template>
        </n-input>
      </div>

      <div class="list-tabs">
        <span
          class="tab"
          :class="{ active: !viewArchived }"
          @click="onViewArchived(false)"
        >{{ t('sidebar.sessions') }}</span>
        <span
          class="tab"
          :class="{ active: viewArchived }"
          @click="onViewArchived(true)"
        >{{ t('sidebar.archived') }}{{ chat.archivedSessions.length ? ` (${chat.archivedSessions.length})` : '' }}</span>
      </div>

      <div class="session-list">
        <template v-for="g in grouped" :key="g.label">
          <div v-if="!searchActive" class="group-header">{{ g.label }}</div>
          <div
            v-for="s in g.items"
            :key="s.id"
            class="session-item"
            :class="{ active: s.id === chat.currentSessionId }"
            @click="openSession(s.id)"
          >
        <span v-if="!viewArchived && s.pinned" class="pin-mark" :title="t('sidebar2.pinMark')">📌</span>
            <span class="title" :title="s.agent ? `agent: ${s.agent}` : undefined">{{ s.title }}</span>
      <n-tag v-if="searchActive && s.archived" size="tiny" :bordered="false" class="arch-tag">{{ t('sidebar2.archTag') }}</n-tag>
            <span class="item-time">{{ fmtRelativeTime(s.updatedAt) }}</span>
            <div class="item-ops" @click.stop>
              <button
                v-if="!viewArchived"
                class="op-btn"
        :title="s.pinned ? t('sidebar2.unpinTitle') : t('sidebar2.pinTitle')"
                @click="onPin(s)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16h14v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z"/></svg>
              </button>
              <button
                v-if="!viewArchived"
                class="op-btn"
        :title="t('sidebar2.newSessionTitle')"
                @click="onRename(s)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
              </button>
              <n-dropdown
                :options="menuOptions(s)"
                trigger="click"
                size="small"
                placement="bottom-end"
                @select="(k: string) => onMenuSelect(k, s)"
              >
                <n-button quaternary circle size="tiny" @click.stop>⋯</n-button>
              </n-dropdown>
            </div>
          </div>
        </template>
        <!-- 滚动分页哨兵:进入视口即自动续一页(rootMargin 提前 240px 预取),取代原来的「加载更多」按钮 -->
        <div v-if="!searchActive" ref="pagerSentinel" class="pager-sentinel">
          <span v-if="loadingMore" class="pager-loading">
            <span class="pager-spinner" />{{ t('sidebar.loading') }}
          </span>
        </div>
        <n-spin v-if="searchActive && searching" size="small" style="margin: 24px auto; display: block" />
        <n-empty
          v-else-if="!displayed.length"
          :description="searchActive ? t('sidebar.noMatch') : (viewArchived ? t('sidebar.noArchived') : t('sidebar.noSessions'))"
          style="margin-top: 40px"
        >
          <template #extra>
            <n-button v-if="!searchActive && !viewArchived" size="small" type="primary" @click="newChat">{{ t('sidebar.newSession') }}</n-button>
          </template>
        </n-empty>
        <transition name="sidebusy-fade">
          <div v-if="busy" class="side-busy">
            <n-spin size="small" />
            <span class="side-busy-text">{{ busy }}</span>
          </div>
        </transition>
      </div>
    </div>

    <n-modal
      v-model:show="renameVisible"
      preset="card"
        :title="t('sidebar2.renameTitle')"
      :style="{ width: '380px', maxWidth: '92vw' }"
      :mask-closable="false"
      :bordered="false"
    >
      <n-input
        v-model:value="renameValue"
        :placeholder="t('sidebar2.renamePlaceholder')"
        :maxlength="100"
        @keyup.enter="submitRename"
      />
      <template #footer>
        <div style="display:flex; justify-content:flex-end; gap:8px">
        <n-button @click="renameVisible = false">{{ t('sidebar2.cancel') }}</n-button>
        <n-button type="primary" @click="submitRename">{{ t('sidebar2.ok') }}</n-button>
        </div>
      </template>
    </n-modal>
  </n-layout-sider>
</template>

<style scoped>
/* 品牌区 */
.brand { display: flex; align-items: center; gap: 10px; padding: 14px 14px 12px; }
.brand-logo {
  width: 32px; height: 32px; border-radius: 10px; flex-shrink: 0;
  background: linear-gradient(135deg, var(--accent), #7a5cff);
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 4px 12px rgba(79, 110, 247, .4);
}
.brand-text { flex: 1; min-width: 0; }
.brand-name { font-size: 16px; font-weight: 700; color: var(--text); letter-spacing: -.2px; line-height: 1.2; }
.brand-sub { font-size: 11px; color: var(--text-3); }
.brand-collapse {
  flex-shrink: 0; width: 28px; height: 28px; border-radius: 8px;
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--text-3); cursor: pointer; transition: background .15s, color .15s;
}
.brand-collapse:hover { background: var(--fill-0); color: var(--text); }
/* 新建对话:渐变主按钮样式(参考主视图) */
.nav-section {
  padding: 6px 12px 12px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 11px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--text-2);
  font-size: 13.5px;
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.nav-item:hover {
  background: var(--fill-0);
  color: var(--text);
}
.nav-item.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}
.nav-item svg {
  flex-shrink: 0;
  opacity: 0.9;
}
.session-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 8px;
}
.section-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .4px;
  text-transform: uppercase;
  color: var(--text-3);
}
.list-tabs {
  display: flex;
  gap: 6px;
  padding: 2px 16px 10px;
}
.list-tabs .tab {
  font-size: 11.5px;
  color: var(--text-2);
  padding: 4px 11px;
  border-radius: 20px;
  cursor: pointer;
  border: 1px solid transparent;
  background: var(--fill-0);
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.list-tabs .tab:hover {
  color: var(--text);
}
.list-tabs .tab.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
  position: relative;
}
/* 滚动分页哨兵:无更多内容时高度为 0(不占位),加载中显示细 spinner */
.pager-sentinel {
  display: flex; align-items: center; justify-content: center;
  min-height: 0; padding: 0;
}
.pager-loading {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 10px 0 14px;
  color: var(--text-3); font-size: 12px;
}
.pager-spinner {
  width: 12px; height: 12px; border-radius: 50%;
  border: 2px solid var(--border-soft); border-top-color: var(--accent);
  animation: pager-spin .7s linear infinite;
}
@keyframes pager-spin { to { transform: rotate(360deg); } }
.side-busy {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: color-mix(in srgb, var(--bg, #fff) 70%, transparent);
  backdrop-filter: blur(2px);
}
.side-busy-text {
  font-size: 12px;
  color: var(--text-2);
}
.sidebusy-fade-enter-active,
.sidebusy-fade-leave-active {
  transition: opacity 0.2s ease;
}
.sidebusy-fade-enter-from,
.sidebusy-fade-leave-to {
  opacity: 0;
}
.arch-tag {
  flex-shrink: 0;
}
.agent-tag {
  flex-shrink: 0;
}
.pin-mark {
  font-size: 11px;
  margin-right: 2px;
  flex-shrink: 0;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 11px;
  border-radius: 10px;
  cursor: pointer;
  margin-bottom: 2px;
  position: relative;
  color: var(--text-2);
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.session-item:hover {
  background: var(--fill-0);
  color: var(--text);
}
/* Semi 规范:按下态用 fill 阶梯,不做 scale 形变 */
.session-item:active {
  background: var(--fill-1);
}
.session-item.active {
  background: var(--accent-soft);
}
.session-item.active .title {
  color: var(--accent-text);
  font-weight: 600;
}
.session-item .title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  color: inherit;
  font-size: 13.5px;
}
.search-box {
  padding: 0 16px 8px;
}
.group-header {
  padding: 8px 12px 4px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-3);
}
.item-time {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-3);
  transition: opacity 0.15s;
}
.session-item:hover .item-time {
  opacity: 0;
}
.item-ops {
  position: absolute;
  top: 50%;
  right: 6px;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 1px 2px;
  border-radius: 6px;
  background: inherit;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.15s;
}
.session-item:hover .item-ops {
  opacity: 1;
  pointer-events: auto;
}
/* 触屏:会话操作(⋯ 菜单)常显,hover 不生效 */
@media (hover: none) {
  .item-ops {
    opacity: 1;
    pointer-events: auto;
  }
}
.op-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
  padding: 0;
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.op-btn:hover {
  background: var(--fill-1);
  color: var(--accent);
}
</style>
