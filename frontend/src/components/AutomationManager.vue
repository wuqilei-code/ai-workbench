<script setup lang="ts">
/**
 * 自动化管理弹窗(侧边栏「自动化」触发):任务列表 + 启用/停用 + 新建/编辑/删除/执行。
 * 定时设置使用可视化 CronPicker(最小间隔 1 小时)。
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useMessage, useDialog, NModal } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { jobApi } from '@/api/jobs'
import { fileApi } from '@/api/files'
import { adminApi } from '@/api/admin'
import { useAuthStore } from '@/stores/auth'
import type { JobTaskInfo } from '@/types'
import CronPicker from '@/components/CronPicker.vue'
import JobRunsModal from '@/components/JobRunsModal.vue'
import SectionTabs from '@/components/ui/SectionTabs.vue'
import ListPager from '@/components/ui/ListPager.vue'
import FilePreview from '@/components/preview/FilePreview.vue'

const props = defineProps<{ show?: boolean; page?: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'close'): void }>()

/** 根节点绑定:page 模式渲染为整页(带面包屑),否则为弹窗 */
const rootBind = computed(() =>
  props.page
    ? { class: 'manage-page' }
    : {
        show: props.show,
        preset: 'card',
        title: t('automation2.title'),
        style: 'width: 720px; max-width: 94vw',
        bordered: false,
        'onUpdate:show': (v: boolean) => emit('update:show', v),
      },
)

const msg = useMessage()
const dialog = useDialog()
const { t } = useI18n()
const auth = useAuthStore()
const jobs = ref<JobTaskInfo[]>([])
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)
/** 分页 */
const page = ref(1)
const pageSize = 8

/** 页内 Tab:已配置 / 执行历史 / 任务模板 */
const activeTab = ref<'jobs' | 'runs' | 'tpl'>('jobs')
/** Tab 项(带任务数徽章) */
const autoTabItems = computed(() => [
  { key: 'jobs' as const, label: t('automation.jobs'), count: jobs.value.length },
  { key: 'runs' as const, label: t('automation.runs') },
  { key: 'tpl' as const, label: t('automation.tpl') },
])
/** 切到「执行历史」时按需加载并重置分页;其余 Tab 无需额外动作 */
function onTabChange(k: string | number) {
  if (k === 'runs') openAllRuns()
}

/** 任务模板(快速开始):点击即带入新建表单 */
const TASK_TEMPLATES: { icon: string; name: string; desc: string; cron: string; cronText: string }[] = [
  { icon: '📰', name: t('automation2.tplNews'), desc: t('automation2.tplNewsDesc'), cron: '0 0 8 * * ?', cronText: t('automation2.tplNewsCron') },
  { icon: '📈', name: t('automation2.tplStock'), desc: t('automation2.tplStockDesc'), cron: '0 0 9 ? * MON-FRI', cronText: t('automation2.tplStockCron') },
  { icon: '🔍', name: t('automation2.tplRival'), desc: t('automation2.tplRivalDesc'), cron: '0 0 10 ? * MON', cronText: t('automation2.tplRivalCron') },
  { icon: '🐞', name: t('automation2.tplBug'), desc: t('automation2.tplBugDesc'), cron: '0 0 20 * * ?', cronText: t('automation2.tplBugCron') },
  { icon: '🤖', name: t('automation2.tplTest'), desc: t('automation2.tplTestDesc'), cron: '0 0 21 ? * FRI', cronText: t('automation2.tplTestCron') },
  { icon: '📊', name: t('automation2.tplReport'), desc: t('automation2.tplReportDesc'), cron: '0 0 18 * * ?', cronText: t('automation2.tplReportCron') },
]

function useTemplate(t: { name: string; desc: string; cron: string }) {
  editingId.value = null
  form.value = { name: t.name, prompt: t.desc, cron: t.cron, agent: 'chat', autoApprove: true, vars: [] }
  formError.value = ''
  modalShow.value = true
}

/** 规范化 cron:仅接受 6 段 Spring 格式;5 段/非法一律置空(视为"不设置")。 */
function sanitizeCron(c?: string | null): string {
  const s = (c || '').trim()
  if (!s) return ''
  const parts = s.split(/\s+/)
  return parts.length === 6 ? s : ''
}

function openAllRuns() {
  activeTab.value = 'runs'
  runsPage.value = 1
  expandedRunKey.value = ''
  loadAllRuns()
}

