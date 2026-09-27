import { ref, computed, watch, type Ref } from 'vue'
import { useChatStore } from '@/stores/chat'
import { i18n } from '@/i18n'

const tr = (k: string) => i18n.global.t(k)
/** 工作区标签项(图标 path + 中文文案)。浏览器不设顶部入口,仅由对话唤出。 */
export interface WorkspaceTab {
  key: string
  label: string
  icon: string
}

const WORKSPACE_TABS: WorkspaceTab[] = [
  { key: 'files', label: tr('workspace2.files'), icon: 'M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z' },
  { key: 'skills', label: tr('workspace2.skills'), icon: 'M19.439 7.85c-.049.322.059.648.289.878l1.568 1.568c.47.47.706 1.087.706 1.704s-.235 1.233-.706 1.704l-1.611 1.611a.98.98 0 0 1-.837.276c-.47-.07-.802-.48-.968-.925a2.501 2.501 0 1 0-3.214 3.214c.446.166.855.497.925.968a.979.979 0 0 1-.276.837l-1.61 1.61a2.404 2.404 0 0 1-1.705.707 2.402 2.402 0 0 1-1.704-.706l-1.568-1.568a1.026 1.026 0 0 0-.877-.29c-.493.074-.84.504-1.02.968a2.5 2.5 0 1 1-3.237-3.237c.464-.18.894-.527.967-1.02a1.026 1.026 0 0 0-.289-.877l-1.568-1.568A2.402 2.402 0 0 1 1.998 12c0-.617.236-1.234.706-1.704L4.23 8.77c.24-.24.581-.353.917-.303.515.077.877.528 1.073 1.01a2.5 2.5 0 1 0 3.259-3.259c-.482-.196-.933-.558-1.01-1.073-.05-.336.062-.676.303-.917l1.525-1.525A2.402 2.402 0 0 1 12 2.001c.617 0 1.234.236 1.704.706l1.568 1.568c.23.23.556.338.877.29.493-.074.84-.504 1.02-.968a2.5 2.5 0 1 1 3.237 3.237c-.464.18-.894.527-.967 1.02Z' },
  { key: 'jobs', label: tr('workspace2.jobs'), icon: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 6v6l4 2' },
  { key: 'favorites', label: tr('workspace2.favorites'), icon: 'M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 21 12 17.77 5.82 21 7 14.14 2 9.27l6.91-1.01z' },
]

/** 浏览器标签元数据(不在顶部入口里 → 无按钮;agent 唤出时仍作为标签展示) */
const BROWSER_TAB: WorkspaceTab = { key: 'browser', label: tr('workspace2.browser'), icon: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 2.07A8 8 0 0 1 20 12h-7zM4.9 5.8l3.4 3.4A3 3 0 0 0 8 9H5.5A8 8 0 0 1 4.9 5.8zM4 12a8 8 0 0 1 .5-2.8H8a3 3 0 0 0-3 3v.5H4zm0 1h1v1h-1zm3 4.2A8 8 0 0 0 12 20c-1.7 0-3.3-.5-4.6-1.4L9 16.9zM20 12a8 8 0 0 1-.5 2.8H16a3 3 0 0 0 3-3v-.5h1zm-8 8a3 3 0 0 1-3-.6L10 17.6a4 4 0 0 0 2-.6c1 .6 1.9 1 3 1.2zm-1-6a3 3 0 1 1 0-6 3 3 0 0 1 0 6z' }

const WS_MIN = 260

/**
 * 右侧工作区面板(文件/技能/定时任务/收藏/浏览器)的开合、标签与宽度拖拽。
 * 从 ChatView 抽出,保持视图只负责渲染。
 *
 * @param opts.isMobile      移动端(打开浏览器时不改宽度;移动端不显示拖拽条)
 * @param opts.onBeforeOpen  打开面板前的副作用(如关闭整页管理视图)
 */
export function useWorkspacePanel(opts: { isMobile: Ref<boolean>; onBeforeOpen?: () => void }) {
  const chat = useChatStore()

  const showFiles = ref(true)
  /** 已打开的标签(可关闭),默认只打开「文件」 */
  const openTabs = ref<string[]>(['files'])
  const activeTab = ref('files')
  /** 面板宽度(px),可拖动调整 */
  const wsWidth = ref(420)
  /** 拖动中(禁用 transition 避免卡顿) */
  const wsResizing = ref(false)

  const workspaceTabs = WORKSPACE_TABS
  const browserTabMeta = BROWSER_TAB

  function openWorkspaceTab(key: string) {
    showFiles.value = true
    if (!openTabs.value.includes(key)) openTabs.value.push(key)
    activeTab.value = key
    if (key === 'browser' && !opts.isMobile.value) {
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
  const browserFollowing = computed(() => chat.isStreaming && Date.now() - chat.lastBrowserToolAt < 120000)

  /** 顶部入口:未激活则打开;再次点击已激活面板则收起 */
  function onLauncherClick(key: string) {
    opts.onBeforeOpen?.()
    if (showFiles.value && activeTab.value === key) {
      showFiles.value = false
    } else {
      openWorkspaceTab(key)
    }
  }

  /** 关闭标签;关闭当前激活标签时自动切到相邻标签 */
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

  let resizeCleanup: (() => void) | null = null

  /** 拖动面板左缘调整宽度:按住 → 随鼠标移动计算新宽度(限最小/最大),松开结束 */
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
      resizeCleanup = null
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
    resizeCleanup = up
  }

  /** 组件卸载兜底:清理拖动期间挂到 document 的监听 */
  function dispose() {
    resizeCleanup?.()
    resizeCleanup = null
  }

  return {
    showFiles,
    openTabs,
    activeTab,
    wsWidth,
    wsResizing,
    workspaceTabs,
    browserTabMeta,
    openTabList,
    browserFollowing,
    openWorkspaceTab,
    closeTab,
    onLauncherClick,
    startResize,
    dispose,
  }
}
