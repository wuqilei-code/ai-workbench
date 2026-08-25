<script setup lang="ts">
import { ref, watch, nextTick, onMounted, onBeforeUnmount, computed, inject, type Ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useFavoritesStore } from '@/stores/favorites'
import { useMessage, useDialog } from 'naive-ui'
import ChatMessage from '@/components/ChatMessage.vue'
import AssistantTurn from '@/components/AssistantTurn.vue'
import ChatInput from '@/components/ChatInput.vue'
import WelcomeBoard from '@/components/WelcomeBoard.vue'
import TemplateLibrary from '@/components/TemplateLibrary.vue'
import DocumentGenerator from '@/components/DocumentGenerator.vue'
import ErrorDiagnose from '@/components/ErrorDiagnose.vue'
import FavoritesPanel from '@/components/FavoritesPanel.vue'
import SkillMarketplace from '@/components/SkillMarketplace.vue'
import FileTree from '@/components/FileTree.vue'
import AutomationManager from '@/components/AutomationManager.vue'
import JobRunsPanel from '@/components/JobRunsPanel.vue'
import NotificationCenter from '@/components/NotificationCenter.vue'
import UserMenu from '@/components/UserMenu.vue'
import BrowserCanvas from '@/components/BrowserCanvas.vue'
import ChangeSummaryCard from '@/components/ChangeSummaryCard.vue'
import DashboardView from '@/views/DashboardView.vue'
import { chatApi } from '@/api/chat'
import type { MessageVo, SkillInfo, UserSkill } from '@/types'
import type { FlowItem, SummaryItem, Turn } from '@/utils/turns'

const route = useRoute()
const router = useRouter()
const chat = useChatStore()
const fav = useFavoritesStore()
const msg = useMessage()
const dialog = useDialog()
const scrollRef = ref<HTMLElement | null>(null)
/** 消息整体内容的包装层(chat-virtual-container):其高度随所有消息撑高而增长。
 *  ResizeObserver 监听它而非某条具体消息,才能在异步加载/流式撑高时正确钉底。 */
const contentRef = ref<HTMLElement | null>(null)
const showFiles = ref(true)
const dashboardShow = ref(false)
/** 工作区右侧面板已打开的标签(可关闭),默认只打开「文件」 */
const openTabs = ref<string[]>(['files'])
/** 当前激活的标签 */
const activeTab = ref('files')
/** 工作区面板宽度(px),可拖动调整 */
const wsWidth = ref(420)
/** 拖动调整宽度中(禁用 transition 避免拖拽卡顿) */
const wsResizing = ref(false)
/** 工作区面板最小宽度(px) */
const WS_MIN = 260
/** 拖动中残留的事件清理(组件卸载时兜底) */
let _resizeCleanup: (() => void) | null = null
let _scrollObsCleanup: (() => void) | null = null

