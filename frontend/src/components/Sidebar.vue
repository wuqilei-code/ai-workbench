<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useDialog, useMessage } from 'naive-ui'
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
}>()

const router = useRouter()
const chat = useChatStore()
const dialog = useDialog()
const msg = useMessage()

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
  if (diff < min) return '刚刚'
  if (diff < hour) return `${Math.floor(diff / min)} 分钟前`
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`
  const dayDiff = Math.floor(diff / day)
  if (dayDiff === 1) return '昨天'
  if (dayDiff < 7) return `${dayDiff} 天前`
  const date = new Date(d)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

/** 会话时间分组:今天/昨天/7 天内/更早 */
function groupOf(ts?: string): string {
  if (!ts) return '更早'
  const d = new Date(ts).getTime()
  if (Number.isNaN(d)) return '更早'
  const now = new Date()
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const day = 86_400_000
  if (d >= todayStart) return '今天'
  if (d >= todayStart - day) return '昨天'
  if (d >= todayStart - 7 * day) return '7 天内'
  return '更早'
}

interface GroupedSessions { label: string; items: SessionVo[] }
/** 分组后的会话列表:搜索时平铺;否则 置顶 → 今天 → 昨天 → 7 天内 → 更早 */
const grouped = computed<GroupedSessions[]>(() => {
  if (searchActive.value) {
    return displayed.value.length ? [{ label: '搜索结果', items: displayed.value }] : []
  }
  const groups: GroupedSessions[] = []
  const pinned = displayed.value.filter((s) => !viewArchived.value && s.pinned)
  const rest = displayed.value.filter((s) => viewArchived.value || !s.pinned)
  if (pinned.length) groups.push({ label: '置顶', items: pinned })
  for (const label of ['今天', '昨天', '7 天内', '更早']) {
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
  if (v) await withBusy('正在加载归档会话…', () => chat.loadArchivedSessions())
}

async function newChat() {
  if (chat.isStreaming) {
    const go = await confirmSwitch('新建会话')
    if (!go) return
  }
  try {
    const s = await withBusy('正在新建会话…', () => chat.createSession('New Chat'))
    router.push(`/session/${s.id}`)
  } catch (e: any) {
    msg.error(e?.message || '新建会话失败')
  }
}

/** 顶部模式切换:work=主工作区 */
async function openSession(id: number) {
  if (id === chat.currentSessionId) {
    router.push(`/session/${id}`)
    return
  }
  if (chat.isStreaming) {
    const go = await confirmSwitch('切换会话')
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
      content: '当前会话仍在生成中。切换后本会话的实时推送会中断(已在服务端执行的操作会继续完成),确认继续？',
      positiveText: '继续',
      negativeText: '取消',
      positiveButtonProps: { type: 'primary' },
      onPositiveClick: () => resolve(true),
      onNegativeClick: () => resolve(false),
      onClose: () => resolve(false),
    })
  })
}

async function remove(id: number) {
  dialog.warning({
    title: '删除会话',
    content: '确定删除该会话？此操作不可恢复。',
    positiveText: '删除',
    negativeText: '取消',
    positiveButtonProps: { type: 'primary' },
    onPositiveClick: async () => {
      try {
        await withBusy('正在删除会话…', () => chat.deleteSession(id))
        msg.success('已删除')
        if (chat.currentSessionId === null) router.push('/')
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

async function onPin(s: { id: number; pinned: number }) {
  try {
    await chat.pinSession(s.id, !s.pinned)
    msg.success(s.pinned ? '已取消置顶' : '已置顶')
  } catch (e: any) { msg.error(e.message) }
}

async function onArchive(s: { id: number; title: string }) {
  dialog.warning({
    title: '归档会话',
    content: `将「${s.title}」移入归档？归档后仍可在归档视图找回。`,
    positiveText: '归档',
    negativeText: '取消',
    positiveButtonProps: { type: 'primary' },
    onPositiveClick: async () => {
      try {
        await withBusy('正在归档会话…', () => chat.archiveSession(s.id))
        msg.success('已归档')
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

async function onRestore(s: { id: number; title: string }) {
  try {
    await withBusy('正在恢复会话…', () => chat.unarchiveSession(s.id))
    msg.success('已取消归档')
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
  if (!v) { msg.warning('标题不能为空'); return }
  if (!renameTarget.value) return
  try {
    await withBusy('正在重命名…', () => chat.renameSession(renameTarget.value!.id, v))
    msg.success('已重命名')
    renameVisible.value = false
  } catch (e: any) { msg.error(e.message) }
}

function onExport(s: { id: number; title: string }, format: 'md' | 'json' | 'pdf' | 'docx') {
  const labels: Record<string, string> = { md: 'Markdown', json: 'JSON', pdf: 'PDF', docx: 'Word' }
  withBusy(`正在导出 ${labels[format] || format}…`, () => chat.exportSession(s.id, format)).catch((e: any) => msg.error(e.message))
}

async function onClear(s: { id: number; title: string }) {
  dialog.warning({
    title: '清空会话',
    content: `将清空「${s.title}」的所有聊天记录（会话保留）。确定继续？`,
    positiveText: '清空',
    negativeText: '取消',
    positiveButtonProps: { type: 'primary' },
    onPositiveClick: async () => {
      try {
        await withBusy('正在清空会话…', () => chat.clearSession(s.id))
        msg.success('已清空')
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

/** 会话项操作菜单选项(主列表 vs 归档列表不同) */
function menuOptions(s: { id: number; pinned?: number; title: string }) {
  if (viewArchived.value) {
    return [
      { label: '总结会话', key: 'summarize' },
      { label: '恢复', key: 'restore' },
      { label: '重命名', key: 'rename' },
      { label: '导出 Markdown', key: 'export-md' },
      { label: '导出 JSON', key: 'export-json' },
      { label: '导出 PDF', key: 'export-pdf' },
      { label: '导出 Word', key: 'export-docx' },
      { type: 'divider' as const, key: 'd1' },
      { label: '删除', key: 'delete' },
    ]
  }
  return [
    { label: '总结会话', key: 'summarize' },
    { label: s.pinned ? '取消置顶' : '置顶', key: 'pin' },
    { label: '重命名', key: 'rename' },
    { label: '导出 Markdown', key: 'export-md' },
    { label: '导出 JSON', key: 'export-json' },
    { label: '导出 PDF', key: 'export-pdf' },
    { label: '导出 Word', key: 'export-docx' },
    { type: 'divider' as const, key: 'd1' },
    { label: '归档', key: 'archive' },
    { label: '清空记录', key: 'clear' },
    { label: '删除', key: 'delete' },
  ]
}

async function onSummarize(s: { id: number; title: string }) {
  try {
    msg.info('正在整理会话内容并生成总结,请稍候…')
    const s2 = await chat.summarizeSession(s.id, s.title)
    router.push(`/session/${s2.id}`)
  } catch (e: any) {
    msg.error(e?.message || '总结失败')
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
    <div class="mode-tabs">
      <span class="mode-toggle" title="收起侧边栏" @click="toggleCollapsed">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/><path d="m16 15-3-3 3-3"/></svg>
      </span>
      <span class="mode-tab active">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
        Work
      </span>
    </div>

    <div class="nav-section">
      <div class="nav-item" @click="emit('open-dashboard')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="9" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="14" y="12" width="7" height="9" rx="1"/><rect x="3" y="16" width="7" height="5" rx="1"/></svg>
        <span>驾驶舱</span>
      </div>
      <div class="nav-item" @click="newChat">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        <span>新建对话</span>
      </div>
      <div class="nav-item" @click="emit('open-plugin-market')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><line x1="9" y1="3" x2="9" y2="21"/></svg>
        <span>技能市场</span>
      </div>
      <div class="nav-item" @click="emit('open-automation')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4M4.93 19.07l2.83-2.83M16.24 7.76l2.83-2.83"/></svg>
        <span>自动化</span>
      </div>
      <div class="nav-item" @click="emit('open-template-lib')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        <span>模板库</span>
      </div>
      <div class="nav-item" @click="emit('open-mcp')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3"/></svg>
        <span>工具库</span>
      </div>
    </div>

    <div class="session-section">
      <div class="section-header">
        <span class="section-title">任务列表</span>
      </div>

      <div class="search-box">
        <n-input
          v-model:value="searchQuery"
          size="small"
          clearable
          placeholder="搜索会话…"
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
        >会话</span>
        <span
          class="tab"
          :class="{ active: viewArchived }"
          @click="onViewArchived(true)"
        >归档{{ chat.archivedSessions.length ? ` (${chat.archivedSessions.length})` : '' }}</span>
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
            <span v-if="!viewArchived && s.pinned" class="pin-mark" title="已置顶">📌</span>
            <span class="title" :title="s.agent ? `agent: ${s.agent}` : undefined">{{ s.title }}</span>
            <n-tag v-if="searchActive && s.archived" size="tiny" :bordered="false" class="arch-tag">归档</n-tag>
            <span class="item-time">{{ fmtRelativeTime(s.updatedAt) }}</span>
            <div class="item-ops" @click.stop>
              <button
                v-if="!viewArchived"
                class="op-btn"
                :title="s.pinned ? '取消置顶' : '置顶'"
                @click="onPin(s)"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 17v5"/><path d="M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16h14v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1z"/></svg>
              </button>
              <button
                v-if="!viewArchived"
                class="op-btn"
                title="重命名"
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
        <n-spin v-if="searchActive && searching" size="small" style="margin: 24px auto; display: block" />
        <n-empty
          v-else-if="!displayed.length"
          :description="searchActive ? '未找到匹配的会话' : (viewArchived ? '暂无归档会话' : '暂无会话')"
          style="margin-top: 40px"
        >
          <template #extra>
            <n-button v-if="!searchActive && !viewArchived" size="small" type="primary" @click="newChat">新建会话</n-button>
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
      title="重命名会话"
      :style="{ width: '380px', maxWidth: '92vw' }"
      :mask-closable="false"
      :bordered="false"
    >
      <n-input
        v-model:value="renameValue"
        placeholder="输入新标题"
        :maxlength="100"
        @keyup.enter="submitRename"
      />
      <template #footer>
        <div style="display:flex; justify-content:flex-end; gap:8px">
          <n-button @click="renameVisible = false">取消</n-button>
          <n-button type="primary" @click="submitRename">保存</n-button>
        </div>
      </template>
    </n-modal>
  </n-layout-sider>
</template>

<style scoped>
.mode-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-soft);
}
.mode-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: var(--control-h); height: var(--control-h);
  border-radius: var(--radius-sm);
  margin-right: 2px;
  flex-shrink: 0;
  cursor: pointer;
  color: var(--text-3);
  transition: color var(--motion-fast) var(--ease-standard), background-color var(--motion-fast) var(--ease-standard);
}
.mode-toggle:hover { color: var(--accent); background: var(--fill-0); }
.mode-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--text-3);
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.mode-tab:hover {
  background: var(--fill-0);
  color: var(--text-2);
}
.mode-tab.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 500;
}
.nav-section {
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-soft);
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  color: var(--text);
  font-size: 14px;
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.nav-item:hover {
  background: var(--fill-0);
  color: var(--accent-text);
}
.nav-item svg {
  flex-shrink: 0;
  opacity: 0.85;
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
  font-size: 13px;
  font-weight: 500;
  color: var(--text-3);
}
.list-tabs {
  display: flex;
  gap: 4px;
  padding: 0 16px 8px;
}
.list-tabs .tab {
  font-size: 12px;
  color: var(--text-3);
  padding: 4px 10px;
  border-radius: 12px;
  cursor: pointer;
  transition: background-color var(--motion-fast) var(--ease-standard), color var(--motion-fast) var(--ease-standard);
}
.list-tabs .tab:hover {
  background: var(--fill-0);
  color: var(--text-2);
}
.list-tabs .tab.active {
  background: var(--accent-soft);
  color: var(--accent);
  font-weight: 500;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 0 8px;
  position: relative;
}
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
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  margin-bottom: 2px;
  position: relative;
  transition: background-color var(--motion-fast) var(--ease-standard);
}
.session-item:hover {
  background: var(--fill-0);
}
/* Semi 规范:按下态用 fill 阶梯,不做 scale 形变 */
.session-item:active {
  background: var(--fill-1);
}
.session-item.active {
  background: var(--accent-soft);
}
.session-item.active .title {
  color: var(--text);
  font-weight: 500;
}
.session-item .title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
  color: var(--text);
  font-size: 14px;
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
