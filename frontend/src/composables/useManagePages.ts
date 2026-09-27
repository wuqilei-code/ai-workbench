import { ref, computed, watch, watchEffect, type Ref } from 'vue'

/** 侧边栏派发的导航事件(由 ChatLayout/Sidebar provide) */
export interface SidebarEvents {
  openPluginMarket: boolean
  openTemplateLib: boolean
  openMyFiles: boolean
  openAutomation: boolean
  openFind: boolean
  openDashboard: boolean
  openMcp: boolean
  /** 当前整页(供侧边栏高亮,由本 composable 回写) */
  managePage?: string
  /** 点击会话(含当前会话)时置位:退出整页视图 */
  closeManage?: boolean
  [k: string]: unknown
}

export type ManagePage = 'skills' | 'tools' | 'templates' | 'dashboard' | 'automation'

/**
 * 整页管理视图(技能市场 / 工具库 / 模板库 / 驾驶舱 / 自动化)的互斥开合与侧边栏联动。
 * 从 ChatView 抽出:视图只负责渲染,导航编排集中在此。
 *
 * @param deps.sidebarEvents 侧边栏事件(可为空)
 * @param deps.onOpenFiles   侧边栏点「我的文件」(打开工作区文件面板)
 * @param deps.onOpenFind    侧边栏点「查找会话」
 * @param deps.sessionId     当前会话 id(变化时关闭所有整页,否则点会话切不回聊天)
 */
export function useManagePages(deps: {
  sidebarEvents: Ref<SidebarEvents> | undefined
  onOpenFiles: () => void
  onOpenFind: () => void
  sessionId: Ref<number | null | undefined>
}) {
  const templateLibShow = ref(false)
  const dashboardShow = ref(false)
  const automationShow = ref(false)
  /** 技能市场整页(点侧边栏「插件市场」打开,占聊天窗口) */
  const skillsPageShow = ref(false)
  /** 工具库整页(点侧边栏「工具库」打开,占聊天窗口) */
  const toolsPageShow = ref(false)

  /** 是否处于管理整页(为真时隐藏会话相关头部与聊天区) */
  const managePageActive = computed(
    () =>
      skillsPageShow.value ||
      toolsPageShow.value ||
      templateLibShow.value ||
      dashboardShow.value ||
      automationShow.value,
  )

  /** 关闭技能市场/工具库/模板库三类整页 */
  function closeManagePages() {
    skillsPageShow.value = false
    toolsPageShow.value = false
    templateLibShow.value = false
  }

  /** 关闭所有整页(含驾驶舱/自动化) */
  function closeAll() {
    closeManagePages()
    dashboardShow.value = false
    automationShow.value = false
  }

  /** 打开/收起某个整页(所有整页互斥;再次点击同一项则收起返回当前会话) */
  function openManagePage(which: ManagePage) {
    const active =
      (which === 'skills' && skillsPageShow.value) ||
      (which === 'tools' && toolsPageShow.value) ||
      (which === 'templates' && templateLibShow.value) ||
      (which === 'dashboard' && dashboardShow.value) ||
      (which === 'automation' && automationShow.value)
    closeAll()
    if (active) return // 同一项二次点击 → 收起,回到当前会话
    if (which === 'skills') skillsPageShow.value = true
    else if (which === 'tools') toolsPageShow.value = true
    else if (which === 'templates') templateLibShow.value = true
    else if (which === 'dashboard') dashboardShow.value = true
    else if (which === 'automation') automationShow.value = true
  }

  const ev = deps.sidebarEvents
  if (ev) {
    watch(
      () => ev.value.openPluginMarket,
      (v) => {
        if (v) openManagePage('skills')
      },
    )
    watch(
      () => ev.value.openTemplateLib,
      (v) => {
        if (v) openManagePage('templates')
      },
    )
    watch(
      () => ev.value.openMyFiles,
      (v) => {
        if (v) {
          closeManagePages()
          deps.onOpenFiles()
        }
      },
    )
    watch(
      () => ev.value.openFind,
      (v) => {
        if (v) deps.onOpenFind()
      },
    )
    watch(
      () => ev.value.openAutomation,
      (v) => {
        if (v) openManagePage('automation')
      },
    )
    watch(
      () => ev.value.openDashboard,
      (v) => {
        if (v) openManagePage('dashboard')
      },
    )
    watch(
      () => ev.value.openMcp,
      (v) => {
        if (v) openManagePage('tools')
      },
    )
    // 点击会话(含当前会话)时,退出整页管理视图,切回聊天
    watch(
      () => ev.value.closeManage,
      (v) => {
        if (v) closeAll()
      },
    )
    // 同步当前整页,供侧边栏高亮
    watchEffect(() => {
      ev.value.managePage = skillsPageShow.value
        ? 'skills'
        : toolsPageShow.value
          ? 'tools'
          : templateLibShow.value
            ? 'templates'
            : dashboardShow.value
              ? 'dashboard'
              : automationShow.value
                ? 'automation'
                : ''
    })
  }

  // 切换会话时关闭所有整页(否则点会话切不回聊天)
  watch(
    () => deps.sessionId.value,
    () => closeAll(),
  )

  return {
    templateLibShow,
    dashboardShow,
    automationShow,
    skillsPageShow,
    toolsPageShow,
    managePageActive,
    closeManagePages,
    closeAllManagePages: closeAll,
    openManagePage,
  }
}
