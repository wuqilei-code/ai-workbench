<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useDialog, useMessage } from 'naive-ui'

const router = useRouter()
const chat = useChatStore()
const auth = useAuthStore()
const dialog = useDialog()
const msg = useMessage()

/** 归档视图开关:false=主列表, true=已归档列表 */
const viewArchived = ref(false)
const sessions = computed(() => viewArchived.value ? chat.archivedSessions : chat.sessions)
const agents = computed(() => chat.agents)
const agentMenuOpen = ref(false)

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
  const s = await chat.createSession('New Chat')
  router.push(`/session/${s.id}`)
}

async function openSession(id: number) {
  router.push(`/session/${id}`)
  await chat.selectSession(id)
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

function onExport(s: { id: number; title: string }, format: 'md' | 'json') {
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
      { label: '恢复', key: 'restore' },
      { label: '重命名', key: 'rename' },
      { label: '导出 Markdown', key: 'export-md' },
      { label: '导出 JSON', key: 'export-json' },
      { type: 'divider' as const, key: 'd1' },
      { label: '删除', key: 'delete' },
    ]
  }
  return [
    { label: s.pinned ? '取消置顶' : '置顶', key: 'pin' },
    { label: '重命名', key: 'rename' },
    { label: '导出 Markdown', key: 'export-md' },
    { label: '导出 JSON', key: 'export-json' },
    { type: 'divider' as const, key: 'd1' },
    { label: '归档', key: 'archive' },
    { label: '清空记录', key: 'clear' },
    { label: '删除', key: 'delete' },
  ]
}

function onMenuSelect(key: string, s: { id: number; pinned?: number; title: string }) {
  switch (key) {
    case 'pin': onPin(s as { id: number; pinned: number }); break
    case 'rename': onRename(s); break
    case 'export-md': onExport(s, 'md'); break
    case 'export-json': onExport(s, 'json'); break
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
  <n-layout-sider bordered :width="260" content-style="display:flex; flex-direction:column">
    <div class="side-head">
      <span class="logo">AI Workbench</span>
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
    <div class="session-list">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: s.id === chat.currentSessionId }"
        @click="openSession(s.id)"
      >
        <span v-if="!viewArchived && s.pinned" class="pin-mark" title="已置顶">📌</span>
        <span class="title" :title="s.agent ? `agent: ${s.agent}` : undefined">{{ s.title }}</span>
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
      <n-empty v-if="!sessions.length" :description="viewArchived ? '暂无归档会话' : '暂无会话'" style="margin-top: 40px" />
    </div>
    <div class="side-foot">
      <div class="user-info">
        <n-avatar round size="small">{{ auth.user?.username?.[0]?.toUpperCase() }}</n-avatar>
        <span class="name">{{ auth.user?.username }}</span>
        <n-tag v-if="auth.isAdmin" size="tiny" type="warning">admin</n-tag>
      </div>
      <div class="foot-actions">
        <n-button v-if="auth.isAdmin" size="small" quaternary @click="router.push('/admin/providers')">模型管理</n-button>
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
  padding: 12px; border-bottom: 1px solid #2a2a2e;
}
.head-actions { display: flex; align-items: center; gap: 4px; }
.logo { font-weight: 600; }
.agent-tag { flex-shrink: 0; margin-right: 4px; }
.session-list { flex: 1; overflow-y: auto; padding: 8px; }
.list-tabs { display: flex; gap: 4px; padding: 0 12px 6px; border-bottom: 1px solid #2a2a2e; }
.list-tabs .tab {
  font-size: 12px; color: #999; padding: 3px 10px; border-radius: 12px; cursor: pointer;
  transition: background .15s, color .15s;
}
.list-tabs .tab:hover { background: #26262a; color: #ddd; }
.list-tabs .tab.active { background: #2f2f35; color: #fff; font-weight: 600; }
.pin-mark { font-size: 11px; margin-right: 2px; flex-shrink: 0; }
.session-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; border-radius: 6px; cursor: pointer; margin-bottom: 2px;
}
.session-item:hover { background: #26262a; }
.session-item.active { background: #2f2f35; }
.session-item .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.side-foot { border-top: 1px solid #2a2a2e; padding: 10px 12px; }
.user-info { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.user-info .name { flex: 1; }
.foot-actions { display: flex; gap: 6px; }
</style>
