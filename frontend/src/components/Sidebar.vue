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

const sessions = computed(() => chat.sessions)
const agents = computed(() => chat.agents)
const agentMenuOpen = ref(false)

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
    <div class="session-list">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: s.id === chat.currentSessionId }"
        @click="openSession(s.id)"
      >
        <span class="title" :title="s.agent ? `agent: ${s.agent}` : undefined">{{ s.title }}</span>
        <n-tag v-if="s.agent" size="tiny" :bordered="false" class="agent-tag">{{ s.agent }}</n-tag>
        <n-button quaternary circle size="tiny" @click.stop="remove(s.id)">✕</n-button>
      </div>
      <n-empty v-if="!sessions.length" description="暂无会话" style="margin-top: 40px" />
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
