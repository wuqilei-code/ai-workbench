<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string }>()
const html = computed(() => renderMarkdown(props.content))
</script>

<template>
  <div class="md-body" v-html="html" />
</template>

<style scoped>
.md-body {
  padding: 16px 24px; overflow: auto; height: 100%; box-sizing: border-box;
  color: #d4d4d4; font-size: 14px; line-height: 1.7;
}
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3) {
  margin: 16px 0 8px; font-weight: 600; color: #e0e0e0;
}
.md-body :deep(h1) { font-size: 1.5em; border-bottom: 1px solid #333; padding-bottom: 6px; }
.md-body :deep(h2) { font-size: 1.3em; }
.md-body :deep(h3) { font-size: 1.15em; }
.md-body :deep(p) { margin: 8px 0; }
.md-body :deep(ul), .md-body :deep(ol) { padding-left: 24px; margin: 8px 0; }
.md-body :deep(li) { margin: 4px 0; }
.md-body :deep(code) {
  background: #2a2a2e; padding: 2px 5px; border-radius: 3px;
  font-family: ui-monospace, monospace; font-size: 0.9em; color: #e6c07b;
}
.md-body :deep(blockquote) {
  border-left: 3px solid #444; padding-left: 12px; margin: 8px 0; color: #999;
}
.md-body :deep(table) { border-collapse: collapse; margin: 8px 0; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid #333; padding: 6px 12px; }
.md-body :deep(th) { background: #26262a; }
.md-body :deep(a) { color: #58a6ff; }
</style>
