<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed, inject, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useFavoritesStore } from '@/stores/favorites'
import { useMessage, useDialog } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import ChatMessage from '@/components/ChatMessage.vue'
import AssistantTurn from '@/components/AssistantTurn.vue'
import ChatInput from '@/components/ChatInput.vue'
import TaskPlan from '@/components/TaskPlan.vue'
import WelcomeBoard from '@/components/WelcomeBoard.vue'
import { useSessionFind } from '@/composables/useSessionFind'
import { useSessionNav } from '@/composables/useSessionNav'
import { useWorkspacePanel } from '@/composables/useWorkspacePanel'
import { useChatScroll } from '@/composables/useChatScroll'
import { useManagePages } from '@/composables/useManagePages'
import { useVirtualList } from '@/composables/useVirtualList'
import { useVirtualWindow } from '@/composables/useVirtualWindow'
import TemplateLibrary from '@/components/TemplateLibrary.vue'
import FavoritesPanel from '@/components/FavoritesPanel.vue'
import SkillMarketplace from '@/components/SkillMarketplace.vue'
import SkillsPanel from '@/components/SkillsPanel.vue'
import SessionPickers from '@/components/SessionPickers.vue'
import FileTree from '@/components/FileTree.vue'
import AutomationManager from '@/components/AutomationManager.vue'
import McpManager from '@/components/McpManager.vue'
import JobRunsPanel from '@/components/JobRunsPanel.vue'
import NotificationCenter from '@/components/NotificationCenter.vue'
import UserMenu from '@/components/UserMenu.vue'
import BrowserCanvas from '@/components/BrowserCanvas.vue'
import DashboardView from '@/views/DashboardView.vue'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const fav = useFavoritesStore()
const msg = useMessage()
const dialog = useDialog()
const { t } = useI18n()
const scrollRef = ref<HTMLElement | null>(null)
/** 消息整体内容的包装层(chat-virtual-container):其高度随所有消息撑高而增长。
 *  ResizeObserver 监听它而非某条具体消息,才能在异步加载/流式撑高时正确钉底。 */
const contentRef = ref<HTMLElement | null>(null)
let _scrollObsCleanup: (() => void) | null = null

const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

/** turn id → 窗口化列表的行 key(与 useVirtualList 的 `'t-' + turn.id` 规则保持一致) */
function turnKeyOf(turnId: string) {
  return 't-' + turnId
}
/** 空会话主页里的输入框实例(与聊天态底部输入框二选一) */
const homeInputRef = ref<InstanceType<typeof ChatInput> | null>(null)
const marketplaceShow = ref(false)
// 技能面板(内置/我的/市场)已抽到 components/SkillsPanel.vue
// 会话技能/工具选择弹窗已抽到 components/SessionPickers.vue
/** 工具库弹窗(点侧边栏「工具库」打开:Server 增/删/改/启停) */
const mcpShow = ref(false)

/** 会话技能/工具选择弹窗(子组件暴露 openSkills/openMcp) */
const sessionPickers = ref<InstanceType<typeof SessionPickers> | null>(null)
function openSkillPicker() { sessionPickers.value?.openSkills() }
function openMcpPicker() { sessionPickers.value?.openMcp() }

/** 技能安装回调(技能面板挂载时自取最新列表,父级无需刷新) */
function onSkillInstalled() {}

/** 侧边栏导航事件监听 */
const sidebarEvents = inject<Ref<import('@/composables/useManagePages').SidebarEvents>>('sidebarEvents')

// 草稿态首次发送创建会话后,把地址同步为 /session/:id(便于刷新/分享)
watch(() => chat.currentSessionId, (id) => {
  if (id == null) return
  if (String(route.params.id || '') !== String(id)) router.replace(`/session/${id}`)
})

/** 侧边栏折叠状态(由 ChatLayout provide):顶部「展开侧边栏」按钮使用 */
const sidebarCollapsed = inject<Ref<boolean>>('sidebarCollapsed', ref(false))
const toggleSidebar = inject<() => void>('toggleSidebar', () => {})
/** 是否移动端(由 ChatLayout provide):工作区改为底部抽屉、顶栏精简 */
const isMobile = inject<Ref<boolean>>('isMobile', ref(false))

/** 右侧工作区面板(文件/技能/定时任务/收藏/浏览器)的开合、标签与拖拽 */
const {
  showFiles,
  activeTab,
  wsWidth,
  wsResizing,
  workspaceTabs,
  openTabList,
  browserFollowing,
  openWorkspaceTab,
  closeTab,
  onLauncherClick,
  startResize,
  dispose: disposeWorkspacePanel,
} = useWorkspacePanel({
  isMobile,
  // 打开工作区面板时关闭整页管理视图(技能市场/工具库/模板库)
  onBeforeOpen: () => {
    skillsPageShow.value = false
    toolsPageShow.value = false
    templateLibShow.value = false
  },
})

// 移动端:工作区默认收起为底部抽屉
watch(isMobile, (m) => { if (m) showFiles.value = false }, { immediate: true })

/** 空会话判断:会话已建但尚无任何消息(turn 为空)。 */
const isNewSession = computed(() => chat.currentSessionId != null && chat.turns.length === 0)

/** 当前活跃的输入框实例:空会话主页 or 聊天态底部 */
function activeInput() {
  return chatInputRef.value || homeInputRef.value
}

