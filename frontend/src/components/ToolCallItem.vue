<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { summarizeToolCall } from '@/utils/turns'
import type { ToolCall } from '@/types'

const props = defineProps<{ tc: ToolCall }>()
const emit = defineEmits<{ download: [path: string] }>()
const msg = useMessage()

/** 工具输出(可能为空 → 不渲染输出框) */
const output = computed(() => props.tc.output ?? '')
const running = computed(() => props.tc.status === 'running')

/** 用户手动展开/折叠覆盖;置 null 恢复自动态 */
const userOverride = ref<boolean | null>(null)

/** 自动态:运行中展开(跟随输出),完成后折叠 */
const expanded = computed(() => userOverride.value ?? running.value)

function toggle() {
  userOverride.value = !expanded.value
}

/** 状态变化(含输出定稿)时恢复自动态:输出完成后自动折叠,每个工具独立 */
watch(
  () => props.tc.status,
  () => {
    userOverride.value = null
  },
)

/** 运行中持续输出时自动滚到底部 */
const boxEl = ref<HTMLElement | null>(null)
watch([output, expanded], async () => {
  if (expanded.value && running.value && boxEl.value) {
    await nextTick()
    boxEl.value.scrollTop = boxEl.value.scrollHeight
  }
})

const chipClass = computed(() => {
  if (props.tc.status === 'completed') return 'ok'
  if (props.tc.status === 'error') return 'err'
  return 'run'
})

const statusIcon = computed(() => {
  if (props.tc.status === 'completed') return '✓'
  if (props.tc.status === 'error') return '✗'
  return '…'
})

const lineCount = computed(() => {
  const t = output.value.trim()
  return t ? t.split('\n').length : 0
})

/** 把工具输出复制为文本(skip 运行中持续输出,避免复制半截) */
async function copyOutput() {
  if (running.value) return
  try {
    await navigator.clipboard.writeText(props.tc.output ?? '')
    msg.success('已复制工具输出')
  } catch (e: any) {
    msg.error(e?.message || '复制失败')
  }
}

/** 把工具输出保存为本地文件下载 */
function downloadOutput() {
  if (running.value) return
  const blob = new Blob([props.tc.output ?? ''], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  const base = props.tc.name.replace(/[^\w-]/g, '_') || 'tool'
  a.href = url
  a.download = `${base}-output.txt`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="tool-item">
    <span class="chip" :class="chipClass">
      🔧 {{ tc.name }} <span class="chip-arg">{{ summarizeToolCall(tc) }}</span>
      <span class="chip-status">{{ statusIcon }}</span>
    </span>

    <div v-if="output" class="tool-output">
      <button class="tool-output-head" @click="toggle">
        <svg class="chevron" :class="{ open: expanded }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
        <span class="label">输出</span>
        <span v-if="lineCount" class="lines">{{ lineCount }} 行</span>
        <span v-if="running" class="live">执行中…</span>
        <span v-else-if="output && !running" class="output-actions" @click.stop>
          <a class="output-act" title="复制工具输出" @click="copyOutput">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </a>
          <a class="output-act" title="下载为文本文件" @click="downloadOutput">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </a>
        </span>
      </button>
      <pre
        v-if="expanded"
        ref="boxEl"
        class="tool-output-body"
      >{{ output }}</pre>
    </div>
  </div>
</template>

<style scoped>
.tool-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; }

.chip {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; padding: 3px 10px; border-radius: 10px;
  background: var(--bg-elev); color: var(--text-2); border: 1px solid var(--border);
  width: fit-content;
  transition: border-color .15s, background .15s;
}
.chip:hover { border-color: var(--border); background: var(--surface-hover); }
.chip-arg { color: var(--accent); }
.chip .chip-status { font-weight: bold; }
.chip.run .chip-status { color: var(--text-3); animation: chip-pulse 1.6s ease-in-out infinite; }
.chip.ok .chip-status { color: var(--ok); }
.chip.err .chip-status { color: var(--err); }
@keyframes chip-pulse { 50% { opacity: 0.45; } }

.tool-output { min-width: 0; }
.tool-output-head {
  display: inline-flex; align-items: center; gap: 5px;
  width: 100%; padding: 2px 4px; margin-left: -4px;
  background: none; border: none; cursor: pointer;
  color: var(--text-3); font-size: 12px; text-align: left;
  border-radius: 4px;
}
.tool-output-head:hover { color: var(--text-2); background: var(--surface-hover); }
.tool-output .chevron { transition: transform .15s; color: var(--text-3); flex-shrink: 0; }
.tool-output .chevron.open { transform: rotate(90deg); }
.tool-output .label { font-weight: 500; color: var(--text-3); }
.tool-output .lines { font-size: 11px; }
.tool-output .live { color: var(--warn); font-size: 11px; }
.tool-output .output-actions {
  margin-left: auto; display: inline-flex; gap: 6px; align-items: center;
  opacity: 0; transition: opacity .15s;
}
.tool-output:hover .output-actions { opacity: 1; }
.output-act {
  color: var(--text-3); text-decoration: none; padding: 2px;
  border-radius: 4px; cursor: pointer; line-height: 0;
  display: inline-flex; align-items: center; justify-content: center;
}
.output-act:hover { background: var(--accent-soft); color: var(--accent-text); }
.tool-output-body {
  margin: 2px 0 6px 18px; padding-left: 12px;
  border-left: 2px solid var(--border);
  font-family: ui-monospace, monospace; font-size: 12px;
  line-height: 1.5; color: var(--text-2);
  white-space: pre-wrap; word-break: break-word;
  max-height: 260px; overflow: auto;
}
</style>
