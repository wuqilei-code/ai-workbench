<script setup lang="ts">
import { computed } from 'vue'
import hljs from 'highlight.js'

const props = defineProps<{ content: string; lang?: string }>()

const highlighted = computed(() => {
  if (!props.content) return ''
  const lang = props.lang && hljs.getLanguage(props.lang) ? props.lang : null
  if (lang) {
    try { return hljs.highlight(props.content, { language: lang }).value } catch {}
  }
  return hljs.highlightAuto(props.content).value
})
</script>

<template>
  <pre class="hljs preview-code"><code v-html="highlighted" /></pre>
</template>

<style scoped>
.preview-code {
  margin: 0; padding: 12px;
  overflow: auto; height: 100%; box-sizing: border-box;
  font-family: ui-monospace, 'Cascadia Code', monospace;
  font-size: 13px; line-height: 1.6;
}
.preview-code code { background: none; padding: 0; }
</style>