/** 欢迎板/模板库点击:把模板填入输入框供用户补充后发送。 */
function onScenarioSelect(prompt: string) {
  const el = activeInput()
  if (el) el.fill(prompt)
  else chat.pendingFill = prompt
}
function onTemplateUse(prompt: string) {
  // 整页管理视图打开时聊天输入框被卸载 → 兜底写入 store,挂载后再消费
  const el = activeInput()
  if (el) el.fill(prompt)
  else chat.pendingFill = prompt
}

/** 「做同款」(HTML 文档模板):把模板 HTML 落到工作区 templates/,作为附件引用到对话并回填提示词 */
async function onTemplateUseHtml(p: { title: string; content: string }) {
  const safe = (p.title || 'template').replace(/[\\/:*?"<>|]/g, '_').slice(0, 60) || 'template'
  const path = `templates/${safe}.html`
  try {
    await fileApi.write(path, p.content || '')
  } catch { /* 写盘失败也继续:仅回填提示词,不阻塞 */ }
  const prompt = `请参考工作区里的 HTML 模板附件《${p.title}》,生成一份同款文档:替换其中的 {{占位}} 为合适内容,整体沿用其排版风格。`
  // replacePrefix:多次「做同款」只保留最新模板的附件,避免叠加引用
  chat.attachFile(path, prompt, 'templates/')
  closeManagePages()
}

/** 文件树右键「问 AI」:把文件挂到输入框附件,并填入引导提示词,用户补充后发送 */
function onAskAIFile(path: string) {
  const el = activeInput()
  if (!el) return
  el.attachFilePath(path)
  el.fill(`请看看工作区里的这个文件:${path}\n\n我需要你:`)
}

/** 侧边栏「测试分析」入口:填入测试提示词,引导用户上传项目 zip 后发送 */
function onTestAnalysis() {
  const el = activeInput()
  if (el) {
    el.fill(
      '我上传了一个项目源码压缩包,请用 test-analysis 技能对源码做静态分析:①生成测试点清单 ②生成测试用例 ③生成测试脚本(通过语法校验) ④输出测试报告。沙箱无法安装依赖,不要运行项目,只做静态分析与语法校验。',
    )
  } else {
    chat.pendingFill = '我上传了一个项目源码压缩包,请用 test-analysis 技能对源码做静态分析:①生成测试点清单 ②生成测试用例 ③生成测试脚本(通过语法校验) ④输出测试报告。沙箱无法安装依赖,不要运行项目,只做静态分析与语法校验。'
  }
}

// ---------- 会话内查找(Ctrl+F)----------

// ---------- 会话内查找(Ctrl+F)----------

const { findOpen, findQuery, findIndex, findInputRef, flashedTurnId, findMatches, openFind, closeFind, locateTurn, navigateFind } =
  // 第二个参数:窗口化下列表按偏移定位(目标行未渲染时 DOM 里没有元素)
  useSessionFind(() => chat.turns, (turnId) => windowScrollToKey(turnKeyOf(turnId), 'center'))

/** 打开「自动化」:整页展示任务列表(再次点击收起) */
function openAutomationModal() {
  openManagePage('automation')
}
/** 打开驾驶舱(整页,再次点击收起) */
function openDashboard() {
  openManagePage('dashboard')
}

/** 跳转到工作区「定时任务」tab(消息中心点击后查看执行记录/报告)。 */
function openJobTab() {
  openWorkspaceTab('jobs')
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

/** 整页管理视图(技能市场/工具库/模板库/驾驶舱/自动化)互斥开合 + 侧边栏联动 */
const {
  templateLibShow,
  dashboardShow,
  automationShow,
  skillsPageShow,
  toolsPageShow,
  managePageActive,
  closeManagePages,
  openManagePage,
} = useManagePages({
  sidebarEvents,
  onOpenFiles: () => { showFiles.value = true },
  onOpenFind: () => openFind(),
  sessionId,
})

async function ensureSession() {
  if (sessionId.value && sessionId.value !== chat.currentSessionId) {
    await chat.selectSession(sessionId.value)
  }
}

/** 会话跳转与消息定位(任务面板/收藏面板 → 定位并高亮) */
const { locating, openSessionFromTask, openSessionFromFavorite } = useSessionNav({
  chat,
  router,
  sessionId,
  locateTurn,
  onBeforeNav: () => { showFiles.value = false },
})

onMounted(() => {
  ensureSession()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('aiwb:test-analysis', onTestAnalysis)
  // 内容高度变化(组件/图片 iframe 异步加载撑高)时贴底跟随:逻辑见 useChatScroll
  nextTick(() => {
    _scrollObsCleanup = observeContentGrowth(scrollRef.value, contentRef.value)
  })
})
watch(() => chat.sessions, (list) => {
  if (list.length) fav.refreshSessionTitles(list)
}, { deep: true })
watch(sessionId, () => {
  stickToBottom.value = true
  ensureSession()
})
onBeforeUnmount(() => {
  chat.closeStream()
  disposeWorkspacePanel()
  _scrollObsCleanup?.()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('aiwb:test-analysis', onTestAnalysis)
})

/** 虚拟列表渲染序列 + 流式/思考占位判定(逻辑见 composables/useVirtualList) */
const { virtualItems, showStreamingBubble, showThinkingFallback } = useVirtualList(chat)

/** 消息列表滚动/钉底/上翻加载(逻辑见 composables/useChatScroll) */
const {
  stickToBottom,
  activePlan,
  scrollToBottom,
  handleVirtualScroll,
  observeContentGrowth,
} = useChatScroll(chat, scrollRef, virtualItems)

/**
 * 变高列表窗口化:只挂载视口附近的行(长会话不再把全部消息塞进 DOM)。
 * 条数 ≤ 阈值时整渲染,短会话行为与之前完全一致。
 * 注意:返回值必须解构成顶层 ref,模板才能自动解包(嵌套在对象里的 ref 不会)。
 */
const {
  rows: vwinRows,
  topSpacer: vwinTopSpacer,
  bottomSpacer: vwinBottomSpacer,
  setRowRef: setWindowRowRef,
  syncViewport: syncWindowViewport,
  scrollToKey: windowScrollToKey,
  reset: resetWindowHeights,
  pruneHeights: pruneWindowHeights,
} = useVirtualWindow(scrollRef, virtualItems, {
  threshold: 40,
  overscan: 6,
  estimate: 240,
  isPinnedToBottom: () => stickToBottom.value,
})

/** 滚动事件:同步窗口化视口几何 + 原有钉底/上翻加载逻辑 */
function onListScroll() {
  syncWindowViewport()
  handleVirtualScroll()
  pruneWindowHeights()
}

// 会话切换:高度缓存与视口几何需重置(不同会话的 turn 完全不同)
watch(sessionId, () => resetWindowHeights())
watch(() => chat.currentSessionId, () => nextTick(() => syncWindowViewport()))
onBeforeUnmount(() => resetWindowHeights())

/** 普通滚动容器(非虚拟化):消息整体渲染,滚动行为与原生一致,避免虚拟列表对变高组件条的估算补偿 */
</script>

<template>
  <div class="chat-view">
    <div class="chat-head">
      <div class="head-left">
        <n-button v-if="sidebarCollapsed || isMobile" size="small" quaternary :title="t('chatview.expandSidebar')" :aria-label="t('chatview.expandSidebar')" @click="toggleSidebar()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/><path d="m14 9 3 3-3 3"/></svg>
        </n-button>
        <span v-if="!managePageActive && chat.currentSessionId" class="title">{{ chat.currentSession?.title || t('chatview.newChatTitle') }}</span>
        <span v-if="!managePageActive" class="conn-badge" :class="{ ok: chat.connected, warn: chat.reconnecting }">
          <span class="conn-dot"></span>
          {{ chat.connected ? t('chatview.connOk') : (chat.reconnecting ? t('chatview.connReconnecting') : t('chatview.connNone')) }}
        </span>
        <n-button v-if="!managePageActive && chat.currentSessionId" class="find-btn" size="small" quaternary :title="t('chatview.findTitle')" :aria-label="t('chatview.findTitle')" @click="openFind">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <span class="btn-label">{{ t('chatview.findBtn') }}</span>
        </n-button>
      </div>
      <div class="head-right">
        <!-- 顶部面板入口:与用户信息同区,点击打开/切换;再次点击已激活的面板则收起工作区 -->
        <div v-if="!managePageActive" class="ws-launcher">
          <n-button
            v-for="tab in workspaceTabs"
            :key="tab.key"
            size="small"
            quaternary
            :type="showFiles && activeTab === tab.key ? 'primary' : 'default'"
            :title="t('workspace.' + tab.key)"
            @click="onLauncherClick(tab.key)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="tab.icon"/></svg>
            <span class="ws-launcher-label">{{ t('workspace.' + tab.key) }}</span>
          </n-button>
        </div>
        <n-button v-if="!managePageActive" size="small" quaternary :title="showFiles ? t('chatview.collapseWs') : t('chatview.expandWs')" :aria-label="showFiles ? t('chatview.collapseWs') : t('chatview.expandWs')" @click="showFiles = !showFiles">
          <svg v-if="showFiles" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/><path d="m8 9 3 3-3 3"/></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/><path d="m8 15 3-3-3-3"/></svg>
        </n-button>
        <NotificationCenter />
        <UserMenu />
      </div>
    </div>

    <!-- 登录过期横幅:token 失效后 SSE 停止重连,提示重新登录而非无限重连 -->
    <div v-if="chat.authExpired" class="auth-expired-bar">
          <span>{{ t('chatview.loginExpired') }}</span>
          <n-button size="tiny" type="primary" @click="router.push('/login')">{{ t('chatview.relogin') }}</n-button>
    </div>

    <!-- 断线横幅:意外断线时显示;切换会话/首次连接中的主动断开不算断线,不打扰 -->
    <div v-if="chat.currentSessionId && !chat.connected && chat.reconnecting && !chat.authExpired" class="reconnect-bar">
          <span>{{ t('chatview.reconnectingTip', { extra: chat.reconnecting ? t('chatview.connReconnecting') : '' }) }}</span>
          <n-button size="tiny" type="primary" @click="chat.forceReconnect">{{ t('chatview.reconnect') }}</n-button>
    </div>

    <!-- 断线兜底横幅:连接断开超时未恢复,本轮已中止 -->
    <div v-if="chat.disconnectAborted && !chat.isStreaming" class="disconnect-abort-bar">
          <span>{{ t('chatview.disconnected') }}</span>
          <n-button size="tiny" quaternary @click="chat.dismissDisconnectAbort">{{ t('chatview.dismiss') }}</n-button>
    </div>

    <!-- 会话内查找栏 -->
    <div v-if="findOpen" class="find-bar">
      <n-input
        ref="findInputRef"
        v-model:value="findQuery"
        size="small"
        clearable
          placeholder="t('chatview.searchPlaceholder')"
        @update:value="() => { findIndex = 0 }"
        @keyup.enter="navigateFind(1)"
      >
        <template #prefix>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </template>
        <template #suffix>
          <span class="find-count">{{ findMatches.length ? findIndex + 1 + ' / ' + findMatches.length : 0 + ' / 0' }}</span>
        </template>
      </n-input>
        <n-button size="small" quaternary :title="t('chatview.prevMatch')" @click="navigateFind(-1)">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15"/></svg>
      </n-button>
        <n-button size="small" quaternary :title="t('chatview.nextMatch')" @click="navigateFind(1)">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
      </n-button>
        <n-button size="small" quaternary :title="t('chatview.closeFind')" @click="closeFind">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </n-button>
    </div>

    <div class="chat-main">
      <DashboardView v-if="dashboardShow" page @close="dashboardShow = false" />
      <AutomationManager v-if="automationShow" page @close="automationShow = false" />
      <!-- 技能市场 / 工具库:整页形式占满聊天窗口(带面包屑) -->
      <SkillMarketplace
        v-if="skillsPageShow"
        page
        @close="skillsPageShow = false"
        @installed="onSkillInstalled"
      />
      <McpManager
        v-if="toolsPageShow"
        page
        @close="toolsPageShow = false"
      />
      <TemplateLibrary
        v-if="templateLibShow"
        page
        @close="templateLibShow = false"
        @use="onTemplateUse"
        @use-html="onTemplateUseHtml"
      />
      <div v-if="!managePageActive" class="chat-col">
        <div class="chat-body">
          <template v-if="chat.currentSessionId && !isNewSession">
            <div
              ref="scrollRef"
              class="chat-virtual"
              role="log"
              aria-live="polite"
              aria-relevant="additions text"
              :aria-label="t('chat.logLabel')"
              @scroll="onListScroll"
            >
              <div
                ref="contentRef"
                class="chat-virtual-container"
              >
              <!-- 窗口化:视口上方未渲染行的占位高度 -->
              <div v-if="vwinTopSpacer > 0" class="vw-spacer" :style="{ height: vwinTopSpacer + 'px' }" aria-hidden="true"></div>
              <div
                v-for="row in vwinRows"
                :key="row.key"
                :ref="(el) => setWindowRowRef(row.key, el as Element | null)"
                class="chat-virtual-inner"
              >
                <div
                  v-if="row.item.kind === 'turn'"
                  class="flow-item"
                  :id="'find-turn-' + row.item.turn.id"
                  :class="{ 'find-flash': flashedTurnId === row.item.turn.id }"
                >
                  <ChatMessage v-if="row.item.turn.userMessage" :message="row.item.turn.userMessage" :session-id="chat.currentSessionId" :turn-id="row.item.turn.id" />
                  <AssistantTurn v-if="row.item.turn.finalAnswer || row.item.turn.steps.length" :turn="row.item.turn" :session-id="chat.currentSessionId" />
                </div>
                <ChatMessage
                  v-else-if="row.item.kind === 'streaming'"
                  :message="{ id: -1, role: 'assistant', content: row.item.text, status: 'streaming', createdAt: '' }"
                  :streaming="true"
                />
                <div v-else-if="row.item.kind === 'thinking'" class="thinking">
        <n-spin size="small" /> {{ t('chatview.thinking') }}
                </div>
                <!-- 历史分页:滚动到顶自动加载更早;加载中显示状态,已到最早则提示 -->
                <div v-else-if="row.item.kind === 'older-loader'" class="older-loader">
                  <span v-if="chat.loadingOlder" class="spinner"></span>
          <span>{{ chat.loadingOlder ? t('chatview.loadingOlder') : (chat.hasOlder ? t('chatview.hasOlder') : t('chatview.allLoaded')) }}</span>
                </div>
                <!-- 已到最早时的顶部占位:保持首条消息与对话框顶部的间距 -->
                <div v-else-if="row.item.kind === 'top-space'" class="top-space"></div>
                <!-- agent 冷启动连接中:空闲回收后发消息,先拉起容器再回复;有流式内容即消失 -->
                <div v-else-if="row.item.kind === 'connecting'" class="connecting-dock">
        <span class="connecting">{{ t('chatview.connecting') }}</span>
                </div>
                <!-- 压缩分割线:此前的早期内容已总结,不展示在对话里(文案固定,压缩中状态由底部占位单独表示) -->
                <div v-else-if="row.item.kind === 'compact-divider'" class="compact-divider">
                  <span class="cd-line"></span>
                  <span class="cd-label">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
        {{ t('chatview.ctxHigh') }}
                  </span>
                  <span class="cd-line"></span>
                </div>
                <!-- 压缩进行中:消息流末尾的状态占位(当前可视区域可见) -->
                <div v-else-if="row.item.kind === 'compact-progress'" class="compact-progress">
                  <span class="spinner"></span>
          <span>{{ t('chatview.ctxTitle') }},{{ t('chatview.collapsedNote') }}</span>
                </div>
                <!-- 中断分割线:用户中断了本轮生成 -->
                <div v-else-if="row.item.kind === 'interrupted-divider'" class="interrupted-divider">
                  <span class="id-line"></span>
                  <span class="id-label">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="7.86 2 16.14 2 22 7.86 22 16.14 16.14 22 7.86 22 2 16.14 2 7.86 7.86 2"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg>
        {{ t('chatview.collapsedNote') }}
                  </span>
                  <span class="id-line"></span>
                </div>
              </div>
              <!-- 窗口化:视口下方未渲染行的占位高度 -->
              <div v-if="vwinBottomSpacer > 0" class="vw-spacer" :style="{ height: vwinBottomSpacer + 'px' }" aria-hidden="true"></div>
              </div>
            </div>
          </template>
            <!-- 无进行中的会话(含首页/草稿/无会话):展示欢迎页,输入框在其中 -->
            <div v-else class="chat-virtual-inner wel-inner">
              <WelcomeBoard @select="onScenarioSelect" @use-html="onTemplateUseHtml">
                <ChatInput ref="homeInputRef" @open-test-analysis="onTestAnalysis" @open-skills="openSkillPicker" @open-mcp="openMcpPicker" />
              </WelcomeBoard>
            </div>
          <!-- 切换/定位遮罩:绝对定位覆盖在列表之上,不卸载列表,避免切换时闪一下顶部 -->
          <div v-if="chat.switching || locating" class="switching-mask">
            <n-spin size="small" />
          <span>{{ chat.switching ? t('chatview.switching') : t('chatview.locating') }}</span>
          </div>
        </div>

        <div v-if="chat.currentSessionId && !isNewSession" class="composer-wrap" :class="{ 'has-plan': activePlan.length }">
          <!-- 未贴底时显示「回到最新」下箭头,位于任务栏上方 -->
          <button
            v-show="!stickToBottom"
            class="scroll-to-bottom-btn"
        title="t('chatview.jumpToLatest')"
        aria-label="t('chatview.jumpToLatest')"
            @click="scrollToBottom"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
          </button>
          <!-- 任务规划卡:钉在输入框正上方,与输入框等宽,实时展示计划进度 -->
          <TaskPlan :todos="activePlan" class="composer-plan" />
          <ChatInput ref="chatInputRef" @open-test-analysis="onTestAnalysis" @open-skills="openSkillPicker" @open-mcp="openMcpPicker" />
        </div>
      </div>

      <!-- 工作区:常驻右侧面板,可拖动左缘调整宽度 -->
      <div
        v-if="!managePageActive"
        class="workspace-panel"
        :class="{ 'workspace-collapsed': !showFiles, 'ws-resizing': wsResizing }"
        :style="{ width: wsWidth + 'px' }"
      >
        <!-- 宽度拖动条(拖动改变工作区宽度) -->
        <div v-if="showFiles && !isMobile" class="ws-divider" :title="t('chatview.resizeWs')" @mousedown.prevent="startResize"></div>
        <div class="workspace-body" v-if="showFiles">
          <!-- 已打开的标签栏(可关闭) -->
          <div v-if="openTabList.length" class="ws-tabbar">
            <div
              v-for="tab in openTabList"
              :key="tab.key"
              class="ws-tab"
              :class="{ active: activeTab === tab.key }"
              @click="activeTab = tab.key"
            >
              <span class="ws-tab-label">{{ t('workspace.' + tab.key) }}</span>
            <span class="ws-tab-close" :title="t('chatview.closeTab')" @click.stop="closeTab(tab.key)">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </span>
            </div>
          </div>
          <div class="workspace-tabs" :class="{ 'ws-tab-browser': activeTab === 'browser' }">
            <template v-if="activeTab === 'files'">
              <FileTree :changed-paths="Array.from(chat.changedFilePaths)" @ask-ai="onAskAIFile" />
            </template>
            <template v-else-if="activeTab === 'skills'">
              <SkillsPanel />
            </template>
            <template v-else-if="activeTab === 'jobs'">
              <JobRunsPanel />
            </template>
            <template v-else-if="activeTab === 'favorites'">
              <FavoritesPanel @open-session="openSessionFromFavorite" />
            </template>
            <template v-else-if="activeTab === 'browser'">
              <BrowserCanvas auto-active :follow="browserFollowing" />
            </template>
        <n-empty v-else :description="t('chatview.noSuggestion')" style="margin-top: 40px" />
          </div>
        </div>
      </div>
    </div>
  </div>

  <McpManager
    :show="mcpShow"
    @update:show="(v: boolean) => mcpShow = v"
  />
  <SkillMarketplace
    v-model:show="marketplaceShow"
    @installed="onSkillInstalled"
  />
  <SessionPickers ref="sessionPickers" />

</template>

<style scoped>
.chat-view {
  display: flex; flex-direction: column;
  height: 100vh; height: 100dvh;
  background: var(--chat-bg);
  /* 刘海屏/手势条:顶部与底部让出安全区,避免头部与输入框被遮挡 */
  padding-top: env(safe-area-inset-top);
  padding-bottom: env(safe-area-inset-bottom);
  padding-left: env(safe-area-inset-left);
  padding-right: env(safe-area-inset-right);
  box-sizing: border-box;
}
.chat-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 20px; border-bottom: 1px solid var(--border-soft);
  background: var(--bg-elev);
}
.head-left { display: flex; align-items: center; gap: 10px; }
.head-left .title { font-weight: 600; color: var(--text); font-size: 15px; }
/* 连接状态徽标:主题无关的软色胶囊 + 脉冲点(参考主视图设计) */
.conn-badge {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 3px 11px; border-radius: 20px;
  font-size: 12px; line-height: 1.6; font-weight: 600;
  color: var(--text-3); background: var(--fill-0);
  border: 1px solid var(--border-soft);
  white-space: nowrap;
}
.conn-dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--text-4); position: relative; flex-shrink: 0;
}
.conn-badge.ok { color: var(--ok); background: rgba(88, 196, 155, .14); border-color: rgba(88, 196, 155, .3); }
.conn-badge.ok .conn-dot { background: var(--ok); }
.conn-badge.ok .conn-dot::after {
  content: ""; position: absolute; inset: -3px; border-radius: 50%;
  background: rgba(88, 196, 155, .35); animation: conn-pulse 1.8s infinite;
}

