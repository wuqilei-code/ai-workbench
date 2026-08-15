<script setup lang="ts">
defineProps<{ content: string }>()
</script>

<template>
  <pre class="preview-text">{{ content }}</pre>
</template>

<style scoped>
.preview-text {
  margin: 0; padding: 12px;
  white-space: pre-wrap; word-break: break-word;
  font-family: ui-monospace, 'Cascadia Code', monospace;
  font-size: 13px; line-height: 1.6;
  color: var(--text);
  overflow: auto; height: 100%; box-sizing: border-box;
}
</style>
