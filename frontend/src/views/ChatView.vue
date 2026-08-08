<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useMessage } from 'naive-ui'
import ChatMessage from '@/components/ChatMessage.vue'
import AssistantTurn from '@/components/AssistantTurn.vue'
import ChatInput from '@/components/ChatInput.vue'
import ModelSelector from '@/components/ModelSelector.vue'
import FileTree from '@/components/FileTree.vue'
import TaskPanel from '@/components/TaskPanel.vue'
import ChangeSummaryCard from '@/components/ChangeSummaryCard.vue'
import { chatApi } from '@/api/chat'
import type { MessageVo, SkillInfo } from '@/types'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const msg = useMessage()
const scrollRef = ref<HTMLDivElement | null>(null)
const showFiles = ref(false)

// ---------- 技能面板(内置全局技能只读列表)----------

const builtinSkills = ref<SkillInfo[]>([])
const builtinLoading = ref(false)
let _builtinLoaded = false
async function loadBuiltinSkills() {
  if (_builtinLoaded) return
  builtinLoading.value = true
  try {
    builtinSkills.value = await chatApi.builtinSkills()
    _builtinLoaded = true
  } catch (e) {
    console.warn('[skills] load builtin failed', e)
  } finally {
    builtinLoading.value = false
  }
}
watch(showFiles, (v) => { if (v) loadBuiltinSkills() })

// ---------- 会话内查找(Ctrl+F)----------

const findOpen = ref(false)
const findQuery = ref('')
const findIndex = ref(0)
const findInputRef = ref<any>(null)
const flashedTurnId = ref<string | null>(null)
let _flashTimer: ReturnType<typeof setTimeout> | null = null

interface FindMatch { turnId: string; role: 'user' | 'assistant' }