/* 单列分页列表 */
.pick-list {
  border: 1px solid var(--border-soft); border-radius: var(--radius);
  background: var(--bg-elev); overflow: hidden;
}
.pick-empty {
  font-size: 13px; color: var(--text-4); text-align: center;
  padding: 46px 12px; line-height: 1.8;
}
.pick-row {
  display: flex; align-items: center; gap: 12px;
  padding: 12px 16px; font-size: 14px; cursor: pointer; color: var(--text);
  border-bottom: 1px solid var(--border-soft);
  transition: background .15s;
}
.pick-row:last-child { border-bottom: none; }
.pick-row:hover { background: var(--surface-hover); }
.pick-row.on { background: var(--fill-0); }
.pick-box {
  flex-shrink: 0; width: 22px; height: 22px; border-radius: 7px;
  display: flex; align-items: center; justify-content: center;
  border: 2px solid var(--border-2); background: var(--bg);
  transition: all .15s;
}
.pick-box.checked { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
.pick-row-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }
.pick-row-tag {
  flex-shrink: 0; font-size: 12px; font-weight: 600;
  padding: 3px 10px; border-radius: 8px;
  background: var(--surface-hover); color: var(--text-3);
}
.pick-row-tag.on { background: var(--fill-1); color: var(--accent-text); }
.pick-page {
  display: flex; align-items: center; justify-content: center; gap: 16px;
  margin-top: 12px;
}
.pick-page-info { font-size: 13px; color: var(--text-2); min-width: 44px; text-align: center; }
.pick-selected {
  margin-top: 14px; display: flex; gap: 12px; align-items: flex-start;
  background: var(--surface-hover); border: 1px solid var(--border-soft);
  border-radius: 10px; padding: 10px 14px;
}
.pick-sel-label { flex-shrink: 0; font-size: 13px; font-weight: 700; color: var(--text-2); padding-top: 3px; }
.pick-chips { display: flex; flex-wrap: wrap; gap: 8px; flex: 1; min-width: 0; }
.pick-chip {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 13px; background: var(--fill-1); color: var(--accent-text);
  border: 1px solid var(--border); border-radius: 8px;
  padding: 4px 10px; cursor: pointer; user-select: none;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  transition: background .15s;
}
.pick-chip:hover { background: var(--fill-2); }
.pick-chip-empty { font-size: 13px; color: var(--text-3); }

