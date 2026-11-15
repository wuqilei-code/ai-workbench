<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { summarizePath, summarizeToolCall } from '@/utils/turns'
import { highlightToolOutput } from '@/utils/highlight'
import {
  oneLine,
  splitPath,
  toolAction,
  toolDiff,
  toolKind,
  toolLinks,
  toolRead,
  toolSearch,
  toolShell,
  type ToolKind,
} from '@/utils/toolView'
import type { ToolCall } from '@/types'
import ToolDiffView from './tool/ToolDiffView.vue'

const props = defineProps<{ tc: ToolCall }>()
const emit = defineEmits<{ download: [path: string]; openFile: [path: string] }>()
const msg = useMessage()
const { t } = useI18n()

/** 工具输出(可能为空 → 不渲染输出框) */
const output = computed(() => props.tc.output ?? '')
const running = computed(() => props.tc.status === 'running')
const isError = computed(() => props.tc.status === 'error')

/** 单次渲染的输出行上限:超大输出只渲染前 N 行,避免几千行文本卡住主线程 */
const OUTPUT_PREVIEW_LINES = 500
const showFullOutput = ref(false)
const rawLines = computed(() => (output.value ? output.value.replace(/\s+$/, '').split('\n') : []))
const outputTruncated = computed(() => !showFullOutput.value && rawLines.value.length > OUTPUT_PREVIEW_LINES)
const shownOutput = computed(() =>
  outputTruncated.value ? rawLines.value.slice(0, OUTPUT_PREVIEW_LINES).join('\n') : output.value,
)
const hiddenOutputLines = computed(() => Math.max(0, rawLines.value.length - OUTPUT_PREVIEW_LINES))

/** 输出高亮后的 HTML(JSON 结构化着色 / 日志关键词着色) */
const outputHtml = computed(() => highlightToolOutput(shownOutput.value))

/** 错误摘要:首个非空行(最常见的失败原因就在第一行) */
const errorSummary = computed(() => {
  const first = output.value.split('\n').map((l) => l.trim()).find((l) => l) || ''
  return first.length > 200 ? first.slice(0, 199) + '…' : first
})

/** 错误详情:首行之后的其余输出(单行错误不重复渲染) */
const errorDetail = computed(() => {
  const i = output.value.indexOf('\n')
  return i >= 0 ? output.value.slice(i + 1).trim() : ''
})
const errorDetailHtml = computed(() => highlightToolOutput(errorDetail.value))

const diffView = computed(() => toolDiff(props.tc))
const shellView = computed(() => toolShell(props.tc))
const readView = computed(() => toolRead(props.tc))
const searchView = computed(() => toolSearch(props.tc))
const linkView = computed(() => toolLinks(props.tc))

/** 卡片类别:按工具名分派;关键数据缺失的工具回退为通用卡片,避免空壳 */
const kind = computed<ToolKind>(() => {
  const k = toolKind(props.tc)
  if (k === 'diff' && !diffView.value) return 'generic'
  if (k === 'read' && !readView.value.path && !readView.value.lineCount) return 'generic'
  if (k === 'link' && !linkView.value.urls.length && !linkView.value.query) return 'generic'
  return k
})

/** 错误统一按纯文本展示(错误信息比 diff/路径列表更重要) */
const bodyKind = computed<ToolKind>(() => (isError.value ? 'generic' : kind.value))

const actionLabel = computed(() => {
  if (kind.value === 'generic') return props.tc.name
  const action = toolAction(props.tc)
  return action ? t(`toolCall.action.${action}`) : props.tc.name
})

/** 标题行次级信息:文件路径 / 命令 / 搜索词 / 首个链接 */
const subtitle = computed(() => {
  switch (kind.value) {
    case 'diff':
      return diffView.value?.path ? summarizePath(diffView.value.path) : ''
    case 'shell':
      return shellView.value ? oneLine(shellView.value.command) : ''
    case 'read':
      return readView.value?.path ? summarizePath(readView.value.path) : ''
    case 'search':
      return searchView.value?.query || ''
    case 'link': {
      const v = linkView.value
      if (!v) return ''
      return v.urls[0] || v.query || ''
    }
    default:
      return summarizeToolCall(props.tc)
  }
})