/** 执行历史:全部任务的运行(分页) */
const allRuns = ref<any[]>([])
const allRunsLoading = ref(false)
const allRunsError = ref('')
const runsPage = ref(1)
const RUNS_PAGE_SIZE = 20
const runsTotal = ref(0)
const expandedRunKey = ref<string>('')
const runLogMap = ref<Record<string, string>>({})
const runLogLoading = ref<Record<string, boolean>>({})
async function loadAllRuns() {
  allRunsLoading.value = true
  allRunsError.value = ''
  try {
    const offset = (runsPage.value - 1) * RUNS_PAGE_SIZE
    const res = await jobApi.allRuns(RUNS_PAGE_SIZE, offset)
    allRuns.value = res.rows || []
    runsTotal.value = res.total || 0
  } catch (e: any) {
    allRunsError.value = e?.message || t('automation2.runsLoadFailed')
  } finally {
    allRunsLoading.value = false
  }
}
function gotoRunsPage(p: number) {
  if (p < 1 || allRunsLoading.value) return
  runsPage.value = p
  expandedRunKey.value = ''
  loadAllRuns()
}
async function toggleRunLog(r: any) {
  const key = String(r.id)
  if (expandedRunKey.value === key) {
    expandedRunKey.value = ''
    return
  }
  expandedRunKey.value = key
  if (runLogMap.value[key] === undefined) {
    runLogLoading.value[key] = true
    try {
      const log = await jobApi.runLog(r.jobId, r.id, 16000)
      runLogMap.value[key] = log || t('automation2.logEmpty')
    } catch (e: any) {
      runLogMap.value[key] = t('automation2.logLoadFailed') + (e?.message || e)
    } finally {
      runLogLoading.value[key] = false
    }
  }
}
/** 结果摘要:去掉过长内容,只取前若干行 */
function digest(text?: string | null, lines = 6): string {
  if (!text) return ''
  const arr = String(text).replace(/\r/g, '').split('\n').filter((l) => l.trim())
  return arr.slice(0, lines).join('\n')
}
function runStatusIcon(s: string): string {
  if (s === 'success') return '✓'
  if (s === 'failed') return '✗'
  if (s === 'timeout') return '⏱'
  if (s === 'running') return '●'
  return '–'
}
function runStatusType(s: string): 'success' | 'error' | 'warning' | 'default' | 'info' {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'error'
  if (s === 'timeout') return 'warning'
  if (s === 'running') return 'info'
  return 'default'
}

/** 执行历史统计 */
const runStats = computed(() => {
  const total = allRuns.value.length
  const success = allRuns.value.filter((r) => r.status === 'success').length
  const failed = allRuns.value.filter((r) => r.status === 'failed' || r.status === 'timeout').length
  const lastAt = allRuns.value.length ? (allRuns.value[0].finishedAt || allRuns.value[0].startedAt) : ''
  return { total, success, failed, lastAt }
})

/** 日志按行拆分并带行号(终端风) */
function logLines(text?: string): string[] {
  if (!text) return []
  return String(text).replace(/\r/g, '').split('\n')
}

