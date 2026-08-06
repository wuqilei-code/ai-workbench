<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { renderAsync } from 'docx-preview'

const props = defineProps<{ blob: Blob }>()
const container = ref<HTMLElement | null>(null)
const error = ref<string | null>(null)

async function render(blob: Blob) {
  if (!container.value || !blob) return
  error.value = null
  container.value.innerHTML = ''
  try {
    await renderAsync(blob, container.value, undefined, {
      className: 'docx',
      inWrapper: true,
      ignoreWidth: false,
      ignoreHeight: false,
      breakPages: true,
      experimental: true,
      useBase64URL: true,
    })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '文档渲染失败'
  }
}

watch(() => props.blob, (b) => { if (b) render(b) }, { immediate: true })
onBeforeUnmount(() => { if (container.value) container.value.innerHTML = '' })
</script>

<template>
  <div class="docx-scroll">
    <div v-if="error" class="docx-error">docx 渲染失败:{{ error }}</div>
    <div ref="container" class="docx-container" />
  </div>
</template>

<style scoped>
.docx-scroll { overflow: auto; height: 100%; background: #f0f0f0; padding: 16px; }
.docx-container {
  background: #fff; max-width: 960px; margin: 0 auto;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15); padding: 40px; min-height: 200px;
  color: #333;
}
.docx-error { color: #ff6b6b; padding: 16px; text-align: center; }
</style>
