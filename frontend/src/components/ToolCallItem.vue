<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { summarizeToolCall } from '@/utils/turns'
import { highlightToolOutput } from '@/utils/highlight'
import type { ToolCall } from '@/types'

const props = defineProps<{ tc: ToolCall }>()
const emit = defineEmits<{ download: [path: string] }>()
const msg = useMessage()

/** 工具输出(可能为空 → 不渲染输出框) */
const output = computed(() => props.tc.output ?? '')
const running = computed(() => props.tc.status === 'running')

/** 输出高亮后的 HTML(JSON 结构化着色 / 日志关键词着色) */
const outputHtml = computed(() => highlightToolOutput(output.value))

/** 用户手动展开/折叠覆盖;置 null 恢复自动态 */
const userOverride = ref<boolean | null>(null)

/** 自动态:运行中展开(跟随输出),完成后折叠。空输出工具已在时间线层面过滤 */
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

/** 按工具名给图标:写/编辑类=铅笔,命令/执行类=终端,其余=扳手 */
const toolIcon = computed(() => {
  const n = (props.tc.name || '').toLowerCase()
  if (/(write|edit|create|save|mk|touch|pencil|file)/.test(n)) return 'pencil'
  if (/(exec|command|bash|shell|run|sh|ls|grep|cat|python|node)/.test(n)) return 'terminal'
  return 'wrench'
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
    <span
      class="chip"
      :class="chipClass"
      :title="`${tc.name} ${summarizeToolCall(tc)}`.trim()"
      @click="output ? toggle() : undefined"
    >
      <svg v-if="toolIcon === 'pencil'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
      <svg v-else-if="toolIcon === 'terminal'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3"/><path d="M13 15h4"/></svg>
      <svg v-else class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
      <span class="chip-name">{{ tc.name }}</span>
      <span class="chip-arg">{{ summarizeToolCall(tc) }}</span>
      <span v-if="output && lineCount" class="chip-lines">{{ expanded ? '收起' : lineCount + ' 行' }}</span>
      <span class="chip-status">{{ statusIcon }}</span>
    </span>

    <transition name="toolout">
      <div v-if="expanded && output" class="tool-output-wrap">
        <div class="tool-output-bar">
          <span>输出</span>
          <span class="output-actions" @click.stop>
            <a class="output-act" title="复制工具输出" @click="copyOutput">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
            </a>
            <a class="output-act" title="下载为文本文件" @click="downloadOutput">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            </a>
          </span>
        </div>
        <pre ref="boxEl" class="tool-output-body" v-html="outputHtml"></pre>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.tool-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; }

/* 朴素单行:图标 + 工具名 + 参数 + 行数 + 状态;有输出时整行可点展开 */
.chip {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 12px; padding: 3px 8px;
  margin-left: -8px;
  background: transparent; color: var(--text-2);
  width: fit-content; max-width: 100%;
  border-radius: 6px;
  transition: background .15s;
  cursor: pointer;
}
.chip:hover { background: var(--surface-hover); }
.t-ic { color: var(--text-3); flex-shrink: 0; }
.chip-name { font-weight: 500; color: var(--text-2); }
.chip-arg {
  color: var(--accent); font-family: ui-monospace, monospace; font-size: 11px;
  max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.chip-lines { color: var(--text-3); font-size: 11px; }
.chip .chip-status { font-weight: 600; }
.chip.run .chip-status { color: var(--text-3); animation: chip-pulse 1.6s ease-in-out infinite; }
.chip.ok .chip-status { color: var(--ok); }
.chip.err .chip-status { color: var(--err); }
@keyframes chip-pulse { 50% { opacity: 0.45; } }

/* 展开的输出:标题条(复制/下载)+ 圆角浅底卡,等宽字体 */
.tool-output-wrap { min-width: 0; margin-top: 4px; }
.tool-output-bar {
  display: flex; align-items: center; gap: 8px;
  font-size: 11px; color: var(--text-3);
  padding: 0 2px 3px;
}
.output-actions {
  margin-left: auto; display: inline-flex; gap: 6px; align-items: center;
}
.output-act {
  color: var(--text-3); text-decoration: none; padding: 2px;
  border-radius: 4px; cursor: pointer; line-height: 0;
  display: inline-flex; align-items: center; justify-content: center;
}
.output-act:hover { background: var(--accent-soft); color: var(--accent-text); }
.tool-output-body {
  margin: 0;
  padding: 10px 12px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  font-family: ui-monospace, monospace; font-size: 12px;
  line-height: 1.55; color: var(--text-2);
  white-space: pre-wrap; word-break: break-word;
  max-height: 280px; overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}
.tool-output-body:hover { scrollbar-color: var(--border) transparent; }
.tool-output-body::-webkit-scrollbar { width: 6px; }
.tool-output-body::-webkit-scrollbar-thumb { background: transparent; border-radius: 3px; }
.tool-output-body:hover::-webkit-scrollbar-thumb { background: var(--border); }

/* 展开/折叠过渡 */
.toolout-enter-active, .toolout-leave-active { transition: opacity .18s ease, transform .18s ease; }
.toolout-enter-from, .toolout-leave-to { opacity: 0; transform: translateY(-4px); }

/* 输出高亮配色(v-html 内容,经 :deep 命中) */
.tool-output-body :deep(.j-key) { color: var(--accent); }
.tool-output-body :deep(.j-str) { color: var(--ok); }
.tool-output-body :deep(.j-num) { color: #3b82f6; }
.tool-output-body :deep(.j-bool) { color: var(--warn); }
.tool-output-body :deep(.j-null) { color: var(--text-4); }
.tool-output-body :deep(.lg-err) { color: var(--err); font-weight: 500; }
.tool-output-body :deep(.lg-warn) { color: var(--warn); }
</style>
