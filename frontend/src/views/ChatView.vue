<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useMessage } from 'naive-ui'
import ChatMessage from '@/components/ChatMessage.vue'
import AssistantTurn from '@/components/AssistantTurn.vue'
import ChatInput from '@/components/ChatInput.vue'
import WelcomeBoard from '@/components/WelcomeBoard.vue'
import TemplateLibrary from '@/components/TemplateLibrary.vue'
import DocumentGenerator from '@/components/DocumentGenerator.vue'
import ErrorDiagnose from '@/components/ErrorDiagnose.vue'
import FileTree from '@/components/FileTree.vue'
import TaskPanel from '@/components/TaskPanel.vue'
import ChangeSummaryCard from '@/components/ChangeSummaryCard.vue'
import { chatApi } from '@/api/chat'
import type { MessageVo, SkillInfo } from '@/types'
import type { VirtualListInst } from 'naive-ui'
import type { FlowItem, SummaryItem, Turn } from '@/utils/turns'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const msg = useMessage()
const virtualListRef = ref<VirtualListInst | null>(null)
const showFiles = ref(true)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)
const templateLibShow = ref(false)
const docGenShow = ref(false)
const errorDiagnoseShow = ref(false)

/** 空会话判断:会话已建但尚无任何消息(turn 为空)。 */
const isNewSession = computed(() => chat.currentSessionId != null && chat.turns.length === 0)

/** 欢迎板/模板库点击:把模板填入输入框供用户补充后发送。 */
function onScenarioSelect(prompt: string) {
  chatInputRef.value?.fill(prompt)
}
function onTemplateUse(prompt: string) {
  chatInputRef.value?.fill(prompt)
}
/** 文档生成:把拼好的提示词填入输入框供确认后发送。 */
function onDocGenerate(prompt: string) {
  chatInputRef.value?.fill(prompt)
}
/** 报错解读:同上,填入输入框供确认后发送。 */
function onErrorGenerate(prompt: string) {
  chatInputRef.value?.fill(prompt)
}

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

/** 滚动虚拟列表到指定 turn 并短暂高亮(虚拟列表只渲染可视项,须按 key 定位)。 */
function locateTurn(turnId: string) {
  const vl = virtualListRef.value
  if (!vl) return
  const idx = virtualItems.value.findIndex(
    (it) => it.kind === 'turn' && it.turn.id === turnId,
  )
  if (idx >= 0) vl.scrollTo({ index: idx, position: 'top' })
  flashedTurnId.value = turnId
  if (_flashTimer) clearTimeout(_flashTimer)
  _flashTimer = setTimeout(() => { flashedTurnId.value = null }, 1600)
}

function navigateFind(dir: number) {
  const total = findMatches.value.length
  if (!total) return
  findIndex.value = (findIndex.value + dir + total) % total
  locateTurn(findMatches.value[findIndex.value].turnId)
}

// ---------- 全局快捷键 ----------

function onKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'f') {
    if (!findOpen.value) { e.preventDefault(); openFind() }
  } else if (e.key === 'Escape') {
    if (findOpen.value) closeFind()
  }
}

/** ---- 会话统计 ---------- */

const sessionId = computed(() => {
  const id = route.params.id
  return id ? Number(id) : null
})

async function ensureSession() {
  if (sessionId.value && sessionId.value !== chat.currentSessionId) {
    await chat.selectSession(sessionId.value)
  }
}

/** 任务面板「来源会话」点击:关抽屉、切会话并滚动高亮触发该任务的那条用户消息。 */
async function onOpenSession(target: { sessionId: number; messageId?: number }) {
  showFiles.value = false
  if (sessionId.value !== target.sessionId) {
    await router.push({ name: 'session', params: { id: target.sessionId } })
  }
  await flashToMessage(target.messageId)
}

/** 定位到指定用户消息所在 turn 并闪亮(消息加载是异步的,轮询等待)。 */
async function flashToMessage(messageId?: number) {
  if (!messageId) return
  const flash = (turnId: string) => {
    locateTurn(turnId)
  }
  for (let i = 0; i < 40; i++) {
    const turn = chat.turns.find((t) => t.userMessage?.id === messageId)
    if (turn) {
      flash(turn.id)
      return
    }
    await new Promise((r) => setTimeout(r, 200))
  }
}

onMounted(() => {
  ensureSession()
  window.addEventListener('keydown', onKeydown)
})
watch(sessionId, () => {
  stickToBottom.value = true
  ensureSession()
})
onBeforeUnmount(() => {
  chat.closeStream()
  window.removeEventListener('keydown', onKeydown)
})

function scrollToBottom() {
  nextTick(() => {
    const vl = virtualListRef.value
    if (!vl) return
    if (!virtualItems.value.length) return
    // 直接滚到内容底部,不依赖 item 高度实测估算(流式高度未同步时按 index 定位会抖动)
    vl.scrollTo({ position: 'bottom' })
  })
}
watch(() => chat.messages.length, scrollToBottom)
watch(() => chat.turns.length, scrollToBottom)