/** 从一次运行的 结果/日志 中解析可交付文件(去重,限于工作区相对路径) */
const RESULT_FILE_RE = /\[结果文件\]\s*([^\s`'"，,]+)/g
function runFiles(b: any): { path: string; name: string }[] {
  const text = `${b?.result || ''}\n${runLogMap.value[String(b?.id)] || ''}`
  const set = new Set<string>()
  let m: RegExpExecArray | null
  RESULT_FILE_RE.lastIndex = 0
  while ((m = RESULT_FILE_RE.exec(text)) !== null) {
    const p = m[1].replace(/^\/workspace\//, '').replace(/^\.?\//, '').trim()
    if (p && !p.includes('..')) set.add(p)
  }
  return Array.from(set).map((path) => ({ path, name: path.split('/').pop() || path }))
}

// 结果文件预览弹窗
const previewPath = ref('')
function openPreview(path: string) { previewPath.value = path }
async function downloadFile(path: string) {
  try { await fileApi.download(path) } catch (e: any) { msg.error(e?.message || t('automation2.downloadFailed')) }
}
/** 把日志按语义轻量着色:tool_result / 提示 / 结果行 */
function lineClass(line: string): string {
  const l = line.trim()
  if (l.startsWith('[tool_result]') || l.startsWith('[tool')) return 'ln-tool'
  if (l.startsWith('[结果文件]')) return 'ln-result'
  if (/^✓|完成|done|success/i.test(l)) return 'ln-ok'
  if (/^✗|错误|失败|error|exit code [1-9]/i.test(l)) return 'ln-err'
  if (/^提示[:：]/.test(l)) return 'ln-hint'
  if (/^[a-zA-Z0-9._-]+\/|^drwx|^-rw/.test(l)) return 'ln-fs'
  return ''
}

const pagedJobs = computed(() => {
  const start = (page.value - 1) * pageSize
  return jobs.value.slice(start, start + pageSize)
})

/** 任务模板分页 */
const tplPage = ref(1)
const TPL_PAGE_SIZE = 4
const pagedTaskTemplates = computed(() => {
  const start = (tplPage.value - 1) * TPL_PAGE_SIZE
  return TASK_TEMPLATES.slice(start, start + TPL_PAGE_SIZE)
})

/** 新建/编辑弹窗 */
const modalShow = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ name: '', prompt: '', cron: '', agent: 'chat', autoApprove: true, vars: [] as { key: string; value: string }[] })
const saving = ref(false)
const formError = ref('')

/** 任务变量行操作 */
function addVar() {
  form.value.vars.push({ key: '', value: '' })
}
function removeVar(i: number) {
  form.value.vars.splice(i, 1)
}

/** 执行记录弹窗 */
const runsShow = ref(false)
const runsJob = ref<{ id: number; name: string } | null>(null)

function openRuns(j: JobTaskInfo) {
  runsJob.value = { id: j.id, name: j.name }
  runsShow.value = true
}

const hasActive = computed(() => jobs.value.some((j) => j.status === 'running' || j.status === 'queued'))

let timer: ReturnType<typeof setInterval> | null = null

async function refresh() {
  try {
    error.value = ''
    jobs.value = await jobApi.list()
    const maxPage = Math.max(1, Math.ceil(jobs.value.length / pageSize))
    if (page.value > maxPage) page.value = maxPage
  } catch (e: any) {
    error.value = e?.message || t('automation2.jobsLoadFailed')
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

function openCreate() {
  editingId.value = null
  form.value = { name: '', prompt: '', cron: '', agent: 'chat', autoApprove: true, vars: [] }
  formError.value = ''
  modalShow.value = true
}

function openEdit(j: JobTaskInfo) {
  editingId.value = j.id
  form.value = {
    name: j.name,
    prompt: j.prompt,
    cron: sanitizeCron(j.cron),
    agent: j.agent || 'chat',
    autoApprove: j.autoApprove,
    vars: Object.entries(j.variables || {}).map(([key, value]) => ({ key, value })),
  }
  formError.value = ''
  modalShow.value = true
}

async function save() {
  if (!form.value.prompt.trim()) {
    formError.value = t('automation2.needPrompt')
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const cron = sanitizeCron(form.value.cron) || null
    const variables: Record<string, string> = {}
    for (const r of form.value.vars) {
      const k = r.key.trim()
      if (k) variables[k] = r.value
    }
    const payload = {
      name: form.value.name,
      prompt: form.value.prompt,
      cron,
      agent: form.value.agent,
      autoApprove: form.value.autoApprove,
      variables,
    }
    if (editingId.value) {
      await jobApi.update(editingId.value, payload)
      msg.success(t('automation2.updated'))
    } else {
      await jobApi.create(payload)
      msg.success(t('automation2.created') + (cron ? '' : t('automation2.createdManual')))
    }
    modalShow.value = false
    await refresh()
  } catch (e: any) {
    formError.value = e?.message || t('automation2.saveFailed')
  } finally {
    saving.value = false
  }
}

async function onRun(j: JobTaskInfo) {
  if (busyId.value !== null) return
  busyId.value = j.id
  try {
    const ok = await jobApi.run(j.id)
    msg.success(ok ? t('automation2.queued') : t('automation2.alreadyQueued'))
    await refresh()
  } catch (e: any) {
    msg.error(e?.message || t('automation2.triggerFailed'))
  } finally {
    busyId.value = null
  }
}

async function onCancel(j: JobTaskInfo) {
  if (busyId.value !== null) return
  busyId.value = j.id
  try {
    await jobApi.cancel(j.id)
    msg.success(t('automation2.canceled'))
    await refresh()
  } catch (e: any) {
    msg.error(e?.message || t('automation2.cancelFailed'))
  } finally {
    busyId.value = null
  }
}

function onDelete(j: JobTaskInfo) {
  dialog.warning({
    title: t('automation2.delTitle'),
    content: t('automation2.delContent', { name: j.name }),
    positiveText: t('automation2.del'),
    negativeText: t('automation2.cancel'),
    onPositiveClick: async () => {
      try {
        await jobApi.remove(j.id)
        msg.success(t('automation2.deleted'))
        await refresh()
      } catch (e: any) {
        msg.error(e?.message || t('automation2.delFailed'))
      }
    },
  })
}

async function onToggleEnabled(j: JobTaskInfo) {
  if (busyId.value !== null) return
  busyId.value = j.id
  try {
    await jobApi.update(j.id, { enabled: !j.enabled })
    await refresh()
  } catch (e: any) {
    msg.error(e?.message || t('automation2.opFailed'))
  } finally {
    busyId.value = null
  }
}

function onMenu(key: string, j: JobTaskInfo) {
  if (key === 'edit') openEdit(j)
  else if (key === 'cancel') onCancel(j)
  else if (key === 'delete') onDelete(j)
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

/** 任务图标:与模板库图标块一致 */
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

/** 全局暂停(仅管理员可切换):暂停后不再触发/派发新任务,运行中的照常完成 */
const jobsPaused = ref(false)
async function loadPaused() {
  if (!auth.isAdmin) return
  try { jobsPaused.value = await adminApi.jobPaused() } catch { /* 忽略 */ }
}
async function togglePaused() {
  try {
    jobsPaused.value = await adminApi.setJobPaused(!jobsPaused.value)
    msg.success(jobsPaused.value ? t('automation2.allPaused') : t('automation2.allResumed'))
  } catch (e: any) {
    msg.error(e?.message || t('automation2.opFailed'))
  }
}

onMounted(() => {
  loading.value = true
  refresh()
  startPolling()
  loadPaused()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <component :is="page ? 'div' : NModal" v-bind="rootBind">
    <div v-if="page" class="manage-crumb">
      <button class="crumb-link" @click="emit('close')">首页</button>
      <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ t('automation2.crumb') }}</span>
    </div>
    <div v-if="page" class="manage-title">{{ t('automation2.crumb') }}</div>
    <div v-if="page" class="manage-sub">{{ t('automation2.manageSub') }}</div>
    <div class="auto-panel">
      <div class="panel-head">
        <SectionTabs
          v-model:active="activeTab"
          :items="autoTabItems"
          :aria-label="t('automation.ariaTabs')"
          @change="onTabChange"
        />
        <div class="head-actions">
          <n-button v-if="auth.isAdmin" size="small" :type="jobsPaused ? 'warning' : 'default'" quaternary @click="togglePaused">
            {{ jobsPaused ? t('automation2.resume') : t('automation2.pauseAll') }}
          </n-button>
          <n-button size="small" quaternary :title="t('automation2.refreshTitle')" @click="refresh">{{ t('automation2.refresh') }}</n-button>
          <n-button size="small" type="primary" @click="openCreate">{{ t('automation2.newTask') }}</n-button>
        </div>
      </div>

      <div class="panel-scroll">
        <n-spin :show="loading" size="small">
          <div v-if="error" class="job-error">{{ error }}</div>

          <!-- 执行历史(全部任务) -->
          <div v-else-if="activeTab === 'runs'">
            <div v-if="allRunsError" class="job-error">{{ allRunsError }}</div>
            <div v-else-if="!allRuns.length && !allRunsLoading" class="job-empty">{{ t('automation2.runsEmpty') }}</div>
            <template v-else>
              <!-- 统计条 -->
              <div class="hist-stats">
                <span class="hs-item"><i class="hs-dot ok" />{{ t('automation2.statOk') }} <b>{{ runStats.success }}</b></span>
                <span class="hs-item"><i class="hs-dot err" />{{ t('automation2.statFail') }} <b>{{ runStats.failed }}</b></span>
                <span class="hs-item">{{ t('automation2.statTotal', { n: runsTotal }) }}</span>
                <span v-if="runStats.lastAt" class="hs-last">{{ t('automation2.statLast', { t: formatTime(runStats.lastAt) }) }}</span>
              </div>
              <div class="hist-list">
                <div v-for="r in allRuns" :key="r.id" class="hist-item" :class="'hst-' + (r.status || '')">
                  <div class="hist-head" @click="toggleRunLog(r)">
                    <span class="hist-badge" :class="'st-' + (r.status || '')">{{ runStatusIcon(r.status) }}</span>
                    <div class="hist-meta">
                      <div class="hist-titlerow">
                        <span class="hist-name">{{ r.jobName }}</span>
                        <n-tag size="tiny" :bordered="false" :type="runStatusType(r.status)">{{ r.statusText }}</n-tag>
                      </div>
                      <div class="hist-subrow">
                        <span class="hist-time">{{ formatTime(r.finishedAt || r.startedAt) }}</span>
                        <span v-if="r.runningSeconds != null" class="hist-sep">| ⏱ {{ formatDuration(r.runningSeconds) }}</span>
                        <span v-if="r.exitCode != null" class="hist-sep">| {{ t('automation2.exitCode', { code: r.exitCode }) }}</span>
                      </div>
                    </div>
                    <svg class="hist-chev" :class="{ open: expandedRunKey === String(r.id) }" viewBox="0 0 24 24" width="16" height="16"><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
                  </div>
                  <div v-if="expandedRunKey === String(r.id)" class="term">
                    <div class="term-bar">
                      <span class="term-dots"><i class="d r" /><i class="d y" /><i class="d g" /></span>
                      <span class="term-title">{{ t('automation2.termTitle') }}</span>
                      <span class="spacer"></span>
                      <span class="term-time">{{ formatTime(r.finishedAt || r.startedAt) }}</span>
                    </div>
                    <div v-if="runLogLoading[String(r.id)]" class="term-loading">{{ t('automation2.logLoading') }}</div>
                    <div v-else class="term-body">
                      <div v-if="!logLines(runLogMap[String(r.id)]).length" class="term-empty">{{ t('automation2.noLog') }}</div>
                      <div v-for="(ln, i) in logLines(runLogMap[String(r.id)])" :key="i" class="term-line" :class="lineClass(ln)">
                        <span class="term-no">{{ i + 1 }}</span>
                        <span class="term-txt">{{ ln }}</span>
                      </div>
                    </div>
                    <div v-if="runFiles(r).length" class="term-files">
                      <div class="tf-files-title">{{ t('automation2.resultFiles') }}</div>
                      <div class="tf-files">
                        <button
                          v-for="f in runFiles(r)"
                          :key="f.path"
                          class="tf-file"
                          :title="f.path"
                          @click="openPreview(f.path)"
                        >
                          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/></svg>
                          <span class="tf-file-name">{{ f.name }}</span>
                          <span class="tf-file-ops">
                            <span class="tf-op" :title="t('automation2.preview')" @click.stop="openPreview(f.path)">{{ t('automation2.preview') }}</span>
                            <span class="tf-op" :title="t('automation2.download')" @click.stop="downloadFile(f.path)">{{ t('automation2.download') }}</span>
                          </span>
                        </button>
                      </div>
                    </div>
                    <div v-if="r.result || r.error" class="term-foot" :class="{ err: !!r.error }">
                      <span class="tf-mark">{{ r.error ? '✗' : '✓' }}</span>
                      <span class="tf-text">{{ digest(r.error || r.result, 1) }}</span>
                      <span class="spacer"></span>
                      <span class="tf-done">{{ r.error ? 'FAILED' : 'DONE' }}</span>
                    </div>
                  </div>
                </div>
              </div>
              <div v-if="runsTotal > RUNS_PAGE_SIZE" class="pager">
                <ListPager :page="runsPage" :item-count="runsTotal" :page-size="RUNS_PAGE_SIZE" @update:page="gotoRunsPage" />
              </div>
            </template>
          </div>

          <!-- 任务模板 -->
          <div v-else-if="activeTab === 'tpl'">
            <div class="tpl-grid">
              <div v-for="t in pagedTaskTemplates" :key="t.name" class="tpl-card" @click="useTemplate(t)">
                <div class="tpl-card-head">
                  <span class="tpl-emoji">{{ t.icon }}</span>
                  <span class="tpl-name">{{ t.name }}</span>
                </div>
                <div class="tpl-desc">{{ t.desc }}</div>
                <div class="tpl-cron">{{ t.cronText }}</div>
              </div>
            </div>
            <div v-if="TASK_TEMPLATES.length > TPL_PAGE_SIZE" class="pager">
              <ListPager v-model:page="tplPage" :item-count="TASK_TEMPLATES.length" :page-size="TPL_PAGE_SIZE" />
            </div>
          </div>

          <!-- 已配置 -->
          <template v-else>
            <div v-if="!jobs.length" class="job-empty">
              {{ t('automation2.emptyTitle') }}<br />
              <span class="job-empty-sub">{{ t('automation2.emptySub') }}</span>
            </div>
            <div v-else class="job-grid">
              <div v-for="j in pagedJobs" :key="j.id" class="job-card" :class="{ disabled: !j.enabled }">
                <div class="job-card-head">
                  <span class="job-icon" :class="'icon-' + statusType(j)" :title="j.statusText">{{ jobIcon(j) }}</span>
                  <div class="job-card-title">
                    <span class="job-name" :title="j.name">{{ j.name }}</span>
                    <span class="job-sub-line">
                      <n-tag size="tiny" :type="statusType(j)" :bordered="false">
                        <template v-if="j.status === 'running'" #icon><span class="mini-spin" /></template>
                        {{ j.statusText }}
                      </n-tag>
                      <span v-if="j.cron" class="cron-chip" :title="j.cron">{{ j.cron }}</span>
                      <span v-else class="once-chip">{{ t('automation2.once') }}</span>
                    </span>
                  </div>
                </div>
                <div class="job-content" :title="j.prompt">{{ j.prompt || t('automation2.emptyPrompt') }}</div>
                <div v-if="Object.keys(j.variables || {}).length" class="job-vars">
                  <span v-for="(v, k) in j.variables" :key="k" class="var-chip" :title="`${k} = ${v}`">{{ k }}</span>
                </div>
                <div class="job-sub">
                  <span v-if="j.nextRunAt" class="t-next">{{ t('automation2.next', { t: formatTime(j.nextRunAt) }) }}</span>
                  <span v-else-if="j.finishedAt" class="t-time">{{ t('automation2.last', { t: formatTime(j.finishedAt) }) }}</span>
                  <span v-if="j.runningSeconds != null" class="t-dur">{{ t('automation2.cost', { d: formatDuration(j.runningSeconds) }) }}</span>
                </div>
                <div class="job-actions">
                  <n-switch size="small" :value="j.enabled" :disabled="busyId !== null" :loading="busyId === j.id" @update:value="onToggleEnabled(j)" />
                  <span class="spacer"></span>
                  <n-button size="tiny" type="primary" secondary :disabled="busyId !== null || j.status === 'running' || j.status === 'queued'" @click="onRun(j)">{{ t('automation2.run') }}</n-button>
                  <n-dropdown
                    trigger="click"
                    placement="bottom-end"
                    :options="[
                      { label: t('automation2.menuEdit'), key: 'edit' },
                      { label: t('automation2.menuCancel'), key: 'cancel', disabled: j.status !== 'queued' && j.status !== 'pending' },
                      { label: t('automation2.menuDelete'), key: 'delete', type: 'error' },
                    ]"
                    @select="(key: string) => onMenu(key, j)"
                  >
                    <n-button size="tiny" quaternary>···</n-button>
                  </n-dropdown>
                </div>
              </div>
            </div>
          </template>
        </n-spin>
      </div>

      <div v-if="activeTab === 'jobs' && jobs.length > pageSize" class="pager">
        <ListPager v-model:page="page" :item-count="jobs.length" :page-size="pageSize" />
      </div>
    </div>

    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? t('automation2.editTitle') : t('automation2.newTitle')" style="width: 600px; max-width: 94vw">
      <div class="job-form">
        <n-form label-placement="top" size="small">
          <n-form-item :label="t('automation2.labelName')">
            <n-input v-model:value="form.name" :placeholder="t('automation2.phName')" maxlength="80" />
          </n-form-item>
          <n-form-item :label="t('automation2.labelCron')">
            <CronPicker v-model:value="form.cron" />
          </n-form-item>
          <n-form-item :label="t('automation2.labelPrompt')">
            <n-input v-model:value="form.prompt" type="textarea" :rows="5" :placeholder="t('automation2.phPrompt')" />
          </n-form-item>
          <n-form-item :label="t('automation2.labelVars')">
            <div class="var-editor">
              <div v-for="(row, i) in form.vars" :key="i" class="var-row">
                <n-input v-model:value="row.key" size="small" :placeholder="t('automation2.phVarKey')" style="width: 150px" />
                <span class="var-eq">=</span>
                <n-input v-model:value="row.value" size="small" :placeholder="t('automation2.phVarValue')" />
                <n-button size="small" quaternary :title="t('automation2.del')" @click="removeVar(i)">✕</n-button>
              </div>
              <n-button size="tiny" dashed @click="addVar">{{ t('automation2.addVar') }}</n-button>
              <span class="approve-hint">{{ t('automation2.varHintPre') }}<code>{{ '${变量名}' }}</code>{{ t('automation2.varHintPost') }}</span>
            </div>
          </n-form-item>
          <n-form-item :label="t('automation2.labelAgent')">
            <n-input :value="t('automation2.agentTaskUnattended')" readonly />
            <span class="approve-hint">{{ t('automation2.agentTaskHint') }}</span>
          </n-form-item>
          <n-form-item :label="t('automation2.labelPerm')">
            <n-switch v-model:value="form.autoApprove" />
            <span class="approve-hint">{{ form.autoApprove ? t('automation2.permAuto') : t('automation2.permDefault') }}</span>
          </n-form-item>
          <div v-if="formError" class="form-error">{{ formError }}</div>
        </n-form>
      </div>
      <template #footer>
        <div class="form-footer">
          <n-button size="small" @click="modalShow = false">{{ t('automation2.cancel') }}</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">{{ editingId ? t('automation2.save') : t('automation2.create') }}</n-button>
        </div>
      </template>
    </n-modal>
  </component>

  <JobRunsModal v-if="runsJob" v-model:show="runsShow" :job-id="runsJob.id" :job-name="runsJob.name" />

  <n-modal
    :show="!!previewPath"
    preset="card"
    :title="previewPath ? previewPath.split(/[\\/]/).pop() : t('automation2.preview')"
    :style="{ width: '82vw', maxWidth: '1200px' }"
    :mask-closable="true"
    :bordered="false"
    @update:show="(v: boolean) => { if (!v) previewPath = '' }"
  >
    <FilePreview v-if="previewPath" :path="previewPath" />
  </n-modal>
</template>

<style scoped>
.auto-panel {
  display: flex; flex-direction: column; gap: 8px;
  flex: 1; min-height: 0;
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 0 12px; flex-shrink: 0; flex-wrap: wrap; gap: 10px;
}
/* 页内 Tab 样式已上收到 components/ui/SectionTabs.vue */
.head-actions { display: flex; align-items: center; gap: 8px; }
.job-error { color: var(--err); font-size: 12px; padding: 8px 0; }
.job-empty { color: var(--text-3); font-size: 13px; padding: 40px 0; text-align: center; line-height: 1.9; }
.job-empty-sub { font-size: 12px; color: var(--text-4); }

/* 列表区占满剩余高度并内部滚动,分页固定底部 */
.panel-scroll { flex: 1; min-height: 0; overflow-y: auto; }

/* 任务卡片网格 */
.job-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.job-card {
  display: flex; flex-direction: column; gap: 8px;
  padding: 14px 16px; border-radius: 12px;
  background: var(--surface); border: 1px solid var(--border-soft);
  transition: border-color .15s, box-shadow .15s;
}
.job-card:hover { border-color: var(--border); box-shadow: var(--shadow); }
.job-card.disabled { opacity: 0.6; }
.job-card-head { display: flex; align-items: center; gap: 11px; }
.job-icon {
  width: 36px; height: 36px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; background: var(--accent-soft);
}
.job-icon.icon-success { background: rgba(52, 211, 153, .15); }
.job-icon.icon-error { background: rgba(239, 68, 68, .15); }
.job-icon.icon-warning { background: rgba(245, 158, 11, .15); }
.job-icon.icon-primary { background: var(--accent-soft); }
.job-card-title { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.job-name {
  font-size: 14.5px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.job-sub-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cron-chip {
  display: inline-block; font-size: 10px; color: var(--accent-text);
  background: var(--surface-hover); border: 1px solid var(--border); border-radius: 4px;
  padding: 1px 5px; font-family: var(--font-mono, monospace); flex-shrink: 0;
}
.once-chip { font-size: 10px; color: var(--text-3); flex-shrink: 0; }
.job-content {
  font-size: 12.5px; color: var(--text-3); line-height: 1.55;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap; min-height: 2.6em;
}
.job-sub { display: flex; gap: 12px; }
.t-next { font-size: 11px; color: var(--warn); white-space: nowrap; }
.t-time { font-size: 11px; color: var(--text-3); white-space: nowrap; }
.t-dur { font-size: 11px; color: var(--text-3); white-space: nowrap; }
.mini-spin {
  display: inline-block; width: 10px; height: 10px; margin-right: 4px;
  border: 2px solid currentColor; border-top-color: transparent;
  border-radius: 50%; animation: am-spin .7s linear infinite;
  vertical-align: -1px;
}
@keyframes am-spin { to { transform: rotate(360deg); } }
.job-actions { display: flex; align-items: center; gap: 6px; padding-top: 8px; border-top: 1px solid var(--border-soft); }
.job-actions .spacer { flex: 1; }

/* 任务模板卡片 */
.tpl-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
.tpl-card {
  padding: 16px; border-radius: 12px; background: var(--surface);
  border: 1px solid var(--border-soft); cursor: pointer;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.tpl-card:hover { border-color: var(--accent); box-shadow: var(--shadow); transform: translateY(-2px); }
.tpl-card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.tpl-emoji { font-size: 20px; }
.tpl-name { font-size: 14.5px; font-weight: 600; color: var(--text); }
.tpl-desc { font-size: 12.5px; color: var(--text-3); line-height: 1.6; min-height: 2.6em; }
.tpl-cron { margin-top: 8px; font-size: 11px; color: var(--accent-text); background: var(--accent-soft); padding: 2px 9px; border-radius: 6px; display: inline-block; }
.manage-sub { font-size: 13px; color: var(--text-3); margin: -6px 0 14px; }

/* 执行历史:统计条 + 卡片 + 深色终端日志 */
.hist-stats { display: flex; align-items: center; gap: 16px; padding: 4px 2px 14px; font-size: 12.5px; color: var(--text-3); flex-wrap: wrap; }
.hist-stats b { color: var(--text); font-weight: 600; }
.hs-item { display: inline-flex; align-items: center; gap: 6px; }
.hs-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.hs-dot.ok { background: var(--ok); }
.hs-dot.err { background: var(--err); }
.hs-last { margin-left: auto; }

.hist-list { display: flex; flex-direction: column; gap: 12px; }
.pager { display: flex; justify-content: center; padding: 14px 0 4px; flex-shrink: 0; }
.hist-item {
  border: 1px solid var(--border-soft); border-radius: 12px; background: var(--surface);
  overflow: hidden; transition: border-color .15s, box-shadow .15s;
}
.hist-item:hover { box-shadow: var(--shadow); }
.hist-item.hst-failed { border-color: rgba(228, 122, 134, .35); }
.hist-head { display: flex; align-items: center; gap: 14px; padding: 14px 18px; cursor: pointer; }
.hist-head:hover { background: var(--fill-0); }
.hist-badge {
  width: 40px; height: 40px; border-radius: 12px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center; font-size: 18px; font-weight: 700;
  background: var(--fill-1); color: var(--text-3);
}
.hist-badge.st-success { background: rgba(88, 196, 155, .16); color: var(--ok); }
.hist-badge.st-failed { background: rgba(228, 122, 134, .16); color: var(--err); }
.hist-badge.st-timeout { background: rgba(232, 178, 107, .16); color: var(--warn); }
.hist-badge.st-running { background: var(--accent-soft); color: var(--accent-text); }
.hist-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.hist-titlerow { display: flex; align-items: center; gap: 9px; }
.hist-name { font-size: 15px; font-weight: 700; color: var(--text); }
.hist-subrow { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--text-3); flex-wrap: wrap; }
.hist-sep { color: var(--text-4); }
.hist-chev { color: var(--text-3); flex-shrink: 0; transition: transform .2s; }
.hist-chev.open { transform: rotate(180deg); }

/* 深色终端风日志 */
.term { background: #0f1216; border-top: 1px solid rgba(255, 255, 255, .06); }
.term-bar {
  display: flex; align-items: center; gap: 10px; padding: 10px 16px;
  background: #15191f; border-bottom: 1px solid rgba(255, 255, 255, .06);
}
.term-dots { display: inline-flex; gap: 6px; }
.term-dots .d { width: 11px; height: 11px; border-radius: 50%; display: inline-block; }
.term-dots .d.r { background: #ff5f57; } .term-dots .d.y { background: #febc2e; } .term-dots .d.g { background: #28c840; }
.term-title { color: #9aa3b2; font-size: 12.5px; font-family: var(--font-mono, ui-monospace, monospace); }
.term-time { color: #6b7280; font-size: 12px; }
.term-loading { padding: 16px; color: #9aa3b2; font-size: 12.5px; }
.term-body { padding: 12px 0; max-height: 340px; overflow: auto; }
.term-empty { padding: 0 16px 8px; color: #6b7280; font-size: 12.5px; }
.term-line { display: flex; gap: 12px; padding: 1px 16px; font-family: var(--font-mono, ui-monospace, monospace); font-size: 12.5px; line-height: 1.75; color: #c9d1d9; }
.term-no { flex-shrink: 0; width: 28px; text-align: right; color: #4b5563; user-select: none; }
.term-txt { white-space: pre-wrap; word-break: break-word; min-width: 0; }
.term-line.ln-tool .term-txt { color: #7dd3fc; }
.term-line.ln-result .term-txt { color: #86efac; }
.term-line.ln-ok .term-txt { color: #86efac; }
.term-line.ln-err .term-txt { color: #fca5a5; }
.term-line.ln-hint .term-txt { color: #c4b5fd; }
.term-line.ln-fs .term-txt { color: #e2c08d; }
.term-foot {
  display: flex; align-items: center; gap: 8px; padding: 10px 16px;
  background: #15191f; border-top: 1px solid rgba(255, 255, 255, .06);
  font-size: 12.5px; color: #9aa3b2;
}
.term-foot .tf-mark { color: var(--ok); font-weight: 700; }
.term-foot.err .tf-mark { color: #fca5a5; }
.term-foot .tf-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.term-foot .tf-done { color: #4ade80; font-family: var(--font-mono, ui-monospace, monospace); font-weight: 700; letter-spacing: .5px; }
.term-foot.err .tf-done { color: #fca5a5; }

/* 结果文件(终端内) */
.term-files { padding: 10px 16px; border-top: 1px solid rgba(255, 255, 255, .06); background: #12161b; }
.tf-files-title { font-size: 11px; letter-spacing: .5px; color: #6b7280; margin-bottom: 8px; text-transform: uppercase; }
.tf-files { display: flex; flex-wrap: wrap; gap: 8px; }
.tf-file {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 6px 10px; border-radius: 8px; cursor: pointer;
  background: rgba(255, 255, 255, .05); border: 1px solid rgba(255, 255, 255, .09);
  color: #c9d1d9; font-size: 12.5px; font-family: inherit;
  transition: background .12s, border-color .12s;
}
.tf-file:hover { background: rgba(255, 255, 255, .1); border-color: var(--accent); }
.tf-file svg { color: #7dd3fc; flex-shrink: 0; }
.tf-file-name { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tf-file-ops { display: inline-flex; gap: 6px; margin-left: 4px; }
.tf-op { font-size: 11.5px; color: #7dd3fc; padding: 1px 6px; border-radius: 5px; }
.tf-op:hover { background: rgba(125, 211, 252, .16); }

.approve-hint { font-size: 12px; color: var(--text-3); margin-left: 10px; }
.var-editor { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.var-editor .approve-hint { margin-left: 0; }
.var-editor code { background: var(--fill-1); padding: 1px 5px; border-radius: 4px; font-family: var(--font-mono, monospace); font-size: 11.5px; }
.var-row { display: flex; align-items: center; gap: 8px; }
.var-eq { color: var(--text-3); font-size: 12px; }
.job-vars { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 2px; }
.var-chip {
  font-size: 10.5px; color: var(--accent-text); background: var(--accent-soft);
  border-radius: 4px; padding: 1px 7px; font-family: var(--font-mono, monospace); white-space: nowrap;
}
.form-error { color: var(--err); font-size: 12px; padding: 4px 0; }
.form-footer { display: flex; justify-content: flex-end; gap: 8px; }

/* 整页模式:面包屑 + 内容滚动 */
.manage-page { flex: 1; min-width: 0; display: flex; flex-direction: column; height: 100%; min-height: 0; overflow-y: auto; padding: 18px 24px 28px; background: var(--bg); box-sizing: border-box; }
.manage-crumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); margin-bottom: 12px; }
.crumb-link { background: none; border: none; padding: 0; color: var(--text-3); cursor: pointer; font-size: 12px; }
.crumb-link:hover { color: var(--accent-text); }
.crumb-sep { color: var(--text-4); }
.crumb-cur { color: var(--text-2); }
.manage-title { font-size: 20px; font-weight: 700; color: var(--text); margin-bottom: 14px; }
</style>