.conn-badge.warn { color: var(--warn); }
.conn-badge.warn .conn-dot { background: var(--warn); animation: conn-pulse 1.2s ease-in-out infinite; }
@keyframes conn-pulse { 0%, 100% { opacity: .4; } 50% { opacity: 1; } }
.head-right { display: flex; align-items: center; gap: 6px; }

/* 移动端顶栏精简:隐藏查找按钮与连接徽标,标题单行截断 */
@media (max-width: 768px) {
  .chat-head { padding: 10px 12px; gap: 8px; }
  .find-btn { display: none; }
  .conn-badge { display: none; }
  .head-left { flex: 1; gap: 6px; min-width: 0; }
  .head-left .title {
    flex: 1; min-width: 0;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
    .head-right .ws-launcher { display: none; }
    .head-right { flex-shrink: 0; }
    /* 移动端:消息列不再限宽 860px,左右内边距收窄,避免 375px 屏上白边浪费一半 */
    .chat-virtual-inner { max-width: none; padding: 0 12px; }
    .chat-virtual-inner > * { padding-top: 16px; padding-bottom: 16px; }
    /* 回到最新按钮:触摸目标放大到 44px(无 hover 设备上可点) */
    .scroll-to-bottom-btn { width: 40px; height: 40px; }
    .switching-mask { font-size: 13px; }
    /* 查找条改为整行,避免与标题挤在一行 */
    .find-bar { left: 0; right: 0; }
  }

/* 中窄屏顶栏拥挤:隐藏面板入口按钮的文字,只保留图标(悬停有 title 提示) */
@media (max-width: 1200px) {
  .ws-launcher-label { display: none; }
  .btn-label { display: none; }
}

/* 左右两栏:左=对话区(自适应),右=工作区(拖动左缘调整宽度) */
.chat-main { flex: 1; display: flex; min-height: 0; position: relative; }
.chat-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-body { flex: 1; overflow: hidden; min-height: 0; position: relative; }
.chat-virtual { height: 100%; overflow-y: auto; overflow-x: hidden; scrollbar-gutter: stable; overflow-anchor: none; }
/* 内容整体包装层:高度随所有消息增长,由 ResizeObserver 监听以在撑高时钉底 */
.chat-virtual-container { min-width: 0; }
/* 窗口化占位块:代替未渲染的行,保持滚动高度与滚动条比例稳定 */
.vw-spacer { width: 100%; pointer-events: none; }
.chat-virtual-inner { max-width: 860px; margin: 0 auto; padding: 0 20px; }
.chat-virtual-inner > * { padding-top: 24px; padding-bottom: 24px; }
/* 空会话主页:占满高度以便内容垂直居中,去掉默认上下留白 */
.wel-inner { max-width: none; height: 100%; padding: 0; }
.wel-inner > * { padding: 0; height: 100%; }
.composer-wrap { max-width: 860px; margin: 0 auto; width: 100%; position: relative; z-index: 2; }
/* 任务规划卡:与输入框等宽(对齐 .chat-input 的左右 20px 内边距) */
.composer-plan { margin: 0 20px 8px; box-shadow: 0 4px 16px rgba(0, 0, 0, .08); }
/* 有任务栏时:去掉与输入框之间的间隙,底部与输入框无缝相接(视觉上连成一块) */
.composer-wrap.has-plan .composer-plan {
  margin-bottom: 0;
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
  border-bottom-color: transparent;
  box-shadow: none;
}
.composer-wrap.has-plan :deep(.chat-input) { padding-top: 0; }
.composer-wrap.has-plan :deep(.input-card) {
  border-top-left-radius: 0;
  border-top-right-radius: 0;
}
/* 「回到最新消息」下箭头:位于任务栏上方居中,未贴底时出现 */
.scroll-to-bottom-btn {
  display: flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 50%;
  margin: 0 auto 10px;
  color: var(--text-2);
  background: var(--card-color, var(--bg-color));
  border: 1px solid var(--border-color, rgba(128,128,128,.25));
  box-shadow: 0 2px 8px rgba(0,0,0,.08);
  cursor: pointer;
  transition: color .15s, background .15s, transform .15s;
}
.scroll-to-bottom-btn:hover {
  color: var(--accent, #18a058);
  background: var(--action-color, rgba(24,160,88,.08));
}
.scroll-to-bottom-btn:active { transform: scale(.94); }
.thinking { display: flex; align-items: center; gap: 8px; color: var(--text-3); padding: 8px 0; }

/* 历史分页顶部提示条:滚动到顶时自动加载更早消息 */
.older-loader {
  display: flex; align-items: center; justify-content: center;
  gap: 8px; padding: 10px 0 4px;
  color: var(--text-3); font-size: 12px;
}
.older-loader .spinner {
  width: 14px; height: 14px; border: 2px solid var(--border);
  border-top-color: var(--accent);
  border-radius: 50%; animation: spin 0.8s linear infinite;
}
/* 已到最早时的顶部占位:给第一条消息留出与顶部的间距 */
.top-space { height: 16px; }

/* agent 冷启动连接中占位:居中 + 呼吸点动画,与 thinking 区分 */
.connecting-dock {
  display: flex; align-items: center; justify-content: center;
  padding: 18px 0; color: var(--text-3); font-size: 13px;
}
.connecting-dock .connecting::before {
  content: ''; display: inline-block; width: 8px; height: 8px;
  border-radius: 50%; background: var(--warn);
  margin-right: 8px; vertical-align: -1px;
  animation: connecting-pulse 1.2s ease-in-out infinite;
}
@keyframes connecting-pulse {
  0%, 100% { opacity: .35; }
  50% { opacity: 1; }
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 会话切换加载遮罩:绝对定位覆盖在列表上,不卸载列表,避免切换时闪一下顶部 */
.switching-mask {
  position: absolute; inset: 0; z-index: 20;
  display: flex; align-items: center; justify-content: center; gap: 10px;
  color: var(--text-3); font-size: 13px;
  background: var(--chat-bg, var(--bg));
}

/* 工作区面板:宽度由内联 style 控制,拖动左缘调整 */
.workspace-panel {
  position: relative;
  flex-shrink: 0;
  display: flex; flex-direction: column;
  border-left: 1px solid var(--border-soft); background: var(--bg-elev);
  transition: width .18s ease;
}
.workspace-panel.ws-resizing { transition: none; }
.workspace-panel.workspace-collapsed { width: 0 !important; border-left: none; }
.workspace-body { flex: 1; display: flex; flex-direction: column; min-height: 0; overflow: hidden; }

/* 宽度拖动条:面板左缘,拖拽调整宽度 */
.ws-divider {
  position: absolute;
  left: -3px; top: 0; bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 5;
}
.ws-divider:hover { background: rgba(0, 0, 0, 0.12); }

/* 顶栏面板入口:与用户信息同区,右侧收起按钮/消息中心之前 */
.ws-launcher {
  display: flex; align-items: center; gap: 2px;
  padding-right: 4px; border-right: 1px solid var(--border-soft);
  margin-right: 2px;
}
.ws-launcher .n-button { margin-right: 2px; }
.ws-launcher .n-button svg { margin-right: 4px; }

/* 已打开标签栏:分段胶囊风格,可关闭 */
.ws-tabbar {
  display: flex; gap: 4px;
  padding: 8px 10px 0;
  overflow-x: auto;
}
.ws-tab {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 10px; border-radius: 8px;
  font-size: 12.5px; color: var(--text-2);
  cursor: pointer; white-space: nowrap;
  transition: background .12s, color .12s;
}
.ws-tab:hover { background: var(--surface-hover); color: var(--text); }
.ws-tab.active {
  color: var(--accent-text); background: var(--accent-soft);
  font-weight: 600;
}
.ws-tab-close {
  display: inline-flex; align-items: center; justify-content: center;
  width: 15px; height: 15px; border-radius: 50%;
  color: var(--text-3);
  opacity: 0; transition: opacity .12s, background .12s;
}
.ws-tab:hover .ws-tab-close, .ws-tab.active .ws-tab-close { opacity: .7; }
.ws-tab-close:hover { opacity: 1 !important; background: var(--border-soft); color: var(--text); }

/* 触屏:关闭按钮常显(hover 不生效) */
@media (hover: none) {
  .ws-tab-close { opacity: .7; }
}

.workspace-tabs { flex: 1; min-height: 0; min-width: 0; overflow-y: auto; overflow-x: hidden; padding: 8px 12px; }
/* 浏览器标签全区域展示:去掉内边距与滚动,画布铺满整个面板 */
.ws-tab-browser { display: flex; flex-direction: column; padding: 0; overflow: hidden; }
.ws-tab-browser > * { flex: 1; min-height: 0; }


/* 移动端:工作区从右侧常驻面板变为底部抽屉 */
@media (max-width: 768px) {
  .workspace-panel,
  .workspace-panel.workspace-collapsed {
    position: fixed;
    left: 0; right: 0; bottom: 0;
    height: 55%;
    width: auto !important;
    border-left: none;
    border-top: 1px solid var(--border-soft);
    border-radius: 12px 12px 0 0;
    box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.15);
    z-index: 100;
    transition: transform .18s ease;
  }
  .workspace-panel.workspace-collapsed {
    transform: translateY(100%);
    border-top: none;
    pointer-events: none;
  }
}

/* 登录过期横幅 */
.auth-expired-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 18px; font-size: 13px; color: var(--err);
  background: rgba(239, 68, 68, 0.08); border-bottom: 1px solid rgba(239, 68, 68, 0.2);
}

/* 断线横幅 */
.reconnect-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 18px; font-size: 13px; color: var(--warn);
  background: rgba(245, 158, 11, 0.08); border-bottom: 1px solid rgba(245, 158, 11, 0.2);
}

