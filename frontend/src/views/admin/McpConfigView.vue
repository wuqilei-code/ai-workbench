<script setup lang="ts">
import { useRouter } from 'vue-router'
import McpManager from '@/components/McpManager.vue'

const props = withDefaults(defineProps<{ scope?: 'admin' | 'user' }>(), { scope: 'admin' })

const router = useRouter()
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <n-button @click="router.push('/')">← 返回</n-button>
      <h2>{{ props.scope === 'admin' ? 'MCP Server 管理（公共）' : '我的 MCP Server' }}</h2>
      <div class="spacer" />
    </div>
    <McpManager :scope="props.scope" />
  </div>
</template>

<style scoped>
.admin-view {
  min-height: 100vh; background: var(--bg);
  max-width: 900px; margin: 0 auto; padding: 20px;
  box-sizing: border-box;
}
.admin-head {
  display: flex; align-items: center; gap: 12px; margin-bottom: 20px;
  padding: 14px 16px; background: var(--bg-elev);
  border: 1px solid var(--border-soft); border-radius: var(--radius);
}
.admin-head h2 { margin: 0; font-size: 16px; color: var(--text); }
.spacer { flex: 1; }
</style>