<script setup lang="ts">
import { computed, ref, watch, nextTick, onMounted } from 'vue'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ content: string }>()
const html = computed(() => renderMarkdown(props.content))
const mdBody = ref<HTMLElement | null>(null)

let mermaidId = 0

async function renderMermaid() {
  if (!mdBody.value) return
  // 找所有 .code-block,按钮文本含 "mermaid" 的
  const blocks = mdBody.value.querySelectorAll('.code-block')
  let hasMermaid = false
  for (const block of Array.from(blocks)) {
    const btn = block.querySelector('.code-copy-btn')
    const label = btn?.textContent || ''
    if (!label.includes('mermaid')) continue
    const codeEl = block.querySelector('code')
    if (!codeEl || codeEl.dataset.rendered) continue
    hasMermaid = true
    const code = codeEl.textContent || ''
    // 动态导入 mermaid
    const { default: mermaid } = await import('mermaid')
    mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })
    const id = `mermaid-${++mermaidId}`
    try {
      const { svg } = await mermaid.render(id, code)
      const container = document.createElement('div')
      container.className = 'mermaid-rendered'
      container.innerHTML = svg
      block.replaceWith(container)
    } catch {
      codeEl.dataset.rendered = '1'
    }
  }
}

watch(() => props.content, () => { nextTick(renderMermaid) })
onMounted(() => { nextTick(renderMermaid) })
</script>

<template>
  <div ref="mdBody" class="md-body" v-html="html" />
</template>

<style scoped>
.md-body {
  padding: 16px 24px; overflow: auto; height: 100%; box-sizing: border-box;
  color: var(--text); font-size: 14px; line-height: 1.7;
}
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3) {
  margin: 16px 0 8px; font-weight: 600; color: var(--text);
}
.md-body :deep(h1) { font-size: 1.5em; border-bottom: 1px solid var(--border); padding-bottom: 6px; }
.md-body :deep(h2) { font-size: 1.3em; }
.md-body :deep(h3) { font-size: 1.15em; }
.md-body :deep(p) { margin: 8px 0; }
.md-body :deep(ul), .md-body :deep(ol) { padding-left: 24px; margin: 8px 0; }
.md-body :deep(li) { margin: 4px 0; }
.md-body :deep(code) {
  background: var(--bg-elev); padding: 2px 5px; border-radius: 3px;
  font-family: ui-monospace, monospace; font-size: 0.9em; color: var(--accent-text);
}
.md-body :deep(pre > code) {
  display: block; padding: 12px 16px; overflow-x: auto; border-radius: 6px;
  background: var(--bg-elev); white-space: pre;
}
.md-body :deep(blockquote) {
  border-left: 3px solid var(--border); padding-left: 12px; margin: 8px 0; color: var(--text-3);
}
.md-body :deep(table) { border-collapse: collapse; margin: 8px 0; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid var(--border); padding: 6px 12px; }
.md-body :deep(th) { background: var(--surface-hover); }
.md-body :deep(a) { color: var(--accent); }
.md-body :deep(.mermaid-rendered) {
  margin: 12px 0; text-align: center; overflow-x: auto;
}
.md-body :deep(.mermaid-rendered svg) { max-width: 100%; height: auto; }
</style>