/* 断线兜底横幅:本轮已中止 */
.disconnect-abort-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 18px; font-size: 13px; color: var(--err);
  background: rgba(239, 68, 68, 0.08); border-bottom: 1px solid rgba(239, 68, 68, 0.2);
}

/* 压缩分割线:早期内容已总结,不展示在对话里 */
.compact-divider {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 0; color: var(--text-3); font-size: 12px;
}
.compact-divider .cd-line {
  flex: 1; height: 1px; background: var(--border-soft);
}
.compact-divider .cd-label {
  display: inline-flex; align-items: center; gap: 6px;
  white-space: nowrap;
}
.compact-divider .spinner {
  width: 11px; height: 11px; border: 2px solid rgba(245, 158, 11, 0.3);
  border-top-color: var(--warn); border-radius: 50%;
  animation: compact-spin 0.8s linear infinite;
}
@keyframes compact-spin { to { transform: rotate(360deg) } }

/* 压缩进行中:消息流末尾占位 */
.compact-progress {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  padding: 16px 0; font-size: 13px; color: var(--warn);
}
.compact-progress .spinner {
  width: 12px; height: 12px; border: 2px solid rgba(245, 158, 11, 0.3);
  border-top-color: var(--warn); border-radius: 50%;
  animation: compact-spin 0.8s linear infinite;
}