/** 工作区标签(收起后顶部显示的小图标,点击展开并切换)。浏览器不设顶部入口,仅由对话唤出 */
const workspaceTabs = [
  { key: 'files', label: '文件', icon: 'M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z' },
  { key: 'skills', label: '技能', icon: 'M19.439 7.85c-.049.322.059.648.289.878l1.568 1.568c.47.47.706 1.087.706 1.704s-.235 1.233-.706 1.704l-1.611 1.611a.98.98 0 0 1-.837.276c-.47-.07-.802-.48-.968-.925a2.501 2.501 0 1 0-3.214 3.214c.446.166.855.497.925.968a.979.979 0 0 1-.276.837l-1.61 1.61a2.404 2.404 0 0 1-1.705.707 2.402 2.402 0 0 1-1.704-.706l-1.568-1.568a1.026 1.026 0 0 0-.877-.29c-.493.074-.84.504-1.02.968a2.5 2.5 0 1 1-3.237-3.237c.464-.18.894-.527.967-1.02a1.026 1.026 0 0 0-.289-.877l-1.568-1.568A2.402 2.402 0 0 1 1.998 12c0-.617.236-1.234.706-1.704L4.23 8.77c.24-.24.581-.353.917-.303.515.077.877.528 1.073 1.01a2.5 2.5 0 1 0 3.259-3.259c-.482-.196-.933-.558-1.01-1.073-.05-.336.062-.676.303-.917l1.525-1.525A2.402 2.402 0 0 1 12 2.001c.617 0 1.234.236 1.704.706l1.568 1.568c.23.23.556.338.877.29.493-.074.84-.504 1.02-.968a2.5 2.5 0 1 1 3.237 3.237c-.464.18-.894.527-.967 1.02Z' },
  { key: 'jobs', label: '定时任务', icon: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2' },
  { key: 'favorites', label: '收藏', icon: 'M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14 2 9.27l6.91-1.01z' },
]
/** 浏览器标签元数据(不在 workspaceTabs 里 → 无顶部按钮;agent 唤出时仍可作为标签展示) */
const browserTabMeta = { key: 'browser', label: '浏览器', icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 2.07A8 8 0 0 1 20 12h-7zM4.9 5.8l3.4 3.4A3 3 0 0 0 8 9H5.5A8 8 0 0 1 4.9 5.8zM4 12a8 8 0 0 1 .5-2.8H8a3 3 0 0 0-3 3v.5H4zm0 1h1v1h-1zm3 4.2A8 8 0 0 0 12 20c-1.7 0-3.3-.5-4.6-1.4L9 16.9zM20 12a8 8 0 0 1-.5 2.8H16a3 3 0 0 0 3-3v-.5h1zm-8 8a8 8 0 0 1-3-.6L10 17.6a4 4 0 0 0 2-.6c1 .6 1.9 1 3 1.2zm-1-6a3 3 0 1 1 0-6 3 3 0 0 1 0 6z' }
function openWorkspaceTab(key: string) {
  showFiles.value = true
  if (!openTabs.value.includes(key)) openTabs.value.push(key)
  activeTab.value = key
  if (key === 'browser' && !isMobile.value) {
    const target = Math.round(window.innerWidth * 0.55)
    if (wsWidth.value < target) wsWidth.value = target
  }
}

/** agent 调用浏览器工具时自动唤出浏览器面板,实时看 agent 操作 */
watch(
  () => chat.browserToolSignal,
  () => {
    if (!showFiles.value) showFiles.value = true
    if (activeTab.value !== 'browser') openWorkspaceTab('browser')
  },
)

/** agent 正在流式执行且近期调用过浏览器工具 → 面板保持实时 */
const browserFollowing = computed(
  () => chat.isStreaming && Date.now() - chat.lastBrowserToolAt < 120000,
)

/** 顶部入口按钮:未激活则打开;再次点击已激活的面板则收起工作区 */
function onLauncherClick(key: string) {
  if (showFiles.value && activeTab.value === key) {
    showFiles.value = false
  } else {
    openWorkspaceTab(key)
  }
}

/** 关闭工作区标签;关闭当前激活标签时自动切到相邻标签 */
function closeTab(key: string) {
  const idx = openTabs.value.indexOf(key)
  if (idx < 0) return
  openTabs.value.splice(idx, 1)
  if (activeTab.value === key) {
    activeTab.value = openTabs.value.length ? openTabs.value[Math.max(0, idx - 1)] : ''
  }
}

/** 已打开标签的元信息(图标+文案),供标签栏渲染 */
const openTabList = computed(() =>
  [...workspaceTabs, browserTabMeta].filter((t) => openTabs.value.includes(t.key)),
)

/** 拖动面板左缘调整宽度:按住 → 随鼠标移动计算新宽度(限制最小/最大),松开结束。 */
function startResize(e: MouseEvent) {
  e.preventDefault()
  wsResizing.value = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  const move = (ev: MouseEvent) => {
    const w = window.innerWidth - ev.clientX
    wsWidth.value = Math.round(Math.max(WS_MIN, Math.min(window.innerWidth * 0.7, w)))
  }
  const up = () => {
    wsResizing.value = false
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    document.removeEventListener('mousemove', move)
    document.removeEventListener('mouseup', up)
    _resizeCleanup = null
  }
  document.addEventListener('mousemove', move)
  document.addEventListener('mouseup', up)
  _resizeCleanup = up
}

const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)
const templateLibShow = ref(false)
const docGenShow = ref(false)
const errorDiagnoseShow = ref(false)
const marketplaceShow = ref(false)
const skillTreeKey = ref(0)
/** 内置技能名(技能栏顶部内置列表;用户技能区/会话配置只展示自定义技能) */
const builtinSkillNames = computed(() => builtinSkills.value.map((s) => s.name))
/** ---- 用户技能(user_skill:自建/安装) ---- */
const userSkills = ref<UserSkill[]>([])
async function loadUserSkills() {
  try { userSkills.value = await chatApi.listMineSkills() } catch { userSkills.value = [] }
}
const skillModalShow = ref(false)
const skillEditingId = ref<number | null>(null)
const skillForm = ref<{ name: string; description: string; content: string; visibility: number; files: { path: string; content: string }[] }>({
  name: '', description: '', content: '', visibility: 0, files: [],
})
function addSkillFile() {
  skillForm.value.files = [...skillForm.value.files, { path: 'scripts/tool.py', content: '' }]
}
function removeSkillFile(i: number) {
  skillForm.value.files.splice(i, 1)
}
function openCreateSkill() {
  skillEditingId.value = null
  skillForm.value = { name: '', description: '', content: '', visibility: 0, files: [] }
  skillModalShow.value = true
}
function openEditSkill(s: UserSkill) {
  skillEditingId.value = s.id
  skillForm.value = {
    name: s.name, description: s.description || '',
    content: s.content || '', visibility: s.visibility || 0,
    files: (s.files || []).map((f) => ({ path: f.path, content: f.content || '' })),
  }
  skillModalShow.value = true
}
async function saveSkill() {
  try {
    if (skillEditingId.value) {
      await chatApi.updateSkill(skillEditingId.value, {
        description: skillForm.value.description,
        content: skillForm.value.content,
        visibility: skillForm.value.visibility,
        files: skillForm.value.files.filter((f) => f.path && !f.path.startsWith('SKILL.md')),
      })
    } else {
      await chatApi.createSkill({
        name: skillForm.value.name.trim(),
        description: skillForm.value.description,
        content: skillForm.value.content,
        visibility: skillForm.value.visibility,
        files: skillForm.value.files.filter((f) => f.path),
      })
    }
    skillModalShow.value = false
    await loadUserSkills()
    msg.success('技能已保存')
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}
async function removeUserSkill(s: UserSkill) {
  const ok = await new Promise<boolean>((resolve) => {
    dialog.warning({
      title: '删除技能',
      content: s.source === 'installed' ? `确定卸载已安装的技能「${s.name}」吗?` : `确定删除技能「${s.name}」吗?`,
      positiveText: '删除', negativeText: '取消',
      onPositiveClick: () => resolve(true), onNegativeClick: () => resolve(false), onClose: () => resolve(false),
    })
  })
  if (!ok) return
  try {
    await chatApi.deleteSkill(s.id)
    await loadUserSkills()
    msg.success('已删除')
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || '删除失败')
  }
}
watch(activeTab, (t) => { if (t === 'skills') loadUserSkills() })
/** 会话自定义技能配置:左=可选项,右=已选;内置技能默认加载,不可配置 */
const customSkills = ref<string[]>([])
const skillsPick = ref<string[]>([])
const skillsOpen = ref(false)
const skillAvail = computed(() => customSkills.value.filter((s) => !skillsPick.value.includes(s)))
async function openSkillPicker() {
  try { customSkills.value = await chatApi.customSkills() } catch { customSkills.value = [] }
  skillsPick.value = (chat.currentSession?.skills || []).filter((s) => customSkills.value.includes(s))
  skillsOpen.value = true
}
function addSkill(s: string) {
  if (!skillsPick.value.includes(s)) skillsPick.value = [...skillsPick.value, s]
}
function removeSkill(s: string) {
  skillsPick.value = skillsPick.value.filter((x) => x !== s)
}
async function applySkills() {
  if (!chat.currentSessionId) return
  try {
    const updated = await chatApi.updateSession(chat.currentSessionId, { skills: skillsPick.value })
    const idx = chat.sessions.findIndex((s) => s.id === chat.currentSessionId)
    if (idx >= 0) chat.sessions[idx] = updated
    skillsOpen.value = false
    msg.success('已保存(内置技能默认加载;自定义技能下一条消息生效)')
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || '保存失败')
  }
}
/** 自动化管理弹窗(点侧边栏「自动化」打开:任务列表 + 启停 + 新建) */
const automationShow = ref(false)
/** 消息定位轮询中(从任务/收藏跳转后等待消息加载) */
const locating = ref(false)
/** 安装技能后刷新技能目录树 */
function onSkillInstalled() {
  skillTreeKey.value++
}

