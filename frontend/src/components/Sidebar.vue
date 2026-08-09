<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useDialog, useMessage } from 'naive-ui'
import type { SessionVo } from '@/types'

const props = withDefaults(defineProps<{ collapsed?: boolean; narrow?: boolean }>(), {
  collapsed: false,
  narrow: false,
})
const emit = defineEmits<{ 'update:collapsed': [v: boolean] }>()

const router = useRouter()
const chat = useChatStore()
const auth = useAuthStore()
const theme = useThemeStore()
const dialog = useDialog()
const msg = useMessage()

/** 汉堡按钮:窄屏可手动收起/展开侧边栏 */
function toggleCollapsed() {
  emit('update:collapsed', !props.collapsed)
}

/** 管理员入口菜单:用户管理 / 模型管理 / 用量统计 */
const adminMenuOptions = [
  { label: '👥 用户管理', key: '/admin/users' },
  { label: '🧩 模型管理', key: '/admin/providers' },
  { label: '📊 用量统计', key: '/admin/usage' },
]
function onAdminSelect(key: string) {
  router.push(key)
}

/** 归档视图开关:false=主列表, true=已归档列表 */
const viewArchived = ref(false)
const sessions = computed(() => viewArchived.value ? chat.archivedSessions : chat.sessions)
const agents = computed(() => chat.agents)
const agentMenuOpen = ref(false)

/** 会话搜索:输入即搜(标题/消息内容),命中含归档;非空时展示搜索结果视图 */
const searchQuery = ref('')
const searching = computed(() => chat.searching)
const searchActive = computed(() => searchQuery.value.trim().length > 0)
const displayed = computed<SessionVo[]>(() =>
  searchActive.value ? chat.searchResults : sessions.value,
)

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
  if (v) await chat.loadArchivedSessions()
}

/** agent 下拉选项:默认 + 可用 primary/all agents */
const agentOptions = computed(() => [
  { label: '默认', key: '__default__' },
  ...agents.value.map((a) => ({ label: a.name, key: a.name })),
])

async function onAgentMenuShow(show: boolean) {
  if (show) {
    await chat.loadAgents()
    agentMenuOpen.value = true
  } else {
    agentMenuOpen.value = false
  }
}

async function onAgentSelect(key: string) {
  const s = await chat.createSession('New Chat', key === '__default__' ? undefined : key)
  router.push(`/session/${s.id}`)
}