const findMatches = computed<FindMatch[]>(() => {
  const q = findQuery.value.trim().toLowerCase()
  if (!q) return []
  const list: FindMatch[] = []
  for (const t of chat.turns) {
    const cands: { m: MessageVo | null; role: 'user' | 'assistant' }[] = []
    if (t.userMessage) cands.push({ m: t.userMessage, role: 'user' })
    for (const s of t.steps) cands.push({ m: s.message, role: 'assistant' })
    if (t.finalAnswer) cands.push({ m: t.finalAnswer.message, role: 'assistant' })
    const hit = cands.find((c) => (c.m?.content || '').toLowerCase().includes(q))
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
function navigateFind(dir: number) {
  const total = findMatches.value.length
  if (!total) return
  findIndex.value = (findIndex.value + dir + total) % total
  const m = findMatches.value[findIndex.value]
  const el = document.getElementById('find-turn-' + m.turnId)
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  flashedTurnId.value = m.turnId
  if (_flashTimer) clearTimeout(_flashTimer)
  _flashTimer = setTimeout(() => { flashedTurnId.value = null }, 1600)
}

// ---------- 会话统计 ----------

const statsOpen = ref(false)
const stats = computed(() => {
  const msgs = chat.messages
  let user = 0, assistant = 0, tool = 0, chars = 0
  let toolCalls = 0
  for (const m of msgs) {
    if (m.role === 'user') user++
    else if (m.role === 'assistant') assistant++
    else if (m.role === 'tool') tool++
    chars += (m.content || '').length
    toolCalls += m.parts?.toolCalls?.length || 0
  }
  return { total: msgs.length, user, assistant, tool, toolCalls, chars, estTokens: Math.ceil(chars / 2) }
})

// ---------- 全局快捷键 ----------

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
    if (!findOpen.value) { e.preventDefault(); openFind() }
  } else if (e.key === 'Escape') {
    if (findOpen.value) closeFind()
  }
}

/** ---- 会话 agent 切换 ---- */
const agentMenuOpen = ref(false)
const agentOptions = computed(() =>
  chat.agents.map((a) => ({ label: a.name, key: a.name, description: a.description })),
)
async function onAgentMenuShow(show: boolean) {
  if (show) {
    await chat.loadAgents()
    agentMenuOpen.value = true
  } else {
    agentMenuOpen.value = false
  }
}
async function onAgentSelect(key: string) {
  try {
    await chat.switchAgent(key)
    msg.success(`已切换到 agent: ${key}`)
  } catch (e: any) {
    msg.error(e.message || '切换 agent 失败')
  }
}

const sessionId = computed(() => {
  const id = route.params.id
  return id ? Number(id) : null
})

async function ensureSession() {
  if (sessionId.value && sessionId.value !== chat.currentSessionId) {
    await chat.selectSession(sessionId.value)
  }
}

/** 任务面板「来自」点击:关闭工作区抽屉并跳到该会话。 */
function onOpenSession(id: number) {
  showFiles.value = false
  if (sessionId.value !== id) {
    router.push({ name: 'session', params: { id } })
  }
}

onMounted(() => {
  ensureSession()
  window.addEventListener('keydown', onKeydown)
})
watch(sessionId, ensureSession)
onBeforeUnmount(() => {
  chat.closeStream()
  window.removeEventListener('keydown', onKeydown)
})

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
watch(() => chat.messages.length, scrollToBottom)
watch(() => chat.turns.length, scrollToBottom)
watch(() => chat.streamingText, scrollToBottom)

onBeforeUnmount(() => {
  chat.closeStream()
})

/** 当前流式目标消息是否已落入某个 turn(step/finalAnswer) */
const activeTurnExists = computed(() => {
  const id = chat.streamingMessageId
  if (!id) return false
  return chat.turns.some((t) =>
    t.steps.some((s) => s.message.opencodeMessageId === id) ||
    (t.finalAnswer?.message.opencodeMessageId === id),
  )
})

/** 最后一个 turn 是否已有 assistant 内容(避免与"思考中"兜底重复) */
const lastTurnHasContent = computed(() => {
  const list = chat.turns
  if (list.length === 0) return false
  const last = list[list.length - 1]
  return last.steps.length > 0 || !!last.finalAnswer
})

const showStreamingBubble = computed(
  () => chat.isStreaming && !!chat.streamingText && !activeTurnExists.value,
)
const showThinkingFallback = computed(
  () =>
    chat.isStreaming &&
    !chat.streamingText &&
    !activeTurnExists.value &&
    !lastTurnHasContent.value,
)
</script>

<template>
  <div class="chat-view">
    <div class="chat-head">
      <div class="head-left">
        <span class="title">{{ chat.currentSession?.title || 'AI Workbench' }}</span>
        <n-tag :type="chat.connected ? 'success' : (chat.reconnecting ? 'warning' : 'default')" size="tiny" round>
          {{ chat.connected ? '已连接' : (chat.reconnecting ? '重连中…' : '未连接') }}
        </n-tag>
      </div>
      <div class="head-right">
        <n-dropdown
          :options="agentOptions"
          :show="agentMenuOpen"
          trigger="click"
          placement="bottom-end"
          @update:show="onAgentMenuShow"
          @select="onAgentSelect"
        >
          <n-tag size="small" type="info" :bordered="false" style="cursor: pointer" :title="agentOptions.map(o => o.description).join(', ')">
            {{ chat.currentSession?.agent || 'chat' }} ▾
          </n-tag>
        </n-dropdown>
        <ModelSelector />
        <n-button size="small" quaternary title="会话内查找 (Ctrl+F)" @click="openFind">查找</n-button>
        <n-button size="small" quaternary title="会话统计" @click="statsOpen = true">统计</n-button>
        <n-button size="small" quaternary @click="showFiles = !showFiles">工作区</n-button>
      </div>
    </div>

    <!-- 断线横幅:自动重连中,可手动立即重连 -->
    <div v-if="chat.currentSessionId && !chat.connected" class="reconnect-bar">
      <span>连接已断开{{ chat.reconnecting ? '，正在自动重连…' : '' }}</span>
      <n-button size="tiny" type="primary" @click="chat.forceReconnect">立即重连</n-button>
    </div>

    <!-- 会话内查找栏 -->
    <div v-if="findOpen" class="find-bar">
      <n-input
        ref="findInputRef"
        v-model:value="findQuery"
        size="small"
        clearable
        placeholder="在本会话中查找…"
        @update:value="() => { findIndex = 0 }"
        @keyup.enter="navigateFind(1)"
      >
        <template #prefix>🔍</template>
        <template #suffix>
          <span class="find-count">{{ findMatches.length ? findIndex + 1 + ' / ' + findMatches.length : 0 + ' / 0' }}</span>
        </template>
      </n-input>
      <n-button size="small" quaternary title="上一个 (Shift+Enter)" @click="navigateFind(-1)">▲</n-button>
      <n-button size="small" quaternary title="下一个 (Enter)" @click="navigateFind(1)">▼</n-button>
      <n-button size="small" quaternary title="关闭 (Esc)" @click="closeFind">✕</n-button>
    </div>

    <div class="chat-body" ref="scrollRef">
      <template v-if="chat.currentSessionId">
        <div
          v-for="item in chat.flow"
          :key="item.kind === 'turn' ? 't-' + item.turn.id : 's-' + (item.summary.sha || '') + '-' + item.summary.at + '-' + item.summary.summary.length"
          class="flow-item"
          :id="item.kind === 'turn' ? 'find-turn-' + item.turn.id : undefined"
          :class="{ 'find-flash': item.kind === 'turn' && flashedTurnId === item.turn.id }"
        >
          <template v-if="item.kind === 'turn'">
            <ChatMessage v-if="item.turn.userMessage" :message="item.turn.userMessage" />
            <AssistantTurn v-if="item.turn.finalAnswer || item.turn.steps.length" :turn="item.turn" :session-id="chat.currentSessionId" />
          </template>
          <ChangeSummaryCard v-else :summary="item.summary" />
        </div>
        <!-- 流式兜底:token 已到但 meta 尚未并入 turn -->
        <ChatMessage
          v-if="showStreamingBubble"
          :message="{ id: -1, role: 'assistant', content: chat.streamingText, status: 'streaming' }"
          :streaming="true"
        />
        <div v-if="showThinkingFallback" class="thinking">
          <n-spin size="small" /> AI 思考中…
        </div>
      </template>
      <n-empty v-else description="选择左侧会话或点击「新会话」开始" style="margin-top: 80px" />
    </div>

    <ChatInput v-if="chat.currentSessionId" />

    <!-- 会话统计 -->
    <n-modal
      v-model:show="statsOpen"
      preset="card"
      title="会话统计"
      :style="{ width: '440px' }"
      :bordered="false"
    >
      <div class="stats-grid">
        <div class="stat"><span class="k">总消息数</span><span class="v">{{ stats.total }}</span></div>
        <div class="stat"><span class="k">用户消息</span><span class="v">{{ stats.user }}</span></div>
        <div class="stat"><span class="k">AI 消息</span><span class="v">{{ stats.assistant }}</span></div>
        <div class="stat"><span class="k">工具消息</span><span class="v">{{ stats.tool }}</span></div>
        <div class="stat"><span class="k">工具调用次数</span><span class="v">{{ stats.toolCalls }}</span></div>
        <div class="stat"><span class="k">总字符数</span><span class="v">{{ stats.chars }}</span></div>
        <div class="stat"><span class="k">约计 token</span><span class="v">{{ stats.estTokens }}</span></div>
      </div>
      <p class="stats-note">token 数为粗略估算（总字符数 / 2），仅供参考。</p>
    </n-modal>

    <n-drawer v-model:show="showFiles" :width="440" placement="right">
      <n-drawer-content title="工作区" closable>
        <n-tabs type="line" animated size="small">
          <n-tab-pane name="files" tab="文件">
            <FileTree :changed-paths="Array.from(chat.changedFilePaths)" />
          </n-tab-pane>
            <n-tab-pane name="skills" tab="技能">
              <n-collapse :default-expanded-names="[]" :bordered="false" class="builtin-collapse">
                <n-collapse-item name="builtin" title="内置技能">
                  <template #header-extra>
                    <span class="builtin-note">{{ builtinLoading ? '加载中…' : builtinSkills.length + ' 个 · 随系统内置,只读' }}</span>
                  </template>
                  <div v-if="builtinLoading" class="builtin-empty">加载中…</div>
                  <div v-else-if="builtinSkills.length" class="builtin-list">
                    <div v-for="s in builtinSkills" :key="s.name" class="builtin-item">
                      <span class="builtin-name">{{ s.name }}</span>
                      <span class="builtin-desc">{{ s.description }}</span>
                    </div>
                  </div>
                  <div v-else class="builtin-empty">暂无内置技能</div>
                </n-collapse-item>
              </n-collapse>
              <n-divider style="margin: 10px 0" />
              <FileTree root=".opencode/skills" skills-mode :changed-paths="Array.from(chat.changedFilePaths)" />
            </n-tab-pane>
            <n-tab-pane name="tasks" tab="任务">
              <TaskPanel @open-session="onOpenSession" />
            </n-tab-pane>
        </n-tabs>
      </n-drawer-content>
    </n-drawer>
  </div>
</template>

<style scoped>
.chat-view { display: flex; flex-direction: column; height: 100vh; }
.chat-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 16px; border-bottom: 1px solid #2a2a2e; background: #1a1a1e;
}
.head-left { display: flex; align-items: center; gap: 10px; }
.head-left .title { font-weight: 600; }
.head-right { display: flex; align-items: center; gap: 8px; }
.chat-body { flex: 1; overflow-y: auto; padding: 20px; }
.thinking { display: flex; align-items: center; gap: 8px; color: #888; padding: 8px 0; }

/* 断线横幅 */
.reconnect-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 6px 16px; font-size: 13px; color: #e8c547;
  background: #3a3320; border-bottom: 1px solid #4a3d1f;
}

