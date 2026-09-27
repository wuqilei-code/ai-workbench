<script setup lang="ts">
/**
 * 工作区「定时任务」tab:只显示任务列表(执行记录通过「自动化」弹窗里的按钮查看)。
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { jobApi } from '@/api/jobs'
import type { JobTaskInfo } from '@/types'
import JobRunsModal from '@/components/JobRunsModal.vue'
import { useI18n } from 'vue-i18n'

const jobs = ref<JobTaskInfo[]>([])
const { t } = useI18n()
const loading = ref(false)
const error = ref('')

/** 执行记录弹窗 */
const runsShow = ref(false)
const runsJob = ref<{ id: number; name: string } | null>(null)

function openRuns(j: JobTaskInfo) {
  runsJob.value = { id: j.id, name: j.name }
  runsShow.value = true
}

/** 分页 */
const page = ref(1)
const pageSize = 8
const pagedJobs = computed(() => {
  const start = (page.value - 1) * pageSize
  return jobs.value.slice(start, start + pageSize)
})

const hasActive = computed(() => jobs.value.some((j) => j.status === 'running' || j.status === 'queued'))

let timer: ReturnType<typeof setInterval> | null = null

async function refresh() {
  try {
    error.value = ''
    jobs.value = await jobApi.list()
    const maxPage = Math.max(1, Math.ceil(jobs.value.length / pageSize))
    if (page.value > maxPage) page.value = maxPage
  } catch (e: any) {
    error.value = e?.message || t('jobRuns2.loadFailed')
  } finally {
    loading.value = false
  }
}

function startPolling() {
  stopPolling()
  timer = setInterval(refresh, hasActive.value ? 4000 : 15000)
}
function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function statusType(j: JobTaskInfo): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  switch (j.status) {
    case 'running': return 'warning'
    case 'queued': return 'primary'
    case 'success': return 'success'
    case 'failed':
    case 'timeout': return 'error'
    case 'cancelled': return 'default'
    default: return 'default'
  }
}

function jobIcon(j: JobTaskInfo): string {
  switch (j.status) {
    case 'running': return '⏳'
    case 'queued': return '⏱️'
    case 'success': return '✅'
    case 'failed':
    case 'timeout': return '❌'
    case 'cancelled': return '⏹️'
    default: return j.cron ? '⏰' : '▶️'
  }
}

function formatTime(s?: string | null): string {
  if (!s) return '-'
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function formatDuration(sec?: number | null): string {
  if (sec == null) return '-'
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

onMounted(() => {
  loading.value = true
  refresh()
  startPolling()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="runs-panel">
    <div class="panel-head">
      <span class="hint">{{ hasActive ? t('jobRuns2.activeHint') : t('jobRuns2.idle') }}</span>
      <n-button size="tiny" quaternary circle :title="t('jobRuns2.refreshTitle')" @click="refresh">↻</n-button>
    </div>

    <n-spin :show="loading" size="small" style="display: block; margin: 24px auto">
      <div v-if="error" class="runs-error">{{ error }}</div>
      <div v-else-if="!jobs.length" class="runs-empty">
        {{ t('jobRuns2.emptyTitle') }}<br />
        <span class="runs-empty-sub">{{ t('jobRuns2.emptySub') }}</span>
      </div>
      <div v-else class="task-list">
        <div v-for="j in pagedJobs" :key="j.id" class="task-item" :class="{ disabled: !j.enabled }">
          <div class="job-icon" :class="'icon-' + statusType(j)" :title="j.statusText">{{ jobIcon(j) }}</div>
          <div class="task-info">
            <div class="task-row">
              <span class="task-name" :title="j.name">{{ j.name }}</span>
              <n-tag size="tiny" :type="statusType(j)" :bordered="false">
                <template v-if="j.status === 'running'" #icon><span class="mini-spin" /></template>
                {{ j.statusText }}
              </n-tag>
            </div>
            <div class="task-content" :title="j.prompt">{{ j.prompt || t('jobRuns2.emptyPrompt') }}</div>
            <div class="task-sub">
              <span v-if="j.nextRunAt" class="t-next">{{ t('jobRuns2.next', { t: formatTime(j.nextRunAt) }) }}</span>
              <span v-else-if="j.finishedAt" class="t-time">{{ t('jobRuns2.last', { t: formatTime(j.finishedAt) }) }}</span>
              <span v-if="j.runningSeconds != null" class="t-dur">{{ t('jobRuns2.cost', { d: formatDuration(j.runningSeconds) }) }}</span>
            </div>
          </div>
          <div class="task-actions">
            <n-button size="tiny" quaternary @click="openRuns(j)">{{ t('jobRuns2.runs') }}</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <div v-if="jobs.length > pageSize" class="pager">
      <n-pagination v-model:page="page" :page-size="pageSize" :item-count="jobs.length" size="small" />
    </div>

    <JobRunsModal v-if="runsJob" v-model:show="runsShow" :job-id="runsJob.id" :job-name="runsJob.name" />
  </div>
</template>

<style scoped>
.runs-panel { display: flex; flex-direction: column; gap: 8px; height: 100%; width: 100%; min-width: 0; overflow-x: hidden; box-sizing: border-box; }
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 2px 0 8px; border-bottom: 1px solid var(--border-soft); flex-shrink: 0;
}
.panel-head .hint { font-size: 12px; color: var(--text-3); }
.runs-error { color: var(--err); font-size: 12px; padding: 8px 0; }
.runs-empty { color: var(--text-3); font-size: 12px; padding: 16px 0; text-align: center; line-height: 1.8; }
.runs-empty-sub { font-size: 11px; color: var(--text-3); opacity: 0.8; }

.task-list { display: flex; flex-direction: column; gap: 8px; overflow-y: auto; overflow-x: hidden; min-height: 0; width: 100%; min-width: 0; box-sizing: border-box; }
.task-item {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; box-sizing: border-box;
  border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.task-item.disabled { opacity: 0.55; }
.task-actions { flex-shrink: 0; }
.job-icon {
  width: 32px; height: 32px; border-radius: 8px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px; background: var(--accent-soft);
}
.job-icon.icon-success { background: rgba(52, 211, 153, .15); }
.job-icon.icon-error { background: rgba(239, 68, 68, .15); }
.job-icon.icon-warning { background: rgba(245, 158, 11, .15); }
.task-info { flex: 1; min-width: 0; }
.task-row { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; min-width: 0; flex-wrap: wrap; }
.task-row :deep(.n-tag) { flex-shrink: 0; max-width: 80px; }
.task-row :deep(.n-tag .n-tag__content) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-name {
  font-size: 13px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  flex: 1; min-width: 0;
}
.task-content {
  font-size: 12px; color: var(--text-3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap;
}
.task-sub { display: flex; gap: 12px; margin-top: 4px; }
.t-next { font-size: 11px; color: var(--warn); white-space: nowrap; }
.t-time { font-size: 11px; color: var(--text-3); white-space: nowrap; }
.t-dur { font-size: 11px; color: var(--text-3); white-space: nowrap; }
.mini-spin {
  display: inline-block; width: 10px; height: 10px; margin-right: 4px;
  border: 2px solid currentColor; border-top-color: transparent;
  border-radius: 50%; animation: jr-spin .7s linear infinite;
  vertical-align: -1px;
}
@keyframes jr-spin { to { transform: rotate(360deg); } }
.pager { display: flex; justify-content: center; padding: 6px 0 2px; }
</style>