/* 中断分割线:用户中断了本轮生成 */
.interrupted-divider {
  display: flex; align-items: center; gap: 12px;
  padding: 14px 0; font-size: 12px;
}
.interrupted-divider .id-line {
  flex: 1; height: 1px; background: var(--border-soft);
}
.interrupted-divider .id-label {
  display: inline-flex; align-items: center; gap: 6px;
  white-space: nowrap; color: var(--text-3);
}

/* 会话内查找栏 */
.find-bar {
  display: flex; align-items: center; gap: 4px;
  padding: 8px 18px; border-bottom: 1px solid var(--border-soft); background: var(--bg-elev);
}
.find-bar .n-input { flex: 1; }
.find-count { color: var(--text-3); font-size: 12px; padding: 0 4px; }

/* 查找跳转目标:命中轮次短暂高亮 */
.flow-item {
  border-radius: 8px;
  animation: msg-in .22s ease both;
}
@keyframes msg-in {
  from { opacity: .5; transform: translateY(4px); }
  to { opacity: 1; transform: none; }
}
.flow-item.find-flash {
  outline: 2px solid var(--warn);
  outline-offset: 4px;
  animation: find-flash 1.6s ease-out;
}
@keyframes find-flash {
  0% { background: rgba(245, 158, 11, 0.15); }
  100% { background: transparent; }
}
</style>