/** 侧边栏导航事件监听 */
const sidebarEvents = inject<Ref<{openPluginMarket:boolean; openTemplateLib:boolean; openMyFiles:boolean; openAutomation:boolean; openFind:boolean; openDashboard:boolean}>>('sidebarEvents')
if (sidebarEvents) {
  watch(() => sidebarEvents.value.openPluginMarket, (v) => { if (v) marketplaceShow.value = true })
  watch(() => sidebarEvents.value.openTemplateLib, (v) => { if (v) templateLibShow.value = true })
  watch(() => sidebarEvents.value.openMyFiles, (v) => { if (v) showFiles.value = true })
  watch(() => sidebarEvents.value.openFind, (v) => { if (v) openFind() })
  watch(() => sidebarEvents.value.openAutomation, (v) => { if (v) openAutomationModal() })
  watch(() => sidebarEvents.value.openDashboard, (v) => { if (v) dashboardShow.value = true })}

/** 侧边栏折叠状态(由 ChatLayout provide):顶部「展开侧边栏」按钮使用 */
const sidebarCollapsed = inject<Ref<boolean>>('sidebarCollapsed', ref(false))
const toggleSidebar = inject<() => void>('toggleSidebar', () => {})
/** 是否移动端(由 ChatLayout provide):工作区改为底部抽屉、顶栏精简 */
const isMobile = inject<Ref<boolean>>('isMobile', ref(false))

// 移动端:工作区默认收起为底部抽屉
watch(isMobile, (m) => { if (m) showFiles.value = false }, { immediate: true })

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

/** 文件树右键「问 AI」:把文件挂到输入框附件,并填入引导提示词,用户补充后发送 */
function onAskAIFile(path: string) {
  console.log('[ChatView] onAskAIFile', path, 'inputRef=', !!chatInputRef.value)
  chatInputRef.value?.attachFilePath(path)
  chatInputRef.value?.fill(`请看看工作区里的这个文件:${path}\n\n我需要你:`)
}

/** 侧边栏「测试分析」入口:填入测试提示词,引导用户上传项目 zip 后发送 */
function onTestAnalysis() {
  chatInputRef.value?.fill(
    '我上传了一个项目源码压缩包,请用 test-analysis 技能对源码做静态分析:①生成测试点清单 ②生成测试用例 ③生成测试脚本(通过语法校验) ④输出测试报告。沙箱无法安装依赖,不要运行项目,只做静态分析与语法校验。',
  )
}

// ---------- 技能面板(内置全局技能只读列表)----------

const builtinSkills = ref<SkillInfo[]>([])
const builtinLoading = ref(false)
async function loadBuiltinSkills() {
  builtinLoading.value = true
  try {
    builtinSkills.value = await chatApi.builtinSkills()
  } catch (e) {
    console.warn('[skills] load builtin failed', e)
  } finally {
    builtinLoading.value = false
  }
}
// immediate:刷新后即使 showFiles 已是 true 也加载一次;之后每次打开也刷新
watch(showFiles, (v) => { if (v) loadBuiltinSkills() }, { immediate: true })

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
/** 打开「自动化」:弹窗展示任务列表(管理:新建/启停/执行)。 */
function openAutomationModal() {
  automationShow.value = true
}

/** 跳转到工作区「定时任务」tab(消息中心点击后查看执行记录/报告)。 */
function openJobTab() {
  openWorkspaceTab('jobs')
}
function closeFind() {
  findOpen.value = false
  findQuery.value = ''
  findIndex.value = 0
}

