<script setup lang="ts">
/**
 * 定时任务执行记录弹窗:展示指定任务的历次执行(分页列表)。
 * 点击某次可展开/收起该次运行日志。
 */
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { jobApi } from '@/api/jobs'
import type { JobTaskRunInfo } from '@/types'

const { t } = useI18n()
const props = defineProps<{ show: boolean; jobId: number; jobName: string }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const runs = ref<JobTaskRunInfo[]>([])
const loading = ref(false)
const error = ref('')
/** 分页 */
const page = ref(1)
const pageSize = 8
const pagedRuns = computed(() => {
  const start = (page.value - 1) * pageSize
  return runs.value.slice(start, start + pageSize)
})

const runLogs = ref<Record<string, string>>({})
const runLogLoading = ref<Record<string, boolean>>({})

async function load(force = true) {
  if (!props.show) return
  loading.value = true
  error.value = ''
  try {
    runs.value = await jobApi.runs(props.jobId)
    const maxPage = Math.max(1, Math.ceil(runs.value.length / pageSize))
    if (page.value > maxPage) page.value = maxPage
  } catch (e: any) {
    error.value = e?.message || t('jobRuns.loadFailed')
  } finally {
    loading.value = false
  }
}

// 用 watch show 替代 @after-enter:组件可能随 v-if 首次挂载时已处于显示态,
// after-enter 不触发导致首次打开空白;watch 只要 show 变 true 即加载,更可靠。
watch(
  () => props.show,
  (v) => {
    if (v) {
      page.value = 1
      load(true)
    }
  },
  { immediate: true },
)

/** 展开/收起某次运行的日志(再点收起)。 */
async function toggleLog(runId: number) {
  const key = String(runId)
  if (runLogs.value[key] !== undefined) {
    delete runLogs.value[key]
    delete runLogLoading.value[key]
    return
  }
  runLogLoading.value[key] = true
  try {
    runLogs.value[key] = await jobApi.runLog(props.jobId, runId, 12000)
  } catch (e: any) {
    runLogs.value[key] = t('jobRuns.logFailed', { msg: e?.message || 'unknown' })
  } finally {
    delete runLogLoading.value[key]
  }
}

function runStatusType(r: JobTaskRunInfo): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  switch (r.status) {
    case 'running': return 'warning'
    case 'success': return 'success'
    case 'failed':
    case 'timeout': return 'error'
    case 'cancelled': return 'default'
    default: return 'default'
  }
}

function runStatusIcon(r: JobTaskRunInfo): string {
  switch (r.status) {
    case 'running': return '⏳'
    case 'success': return '✅'
    case 'failed':
    case 'timeout': return '❌'
    case 'cancelled': return '⏹️'
    default: return '•'
  }
}

