<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'

/**
 * 流式打字效果:
 * - 已写完的行:整行柔和淡入
 * - 正在写的当前行:尾部 dimTail 个字符保持"隐藏光效",其余随输出立即显现;
 *   dimTail=0 时全部立即显现(适用于思考过程这类多行连续流)
 * - 停顿 400ms 后尾部也全部显现
 */
const props = defineProps<{ text: string; active?: boolean; dimTail?: number; maxChars?: number }>()

const tail = computed(() => (props.dimTail === undefined ? 12 : props.dimTail))

/** 超长文本(思考块可达数万字)只渲染尾部,保持 DOM 恒定轻量、流式顺滑 */
const MAX = computed(() => (props.maxChars === undefined ? 0 : props.maxChars))
const truncated = computed(() => MAX.value > 0 && props.text.length > MAX.value)
const hiddenCount = computed(() => (truncated.value ? props.text.length - MAX.value : 0))
const viewText = computed(() => (truncated.value ? props.text.slice(-MAX.value) : props.text))

const lines = computed(() => viewText.value.split('\n'))
const completed = computed(() => lines.value.slice(0, -1))
const pending = computed(() => lines.value[lines.value.length - 1] ?? '')

/** 当前行已显现的字符数(尾部 tail 个字符保持隐藏光效) */
const commitLen = ref(0)
let lastChange = Date.now()
let ticker: ReturnType<typeof setInterval> | null = null

watch(pending, () => {
  lastChange = Date.now()
})

// 换行后进入新的一行:重新从隐藏光效开始(dimTail=0 时无影响,立即显现)
watch(() => completed.value.length, () => {
  commitLen.value = 0
  lastChange = Date.now()
})

ticker = setInterval(() => {
  if (!props.active) return
  const p = pending.value
  const keep = tail.value
  if (Date.now() - lastChange > 400) {
    if (commitLen.value !== p.length) commitLen.value = p.length
    return
  }
  if (p.length > commitLen.value + keep) commitLen.value = p.length - keep
}, 120)

onBeforeUnmount(() => {
  if (ticker) clearInterval(ticker)
})

watch(() => props.active, (a) => {
  if (!a) {
    if (ticker) clearInterval(ticker)
    commitLen.value = pending.value.length
  }
})
</script>

<template>
  <span class="ty-reveal" :class="{ 'ty-static': !active }">
    <span v-if="hiddenCount > 0" class="ty-omitted">…前面 {{ hiddenCount }} 字已输出</span>
    <template v-for="(ln, i) in completed" :key="i">
      <span class="ty-line">{{ ln }}</span>
    </template>
    <span v-if="active" class="ty-pending"><span class="ty-commit">{{ pending.slice(0, commitLen) }}</span><span v-if="commitLen < pending.length" class="ty-dim">{{ pending.slice(commitLen) }}</span></span>
    <span v-else class="ty-line">{{ pending }}</span>
    <span v-if="active" class="cursor"></span>
  </span>
</template>

<style scoped>
.ty-reveal {
  white-space: pre-wrap;
  word-break: break-word;
  min-height: 1.2em;
}
.ty-omitted {
  display: block;
  font-size: 11px;
  font-style: normal;
  color: var(--text-3);
  opacity: .8;
  margin-bottom: 2px;
}
.ty-line {
  display: block;
  animation: ty-in 0.3s ease-out backwards;
}
.ty-static .ty-line {
  animation: none;
}
.ty-pending { display: block; }
.ty-commit { opacity: 1; }
/* 尾部隐藏光效:低透明度 + 主题色微光 */
.ty-dim {
  opacity: 0.18;
  text-shadow: 0 0 8px color-mix(in srgb, var(--accent) 35%, transparent);
}
@keyframes ty-in {
  from { opacity: 0.18; }
  to { opacity: 1; }
}
.cursor {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 2px;
  vertical-align: text-bottom;
  border-radius: 1px;
  background: linear-gradient(180deg, transparent, var(--accent), transparent);
  box-shadow: 0 0 6px var(--accent), 0 0 14px var(--accent);
  animation: cursor-glow 1s ease-in-out infinite;
}
@keyframes cursor-glow { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
</style>