const lineCount = computed(() => {
  const s = output.value.trim()
  return s ? s.split('\n').length : 0
})

/** 行尾补充信息:读取行范围 / 匹配条数 / 输出行数 */
const meta = computed(() => {
  if (kind.value === 'read') {
    const v = readView.value
    if (!v || !v.lineCount) return ''
    // 有偏移时显示"第 N–M 行";否则只报行数(limit 默认值不代表真实文件长度)
    if (v.offset && v.offset > 0) {
      const from = v.offset + 1
      return t('toolCall.lineRange', { from, to: from + v.lineCount - 1 })
    }
    return t('toolCall.lines', { n: v.lineCount })
  }
  if (kind.value === 'search') {
    const n = searchView.value?.itemCount ?? 0
    return n ? t('toolCall.matches', { n }) : ''
  }
  if (kind.value === 'shell' || kind.value === 'generic') {
    return lineCount.value ? t('toolCall.lines', { n: lineCount.value }) : ''
  }
  return ''
})

/** 是否有可展开的内容(参数里就有 diff/命令/链接的工具,无需等输出) */
const hasBody = computed(() => {
  if (isError.value) return !!output.value
  switch (kind.value) {
    case 'diff':
      return !!diffView.value
    case 'shell':
      return !!shellView.value || !!output.value
    case 'read':
      return !!output.value
    case 'search':
      return !!searchView.value?.items.length || !!output.value
    case 'link':
      return !!linkView.value?.urls.length || !!output.value
    default:
      return !!output.value
  }
})

/** 用户手动展开/折叠覆盖;置 null 恢复自动态 */
const userOverride = ref<boolean | null>(null)

/** 自动态:运行中展开(跟随输出)、出错展开(便于定位);空输出工具已在时间线层面过滤 */
const expanded = computed(() => userOverride.value ?? (running.value || isError.value))

function toggle() {
  userOverride.value = !expanded.value
}