/** 滚动到指定 turn 并短暂高亮(普通滚动容器按 DOM id 定位)。 */
function locateTurn(turnId: string) {
  const el = document.getElementById('find-turn-' + turnId)
  el?.scrollIntoView({ block: 'start' })
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

/** 收藏面板点击:切会话并定位到收藏项所在 turn(异步轮询等待消息加载)。 */
async function onOpenFavorite(target: { sessionId: number; turnId?: string }) {
  showFiles.value = false
  if (sessionId.value !== target.sessionId) {
    await router.push({ name: 'session', params: { id: target.sessionId } })
  }
  const flash = (turnId: string) => locateTurn(turnId)
  if (!target.turnId) return
  for (let i = 0; i < 40; i++) {
    const turn = chat.turns.find((t) => t.id === target.turnId)
    if (turn) {
      flash(turn.id)
      return
    }
    await new Promise((r) => setTimeout(r, 200))
  }
}

/** 定位到指定用户消息所在 turn 并闪亮(消息加载是异步的,轮询等待)。 */
async function flashToMessage(messageId?: number) {
  if (!messageId) return
  locating.value = true
  const flash = (turnId: string) => {
    locateTurn(turnId)
  }
  try {
    for (let i = 0; i < 40; i++) {
      const turn = chat.turns.find((t) => t.userMessage?.id === messageId)
      if (turn) {
        flash(turn.id)
        return
      }
      await new Promise((r) => setTimeout(r, 200))
    }
  } finally {
    locating.value = false
  }
}

onMounted(() => {
  ensureSession()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('aiwb:test-analysis', onTestAnalysis)
  // 内容高度变化(组件/图片 iframe 异步加载撑高、新消息追加)时,若用户贴底则保持钉在底部,
  // 避免"图渲染完视图向上滚,看不到最新输出"。
  // 注意:必须监听内容整体包装层(chat-virtual-container,高度随所有消息增长),
  // 而非单条消息或滚动容器(滚动容器尺寸固定不触发;单条消息大多在顶部,下方增长不会被察觉)。
  nextTick(() => {
    const scroller = scrollRef.value
    const inner = contentRef.value
    if (scroller && inner && typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(() => {
        if (!stickToBottom.value) return
        if (scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight > 4) {
          scroller.scrollTop = scroller.scrollHeight
        }
      })
      ro.observe(inner)
      _scrollObsCleanup = () => ro.disconnect()
    }
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
  _resizeCleanup?.()
  _scrollObsCleanup?.()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('aiwb:test-analysis', onTestAnalysis)
})

function scrollToBottom() {
  nextTick(() => {
    const el = scrollRef.value
    if (!el) return
    if (!virtualItems.value.length) return
    el.scrollTop = el.scrollHeight
  })
}

/** 同步滚到底(用于会话切换:遮罩摘掉、列表刚挂载的那一帧立即定位,避免"先顶部后底部"闪烁) */
function scrollToBottomNow() {
  const el = scrollRef.value
  if (!el) return
  if (!virtualItems.value.length) return
  el.scrollTop = el.scrollHeight
}

/** 是否处于"打开会话后的贴底稳定期":期间强制 stickToBottom=true,忽略瞬时滚动事件。
 *  否则异步测量撑高内容时,先出现的"未贴底"瞬时事件会误判 stickToBottom=false,
 *  内容撑高后不再回钉 → 表现为"一开始在最下方,又自动向上滚一段"。 */
let _openSettleUntil = 0
function pinOnOpen() {
  stickToBottom.value = true
  _openSettleUntil = Date.now() + 1000
  scrollToBottomNow()
  const tick = () => {
    scrollToBottomNow()
    if (Date.now() < _openSettleUntil) requestAnimationFrame(tick)
  }
  requestAnimationFrame(tick)
  setTimeout(scrollToBottomNow, 200)
  setTimeout(scrollToBottomNow, 500)
}

// 会话切换:switching=true 时列表被 v-else-if 卸载、遮罩覆盖;false 时列表重新挂载(默认滚到顶部)。
// 在切换完成的同一帧立即滚到底,并用 rAF + 延迟在异步测量收尾时继续钉底,消除"闪一下顶部"/
// "先到底又自动上滚"。
watch(() => chat.switching, (now, prev) => {
  if (!now && prev) pinOnOpen()
}, { flush: 'post' })

/** 用户提交/拒绝权限或问题后(resumeStreaming 置 isStreaming=true),agent 会继续输出。
 *  此时把用户拉回底部,避免"确认后看不到后续输出"。
 *  nextTick 滚动可能早于虚拟列表渲染新项,故延迟再滚一次保证到位。 */
watch(() => chat.isStreaming, (now, prev) => {
  if (now && !prev) {
    stickToBottom.value = true
    scrollToBottom()
    setTimeout(scrollToBottom, 200)
    setTimeout(scrollToBottom, 600)
  }
})

/** 用户是否停留在底部区域(阈值内视为贴底)。贴底时流式输出自动跟随;上翻阅读时不被拉回。 */
const stickToBottom = ref(true)
let _prevScrollTop = 0
/** 正在前插更早分页:期间抑制自动滚底(messages.length 变化由 loadOlderMessages 前插引起) */
const isBackfilling = ref(false)
function handleVirtualScroll() {
  const el = scrollRef.value
  if (!el) return
  // 打开会话后的贴底稳定期:异步测量/自动滚动引发的瞬时非贴底事件不误判 stickToBottom,
  // 否则内容撑高后不再回钉 → "先到底又自动向上滚"。稳定期结束恢复正常判定。
  if (Date.now() < _openSettleUntil) {
    stickToBottom.value = true
    return
  }
  const nearBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - 120
  stickToBottom.value = nearBottom
  // 滚动到顶:加载更早历史(loadingOlder 防重入;hasOlder=false 表示已到最早)
  if (el.scrollTop <= 60 && chat.hasOlder && !chat.loadingOlder) {
    isBackfilling.value = true
    // nextTick 后复位:store 的 loadingOlder 是同步复位的,而 messages/turns 的 watch
    // 在组件下一帧调度,届时标志需仍为 true 才能抑制"前插后自动滚底"
    chat.loadOlderMessages()
      .catch(() => {})
      .finally(() => nextTick(() => { isBackfilling.value = false }))
  }
}
// 仅当贴底时才在流式时跟随底部,交给 vueuc 内置 ResizeObserver 跟随(避免与强制滚动竞争抖动)
watch(() => chat.streamingText, () => {
  if (stickToBottom.value) scrollToBottom()
})

// 前插更早分页时(backfilling)不自动滚底:用户正停留在顶部阅读历史,不应被拉回底部
// 同时尊重 stickToBottom:用户已上翻阅读时,后台追加消息也不拉回,避免"卡在最底部组件"无法上翻
watch(() => chat.messages.length, () => {
  if (isBackfilling.value || chat.loadingOlder) return
  if (!stickToBottom.value) return
  // 打开历史会话 / 压缩后刷新:等渲染后再滚到底(内容高度异步实测,需延迟多次)
  scrollToBottom()
  setTimeout(scrollToBottom, 150)
  setTimeout(scrollToBottom, 400)
})
watch(() => chat.turns.length, () => {
  if (isBackfilling.value || chat.loadingOlder) return
  if (!stickToBottom.value) return
  scrollToBottom()
})

/** 当前流式目标消息是否已落入某个 turn(step/finalAnswer) */
const activeTurnExists = computed(() => {
  const id = chat.streamingMessageId
  if (!id) return false
  return chat.turns.some((t) =>
    t.steps.some((s) => s.message.agentMessageId === id) ||
    (t.finalAnswer?.message.agentMessageId === id),
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
  | { key: string; kind: 'connecting' }
  | { key: string; kind: 'older-loader' }
  | { key: string; kind: 'top-space' }
  | { key: string; kind: 'compact-divider' }
  | { key: string; kind: 'compact-progress' }
  | { key: string; kind: 'interrupted-divider' }

const virtualItems = computed<VirtualListItem[]>(() => {
  const items: VirtualListItem[] = []
  // 历史分页:还有更早消息或正在加载时,顶部固定显示提示条(滚动到顶触发加载)
  if (chat.hasOlder || chat.loadingOlder) {
    items.push({ key: '__older__', kind: 'older-loader' })
  } else if (chat.messages.length > 0) {
    // 已到最早:插入空占位,避免第一条消息紧贴对话框顶部
    items.push({ key: '__top-space__', kind: 'top-space' })
  }
  const msgs = chat.messages
  // 压缩点(遗留兼容):deepagents 不再落 compaction 记录,该分支恒空。
  const compactionMarks = msgs
    .filter((m) => m.role === 'compaction')
    .map((m) => ({ id: m.id, status: m.status }))
  // 中断点:用户中断生成时后端落库 role=interrupted 记录,前端渲染为"已中断"分割线。
  const interruptedMarks = msgs
    .filter((m) => m.role === 'interrupted')
    .map((m) => ({ id: m.id }))
  // 统一归并:压缩/中断标记都按 id 插到对应 flow 项之前。
  const dividerMarks = [
    ...compactionMarks.map((m) => ({
      id: m.id,
      kind: 'compact-divider' as VirtualListItem['kind'],
      key: '__compact__-' + m.id,
    })),
    ...interruptedMarks.map((m) => ({
      id: m.id,
      kind: 'interrupted-divider' as VirtualListItem['kind'],
      key: '__interrupted__-' + m.id,
    })),
  ].sort((a, b) => (a.id ?? 0) - (b.id ?? 0))
  // flow 每项的锚点消息 id:turn 用其 user 消息 id;summary 无对应消息(deepagents 恒空)。
  const summaryIdOf = (): number => -1
  const flowKeyById = chat.flow.map((it) => {
    if (it.kind === 'turn') return it.turn.userMessage?.id ?? it.turn.steps[0]?.message?.id ?? -1
    return summaryIdOf()
  })
  const flowItems: VirtualListItem[] = chat.flow.map((it) =>
    it.kind === 'turn'
      ? { key: 't-' + it.turn.id, kind: 'turn', turn: it.turn }
      : {
          key: 's-' + (it.summary.sha || '') + '-' + (it.summary.at || '') + '-' + it.summary.summary.length,
          kind: 'summary',
          summary: it.summary,
        },
  )
  // 双指针归并:压缩/中断标记按 id 插到对应 flow 项之前。
  // 本地乐观消息(id 为负,发送中的用户消息/未刷新定稿)必定是最新的,
  // 任何已落库的标记记录都在它之前 → 负 id 一律判定"标记在前",否则按 id 比较。
  const dividerBeforeFlow = (divId: number, flowId: number): boolean => {
    if (flowId < 0) return true
    return divId < flowId
  }
  let fi = 0
  let ci = 0
  while (fi < flowItems.length || ci < dividerMarks.length) {
    const cm = dividerMarks[ci]
    const nextFlowId = flowKeyById[fi] ?? Number.MAX_SAFE_INTEGER
    if (cm && (fi >= flowItems.length || dividerBeforeFlow(cm.id ?? 0, nextFlowId))) {
      items.push({ key: cm.key, kind: cm.kind })
      ci++
    } else {
      items.push(flowItems[fi])
      fi++
    }
  }
  // 流式进行中:末尾显示流式气泡或思考占位(deepagents 无压缩/冷启动连接态)
  if (showStreamingBubble.value) {
    items.push({ key: '__streaming__', kind: 'streaming', text: chat.streamingText })
  } else if (showThinkingFallback.value) {
    items.push({ key: '__thinking__', kind: 'thinking' })
  }
  return items
})

/** 普通滚动容器(非虚拟化):消息整体渲染,滚动行为与原生一致,避免虚拟列表对变高组件条的估算补偿 */
</script>

<template>
  <div class="chat-view">
    <div class="chat-head">
      <div class="head-left">
        <n-button v-if="sidebarCollapsed || isMobile" size="small" quaternary title="展开侧边栏" @click="toggleSidebar()">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M9 3v18"/><path d="m14 9 3 3-3 3"/></svg>
        </n-button>
        <span class="title">{{ chat.currentSession?.title || 'AI Workbench' }}</span>
        <span class="conn-badge" :class="{ ok: chat.connected, warn: chat.reconnecting }">
          <span class="conn-dot"></span>
          {{ chat.connected ? '已连接' : (chat.reconnecting ? '重连中…' : '未连接') }}
        </span>
        <n-button class="find-btn" size="small" quaternary title="会话内置/自定义技能(自定义按会话勾选加载)" @click="openSkillPicker">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right:4px"><path d="M19.439 7.85c-.049.322.059.648.289.878l1.568 1.568c.47.47.706 1.087.706 1.704s-.235 1.233-.706 1.704l-1.611 1.611a.98.98 0 0 1-.837.276c-.47-.07-.802-.48-.968-.925a2.501 2.501 0 1 0-3.214 3.214c.446.166.855.497.925.968a.979.979 0 0 1-.276.837l-1.61 1.61a2.404 2.404 0 0 1-1.705.707 2.402 2.402 0 0 1-1.704-.706l-1.568-1.568a1.026 1.026 0 0 0-.877-.29c-.493.074-.84.504-1.02.968a2.5 2.5 0 1 1-3.237-3.237c.464-.18.894-.527.967-1.02a1.026 1.026 0 0 0-.289-.877l-1.568-1.568A2.402 2.402 0 0 1 12 2c.617 0 1.234.236 1.704.706l1.568 1.568c.23.23.556.338.877.29.493-.074.84-.504 1.02-.968a2.5 2.5 0 1 1 3.237 3.237c-.464.18-.894.527-.967 1.02Z"/></svg>
          技能
        </n-button>
        <n-button class="find-btn" size="small" quaternary title="会话内查找 (Ctrl+F)" @click="openFind">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right:4px"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          查找
        </n-button>
      </div>
      <div class="head-right">
        <!-- 顶部面板入口:与用户信息同区,点击打开/切换;再次点击已激活的面板则收起工作区 -->
        <div class="ws-launcher">
          <n-button
            v-for="t in workspaceTabs"
            :key="t.key"
            size="small"
            quaternary
            :type="showFiles && activeTab === t.key ? 'primary' : 'default'"
            :title="t.label"
            @click="onLauncherClick(t.key)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="t.icon"/></svg>
            <span class="ws-launcher-label">{{ t.label }}</span>
          </n-button>
        </div>
        <n-button size="small" quaternary :title="showFiles ? '收起工作区' : '展开工作区'" @click="showFiles = !showFiles">
          <svg v-if="showFiles" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/><path d="m8 9 3 3-3 3"/></svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M15 3v18"/><path d="m8 15 3-3-3-3"/></svg>
        </n-button>
        <NotificationCenter />
        <UserMenu />
      </div>
    </div>

    <!-- 登录过期横幅:token 失效后 SSE 停止重连,提示重新登录而非无限重连 -->
    <div v-if="chat.authExpired" class="auth-expired-bar">
      <span>登录已过期,实时连接已断开,请重新登录后继续。</span>
      <n-button size="tiny" type="primary" @click="router.push('/login')">重新登录</n-button>
    </div>

    <!-- 断线横幅:意外断线时显示;切换会话/首次连接中的主动断开不算断线,不打扰 -->
    <div v-if="chat.currentSessionId && !chat.connected && chat.reconnecting && !chat.authExpired" class="reconnect-bar">
      <span>连接已断开{{ chat.reconnecting ? '，正在自动重连…' : '' }}</span>
      <n-button size="tiny" type="primary" @click="chat.forceReconnect">立即重连</n-button>
    </div>

    <!-- 断线兜底横幅:连接断开超时未恢复,本轮已中止 -->
    <div v-if="chat.disconnectAborted && !chat.isStreaming" class="disconnect-abort-bar">
      <span>连接已断开，本轮回答已中止。请检查网络后重新提问。</span>
      <n-button size="tiny" quaternary @click="chat.dismissDisconnectAbort">知道了</n-button>
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
        <template #prefix>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </template>
        <template #suffix>
          <span class="find-count">{{ findMatches.length ? findIndex + 1 + ' / ' + findMatches.length : 0 + ' / 0' }}</span>
        </template>
      </n-input>
      <n-button size="small" quaternary title="上一个 (Shift+Enter)" @click="navigateFind(-1)">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15"/></svg>
      </n-button>
      <n-button size="small" quaternary title="下一个 (Enter)" @click="navigateFind(1)">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
      </n-button>
      <n-button size="small" quaternary title="关闭 (Esc)" @click="closeFind">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
      </n-button>
    </div>

    <div class="chat-main">
      <DashboardView v-if="dashboardShow" @close="dashboardShow = false" />
      <div class="chat-col">
        <div class="chat-body">
          <template v-if="chat.currentSessionId && !isNewSession">
            <div
              ref="scrollRef"
              class="chat-virtual"
              @scroll="handleVirtualScroll"
            >
              <div
                ref="contentRef"
                class="chat-virtual-container"
              >
              <div
                v-for="item in virtualItems"
                :key="item.key"
                class="chat-virtual-inner"
              >
                <div
                  v-if="item.kind === 'turn'"
                  class="flow-item"
                  :id="'find-turn-' + item.turn.id"
                  :class="{ 'find-flash': flashedTurnId === item.turn.id }"
                >
                  <ChatMessage v-if="item.turn.userMessage" :message="item.turn.userMessage" :session-id="chat.currentSessionId" :turn-id="item.turn.id" />
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
                <!-- 历史分页:滚动到顶自动加载更早;加载中显示状态,已到最早则提示 -->
                <div v-else-if="item.kind === 'older-loader'" class="older-loader">
                  <span v-if="chat.loadingOlder" class="spinner"></span>
                  <span>{{ chat.loadingOlder ? '正在加载更早消息…' : (chat.hasOlder ? '还有更早的历史消息,滚动到顶加载' : '已加载全部历史消息') }}</span>
                </div>
                <!-- 已到最早时的顶部占位:保持首条消息与对话框顶部的间距 -->
                <div v-else-if="item.kind === 'top-space'" class="top-space"></div>
                <!-- agent 冷启动连接中:空闲回收后发消息,先拉起容器再回复;有流式内容即消失 -->
                <div v-else-if="item.kind === 'connecting'" class="connecting-dock">
                  <span class="connecting">正在连接智能体,请稍候…</span>
                </div>
                <!-- 压缩分割线:此前的早期内容已总结,不展示在对话里(文案固定,压缩中状态由底部占位单独表示) -->
                <div v-else-if="item.kind === 'compact-divider'" class="compact-divider">
                  <span class="cd-line"></span>
                  <span class="cd-label">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>
                    会话已压缩:早期内容已总结
                  </span>
                  <span class="cd-line"></span>
                </div>
                <!-- 压缩进行中:消息流末尾的状态占位(当前可视区域可见) -->
                <div v-else-if="item.kind === 'compact-progress'" class="compact-progress">
                  <span class="spinner"></span>
                  <span>正在压缩会话,总结早期内容…</span>
                </div>
                <!-- 中断分割线:用户中断了本轮生成 -->
                <div v-else-if="item.kind === 'interrupted-divider'" class="interrupted-divider">
                  <span class="id-line"></span>
                  <span class="id-label">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="7.86 2 16.14 2 22 7.86 22 16.14 16.14 22 7.86 22 2 16.14 2 7.86 7.86 2"/><path d="m15 9-6 6"/><path d="m9 9 6 6"/></svg>
                    已中断
                  </span>
                  <span class="id-line"></span>
                </div>
              </div>
              </div>
            </div>
          </template>
            <div v-else-if="chat.currentSessionId" class="chat-virtual-inner">
              <WelcomeBoard @select="onScenarioSelect" />
            </div>
            <n-empty v-else description="选择左侧会话或点击「新会话」开始" style="margin-top: 80px" />
          <!-- 切换/定位遮罩:绝对定位覆盖在列表之上,不卸载列表,避免切换时闪一下顶部 -->
          <div v-if="chat.switching || locating" class="switching-mask">
            <n-spin size="small" />
            <span>{{ chat.switching ? '正在加载会话…' : '正在定位消息…' }}</span>
          </div>
        </div>

        <div v-if="chat.currentSessionId" class="composer-wrap">
          <ChatInput ref="chatInputRef" @open-doc-gen="docGenShow = true" @open-error-diagnose="errorDiagnoseShow = true" @open-test-analysis="onTestAnalysis" />
        </div>
      </div>

      <!-- 工作区:常驻右侧面板,可拖动左缘调整宽度 -->
      <div
        class="workspace-panel"
        :class="{ 'workspace-collapsed': !showFiles, 'ws-resizing': wsResizing }"
        :style="{ width: wsWidth + 'px' }"
      >
        <!-- 宽度拖动条(拖动改变工作区宽度) -->
        <div v-if="showFiles && !isMobile" class="ws-divider" :title="'拖动调整宽度'" @mousedown.prevent="startResize"></div>
        <div class="workspace-body" v-if="showFiles">
          <!-- 已打开的标签栏(可关闭) -->
          <div v-if="openTabList.length" class="ws-tabbar">
            <div
              v-for="t in openTabList"
              :key="t.key"
              class="ws-tab"
              :class="{ active: activeTab === t.key }"
              @click="activeTab = t.key"
            >
              <span class="ws-tab-label">{{ t.label }}</span>
              <span class="ws-tab-close" title="关闭" @click.stop="closeTab(t.key)">
                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </span>
            </div>
          </div>
          <div class="workspace-tabs" :class="{ 'ws-tab-browser': activeTab === 'browser' }">
            <template v-if="activeTab === 'files'">
              <FileTree :changed-paths="Array.from(chat.changedFilePaths)" @ask-ai="onAskAIFile" />
            </template>
            <template v-else-if="activeTab === 'skills'">
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
              <div class="user-skills">
              <div class="us-head">
                <span class="us-title">用户技能</span>
                <n-button size="tiny" type="primary" secondary @click="openCreateSkill">＋ 新建技能</n-button>
              </div>
              <div v-if="!userSkills.length" class="us-empty">
                暂无用户技能,点击「新建技能」创建,或在技能市场安装
              </div>
              <div v-for="s in userSkills" :key="s.id" class="us-item">
                <div class="us-main">
                  <span class="us-name">{{ s.name }}</span>
                  <span class="us-tag" :class="s.visibility === 1 ? 'pub' : 'pri'">{{ s.visibility === 1 ? '公开' : '私有' }}</span>
                  <span class="us-tag" :class="s.source === 'installed' ? 'inst' : 'own'">{{ s.source === 'installed' ? '市场安装' : '自建' }}</span>
                  <div class="us-desc">{{ s.description }}</div>
                </div>
                <div class="us-actions">
                  <n-button size="tiny" quaternary :disabled="s.source === 'installed'" title="编辑(市场安装的技能只读)" @click="openEditSkill(s)">编辑</n-button>
                  <n-button size="tiny" quaternary type="error" @click="removeUserSkill(s)">删除</n-button>
                </div>
              </div>
            </div>
            </template>
            <template v-else-if="activeTab === 'jobs'">
              <JobRunsPanel />
            </template>
            <template v-else-if="activeTab === 'favorites'">
              <FavoritesPanel @open-session="onOpenFavorite" />
            </template>
            <template v-else-if="activeTab === 'browser'">
              <BrowserCanvas auto-active :follow="browserFollowing" @stopped="closeTab('browser')" />
            </template>
            <n-empty v-else description="从上方按钮打开面板" style="margin-top: 40px" />
          </div>
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
  <SkillMarketplace
    v-model:show="marketplaceShow"
    @installed="onSkillInstalled"
  />
  <n-modal v-model:show="skillsOpen" preset="card" title="会话技能配置" style="width: 620px; max-width: 94vw" :bordered="false">
    <div class="sp-desc">内置技能默认加载;下面是你的自定义技能(市场安装/自己创建),点选到右侧即启用。</div>
    <div class="sp-grid">
      <div class="sp-col">
        <div class="sp-col-title">技能列表</div>
        <div v-if="!skillAvail.length" class="sp-empty">暂无可用技能</div>
        <div v-for="s in skillAvail" :key="s" class="sp-item" :title="`启用 ${s}`" @click="addSkill(s)">
          <span class="sp-name">{{ s }}</span>
          <span class="sp-arrow">→</span>
        </div>
      </div>
      <div class="sp-col sp-col-selected">
        <div class="sp-col-title">已选择</div>
        <div v-if="!skillsPick.length" class="sp-empty">未选择</div>
        <div v-for="s in skillsPick" :key="s" class="sp-item" :title="`移除 ${s}`" @click="removeSkill(s)">
          <span class="sp-name">{{ s }}</span>
          <span class="sp-arrow">✕</span>
        </div>
      </div>
    </div>
    <div class="sp-actions">
      <n-button size="small" quaternary @click="skillsOpen = false">取消</n-button>
      <n-button size="small" type="primary" @click="applySkills">应用</n-button>
    </div>
  </n-modal>

  <n-modal v-model:show="skillModalShow" preset="card" :title="skillEditingId ? '编辑技能' : '新建技能'" style="width: 640px; max-width: 94vw" :bordered="false">
    <div class="sk-form">
      <div class="sk-row">
        <span class="sk-label">技能名</span>
        <n-input v-model:value="skillForm.name" :disabled="!!skillEditingId" placeholder="小写字母/数字/连字符,如 note-summarizer" />
      </div>
      <div class="sk-row">
        <span class="sk-label">描述</span>
        <n-input v-model:value="skillForm.description" placeholder="一句话说明何时使用该技能" />
      </div>
      <div class="sk-row">
        <span class="sk-label">可见性</span>
        <n-select
          v-model:value="skillForm.visibility"
          :options="[{ label: '私有(仅自己可见/使用)', value: 0 }, { label: '公开(发布到技能市场)' , value: 1 }]"
          placeholder="可见性"
        />
      </div>
      <div class="sk-row">
        <span class="sk-label">内容(SKILL.md)</span>
        <n-input
          v-model:value="skillForm.content"
          type="textarea"
          :rows="12"
          placeholder="技能正文,可含 markdown;frontmatter 会自动补齐(name/description)"
        />
      </div>
      <div class="sk-row">
        <span class="sk-label">
          附加文件(脚本/数据/参考等,随技能打包进 skills/&lt;name&gt;/)
          <n-button size="tiny" quaternary type="primary" style="margin-left:6px" @click="addSkillFile">＋ 添加文件</n-button>
        </span>
        <div v-if="skillForm.files.length" class="sk-files">
          <div v-for="(f, fi) in skillForm.files" :key="fi" class="sk-file">
            <div class="sk-file-head">
              <n-input v-model:value="f.path" size="small" placeholder="文件路径(含文件名),如 scripts/tool.py" class="sk-file-path" />
              <n-button size="tiny" quaternary type="error" @click="removeSkillFile(fi)">✕</n-button>
            </div>
            <n-input v-model:value="f.content" type="textarea" :rows="5" placeholder="文件内容" />
          </div>
        </div>
        <div v-else class="sk-empty" style="font-size:12px;color:var(--text-3)">暂无附加文件(可选)</div>
      </div>
      <div class="sk-actions">
        <n-button size="small" quaternary @click="skillModalShow = false">取消</n-button>
        <n-button size="small" type="primary" @click="saveSkill">保存</n-button>
      </div>
    </div>
  </n-modal>

  <AutomationManager v-model:show="automationShow" />
</template>

<style scoped>
.chat-view { display: flex; flex-direction: column; height: 100vh; height: 100dvh; background: var(--chat-bg); }
.chat-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 20px; border-bottom: 1px solid var(--border-soft);
  background: var(--bg-elev);
}
.head-left { display: flex; align-items: center; gap: 10px; }
.head-left .title { font-weight: 600; color: var(--text); font-size: 15px; }
/* 连接状态徽标:与主题一致的小圆点 + 文字 */
.conn-badge {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 2px 9px; border-radius: 10px;
  font-size: 11px; line-height: 1.6;
  color: var(--text-3); background: var(--surface-hover);
  border: 1px solid var(--border-soft);
  white-space: nowrap;
}
.conn-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--text-4);
  flex-shrink: 0;
}
.conn-badge.ok { color: var(--ok); }
.conn-badge.ok .conn-dot { background: var(--ok); }

.sp-desc { font-size: 12px; color: var(--text-3); margin-bottom: 10px; }
.sp-grid { display: flex; gap: 12px; min-height: 220px; }
.sp-col { flex: 1; border: 1px solid var(--border-soft); border-radius: var(--radius-sm); padding: 8px; overflow:auto; max-height: 320px; background: var(--bg-elev); }
.sp-col-selected { border-color: rgba(139,92,246,.4); }
.sp-col-title { font-size: 12px; font-weight: 600; color: var(--text-2); margin-bottom: 6px; }
.sp-empty { font-size: 12px; color: var(--text-4); padding: 10px 2px; }
.sp-item { display: flex; align-items: center; justify-content: space-between; padding: 5px 8px; border-radius: 6px; font-size: 13px; cursor: pointer; color: var(--text); }
.sp-item:hover { background: var(--surface-hover); }
.sp-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sp-arrow { color: var(--accent); flex-shrink: 0; }
.sp-col-selected .sp-arrow { color: var(--err); }
.sp-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }

.user-skills { padding: 8px 0; }
.us-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.us-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.us-empty { font-size: 12px; color: var(--text-3); padding: 12px 2px; }
.us-item { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 8px 10px; border: 1px solid var(--border-soft); border-radius: var(--radius-sm); margin-bottom: 8px; background: var(--bg-elev); }
.us-main { min-width: 0; flex: 1; }
.us-name { font-size: 13px; font-weight: 600; color: var(--text); margin-right: 6px; }
.us-tag { font-size: 11px; padding: 1px 6px; border-radius: 8px; margin-right: 6px; }
.us-tag.pub { background: rgba(139,92,246,.14); color: var(--accent); }
.us-tag.pri { background: var(--surface-hover); color: var(--text-3); }
.us-tag.own { background: rgba(88,196,155,.12); color: var(--ok); }
.us-tag.inst { background: #f59e0b22; color: #f59e0b; }
.us-desc { font-size: 12px; color: var(--text-2); margin-top: 3px; word-break: break-word; }
.us-actions { display: flex; flex-direction: column; gap: 2px; flex-shrink: 0; }

.sk-form { display: flex; flex-direction: column; gap: 12px; }
.sk-row { display: flex; flex-direction: column; gap: 4px; }
.sk-label { font-size: 12px; color: var(--text-2); }
.sk-files { display: flex; flex-direction: column; gap: 8px; }
.sk-file { display: flex; flex-direction: column; gap: 6px; border: 1px solid var(--border-soft); border-radius: var(--radius-sm); padding: 6px; }
.sk-file-head { display: flex; gap: 6px; align-items: center; }
.sk-file-path { flex: 1; }
.sk-file :deep(.n-input) { border-radius: 6px; }
.sk-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
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
}

/* 中窄屏顶栏拥挤:隐藏面板入口按钮的文字,只保留图标(悬停有 title 提示) */
@media (max-width: 1200px) {
  .ws-launcher-label { display: none; }
}

/* 左右两栏:左=对话区(自适应),右=工作区(拖动左缘调整宽度) */
.chat-main { flex: 1; display: flex; min-height: 0; position: relative; }
.chat-col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.chat-body { flex: 1; overflow: hidden; min-height: 0; position: relative; }
.chat-virtual { height: 100%; overflow-y: auto; overflow-x: hidden; scrollbar-gutter: stable; overflow-anchor: none; }
/* 内容整体包装层:高度随所有消息增长,由 ResizeObserver 监听以在撑高时钉底 */
.chat-virtual-container { min-width: 0; }
.chat-virtual-inner { max-width: 860px; margin: 0 auto; padding: 0 20px; }
.chat-virtual-inner > * { padding-top: 24px; padding-bottom: 24px; }
.composer-wrap { max-width: 860px; margin: 0 auto; width: 100%; }
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

/* 已打开标签栏:类似编辑器标签,可关闭 */
.ws-tabbar {
  display: flex; gap: 2px;
  padding: 6px 8px 0;
  border-bottom: 1px solid var(--border-soft);
  overflow-x: auto;
}
.ws-tab {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 8px; border-radius: 6px 6px 0 0;
  font-size: 12px; color: var(--text-2);
  cursor: pointer; white-space: nowrap;
  border: 1px solid transparent; border-bottom: none;
}
.ws-tab:hover { background: var(--surface-hover); }
.ws-tab.active {
  color: var(--accent); background: var(--surface);
  border-color: var(--border-soft);
}
.ws-tab-close {
  display: inline-flex; align-items: center; justify-content: center;
  width: 14px; height: 14px; border-radius: 50%;
  color: var(--text-3);
}
.ws-tab-close:hover { background: var(--border-soft); color: var(--text); }

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
.flow-item { border-radius: 8px; }
.flow-item.find-flash {
  outline: 2px solid var(--warn);
  outline-offset: 4px;
  animation: find-flash 1.6s ease-out;
}
@keyframes find-flash {
  0% { background: rgba(245, 158, 11, 0.15); }
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
.builtin-name { font-size: 12px; font-weight: 600; color: var(--accent); }
.builtin-desc {
  font-size: 12px; color: var(--text-2); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
</style>
