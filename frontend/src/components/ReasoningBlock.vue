<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue'

const props = defineProps<{ text: string; isStreaming?: boolean }>()

/** 用户手动展开/折叠覆盖;null 恢复自动态 */
const userOverride = ref<boolean | null>(null)

/** 自动态:流式中展开,完成后折叠;手动态优先 */
const expanded = computed(() => userOverride.value ?? props.isStreaming)

watch(
  () => props.isStreaming,
  (val) => {
    if (!val) userOverride.value = null
  },
)

function toggle() {
  userOverride.value = !expanded.value
}

const lineCount = computed(() => {
  const t = props.text?.trim()
  return t ? t.split('\n').length : 0
})

const boxEl = ref<HTMLElement | null>(null)
watch([() => props.text, expanded], async () => {
  if (expanded.value && boxEl.value) {
    await nextTick()
    boxEl.value.scrollTop = boxEl.value.scrollHeight
  }
})
</script>

<template>
  <div v-if="text" class="reasoning">
    <button class="reasoning-head" @click="toggle">
      <svg class="chevron" :class="{ open: expanded }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
      <span class="label">思考链</span>
      <span v-if="lineCount > 0" class="meta">{{ lineCount }} 行</span>
      <span v-if="isStreaming" class="run-tag">生成中…</span>
    </button>
    <div v-if="expanded" ref="boxEl" class="reasoning-body">{{ text }}</div>
  </div>
</template>

<style scoped>
.reasoning { margin: 2px 0; }
.reasoning-head {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 2px 4px; margin-left: -4px;
  border: none; background: none; cursor: pointer;
  font-size: 12px; color: var(--text-3);
  border-radius: 4px;
}
.reasoning-head:hover { color: var(--text-2); background: var(--surface-hover); }
.reasoning-head .chevron { transition: transform .15s; flex-shrink: 0; }
.reasoning-head .chevron.open { transform: rotate(90deg); }
.reasoning-head .label { font-weight: 500; }
.reasoning-head .meta { font-size: 11px; }
.reasoning-head .run-tag { color: var(--accent); font-size: 11px; }
.reasoning-body {
  margin: 2px 0 4px 18px; padding-left: 12px;
  border-left: 2px solid var(--accent-soft);
  font-size: 12px; color: var(--text-3);
  font-style: italic; line-height: 1.6;
  white-space: pre-wrap; word-break: break-word;
  max-height: 180px; overflow-y: auto;
}
</style>
