<script setup lang="ts">
import { ref, computed } from 'vue'
import type { ChangeSummary } from '@/types'

const props = defineProps<{ summary: ChangeSummary }>()

/** 文件太多时默认只展示前 N 个,其余折叠,避免 680 个 chip 撑爆卡片 */
const MAX_VISIBLE_FILES = 30
const showAllFiles = ref(false)
const visibleFiles = computed(() =>
  showAllFiles.value ? (props.summary.files || []) : (props.summary.files || []).slice(0, MAX_VISIBLE_FILES),
)
const hiddenCount = computed(() => Math.max(0, (props.summary.files?.length || 0) - MAX_VISIBLE_FILES))

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
      <span class="icon">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="16" y2="17"/></svg>
      </span>
      <span class="title">文件变动</span>
      <span v-if="summary.files?.length" class="count">本轮改动 {{ summary.files.length }} 个文件</span>
    </div>
    <div v-if="summary.files?.length" class="summary-files">
      <span
        v-for="(f, i) in visibleFiles"
        :key="i"
        class="file-chip"
        :title="f.path"
      >
        <span class="st">{{ statusLabel(f.status) }}</span>
        {{ f.path.split(/[\\/]/).pop() }}
      </span>
      <span v-if="hiddenCount > 0" class="file-chip more-chip" @click="showAllFiles = !showAllFiles">
        {{ showAllFiles ? '收起' : `… 还有 ${hiddenCount} 个文件` }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.summary-card {
  margin: 4px 0 16px 48px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius);
  background: var(--accent-soft);
  padding: 12px 16px;
  max-width: 75%;
}

/* 移动端:头像变小(26px)+间距收紧(8px)+消息内边距(2px),内容列左缩进变为 36px,同步对齐「结果文件」 */
@media (max-width: 768px) {
  .summary-card { margin-left: 36px; max-width: 82%; }
}
.summary-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.summary-head .icon { font-size: 14px; }
.summary-head .title { font-size: 13px; font-weight: 600; color: var(--accent-text); }
.summary-head .count { font-size: 12px; color: var(--text-3); }
.summary-files { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.file-chip {
  font-size: 12px; padding: 3px 10px; border-radius: 10px;
  background: var(--surface); border: 1px solid var(--border); color: var(--text-2);
  max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.more-chip { border-style: dashed; color: var(--accent-text); background: transparent; cursor: pointer; }
.more-chip:hover { background: var(--accent-soft); }
.file-chip .st { color: var(--accent-text); font-weight: 600; margin-right: 4px; }
</style>
