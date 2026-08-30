<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, nextTick, provide, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useFavoritesStore } from '@/stores/favorites'
import Sidebar from '@/components/Sidebar.vue'
import Onboarding from '@/components/Onboarding.vue'

const chat = useChatStore()
const fav = useFavoritesStore()
const router = useRouter()
const onboardingRef = ref<InstanceType<typeof Onboarding> | null>(null)

/** 窄屏(<960px)侧边栏默认收起;移动端(<768px)侧边栏改为抽屉。 */
const isNarrow = ref(false)
const isMobile = ref(false)
const sidebarCollapsed = ref(false)
const mobileSidebarOpen = ref(false)
/** 首次进入:正在加载模型/会话/归档列表(避免白屏)。 */
const initLoading = ref(true)

/** 侧边栏导航事件:提供给子组件(ChatView)使用 */
const sidebarEvents = ref({
  openPluginMarket: false,
  openTemplateLib: false,
  openMcp: false,
  openMyFiles: false,
  openAutomation: false,
  openFind: false,
  openDashboard: false,
})
provide('sidebarEvents', sidebarEvents)

/** 侧边栏折叠状态:提供给子组件(ChatView 顶部「展开侧边栏」按钮)使用 */
provide('sidebarCollapsed', sidebarCollapsed)
/** 是否移动端:ChatView/ChatInput 据此切换抽屉布局 */
provide('isMobile', isMobile)

function toggleSidebar() {
  if (isMobile.value) {
    mobileSidebarOpen.value = !mobileSidebarOpen.value
  } else {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }
}
provide('toggleSidebar', toggleSidebar)

function triggerSidebarEvent(key: keyof typeof sidebarEvents.value) {
  sidebarEvents.value[key] = true
  // 重置以便重复触发
  setTimeout(() => { sidebarEvents.value[key] = false }, 100)
}

function applyBreakpoints() {
  const w = window.innerWidth
  isNarrow.value = w < 960
  isMobile.value = w < 768
}

// 移动端抽屉:路由变化(选中会话/新建)后自动关闭
watch(() => router.currentRoute.value.fullPath, () => {
  mobileSidebarOpen.value = false
})

onMounted(() => {
  applyBreakpoints()
  window.addEventListener('resize', applyBreakpoints)
  initLoading.value = true
  fav.load()
  Promise.all([chat.loadModels(), chat.loadSessions(), chat.loadArchivedSessions()])
    .catch((e) => console.error('[chat-layout] init failed', e))
    .then(async () => {
      // URL 已指定会话(直链/刷新/分享):尊重用户选择,绝不强制跳到最新会话,
      // 否则刷新页面会被弹走,生成跑在 A 会话而人看的是 B 会话(表现为"一直空白")。
      const cur = router.currentRoute.value
      if (cur.name === 'session' && cur.params.id) {
        initLoading.value = false
        nextTick(() => onboardingRef.value?.tryShowOnboarding())
        return
      }
      // 无会话 id:有历史则默认打开最近的一个(列表已按 pinned→updatedAt 排序)
      if (chat.sessions.length > 0) {
        const latest = chat.sessions[0]
        if (latest && router.currentRoute.value.path !== `/session/${latest.id}`) {
          await router.replace(`/session/${latest.id}`)
        }
      } else {
        // 首次登录/无任何会话:默认创建一个新会话并跳转到它,避免空列表无从下手
        const s = await chat.createSession('新对话')
        if (s) router.replace(`/session/${s.id}`)
      }
      initLoading.value = false
      nextTick(() => onboardingRef.value?.tryShowOnboarding())
    })
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', applyBreakpoints)
})
</script>

<template>
  <template v-if="isMobile">
    <n-layout style="height: 100vh; height: 100dvh">
      <n-layout-content content-style="height:100vh; height:100dvh; overflow:hidden">
        <div v-if="initLoading" class="layout-loading">
          <n-spin size="small" />
          <span>正在加载工作区…</span>
        </div>
        <router-view v-else />
      </n-layout-content>
    </n-layout>
    <n-drawer v-model:show="mobileSidebarOpen" placement="left" :width="260" :z-index="200">
      <div class="mobile-sidebar">
        <Sidebar
        :collapsed="false"
        :narrow="true"
        @update:collapsed="mobileSidebarOpen = false"
        @open-plugin-market="mobileSidebarOpen = false; triggerSidebarEvent('openPluginMarket')"
        @open-template-lib="mobileSidebarOpen = false; triggerSidebarEvent('openTemplateLib')"
        @open-mcp="mobileSidebarOpen = false; triggerSidebarEvent('openMcp')"
        @open-my-files="mobileSidebarOpen = false; triggerSidebarEvent('openMyFiles')"
        @open-automation="mobileSidebarOpen = false; triggerSidebarEvent('openAutomation')"
        @open-find="mobileSidebarOpen = false; triggerSidebarEvent('openFind')"
        @open-dashboard="mobileSidebarOpen = false; triggerSidebarEvent('openDashboard')"
      />
      </div>
    </n-drawer>
  </template>
  <template v-else>
    <n-layout has-sider style="height: 100vh">
      <Sidebar
        :collapsed="sidebarCollapsed"
        :narrow="isNarrow"
        @update:collapsed="sidebarCollapsed = $event"
        @open-plugin-market="triggerSidebarEvent('openPluginMarket')"
        @open-template-lib="triggerSidebarEvent('openTemplateLib')"
        @open-mcp="triggerSidebarEvent('openMcp')"
        @open-my-files="triggerSidebarEvent('openMyFiles')"
        @open-automation="triggerSidebarEvent('openAutomation')"
        @open-find="triggerSidebarEvent('openFind')"
        @open-dashboard="triggerSidebarEvent('openDashboard')"
      />
      <n-layout>
        <n-layout-content content-style="height:100vh; height:100dvh; overflow:hidden">
          <div v-if="initLoading" class="layout-loading">
            <n-spin size="small" />
            <span>正在加载工作区…</span>
          </div>
          <router-view v-else />
        </n-layout-content>
      </n-layout>
    </n-layout>
  </template>
  <Onboarding ref="onboardingRef" />
</template>

<style scoped>
.layout-loading {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--text-color-2);
  font-size: 13px;
}
.mobile-sidebar {
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
