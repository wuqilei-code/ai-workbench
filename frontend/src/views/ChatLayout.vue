<script setup lang="ts">
import { onMounted, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import Sidebar from '@/components/Sidebar.vue'
import Onboarding from '@/components/Onboarding.vue'

const chat = useChatStore()
const router = useRouter()
const onboardingRef = ref<InstanceType<typeof Onboarding> | null>(null)

/** 窄屏(<960px)侧边栏默认收起;把宽度变化告知 Sidebar 以联动汉堡按钮。 */
const isNarrow = ref(false)
const sidebarCollapsed = ref(false)

function applyIsNarrow() {
  isNarrow.value = window.innerWidth < 960
}

onMounted(() => {
  applyIsNarrow()
  window.addEventListener('resize', applyIsNarrow)
  Promise.all([chat.loadModels(), chat.loadSessions(), chat.loadArchivedSessions()]).then(async () => {
    // 首次登录/无任何会话时,默认创建一个新会话并跳转到它,避免空列表无从下手
    if (chat.sessions.length === 0) {
      const s = await chat.createSession('新对话')
      if (s) router.replace(`/session/${s.id}`)
    }
    nextTick(() => onboardingRef.value?.tryShowOnboarding())
  })
})
</script>

<template>
  <n-layout has-sider style="height: 100vh">
    <Sidebar :collapsed="sidebarCollapsed" :narrow="isNarrow" @update:collapsed="sidebarCollapsed = $event" />
    <n-layout>
      <n-layout-content content-style="height:100vh; overflow:hidden">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
  <Onboarding ref="onboardingRef" />
</template>