function formatTimeFull(s?: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

function formatTimeShort(s?: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function formatDuration(sec?: number | null): string {
  if (sec == null) return '-'
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

/** 结果摘要只取第一行(退出码/回写数等结构化信息),避免混入日志尾部碎片。 */
function resultFirstLine(r: JobTaskRunInfo): string {
  if (!r.result) return ''
  const first = r.result.split('\n')[0] || ''
  return first.trim()
}
</script>

<template>
  <n-modal
    v-model:show="props.show"
    preset="card"
    :title="t('jobRuns.title', { name: jobName })"
    style="width: 760px; max-width: 94vw"
    :bordered="false"
    @update:show="emit('update:show', $event)"
  >
    <div class="runs-modal">
      <div class="rm-job-bar">
      <span class="rm-job-label">{{ t('jobRuns.job') }}</span>
        <span class="rm-job-name" :title="jobName">{{ jobName }}</span>
      </div>
    <div v-if="loading" class="rm-loading">{{ t('jobRuns.loading') }}</div>
      <div v-else-if="error" class="rm-error">{{ error }}</div>
    <div v-else-if="!runs.length" class="rm-empty">{{ t('jobRuns.empty') }}</div>
      <div v-else class="rm-list">
        <div v-for="r in pagedRuns" :key="r.id" class="rm-item">
          <div class="rm-main" @click="toggleLog(r.id)">
            <span class="rm-status">{{ runStatusIcon(r) }}</span>
            <n-tag size="tiny" :type="runStatusType(r)" :bordered="false">{{ r.statusText }}</n-tag>
            <span class="rm-time" :title="formatTimeFull(r.startedAt)">{{ formatTimeShort(r.startedAt) }}</span>
            <span class="rm-dur">{{ formatDuration(r.runningSeconds) }}</span>
      <span v-if="r.changedFiles != null" class="rm-files">{{ t('jobRuns.files', { n: r.changedFiles }) }}</span>
      <span v-if="r.exitCode != null" class="rm-exit">{{ t('jobRuns.exit', { n: r.exitCode }) }}</span>
            <span class="rm-caret">{{ runLogs[String(r.id)] !== undefined ? '▾' : '▸' }}</span>
          </div>
          <div v-if="resultFirstLine(r)" class="rm-summary" :title="r.result || ''">{{ resultFirstLine(r) }}</div>
      <div v-if="runLogLoading[String(r.id)]" class="rm-log-loading">{{ t('jobRuns.logLoading') }}</div>
      <pre v-else-if="runLogs[String(r.id)] !== undefined" class="rm-log">{{ runLogs[String(r.id)] || t('jobRuns.logEmpty') }}</pre>
        </div>
      </div>
      <div v-if="runs.length > pageSize" class="rm-pager">
        <n-pagination v-model:page="page" :page-size="pageSize" :item-count="runs.length" size="small" />
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.runs-modal { display: flex; flex-direction: column; gap: 8px; min-height: 320px; }
.rm-job-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px; border-radius: var(--radius-sm);
  background: var(--surface-hover); border: 1px solid var(--border-soft);
  min-width: 0; flex-shrink: 0;
}
.rm-job-label { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.rm-job-name {
  font-size: 13px; font-weight: 600; color: var(--text-1); flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.rm-loading, .rm-empty, .rm-error { color: var(--text-3); font-size: 13px; padding: 24px 0; text-align: center; }
.rm-error { color: var(--err); }
.rm-list { display: flex; flex-direction: column; gap: 8px; max-height: 55vh; overflow-y: auto; min-width: 0; }
.rm-item {
  border: 1px solid var(--border-soft); border-radius: var(--radius-sm);
  background: var(--surface); overflow: hidden; width: 100%; box-sizing: border-box;
  flex-shrink: 0;
}
.rm-main {
  display: flex; align-items: center; gap: 10px; padding: 8px 10px; cursor: pointer; flex-wrap: wrap;
  min-width: 0; box-sizing: border-box;
}
.rm-main:hover { background: var(--surface-hover); }
.rm-main :deep(.n-tag) { flex-shrink: 0; max-width: 70px; }
.rm-main :deep(.n-tag .n-tag__content) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rm-status { font-size: 13px; flex-shrink: 0; }
.rm-time { font-size: 12px; color: var(--text-2); font-family: Consolas, Menlo, monospace; white-space: nowrap; }
.rm-dur { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.rm-files { font-size: 12px; color: var(--accent); white-space: nowrap; }
.rm-exit { font-size: 12px; color: var(--text-3); white-space: nowrap; }
.rm-caret { margin-left: auto; color: var(--text-3); font-size: 11px; flex-shrink: 0; }
.rm-summary {
  font-size: 12px; color: var(--text-3); line-height: 1.5;
  padding: 4px 10px; border-top: 1px solid var(--border-soft);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap; word-break: break-all;
}
.rm-log-loading { color: var(--text-3); font-size: 12px; padding: 8px; }
.rm-log {
  margin: 0; padding: 8px 10px; font-size: 11px; line-height: 1.5; color: var(--ok);
  font-family: Consolas, Menlo, monospace; white-space: pre-wrap; word-break: break-all;
  border-top: 1px solid var(--border-soft); display: block;
}
.rm-pager { display: flex; justify-content: center; padding: 6px 0 2px; flex-shrink: 0; }
</style>
