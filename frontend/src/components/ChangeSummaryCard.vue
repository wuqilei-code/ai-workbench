<script setup lang="ts">
import type { ChangeSummary } from '@/types'

defineProps<{ summary: ChangeSummary }>()

function statusLabel(status: string): string {
  switch (status) {
    case 'A': return '新增'
    case 'M': return '修改'
    case 'D': return '删除'
    case 'R': return '重命名'
    default: return status
  }
}
</script>

<template>
  <div class="summary-card">
    <div class="summary-head">
      <span class="icon">📝</span>
      <span class="title">AI 变更总结</span>
      <span v-if="summary.files?.length" class="count">本轮改动 {{ summary.files.length }} 个文件</span>
    </div>
    <div class="summary-text">{{ summary.summary }}</div>
    <div v-if="summary.files?.length" class="summary-files">
      <span
        v-for="(f, i) in summary.files"
        :key="i"
        class="file-chip"
        :title="f.path"
      >
        <span class="st">{{ statusLabel(f.status) }}</span> {{ f.path.split(/[\\/]/).pop() }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.summary-card {
  margin: 4px 0 16px;
  border: 1px solid #3a3a40;
  border-left: 3px solid #7c5cff;
  border-radius: 8px;
  background: rgba(124, 92, 255, 0.06);
  padding: 10px 14px;
  max-width: 75%;
}
.summary-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.summary-head .icon { font-size: 14px; }
.summary-head .title { font-size: 13px; font-weight: 600; color: #c4b5fd; }
.summary-head .count { font-size: 12px; color: #888; }
.summary-text { font-size: 14px; line-height: 1.6; color: #e0e0e6; word-break: break-word; }
.summary-files { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.file-chip {
  font-size: 12px; padding: 2px 8px; border-radius: 10px;
  background: #26262a; border: 1px solid #333; color: #b0b0b6;
  max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.file-chip .st { color: #7c5cff; font-weight: 600; margin-right: 4px; }
</style>