/** 用户是否停留在底部区域(阈值内视为贴底)。贴底时流式输出自动跟随;上翻阅读时不被拉回。 */
const stickToBottom = ref(true)
function handleVirtualScroll() {
  const el = virtualListRef.value?.getScrollContainer?.()
  if (!el) return
  stickToBottom.value = el.scrollTop + el.clientHeight >= el.scrollHeight - 120
}
// 仅当贴底时才在流式时跟随底部,交给 vueuc 内置 ResizeObserver 跟随(避免与强制滚动竞争抖动)
watch(() => chat.streamingText, () => {
  if (stickToBottom.value) scrollToBottom()
})

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

// ---------- 虚拟列表 ----------

/** 虚拟列表每项的 key:turn 用 turn.id,summary 用 sha/at/长度(与原 v-for key 一致) */
type VirtualListItem =
  | { key: string; kind: 'turn'; turn: Turn }
  | { key: string; kind: 'summary'; summary: SummaryItem }
  | { key: string; kind: 'streaming'; text: string }
  | { key: string; kind: 'thinking' }

const virtualItems = computed<VirtualListItem[]>(() => {
  const items: VirtualListItem[] = chat.flow.map((it) => {
    if (it.kind === 'turn') return { key: 't-' + it.turn.id, kind: 'turn', turn: it.turn }
    const s = it.summary
    return {
      key: 's-' + (s.sha || '') + '-' + (s.at || '') + '-' + s.summary.length,
      kind: 'summary',
      summary: s,
    }
  })
  if (showStreamingBubble.value) {
    items.push({ key: '__streaming__', kind: 'streaming', text: chat.streamingText })
  } else if (showThinkingFallback.value) {
    items.push({ key: '__thinking__', kind: 'thinking' })
  }
  return items
})

/** 虚拟列表预估值(px);itemResizable 开启后由 ResizeObserver 实测每项高度修正 */
const itemSize = 120
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
        <n-button size="small" quaternary title="会话内查找 (Ctrl+F)" @click="openFind">查找</n-button>
        <n-button size="small" quaternary title="提示词模板库" @click="templateLibShow = true">模板库</n-button>
        <n-button size="small" quaternary title="一键生成文档(周报/会议纪要/邮件/分析报告)" @click="docGenShow = true">生成文档</n-button>
        <n-button size="small" quaternary title="粘贴报错信息,让 AI 解读" @click="errorDiagnoseShow = true">解读报错</n-button>
        <n-button size="small" quaternary @click="showFiles = !showFiles">工作区</n-button>
      </div>
    </div>

    <!-- 断线横幅:意外断线时显示;切换会话/首次连接中的主动断开不算断线,不打扰 -->
    <div v-if="chat.currentSessionId && !chat.connected && chat.reconnecting" class="reconnect-bar">
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

    <div class="chat-main">
      <div class="chat-col">
        <div class="chat-body">
          <template v-if="chat.currentSessionId && !isNewSession">
            <n-virtual-list
              ref="virtualListRef"
              class="chat-virtual"
              :items="virtualItems"
              :item-size="itemSize"
              item-resizable
              key-field="key"
              :items-style="{ padding: '24px 20px' }"
              @scroll="handleVirtualScroll"
            >
              <template #default="{ item }">
                <div
                  v-if="item.kind === 'turn'"
                  class="flow-item"
                  :id="'find-turn-' + item.turn.id"
                  :class="{ 'find-flash': flashedTurnId === item.turn.id }"
                >
                  <ChatMessage v-if="item.turn.userMessage" :message="item.turn.userMessage" />
                  <AssistantTurn v-if="item.turn.finalAnswer || item.turn.steps.length" :turn="item.turn" :session-id="chat.currentSessionId" />
                </div>
                <ChangeSummaryCard v-else-if="item.kind === 'summary'" :summary="item.summary" />
                <ChatMessage
                  v-else-if="item.kind === 'streaming'"
                  :message="{ id: -1, role: 'assistant', content: item.text, status: 'streaming' }"
                  :streaming="true"
                />
                <div v-else-if="item.kind === 'thinking'" class="thinking">
                  <n-spin size="small" /> AI 思考中…
                </div>
              </template>
            </n-virtual-list>
          </template>
          <WelcomeBoard v-else-if="chat.currentSessionId" @select="onScenarioSelect" />
          <n-empty v-else description="选择左侧会话或点击「新会话」开始" style="margin-top: 80px" />
        </div>

        <ChatInput v-if="chat.currentSessionId" ref="chatInputRef" />
      </div>

      <!-- 工作区:常驻右侧面板,可向右收起 -->
      <div class="workspace-panel" :class="{ 'workspace-collapsed': !showFiles }">
        <div class="workspace-body" v-if="showFiles">
          <div class="workspace-head">
            <span class="workspace-title">工作区</span>
            <n-button size="small" quaternary class="workspace-collapse-btn" title="收起工作区" @click="showFiles = false">
              <span class="ws-arrow">»</span>
            </n-button>
          </div>
          <div class="workspace-tabs">
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
          </div>
        </div>
        <!-- 收起态:右侧竖条,点击展开 -->
        <div v-else class="workspace-collapsed-tab" title="展开工作区" @click="showFiles = true">
          <span class="ws-vertical-text">工作区</span>
        </div>
      </div>
    </div>
  </div>

  <TemplateLibrary
    v-model:show="templateLibShow"
    @use="onTemplateUse"
  />
  <DocumentGenerator
    v-model:show="docGenShow"
    @generate="onDocGenerate"
  />
  <ErrorDiagnose
    v-model:show="errorDiagnoseShow"
    @generate="onErrorGenerate"
  />
