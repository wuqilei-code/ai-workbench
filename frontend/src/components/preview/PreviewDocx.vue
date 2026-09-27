<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderAsync } from 'docx-preview'

const { t } = useI18n()
const props = defineProps<{ blob: Blob }>()
const container = ref<HTMLElement | null>(null)
const error = ref<string | null>(null)
const rendering = ref(false)

async function render(blob: Blob) {
  // blob 未加载完成时为空 Blob,跳过,等真实数据到达再渲染
  if (!container.value || !blob || blob.size === 0) return
  error.value = null
  rendering.value = true
  if (container.value) container.value.innerHTML = ''
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
    error.value = e instanceof Error ? e.message : t('preview.docxFailed')
  } finally {
    rendering.value = false
  }
}

watch(() => props.blob, (b) => { if (b) render(b) }, { immediate: true })
onBeforeUnmount(() => { if (container.value) container.value.innerHTML = '' })
</script>

<template>
  <div class="docx-scroll">
    <div ref="container" class="docx-container" />
    <div v-if="rendering" class="docx-rendering">
      <span class="spinner"></span>
      <span>{{ t('tail.docxRendering') }}</span>
    </div>
    <div v-if="error && !rendering" class="docx-error">{{ t('preview.docxFailed') }}:{{ error }}</div>
  </div>
</template>

<style scoped>
.docx-scroll {
  position: relative;
  overflow: auto; height: 100%;
  background: #f0f0f0; padding: 16px;
}
.docx-rendering {
  position: absolute; inset: 0; z-index: 2;
  display: flex; align-items: center; justify-content: center; gap: 10px;
  background: rgba(240, 240, 240, 0.9);
  color: #666; font-size: 13px;
}
.spinner {
  width: 16px; height: 16px; border: 2px solid #ccc; border-top-color: #555;
  border-radius: 50%; animation: docx-spin 0.8s linear infinite;
}
@keyframes docx-spin { to { transform: rotate(360deg); } }
.docx-container {
  background: #fff; max-width: 960px; margin: 0 auto;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15); padding: 40px; min-height: 200px;
  color: #333;
}
.docx-error { color: #ff6b6b; padding: 16px; text-align: center; }
</style>