async function newChat() {
  if (chat.isStreaming) {
    const go = await confirmSwitch('新建会话')
    if (!go) return
  }
  const s = await chat.createSession('New Chat')
  router.push(`/session/${s.id}`)
}

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
    onPositiveClick: async () => {
      try {
        await chat.deleteSession(id)
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
    onPositiveClick: async () => {
      try {
        await chat.archiveSession(s.id)
        msg.success('已归档')
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

async function onRestore(s: { id: number; title: string }) {
  try {
    await chat.unarchiveSession(s.id)
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
    await chat.renameSession(renameTarget.value.id, v)
    msg.success('已重命名')
    renameVisible.value = false
  } catch (e: any) { msg.error(e.message) }
}

function onExport(s: { id: number; title: string }, format: 'md' | 'json' | 'pdf' | 'docx') {
  chat.exportSession(s.id, format).catch((e: any) => msg.error(e.message))
}

async function onClear(s: { id: number; title: string }) {
  dialog.warning({
    title: '清空会话',
    content: `将清空「${s.title}」的所有聊天记录（会话保留）。确定继续？`,
    positiveText: '清空',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await chat.clearSession(s.id)
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

function logout() {
  auth.logout()
}
</script>

<template>
  <n-layout-sider bordered :width="260" :collapsed-width="0" collapsible :collapsed="props.collapsed" show-trigger="bar" collapse-mode="width" content-style="display:flex; flex-direction:column" @update:collapsed="emit('update:collapsed', $event)">
    <div class="side-head">
      <div class="head-left">
        <button v-if="props.narrow" class="hamburger" title="收起/展开侧边栏" @click="toggleCollapsed">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
        </button>
        <span class="logo">AI Workbench</span>
      </div>
      <div class="head-actions">
        <n-button size="small" type="primary" @click="newChat" title="新建默认会话">+ 新会话</n-button>
        <n-dropdown
          :options="agentOptions"
          :show="agentMenuOpen"
          trigger="click"
          @update:show="onAgentMenuShow"
          @select="onAgentSelect"
        >
          <n-button size="small" quaternary title="选择 agent 新建会话">▾</n-button>
        </n-dropdown>
      </div>
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
    <div class="search-box">
      <n-input
        v-model:value="searchQuery"
        size="small"
        clearable
        placeholder="搜索会话/消息…"
        @update:value="onSearchInput"
        @clear="clearSearch"
      >
        <template #prefix>🔍</template>
      </n-input>
    </div>
    <div class="session-list">
      <div
        v-for="s in displayed"
        :key="s.id"
        class="session-item"
        :class="{ active: s.id === chat.currentSessionId }"
        @click="openSession(s.id)"
      >
        <span v-if="!viewArchived && s.pinned" class="pin-mark" title="已置顶">📌</span>
        <span class="title" :title="s.agent ? `agent: ${s.agent}` : undefined">{{ s.title }}</span>
        <n-tag v-if="searchActive && s.archived" size="tiny" :bordered="false" class="arch-tag">归档</n-tag>
        <n-tag v-if="s.agent" size="tiny" :bordered="false" class="agent-tag">{{ s.agent }}</n-tag>
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
      <n-spin v-if="searchActive && searching" size="small" style="margin: 24px auto; display: block" />
      <n-empty
        v-else-if="!displayed.length"
        :description="searchActive ? '未找到匹配的会话' : (viewArchived ? '暂无归档会话' : '暂无会话')"
        style="margin-top: 40px"
      />
    </div>
    <div class="side-foot">
      <div class="user-info">
        <n-avatar round size="small">{{ auth.user?.username?.[0]?.toUpperCase() }}</n-avatar>
        <span class="name">{{ auth.user?.username }}</span>
        <n-tag v-if="auth.isAdmin" size="tiny" type="warning">admin</n-tag>
      </div>
      <div class="foot-actions">
        <n-button size="small" quaternary :title="theme.mode === 'dark' ? '切换到浅色主题' : '切换到深色主题'" @click="theme.toggle()">{{ theme.mode === 'dark' ? '☀️' : '🌙' }}</n-button>
        <n-dropdown v-if="auth.isAdmin" :options="adminMenuOptions" trigger="click" placement="top-end" @select="onAdminSelect">
          <n-button size="small" quaternary>⚙️ 管理</n-button>
        </n-dropdown>
        <n-button size="small" quaternary @click="logout">退出</n-button>
      </div>
    </div>

    <n-modal
      v-model:show="renameVisible"
      preset="card"
      title="重命名会话"
      :style="{ width: '380px' }"
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
.side-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 12px; border-bottom: 1px solid var(--border-soft);
}
.head-left { display: flex; align-items: center; gap: 8px; min-width: 0; }
.hamburger {
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; border: 1px solid var(--border); border-radius: var(--radius-sm);
  color: var(--text-3); cursor: pointer; padding: 4px; flex-shrink: 0;
}
.hamburger:hover { background: var(--surface-hover); color: var(--text-2); }
.head-actions { display: flex; align-items: center; gap: 4px; }
.logo {
  font-weight: 700; font-size: 15px;
  background: linear-gradient(135deg, var(--accent-text), #9d8dff);
  -webkit-background-clip: text; background-clip: text; color: transparent;
  letter-spacing: .3px;
}
.agent-tag { flex-shrink: 0; margin-right: 4px; }
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.list-tabs { display: flex; gap: 4px; padding: 10px 12px 8px; }
.list-tabs .tab {
  font-size: 12px; color: var(--text-3); padding: 4px 12px; border-radius: 14px; cursor: pointer;
  transition: background .15s, color .15s;
}
.list-tabs .tab:hover { background: var(--surface-hover); color: var(--text-2); }
.list-tabs .tab.active { background: var(--accent-soft); color: var(--accent-text); font-weight: 600; }
.search-box { padding: 8px 12px 12px; }
.arch-tag { flex-shrink: 0; margin-right: 4px; }
.pin-mark { font-size: 11px; margin-right: 2px; flex-shrink: 0; }
.session-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 9px 12px; border-radius: var(--radius-sm); cursor: pointer; margin-bottom: 2px;
  transition: background .15s;
}
.session-item:hover { background: var(--surface-hover); }
.session-item.active { background: var(--accent-soft); }
.session-item.active .title { color: var(--accent-text); }
.session-item .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; color: var(--text-2); }
.side-foot { border-top: 1px solid var(--border-soft); padding: 10px 12px 12px; }
.user-info { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.user-info .name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-2); }
.foot-actions { display: flex; align-items: center; gap: 4px; justify-content: space-between; }
</style>
