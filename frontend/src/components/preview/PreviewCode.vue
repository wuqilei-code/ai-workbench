<script setup lang="ts">
import { computed } from 'vue'
import hljs from 'highlight.js'

const props = defineProps<{ content: string; lang?: string }>()

/** 超过该长度不再走 highlight.js(大型文件 tokenize 会卡死页面),直接纯文本展示 */
const HIGHLIGHT_MAX = 50_000

const plain = computed(() => props.content.length > HIGHLIGHT_MAX)

const highlighted = computed(() => {
  if (plain.value || !props.content) return ''
  const lang = props.lang && hljs.getLanguage(props.lang) ? props.lang : null
  if (lang) {
    try { return hljs.highlight(props.content, { language: lang }).value } catch {}
  }
  return hljs.highlightAuto(props.content).value
})
</script>

<template>
  <!-- 纯文本(大文件)时允许换行:避免超长单行(如压缩后的 JSON)撑出巨大行宽导致卡顿 -->
  <pre class="hljs preview-code" :class="{ 'preview-code--wrap': plain }"><code v-if="plain">{{ content }}</code><code v-else v-html="highlighted" /></pre>
</template>

<style scoped>
.preview-code {
  margin: 0; padding: 12px;
  overflow: auto; height: 100%; box-sizing: border-box;
  font-family: ui-monospace, 'Cascadia Code', monospace;
  font-size: 13px; line-height: 1.6;
}
.preview-code--wrap { white-space: pre-wrap; word-break: break-word; }
.preview-code code { background: none; padding: 0; }
</style>
