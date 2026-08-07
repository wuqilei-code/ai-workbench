<script setup lang="ts">
import { onMounted } from 'vue'
import { useChatStore } from '@/stores/chat'
import Sidebar from '@/components/Sidebar.vue'

const chat = useChatStore()

onMounted(async () => {
  await Promise.all([chat.loadModels(), chat.loadSessions(), chat.loadArchivedSessions()])
})
</script>

<template>
  <n-layout has-sider style="height: 100vh">
    <Sidebar />
    <n-layout>
      <n-layout-content content-style="height:100vh; overflow:hidden">
        <router-view />
      </n-layout-content>
    </n-layout>
  </n-layout>
</template>