/* 会话内查找栏 */
.find-bar {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 16px; border-bottom: 1px solid #2a2a2e; background: #1e1e22;
}
.find-bar .n-input { flex: 1; }
.find-count { color: #888; font-size: 12px; padding: 0 4px; }

/* 查找跳转目标:命中轮次短暂高亮 */
.flow-item { border-radius: 8px; }
.flow-item.find-flash {
  outline: 2px solid #e8c547;
  outline-offset: 4px;
  animation: find-flash 1.6s ease-out;
}
@keyframes find-flash {
  0% { background: rgba(232, 197, 71, 0.25); }
  100% { background: transparent; }
}

/* 会话统计 */
.stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.stat {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 12px 8px; border-radius: 8px; background: #202024; border: 1px solid #2a2a2e;
}
.stat .k { font-size: 12px; color: #888; }
.stat .v { font-size: 20px; font-weight: 600; }
.stats-note { color: #888; font-size: 12px; margin-top: 12px; }

/* 内置技能列表(技能 tab 顶部,默认折叠、只读) */
.builtin-collapse { margin-bottom: 2px; }
.builtin-note { font-size: 11px; color: #888; margin-left: 8px; }
.builtin-empty { color: #888; font-size: 12px; padding: 8px 0; }
.builtin-list { display: flex; flex-direction: column; gap: 6px; }
.builtin-item {
  display: flex; flex-direction: column; gap: 2px;
  padding: 8px 10px; border-radius: 6px; background: #202024; border: 1px solid #2a2a2e;
}
.builtin-name { font-size: 12px; font-weight: 600; color: #e8c547; }
.builtin-desc {
  font-size: 12px; color: #aaa; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
</style>
