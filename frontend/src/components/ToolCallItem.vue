<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { summarizeToolCall, extractFilePath, isFileProducingTool } from '@/utils/turns'
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

const downloadPath = computed(() =>
  props.tc.status === 'completed' && isFileProducingTool(props.tc)
    ? extractFilePath(props.tc)
    : null,
)

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
  const base = props.tc.tool.replace(/[^\w-]/g, '_') || 'tool'
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
      🔧 {{ tc.tool }} <span class="chip-arg">{{ summarizeToolCall(tc) }}</span>
      <span class="chip-status">{{ statusIcon }}</span>
      <a
        v-if="downloadPath"
        class="chip-download"
        title="下载该文件"
        @click.stop="emit('download', downloadPath)"
      >📥</a>
    </span>

    <div v-if="output" class="tool-output" :class="{ collapsed: !expanded }">
      <button class="tool-output-head" @click="toggle">
        <span class="chevron">{{ expanded ? '▾' : '▸' }}</span>
        <span class="label">工具输出</span>
        <span v-if="lineCount" class="lines">{{ lineCount }} 行</span>
        <span v-if="running" class="live">执行中…</span>
        <span v-else-if="output && !running" class="output-actions" @click.stop>
          <a class="output-act" title="复制工具输出" @click="copyOutput">⧉ 复制</a>
          <a class="output-act" title="下载为文本文件" @click="downloadOutput">⬇ 下载</a>
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
}
.chip-arg { color: var(--accent); }
.chip .chip-status { font-weight: bold; }
.chip-download {
  margin-left: 2px; padding: 0 2px; cursor: pointer;
  font-size: 13px; line-height: 1; text-decoration: none;
  border-radius: 4px;
}
.chip-download:hover { background: var(--surface-hover); }
.chip.run .chip-status { color: var(--text-3); }
.chip.ok .chip-status { color: var(--ok); }
.chip.err .chip-status { color: var(--err); }

.tool-output {
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: var(--bg-elev); overflow: hidden; min-width: 0;
}
.tool-output-head {
  display: flex; align-items: center; gap: 6px;
  width: 100%; padding: 5px 10px;
  background: transparent; border: none; cursor: pointer;
  color: var(--text-2); font-size: 12px; text-align: left;
}
.tool-output-head:hover { background: var(--surface-hover); }
.tool-output .chevron { font-size: 10px; color: var(--text-3); }
.tool-output .label { font-weight: 600; color: var(--text-2); }
.tool-output .lines { color: var(--text-3); font-size: 11px; }
.tool-output .live { color: var(--warn); font-size: 11px; }
.tool-output .output-actions {
  margin-left: auto; display: inline-flex; gap: 8px; align-items: center;
}
.output-act {
  color: var(--text-3); font-size: 11px; text-decoration: none; padding: 1px 5px;
  border-radius: 4px; cursor: pointer;
}
.output-act:hover { background: var(--accent-soft); color: var(--accent-text); }
.tool-output-body {
  margin: 0; padding: 8px 10px;
  max-height: 260px; overflow: auto;
  border-top: 1px solid var(--border);
  font-family: ui-monospace, monospace; font-size: 12px;
  line-height: 1.5; color: var(--text-2);
  white-space: pre-wrap; word-break: break-word;
}
</style>