function onChipClick() {
  if (hasBody.value) toggle()
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

/** 输出按行切分(去尾空白、丢空行),预览用 */
const outputLines = computed(() =>
  output.value
    .replace(/\s+$/, '')
    .split('\n')
    .map((l) => l.trimEnd())
    .filter((l) => l.trim()),
)

function clip(l: string): string {
  return l.length > 220 ? l.slice(0, 219) + '…' : l
}

/** 命令类折叠态:尾部 3 行预览(日志最有用的是最后几行) */
const tailPreview = computed(() => {
  if (kind.value !== 'shell' || expanded.value || !output.value) return []
  return outputLines.value.slice(-3).map(clip)
})

/** 读取 / 通用类折叠态:开头几行预览(文件头、页面正文开头) */
const headPreview = computed(() => {
  if (expanded.value || !output.value) return []
  if (kind.value !== 'read' && kind.value !== 'generic') return []
  const n = kind.value === 'read' ? 2 : 1
  return outputLines.value.slice(0, n).map(clip)
})

/** 搜索类折叠态:前两条命中(路径:行号 + 内容) */
const searchPreview = computed(() => {
  if (kind.value !== 'search' || expanded.value) return []
  return searchView.value.items
    .slice(0, 2)
    .map((it) => clip(`${it.path}${it.line ? ':' + it.line : ''}${it.detail ? '  ' + it.detail : ''}`))
})

/** 折叠态预览行:命令看尾部,其余看头部/命中 */
const previewLines = computed(() => {
  if (tailPreview.value.length) return tailPreview.value
  if (searchPreview.value.length) return searchPreview.value
  return headPreview.value
})

/** 预览之外还剩多少行(粗略:输出行数 - 已展示行数) */
const previewMore = computed(() => {
  if (!previewLines.value.length) return 0
  return Math.max(0, lineCount.value - previewLines.value.length)
})

/** 搜索结果的路径列表(拆成目录 + 文件名,便于扫读) */
const searchRows = computed(() =>
  (searchView.value?.items ?? []).map((it) => {
    const { dir, base } = splitPath(it.path)
    return { ...it, dir, base }
  }),
)

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

/** 按工具名给图标:写/编辑类=铅笔,命令类=终端,读取=文件,搜索=放大镜,链接=链接 */
const toolIcon = computed(() => {
  switch (kind.value) {
    case 'diff':
      return 'pencil'
    case 'shell':
      return 'terminal'
    case 'read':
      return 'file'
    case 'search':
      return 'search'
    case 'link':
      return 'link'
    default:
      return 'wrench'
  }
})

const chipTitle = computed(() => `${props.tc.name} ${subtitle.value}`.trim())

/** 把工具输出复制为文本(skip 运行中持续输出,避免复制半截) */
async function copyOutput() {
  if (running.value) return
  try {
    await navigator.clipboard.writeText(props.tc.output ?? '')
    msg.success(t('toolCall.copied'))
  } catch (e: any) {
    msg.error(e?.message || t('toolCall.copyFailed'))
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
  <div class="tool-item" :class="['kind-' + kind]">
    <button
      type="button"
      class="chip"
      :class="chipClass"
      :title="chipTitle"
      :aria-expanded="hasBody ? expanded : undefined"
      :aria-disabled="hasBody ? undefined : 'true'"
      :tabindex="hasBody ? undefined : -1"
      @click="onChipClick"
    >
      <svg v-if="toolIcon === 'pencil'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"/></svg>
      <svg v-else-if="toolIcon === 'terminal'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3"/><path d="M13 15h4"/></svg>
      <svg v-else-if="toolIcon === 'file'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
      <svg v-else-if="toolIcon === 'search'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
      <svg v-else-if="toolIcon === 'link'" class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/></svg>
      <svg v-else class="t-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>

      <span class="chip-name">{{ actionLabel }}</span>
      <span v-if="subtitle" class="chip-arg" :title="subtitle">{{ subtitle }}</span>
      <span v-if="diffView && kind === 'diff'" class="chip-diff">
        <span class="add">+{{ diffView.diff.additions }}</span>
        <span class="del">-{{ diffView.diff.deletions }}</span>
      </span>
      <span v-if="meta" class="chip-meta">{{ meta }}</span>
      <span class="chip-status">{{ statusIcon }}</span>
      <svg
        v-if="hasBody"
        class="chip-chev"
        :class="{ open: expanded }"
        width="11" height="11" viewBox="0 0 24 24"
      ><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
    </button>

    <!-- 折叠态预览:命令看尾部日志,读取/通用看开头,搜索看前两条命中;点击也可展开 -->
    <button
      v-if="previewLines.length"
      type="button"
      class="shell-preview"
      :title="t('toolCall.expand')"
      @click="toggle()"
    >
      <span v-for="(l, i) in previewLines" :key="i" class="shell-preview-line">{{ l }}</span>
      <span v-if="previewMore > 0" class="shell-preview-more">{{ t('toolCall.earlierLines', { n: previewMore }) }}</span>
    </button>

    <transition name="toolout">
      <div v-if="expanded && hasBody" class="tool-body">
        <!-- 输出操作条:复制 / 下载工具输出 -->
        <div v-if="output" class="tool-output-bar">
          <span>{{ isError ? t('toolCall.error') : bodyKind === 'diff' ? t('toolCall.result') : t('toolCall.output') }}</span>
          <span class="output-actions" @click.stop>
            <button type="button" class="output-act" :title="t('toolCall.copyTitle')" @click="copyOutput">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
            </button>
            <button type="button" class="output-act" :title="t('toolCall.downloadTitle')" @click="downloadOutput">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
            </button>
          </span>
        </div>

        <!-- 错误:独立错误卡(摘要 + 详情),比通用输出框更醒目 -->
        <div v-if="isError" class="err-card">
          <div class="err-head">
            <svg class="err-ic" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5"/><path d="M12 16.5v.01"/></svg>
            <span class="err-title">{{ t('toolCall.failed') }}</span>
            <span class="err-summary" :title="errorSummary">{{ errorSummary || t('toolCall.empty') }}</span>
          </div>
          <pre v-if="errorDetail" class="err-detail" v-html="errorDetailHtml" />
        </div>

        <!-- 文件编辑:diff 卡片 -->
        <ToolDiffView v-else-if="bodyKind === 'diff' && diffView" :view="diffView" :status="tc.status" @open="emit('openFile', $event)" />

        <!-- 命令:命令行 + 输出 -->
        <template v-else-if="bodyKind === 'shell'">
          <div v-if="shellView" class="cmd-line">
            <span class="cmd-prompt">$</span>
            <code>{{ shellView.command }}</code>
            <span v-if="shellView.timeout" class="cmd-timeout">{{ t('toolCall.timeout', { n: shellView.timeout }) }}</span>
          </div>
          <pre v-if="output" ref="boxEl" class="tool-output-body" v-html="outputHtml" />
        </template>

        <!-- 读取/列目录:路径 + 原文 -->
        <template v-else-if="bodyKind === 'read'">
          <button
            v-if="readView.path"
            type="button"
            class="body-meta body-meta--link"
            :title="`${readView.path} · ${t('toolCall.openFile')}`"
            @click="emit('openFile', readView.path)"
          >{{ readView.path }}</button>
          <pre v-if="output" class="tool-output-body plain">{{ shownOutput }}</pre>
        </template>

        <!-- 搜索:结果路径列表(解析不出路径时回退原文) -->
        <template v-else-if="bodyKind === 'search'">
          <div v-if="searchView.scope || searchView.glob" class="body-meta">
            <span v-if="searchView.scope">{{ t('toolCall.inDir', { dir: searchView.scope }) }}</span>
            <span v-if="searchView.glob" class="meta-sep">{{ t('toolCall.filter', { glob: searchView.glob }) }}</span>
          </div>
          <ul v-if="searchRows.length" class="path-list">
            <li v-for="(it, i) in searchRows" :key="i">
              <button
                type="button"
                class="path-row"
                :title="`${it.path} · ${t('toolCall.openFile')}`"
                @click="emit('openFile', it.path)"
              >
                <span class="p-dir">{{ it.dir }}</span><span class="p-base">{{ it.base }}</span>
                <span v-if="it.line" class="p-line">:{{ it.line }}</span>
                <span v-if="it.detail" class="p-detail" :title="it.detail">{{ it.detail }}</span>
              </button>
            </li>
          </ul>
          <pre v-else-if="output" ref="boxEl" class="tool-output-body" v-html="outputHtml" />
        </template>

        <!-- 联网:可点击链接 + 原文 -->
        <template v-else-if="bodyKind === 'link'">
          <ul v-if="linkView?.urls.length" class="link-list">
            <li v-for="u in linkView.urls" :key="u">
              <a :href="u" target="_blank" rel="noopener noreferrer">{{ u }}</a>
            </li>
          </ul>
          <pre v-if="output" ref="boxEl" class="tool-output-body" v-html="outputHtml" />
        </template>

        <!-- 通用工具:参数/输出原文 -->
        <template v-else>
          <pre v-if="output" ref="boxEl" class="tool-output-body" v-html="outputHtml" />
        </template>

        <!-- 超大输出:只渲染前 N 行,按需展开(避免几千行 DOM 卡住) -->
        <button
          v-if="outputTruncated && !isError && bodyKind !== 'diff'"
          type="button"
          class="out-more"
          @click="showFullOutput = true"
        >{{ t('toolCall.showAllLines', { n: hiddenOutputLines }) }}</button>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.tool-item { display: flex; flex-direction: column; gap: 4px; min-width: 0; }

/* 单行工具行:图标 + 动作 + 对象 + 统计 + 状态;有内容时整行可展开 */
.chip {
  display: inline-flex; align-items: center; gap: 7px;
  font-size: 12px; padding: 3px 8px;
  margin-left: -8px;
  background: transparent; color: var(--text-2);
  border: 0; font-family: inherit; text-align: left; line-height: 1.5;
  width: fit-content; max-width: 100%;
  border-radius: 6px;
  transition: background .15s;
  cursor: pointer;
}
.chip:hover { background: var(--surface-hover); }
.chip:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.chip[aria-disabled="true"] { cursor: default; }
.chip[aria-disabled="true"]:hover { background: transparent; }
.t-ic { color: var(--text-3); flex-shrink: 0; }
.chip-name { font-weight: 500; color: var(--text-2); flex-shrink: 0; }
.chip-arg {
  color: var(--accent); font-family: ui-monospace, monospace; font-size: 11px;
  max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.chip-meta { color: var(--text-3); font-size: 11px; flex-shrink: 0; }
.chip-diff {
  display: inline-flex; gap: 5px; flex-shrink: 0;
  font-family: ui-monospace, monospace; font-size: 11px;
}
.chip-diff .add { color: var(--ok); }
.chip-diff .del { color: var(--err); }
.chip .chip-status { font-weight: 600; }
.chip.run .chip-status { color: var(--text-3); animation: chip-pulse 1.6s ease-in-out infinite; }
.chip.ok .chip-status { color: var(--ok); }
.chip.err .chip-status { color: var(--err); }
.chip-chev { color: var(--text-4); flex-shrink: 0; transition: transform .15s; }
.chip-chev.open { transform: rotate(180deg); }
@keyframes chip-pulse { 50% { opacity: 0.45; } }

/* 命令折叠态:尾部输出预览 */
/* 折叠态预览:细边框 + 轻微底色。
   注意必须显式写 border(只写 border-left 时,其余三边会退回浏览器默认的按钮立体边框,
   在浅色主题下就是一圈突兀的黑框)。 */
.shell-preview {
  display: flex; flex-direction: column; gap: 2px;
  margin: 2px 0 0 2px; padding: 7px 10px;
  border: 1px solid var(--border-soft);
  border-left: 2px solid var(--border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--text) 3%, transparent);
  font-family: ui-monospace, monospace; font-size: 11px; line-height: 1.55;
  color: var(--text-3); text-align: left;
  cursor: pointer; max-width: 100%; overflow: hidden;
}
.shell-preview:hover { border-color: var(--border); background: var(--surface-hover); }
.shell-preview:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.shell-preview-line { white-space: pre; overflow: hidden; text-overflow: ellipsis; }
.shell-preview-more { color: var(--text-4); font-size: 10.5px; }

/* 展开体 */
.tool-body { min-width: 0; margin-top: 4px; display: flex; flex-direction: column; gap: 6px; }
.tool-output-bar {
  display: flex; align-items: center; gap: 8px;
  font-size: 11px; color: var(--text-3);
  padding: 0 2px;
}
.output-actions {
  margin-left: auto; display: inline-flex; gap: 6px; align-items: center;
}
.output-act {
  color: var(--text-3); text-decoration: none; padding: 2px;
  border: 0; background: transparent;
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
.tool-output-body.plain { color: var(--text-2); }
.tool-output-body.err { border-color: color-mix(in srgb, var(--err) 35%, transparent); }

/* 错误卡:摘要行 + 详情 */
.err-card {
  border: 1px solid color-mix(in srgb, var(--err) 38%, transparent);
  background: color-mix(in srgb, var(--err) 7%, transparent);
  border-radius: 8px;
  overflow: hidden;
}
.err-head {
  display: flex; align-items: baseline; gap: 7px;
  padding: 7px 10px; min-width: 0;
  font-size: 11.5px;
}
.err-ic { color: var(--err); flex-shrink: 0; align-self: center; }
.err-title { color: var(--err); font-weight: 600; flex-shrink: 0; }
.err-summary {
  color: var(--text-2); font-family: ui-monospace, monospace; font-size: 11px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0;
}
.err-detail {
  margin: 0; padding: 8px 10px;
  border-top: 1px solid color-mix(in srgb, var(--err) 22%, transparent);
  font-family: ui-monospace, monospace; font-size: 11px; line-height: 1.55;
  color: var(--text-2); white-space: pre-wrap; word-break: break-word;
  max-height: 240px; overflow: auto; scrollbar-width: thin;
}
.err-detail::-webkit-scrollbar { width: 6px; }
.err-detail::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
.tool-output-body:hover { scrollbar-color: var(--border) transparent; }
.tool-output-body::-webkit-scrollbar { width: 6px; }
.tool-output-body::-webkit-scrollbar-thumb { background: transparent; border-radius: 3px; }
.tool-output-body:hover::-webkit-scrollbar-thumb { background: var(--border); }

/* 命令行 */
.cmd-line {
  display: flex; align-items: baseline; gap: 7px;
  padding: 7px 10px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  font-family: ui-monospace, monospace; font-size: 12px;
}
.cmd-prompt { color: var(--ok); flex-shrink: 0; user-select: none; }
.cmd-line code { color: var(--text); white-space: pre-wrap; word-break: break-word; }
.cmd-timeout { margin-left: auto; color: var(--text-4); font-size: 10.5px; flex-shrink: 0; }

/* 读取/搜索的辅助信息行 */
.body-meta {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 11px; color: var(--text-3);
  font-family: ui-monospace, monospace;
  overflow: hidden; word-break: break-all;
}
/* 路径可点击 → 打开右侧文件预览 */
.body-meta--link {
  padding: 0; border: 0; background: transparent; text-align: left;
  font-family: ui-monospace, monospace; font-size: 11px;
  color: var(--text-3); cursor: pointer; border-radius: 4px;
  max-width: 100%;
}
.body-meta--link:hover { color: var(--accent-text); text-decoration: underline; }
.body-meta--link:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.meta-sep { color: var(--text-4); }

/* 搜索结果的路径列表 */
.path-list {
  list-style: none; margin: 0; padding: 4px 2px;
  border: 1px solid var(--border-soft); border-radius: 8px;
  background: var(--surface);
  max-height: 280px; overflow: auto;
  scrollbar-width: thin;
}
.path-list::-webkit-scrollbar { width: 6px; }
.path-list::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
.path-row {
  display: flex; align-items: baseline; gap: 0;
  padding: 2px 9px; width: 100%;
  border: 0; background: transparent; text-align: left;
  font-family: ui-monospace, monospace; font-size: 11.5px;
  color: inherit; cursor: pointer;
  min-width: 0;
}
.path-row:hover { background: var(--surface-hover); }
.path-row:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.p-dir { color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
.p-base { color: var(--text); flex-shrink: 0; }
.p-line { color: var(--accent-text); flex-shrink: 0; }
.p-detail {
  margin-left: 8px; color: var(--text-3);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  min-width: 0;
}

/* 链接列表 */
.link-list {
  list-style: none; margin: 0; padding: 6px 10px;
  border: 1px solid var(--border-soft); border-radius: 8px;
  background: var(--surface);
  display: flex; flex-direction: column; gap: 3px;
}
.link-list a {
  color: var(--accent-text); font-size: 11.5px;
  font-family: ui-monospace, monospace;
  text-decoration: none; word-break: break-all;
}
.link-list a:hover { text-decoration: underline; }

/* 超大输出:继续展开 */
.out-more {
  display: block; width: 100%;
  padding: 6px 10px; margin: 0;
  border: 1px solid var(--border-soft); border-radius: 8px;
  background: var(--surface); color: var(--accent-text);
  font-size: 11px; cursor: pointer; text-align: center;
}
.out-more:hover { background: var(--accent-soft); }

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
