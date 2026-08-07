<script setup lang="ts">
import { ref } from 'vue'
import { fileApi } from '@/api/files'
import { useMessage, useDialog } from 'naive-ui'
import type { ChangeSummary, FileDiffResult } from '@/types'

const props = defineProps<{ summary: ChangeSummary }>()

const msg = useMessage()
const dialog = useDialog()

// ---------- 点击文件 chip → 打开该文件的版本 diff / 回滚 ----------
const diffVisible = ref(false)
const diffPath = ref('')
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

function fmtTime(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 点击某文件:展示"本轮(parent→sha)"的 diff;无 parent 时退化为 sha→当前。 */
async function openFileDiff(path: string) {
  diffPath.value = path
  diffVisible.value = true
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
    content: `将「${diffPath.value}」回滚到本轮总结对应版本(${shortSha(sha)})的内容。确定继续?`,
    positiveText: '回滚',
    negativeText: '取消',
    onPositiveClick: async () => {
      restoring.value = true
      try {
        await fileApi.restore(diffPath.value, sha)
        msg.success('已回滚')
        diffVisible.value = false
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
        :title="`查看「${f.path}」的差异`"
        @click="openFileDiff(f.path)"
      >
        <span class="st">{{ statusLabel(f.status) }}</span> {{ f.path.split(/[\\/]/).pop() }}
      </span>
    </div>

    <n-modal
      v-model:show="diffVisible"
      preset="card"
      :title="`变更 · ${diffPath ? diffPath.split(/[\\/]/).pop() : ''}`"
      :style="{ width: '76vw', maxWidth: '820px' }"
      :mask-closable="true"
      :bordered="false"
      class="summary-diff-modal"
    >
      <div class="diff-toolbar">
        <span v-if="summary.sha" class="diff-baseline">
          {{ summary.parentSha ? `${shortSha(summary.parentSha)} → ${shortSha(summary.sha)} (本轮)` : `${shortSha(summary.sha)} → 当前` }}
        </span>
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
    </n-modal>
  </div>
</template>

<style scoped>
.summary-card {
  margin: 4px 0 16px;
  border: 1px solid #3a3a40;
  border-left: 3px solid #7c5cff;
  border-radius: 8px;
  background: rgba(124, 92, 255, 0.06);
  padding: 10px 14px;
  max-width: 75%;
}
.summary-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.summary-head .icon { font-size: 14px; }
.summary-head .title { font-size: 13px; font-weight: 600; color: #c4b5fd; }
.summary-head .count { font-size: 12px; color: #888; }
.summary-text { font-size: 14px; line-height: 1.6; color: #e0e0e6; word-break: break-word; }
.summary-files { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
.file-chip {
  font-size: 12px; padding: 2px 8px; border-radius: 10px;
  background: #26262a; border: 1px solid #333; color: #b0b0b6;
  max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  cursor: pointer; transition: border-color .15s;
}
.file-chip:hover { border-color: #7c5cff; color: #e0e0e6; }
.file-chip .st { color: #7c5cff; font-weight: 600; margin-right: 4px; }
.diff-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; }
.diff-baseline { font-size: 12px; color: #7ab8ff; font-family: ui-monospace, monospace; }
.diff-view { max-height: 60vh; overflow: auto; }
.diff-pre { margin: 0; font-size: 12px; line-height: 1.55; font-family: ui-monospace, monospace; white-space: pre; overflow-x: auto; }
.diff-pre code { display: block; padding: 8px 0; }
.diff-line { display: block; padding: 0 8px; }
.dl-add { background: #1f4a2e; color: #b6e7c5; }
.dl-del { background: #4a2020; color: #e7b6b6; }
.dl-hunk { background: #24304a; color: #9db8e0; }
.dl-meta { color: #666; }
.dl-ctx { color: #ccc; }
.diff-note { color: #888; font-size: 12px; padding: 12px 4px; }
</style>

<style>
/* n-modal teleport 到 body:让内容区自适应,滚动交给内部 .diff-view */
.summary-diff-modal .n-card__content { overflow: hidden; padding: 16px; }
</style>
