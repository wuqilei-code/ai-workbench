<script setup lang="ts">
import { computed } from 'vue'
import MarkdownRender from 'markstream-vue'
import { useThemeStore } from '@/stores/theme'
import { previewLinks } from '@/utils/markdown'

const props = withDefaults(
  defineProps<{
    /** Markdown 原文(流式期间随 token 增长) */
    content: string
    /** 内容是否已定稿:定稿后按最终态渲染,流式期间交给 markstream 平滑增量渲染 */
    final?: boolean
  }>(),
  { final: true },
)

const theme = useThemeStore()
const isDark = computed(() => theme.mode === 'dark')

/** 记录已注册过 preview_links 的解析器实例,避免重复 push 规则 */
const registeredParsers = new WeakSet<object>()

/** 在 markstream 的解析器上注册预览链接重写规则(与 legacy markdown-it 共用同一规则) */
function configureMarkdownIt(md: any) {
  if (!registeredParsers.has(md)) {
    registeredParsers.add(md)
    md.core.ruler.push('preview_links', previewLinks)
  }
  return md
}
</script>

<template>
  <MarkdownRender
    mode="chat"
    :content="props.content"
    :final="props.final"
    smooth-streaming="auto"
    :fade="false"
    :is-dark="isDark"
    :custom-markdown-it="configureMarkdownIt"
    class="stream-md"
  />
</template>

<style scoped>
/* 预览链接(agent 输出的 localhost/api/preview 地址重写后的按钮样式) */
.stream-md :deep(a.preview-link) {
  display: inline-block;
  margin: 2px 0;
  padding: 4px 12px;
  border-radius: 6px;
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid var(--border);
  text-decoration: none;
  font-size: 13px;
  line-height: 1.6;
}
.stream-md :deep(a.preview-link:hover) { background: var(--accent); color: var(--accent-contrast); }
</style>
