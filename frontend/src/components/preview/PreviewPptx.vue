<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import type { PptxViewer } from '@aiden0z/pptx-renderer'

const props = defineProps<{ blob: Blob }>()

const container = ref<HTMLElement | null>(null)
const error = ref<string | null>(null)
const rendering = ref(false)
const slideCount = ref(0)

let viewer: PptxViewer | null = null

/** 动态引入渲染器(含 ECharts/JSZip),放入独立 chunk,仅在预览 PPTX 时加载 */
async function render(blob: Blob) {
  if (!container.value || !blob || blob.size === 0) return
  error.value = null
  viewer?.destroy()
  viewer = null
  rendering.value = true
  if (container.value) container.value.innerHTML = ''
  slideCount.value = 0
  try {
    const { PptxViewer: V, RECOMMENDED_ZIP_LIMITS } = await import('@aiden0z/pptx-renderer')
    if (!container.value) return
    viewer = await V.open(await blob.arrayBuffer(), container.value, {
      renderMode: 'list',
      zipLimits: RECOMMENDED_ZIP_LIMITS,
      lazySlides: true,
      lazyMedia: true,
      listOptions: { windowed: true, batchSize: 8, initialSlides: 4 },
    })
    slideCount.value = (viewer as PptxViewer).slideCount || 0
  } catch (e) {
    error.value = e instanceof Error ? e.message : '幻灯片渲染失败'
  } finally {
    rendering.value = false
  }
}

watch(() => props.blob, (b) => { if (b) render(b) }, { immediate: true })

onBeforeUnmount(() => {
  viewer?.destroy()
  viewer = null
})
</script>

<template>
  <div class="pptx-scroll">
    <div ref="container" class="pptx-container" />
    <div v-if="rendering" class="pptx-rendering">
      <span class="spinner"></span>
      <span>正在渲染幻灯片…</span>
    </div>
    <div v-if="error && !rendering" class="pptx-error">幻灯片渲染失败:{{ error }}</div>
    <div v-if="!rendering && slideCount && !error" class="pptx-count">共 {{ slideCount }} 页</div>
  </div>
</template>

<style scoped>
.pptx-scroll {
  position: relative;
  height: 100%;
  overflow: auto;
  background: #f0f0f0;
  padding: 16px;
}
.pptx-container { height: 100%; }
.pptx-rendering {
  position: absolute; inset: 0; z-index: 2;
  display: flex; align-items: center; justify-content: center; gap: 10px;
  background: rgba(240, 240, 240, 0.9);
  color: #666; font-size: 13px;
}
.spinner {
  width: 16px; height: 16px; border: 2px solid #ccc; border-top-color: #555;
  border-radius: 50%; animation: pptx-spin 0.8s linear infinite;
}
@keyframes pptx-spin { to { transform: rotate(360deg); } }
.pptx-error { color: #ff6b6b; padding: 16px; text-align: center; }
.pptx-count {
  position: sticky; bottom: 0; z-index: 1;
  text-align: center; font-size: 12px; color: #888;
  padding: 6px 0;
}
</style>