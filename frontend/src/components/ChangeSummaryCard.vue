<script setup lang="ts">
import { ref } from 'vue'
import { fileApi } from '@/api/files'
import { useMessage, useDialog } from 'naive-ui'
import type { ChangeSummary, FileDiffResult } from '@/types'

const props = defineProps<{ summary: ChangeSummary }>()

const msg = useMessage()
const dialog = useDialog()

// ---------- 点击文件 chip → 卡片内直接展开该文件的版本 diff / 回滚 ----------
const openPath = ref('')
const diffLoading = ref(false)
const diffResult = ref<FileDiffResult | null>(null)
const restoring = ref(false)

function statusLabel(status: string): string {
  switch (status) {
    case 'A': return '新增'
    case 'M': return '修改'
    case 'D': return '删除'
    case 'R': return '重命名'
    default: return status
  }
}

function shortSha(sha: string): string {
  return sha && sha.length > 7 ? sha.slice(0, 7) : sha || ''
}

/** 点击某文件:切换展开/收起,展示"本轮(parent→sha)"的 diff;无 parent 时退化为 sha→当前。 */
async function toggleFileDiff(path: string) {
  if (openPath.value === path) {
    openPath.value = ''
    diffResult.value = null
    return
  }
  openPath.value = path
  diffResult.value = null
  diffLoading.value = true
  try {
    const from = props.summary.parentSha || props.summary.sha
    if (from) {
      // parent→sha 精确展示本轮 AI 改了什么;无 parent(首个快照)则 sha→当前工作区
      const to = props.summary.parentSha ? props.summary.sha : undefined
      diffResult.value = await fileApi.diff(path, from, to)
    } else {
      const hist = await fileApi.history(path)
      if (hist && hist.length) {
        diffResult.value = await fileApi.diff(path, hist[0].sha)
      } else {
        diffResult.value = { textDiff: true, raw: '', lines: [] }
      }
    }
  } catch (e: any) {
    msg.error(e?.message || '加载差异失败')
  } finally {
    diffLoading.value = false
  }
}

function restoreTo(sha = props.summary.sha) {
  if (!sha) {
    msg.warning('该总结无版本信息,无法回滚')
    return
  }
  dialog.warning({
    title: '回滚确认',
    content: `将「${openPath.value}」回滚到本轮总结对应版本(${shortSha(sha)})的内容。确定继续?`,
    positiveText: '回滚',
    negativeText: '取消',
    onPositiveClick: async () => {
      restoring.value = true
      try {
        await fileApi.restore(openPath.value, sha)
        msg.success('已回滚')
        openPath.value = ''
        diffResult.value = null
      } catch (e: any) {
        msg.error(e?.message ?? '回滚失败')
      } finally {
        restoring.value = false
      }
    },
  })
}
</script>

<template>
  <div class="summary-card">
    <div class="summary-head">
      <span class="icon">📝</span>
      <span class="title">AI 变更总结</span>
      <span v-if="summary.files?.length" class="count">本轮改动 {{ summary.files.length }} 个文件</span>
      <span v-if="summary.sha" class="count" :title="summary.sha">@{{ shortSha(summary.sha) }}</span>
    </div>
    <div class="summary-text">{{ summary.summary }}</div>
    <div v-if="summary.files?.length" class="summary-files">
      <span
        v-for="(f, i) in summary.files"
        :key="i"
        class="file-chip"
        :class="{ open: openPath === f.path }"
        :title="openPath === f.path ? '收起差异' : `展开「${f.path}」的差异`"
        @click="toggleFileDiff(f.path)"
      >
        <span class="st">{{ statusLabel(f.status) }}</span>
        {{ f.path.split(/[\\/]/).pop() }}
        <span class="chip-arrow">{{ openPath === f.path ? '▲' : '▼' }}</span>
      </span>
    </div>

    <!-- 内嵌 diff:点击文件后直接在卡片内展开,不再弹新窗口 -->
    <div v-if="openPath" class="inline-diff">
      <div class="diff-toolbar">
        <span v-if="summary.sha" class="diff-baseline">
          {{ summary.parentSha ? `${shortSha(summary.parentSha)} → ${shortSha(summary.sha)} (本轮)` : `${shortSha(summary.sha)} → 当前` }}
        </span>
        <span class="diff-filename">{{ openPath.split(/[\\/]/).pop() }}</span>
        <n-button size="tiny" type="warning" :loading="restoring" :disabled="!summary.sha" @click="restoreTo()">
          回滚到此版本
        </n-button>
      </div>
      <n-spin :show="diffLoading">
        <div v-if="diffResult" class="diff-view">
          <div v-if="!diffResult.textDiff" class="diff-note">二进制文件,无法按行显示差异</div>
          <div v-else-if="!diffResult.lines.length" class="diff-note">两版内容一致,无差异</div>
          <pre v-else class="diff-pre"><code>
            <span v-for="(l, i) in diffResult.lines" :key="i" class="diff-line" :class="'dl-' + l.kind">{{ l.content }}</span>
          </code></pre>
        </div>
      </n-spin>
    </div>
  </div>
</template>

<style scoped>
.summary-card {
  margin: 4px 0 16px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--accent);
  border-radius: var(--radius);
  background: var(--accent-soft);
  padding: 12px 16px;
  max-width: 75%;
}
.summary-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.summary-head .icon { font-size: 14px; }
.summary-head .title { font-size: 13px; font-weight: 600; color: var(--accent-text); }
.summary-head .count { font-size: 12px; color: var(--text-3); }
.summary-text { font-size: 14px; line-height: 1.6; color: var(--text); word-break: break-word; }
.summary-files { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.file-chip {
  font-size: 12px; padding: 3px 10px; border-radius: 10px;
  background: var(--surface); border: 1px solid var(--border); color: var(--text-2);
  max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  cursor: pointer; transition: border-color .15s;
}
.file-chip:hover { border-color: var(--accent); color: var(--text); }
.file-chip.open { border-color: var(--accent); color: var(--text); background: var(--accent-soft); }
.file-chip .st { color: var(--accent-text); font-weight: 600; margin-right: 4px; }
.chip-arrow { margin-left: 4px; font-size: 10px; opacity: .7; }
.inline-diff { margin-top: 10px; border-top: 1px dashed var(--border); padding-top: 10px; }
.diff-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.diff-baseline { font-size: 12px; color: var(--accent-text); font-family: ui-monospace, monospace; }
.diff-filename { font-size: 12px; color: var(--text-2); font-family: ui-monospace, monospace; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.diff-view { max-height: 40vh; overflow: auto; border: 1px solid var(--border-soft); border-radius: var(--radius-sm); }
.diff-pre { margin: 0; font-size: 12px; line-height: 1.55; font-family: ui-monospace, monospace; white-space: pre; overflow-x: auto; }
.diff-pre code { display: block; padding: 8px 0; }
.diff-line { display: block; padding: 0 8px; }
.dl-add { background: rgba(88, 196, 155, 0.18); color: #b6e7d2; }
.dl-del { background: rgba(228, 122, 134, 0.18); color: #e7b6bb; }
.dl-hunk { background: rgba(107, 138, 253, 0.14); color: #a9bcff; }
.dl-meta { color: var(--text-3); }
.dl-ctx { color: var(--text-2); }
.diff-note { color: var(--text-3); font-size: 12px; padding: 12px 4px; }
</style>
