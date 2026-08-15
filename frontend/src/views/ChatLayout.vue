<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, nextTick, provide } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { chatApi } from '@/api/chat'
import Sidebar from '@/components/Sidebar.vue'
import Onboarding from '@/components/Onboarding.vue'

const chat = useChatStore()
const router = useRouter()
const onboardingRef = ref<InstanceType<typeof Onboarding> | null>(null)

/** 窄屏(<960px)侧边栏默认收起;把宽度变化告知 Sidebar 以联动汉堡按钮。 */
const isNarrow = ref(false)
const sidebarCollapsed = ref(false)
/** 首次进入:正在加载模型/会话/归档列表(避免白屏)。 */
const initLoading = ref(true)

/** 侧边栏导航事件:提供给子组件(ChatView)使用 */
const sidebarEvents = ref({
  openPluginMarket: false,
  openTemplateLib: false,
  openMyFiles: false,
  openAutomation: false,
  openFind: false,
  openMyMcp: false,
})
provide('sidebarEvents', sidebarEvents)

/** 侧边栏折叠状态:提供给子组件(ChatView 顶部「展开侧边栏」按钮)使用 */
provide('sidebarCollapsed', sidebarCollapsed)
function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
}
provide('toggleSidebar', toggleSidebar)

function triggerSidebarEvent(key: keyof typeof sidebarEvents.value) {
  sidebarEvents.value[key] = true
  // 重置以便重复触发
  setTimeout(() => { sidebarEvents.value[key] = false }, 100)
}

function applyIsNarrow() {
  isNarrow.value = window.innerWidth < 960
}

onMounted(() => {
  applyIsNarrow()
  window.addEventListener('resize', applyIsNarrow)
  initLoading.value = true
  // 提前拉起该用户 agent 实例(无会话也预热,避免首个「新建会话」冷启动容器等待数秒;
  // 后端幂等:已就绪时几乎零开销)。失败静默,不影响初始化。
  chatApi.warmupMine().catch((e) => console.debug('[chat-layout] warmup skipped', e))
  Promise.all([chat.loadModels(), chat.loadSessions(), chat.loadArchivedSessions()])
    .catch((e) => console.error('[chat-layout] init failed', e))
    .then(async () => {
      // 有历史会话:默认打开最近的一个(列表已按 pinned→updatedAt 排序)
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
  window.removeEventListener('resize', applyIsNarrow)
})
</script>

<template>
  <n-layout has-sider style="height: 100vh">
    <Sidebar
      :collapsed="sidebarCollapsed"
      :narrow="isNarrow"
      @update:collapsed="sidebarCollapsed = $event"
      @open-plugin-market="triggerSidebarEvent('openPluginMarket')"
      @open-template-lib="triggerSidebarEvent('openTemplateLib')"
      @open-my-files="triggerSidebarEvent('openMyFiles')"
      @open-automation="triggerSidebarEvent('openAutomation')"
      @open-find="triggerSidebarEvent('openFind')"
      @open-my-mcp="triggerSidebarEvent('openMyMcp')"
    />
    <n-layout>
      <n-layout-content content-style="height:100vh; overflow:hidden">
        <div v-if="initLoading" class="layout-loading">
          <n-spin size="small" />
          <span>正在加载工作区…</span>
        </div>
        <router-view v-else />
      </n-layout-content>
    </n-layout>
  </n-layout>
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
</style>