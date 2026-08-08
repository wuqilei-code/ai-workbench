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
  <div v-if="text" class="reasoning-wrap">
    <div class="reasoning-header" @click="toggle">
      <span class="reasoning-toggle">{{ expanded ? '▼' : '▶' }}</span>
      思维链
      <span v-if="lineCount > 0" class="reasoning-meta">{{ lineCount }} 行</span>
      <span v-if="isStreaming" class="reasoning-running-tag">输出中…</span>
    </div>
    <div v-if="expanded" ref="boxEl" class="reasoning-box">{{ text }}</div>
  </div>
</template>

<style scoped>
.reasoning-wrap {
  margin: 4px 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  overflow: hidden;
}
.reasoning-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  cursor: pointer;
  user-select: none;
  font-size: 12px;
  color: var(--text-2);
  background: var(--bg-elev);
}
.reasoning-header:hover {
  background: var(--surface-hover);
}
.reasoning-toggle {
  font-size: 10px;
  width: 12px;
  text-align: center;
  flex-shrink: 0;
}
.reasoning-meta {
  color: var(--text-3);
  font-size: 11px;
}
.reasoning-running-tag {
  color: var(--accent);
  font-size: 11px;
  margin-left: auto;
}
.reasoning-box {
  font-size: 12px;
  color: var(--text-2);
  font-style: italic;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-elev);
  padding: 6px 10px;
  max-height: 180px;
  overflow-y: auto;
  border-top: 1px solid var(--border-soft);
}
.reasoning-box::-webkit-scrollbar {
  width: 6px;
}
.reasoning-box::-webkit-scrollbar-thumb {
  background: var(--border);
  border-radius: 3px;
}
</style>