</template>

<style scoped>
.chat-view { display: flex; flex-direction: column; height: 100vh; }
.chat-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 18px; border-bottom: 1px solid var(--border-soft);
  background: var(--bg-elev);
}
.head-left { display: flex; align-items: center; gap: 10px; }
.head-left .title { font-weight: 600; color: var(--text); }
.head-right { display: flex; align-items: center; gap: 8px; }

/* 左右两栏:左=对话区(自适应),右=工作区(固定宽,可收起) */
.chat-main { flex: 1; display: flex; min-height: 0; }
.chat-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-body { flex: 1; overflow: hidden; min-height: 0; position: relative; }
.chat-virtual { height: 100%; }
.thinking { display: flex; align-items: center; gap: 8px; color: var(--text-3); padding: 8px 0; }

/* 工作区面板 */
.workspace-panel {
  width: 440px; flex-shrink: 0;
  display: flex; flex-direction: column;
  border-left: 1px solid var(--border-soft); background: var(--bg-elev);
  transition: width .18s ease;
}
.workspace-panel.workspace-collapsed { width: 24px; }
.workspace-body { flex: 1; display: flex; flex-direction: column; min-height: 0; overflow: hidden; }
.workspace-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 10px 10px 14px; border-bottom: 1px solid var(--border-soft); flex-shrink: 0;
}
.workspace-title { font-size: 14px; font-weight: 600; color: var(--text); }
.workspace-collapse-btn { color: var(--text-3); }
.workspace-collapse-btn:hover { color: var(--accent-text); }
.ws-arrow { font-size: 14px; }
.workspace-tabs { flex: 1; min-height: 0; overflow-y: auto; padding: 4px 10px; }

/* 收起态竖条:右侧显示「工作区」竖排文字 */
.workspace-collapsed-tab {
  flex: 1; display: flex; align-items: center; justify-content: center;
  cursor: pointer; user-select: none; color: var(--text-3);
  border-left: 1px solid var(--border-soft); background: var(--bg-elev);
  transition: color .15s, background .15s;
}
.workspace-collapsed-tab:hover { color: var(--accent-text); background: var(--surface-hover); }
.ws-vertical-text { writing-mode: vertical-rl; font-size: 13px; letter-spacing: 3px; }

/* 断线横幅 */
.reconnect-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 18px; font-size: 13px; color: var(--warn);
  background: rgba(232, 178, 107, 0.08); border-bottom: 1px solid rgba(232, 178, 107, 0.2);
}

/* 会话内查找栏 */
.find-bar {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 18px; border-bottom: 1px solid var(--border-soft); background: var(--bg-elev);
}
.find-bar .n-input { flex: 1; }
.find-count { color: var(--text-3); font-size: 12px; padding: 0 4px; }

/* 查找跳转目标:命中轮次短暂高亮 */
.flow-item { border-radius: 10px; }
.flow-item.find-flash {
  outline: 2px solid var(--warn);
  outline-offset: 4px;
  animation: find-flash 1.6s ease-out;
}
@keyframes find-flash {
  0% { background: rgba(232, 178, 107, 0.18); }
  100% { background: transparent; }
}

/* 内置技能列表(技能 tab 顶部,默认折叠、只读) */
.builtin-collapse { margin-bottom: 2px; }
.builtin-note { font-size: 11px; color: var(--text-3); margin-left: 8px; }
.builtin-empty { color: var(--text-3); font-size: 12px; padding: 8px 0; }
.builtin-list { display: flex; flex-direction: column; gap: 6px; }
.builtin-item {
  display: flex; flex-direction: column; gap: 2px;
  padding: 8px 10px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.builtin-name { font-size: 12px; font-weight: 600; color: var(--accent-text); }
.builtin-desc {
  font-size: 12px; color: var(--text-2); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
</style>
