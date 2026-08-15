<script setup lang="ts">
/**
 * 自动化管理弹窗(侧边栏「自动化」触发):任务列表 + 启用/停用 + 新建/编辑/删除/执行。
 * 定时设置使用可视化 CronPicker(最小间隔 1 小时)。
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useMessage, useDialog } from 'naive-ui'
import { jobApi } from '@/api/jobs'
import type { JobTaskInfo } from '@/types'
import CronPicker from '@/components/CronPicker.vue'
import JobRunsModal from '@/components/JobRunsModal.vue'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const msg = useMessage()
const dialog = useDialog()
const jobs = ref<JobTaskInfo[]>([])
const loading = ref(false)
const error = ref('')
const busyId = ref<number | null>(null)
/** 分页 */
const page = ref(1)
const pageSize = 8

const pagedJobs = computed(() => {
  const start = (page.value - 1) * pageSize
  return jobs.value.slice(start, start + pageSize)
})

/** 新建/编辑弹窗 */
const modalShow = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ name: '', prompt: '', cron: '', agent: 'chat', autoApprove: true })
const saving = ref(false)
const formError = ref('')

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
    error.value = e?.message || '加载定时任务失败'
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
  form.value = { name: '', prompt: '', cron: '', agent: 'chat', autoApprove: true }
  formError.value = ''
  modalShow.value = true
}

function openEdit(j: JobTaskInfo) {
  editingId.value = j.id
  form.value = {
    name: j.name,
    prompt: j.prompt,
    cron: j.cron || '',
    agent: j.agent || 'chat',
    autoApprove: j.autoApprove,
  }
  formError.value = ''
  modalShow.value = true
}

async function save() {
  if (!form.value.prompt.trim()) {
    formError.value = '请输入任务提示词'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    const cron = form.value.cron.trim() || null
    if (editingId.value) {
      await jobApi.update(editingId.value, { ...form.value, cron })
      msg.success('已更新任务')
    } else {
      await jobApi.create({ ...form.value, cron })
      msg.success('已创建任务' + (cron ? '' : '(手动,已加入队列)'))
    }
    modalShow.value = false
    await refresh()
  } catch (e: any) {
    formError.value = e?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function onRun(j: JobTaskInfo) {
  if (busyId.value !== null) return
  busyId.value = j.id
  try {
    const ok = await jobApi.run(j.id)
    msg.success(ok ? '已加入执行队列' : '任务已在队列/运行中')
    await refresh()
  } catch (e: any) {
    msg.error(e?.message || '触发失败')
  } finally {
    busyId.value = null
  }
}

async function onCancel(j: JobTaskInfo) {
  if (busyId.value !== null) return
  busyId.value = j.id
  try {
    await jobApi.cancel(j.id)
    msg.success('已取消本次排队')
    await refresh()
  } catch (e: any) {
    msg.error(e?.message || '取消失败')
  } finally {
    busyId.value = null
  }
}

function onDelete(j: JobTaskInfo) {
  dialog.warning({
    title: '删除任务',
    content: `确定删除定时任务「${j.name}」吗?历史记录会一并清除。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await jobApi.remove(j.id)
        msg.success('已删除')
        await refresh()
      } catch (e: any) {
        msg.error(e?.message || '删除失败')
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
    msg.error(e?.message || '操作失败')
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

onMounted(() => {
  loading.value = true
  refresh()
  startPolling()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <n-modal v-model:show="props.show" preset="card" title="自动化 · 定时任务" style="width: 720px; max-width: 94vw" :bordered="false" @update:show="emit('update:show', $event)">
    <div class="auto-panel">
      <div class="panel-head">
        <span class="hint">{{ hasActive ? '有任务运行/排队,自动刷新' : '空闲' }} · 最小间隔 1 小时</span>
        <div class="head-actions">
          <n-button size="tiny" quaternary circle title="刷新" @click="refresh">↻</n-button>
          <n-button size="tiny" type="primary" secondary title="新建定时任务" @click="openCreate">＋ 新建</n-button>
        </div>
      </div>

      <div class="job-body">
        <n-spin :show="loading" size="small" style="display: block; margin: 24px auto">
          <div v-if="error" class="job-error">{{ error }}</div>
          <div v-else-if="!jobs.length" class="job-empty">
            暂无定时任务<br />
            <span class="job-empty-sub">点击「＋ 新建」创建:填任务提示词,用可视化选择器设定时(最小 1 小时);不设定时则创建后立即执行。</span>
          </div>
          <div v-else class="job-list">
            <div v-for="j in pagedJobs" :key="j.id" class="job-item" :class="{ disabled: !j.enabled }">
            <div class="job-icon" :class="'icon-' + statusType(j)" :title="j.statusText">
              {{ jobIcon(j) }}
            </div>
            <div class="job-info">
              <div class="job-row">
                <span class="job-name" :title="j.name">{{ j.name }}</span>
                <n-tag size="tiny" :type="statusType(j)" :bordered="false" class="job-status">
                  <template v-if="j.status === 'running'" #icon><span class="mini-spin" /></template>
                  {{ j.statusText }}
                </n-tag>
                <span v-if="j.cron" class="cron-chip" :title="j.cron">{{ j.cron }}</span>
                <span v-else class="once-chip">一次性</span>
              </div>
              <div class="job-content" :title="j.prompt">{{ j.prompt || '(空提示词)' }}</div>
              <div class="job-sub">
                <span v-if="j.nextRunAt" class="t-next">下次 {{ formatTime(j.nextRunAt) }}</span>
                <span v-else-if="j.finishedAt" class="t-time">上次 {{ formatTime(j.finishedAt) }}</span>
                <span v-if="j.runningSeconds != null" class="t-dur">耗时 {{ formatDuration(j.runningSeconds) }}</span>
              </div>
            </div>
            <div class="job-actions">
              <n-switch size="small" :value="j.enabled" :disabled="busyId !== null" :loading="busyId === j.id" @update:value="onToggleEnabled(j)">
                <template #checked>启用</template>
                <template #unchecked>停用</template>
              </n-switch>
              <n-button size="tiny" quaternary @click="openRuns(j)">执行记录</n-button>
              <n-button size="tiny" type="primary" secondary :disabled="busyId !== null || j.status === 'running' || j.status === 'queued'" @click="onRun(j)">执行</n-button>
              <n-dropdown
                trigger="click"
                placement="bottom-end"
                :options="[
                  { label: '编辑', key: 'edit' },
                  { label: '取消排队', key: 'cancel', disabled: j.status !== 'queued' && j.status !== 'pending' },
                  { label: '删除', key: 'delete', type: 'error' },
                ]"
                @select="(key: string) => onMenu(key, j)"
              >
                <n-button size="tiny" quaternary>···</n-button>
              </n-dropdown>
            </div>
            </div>
          </div>
        </n-spin>
      </div>


      <div v-if="jobs.length > pageSize" class="pager">
        <n-pagination v-model:page="page" :page-size="pageSize" :item-count="jobs.length" size="small" />
      </div>
    </div>

    <n-modal v-model:show="modalShow" preset="card" :title="editingId ? '编辑定时任务' : '新建定时任务'" style="width: 560px">
      <div class="job-form">
        <n-form label-placement="top" size="small">
          <n-form-item label="任务名称">
            <n-input v-model:value="form.name" placeholder="如:每日数据汇总" maxlength="80" />
          </n-form-item>
          <n-form-item label="任务提示词(必填)">
            <n-input v-model:value="form.prompt" type="textarea" :rows="5" placeholder="告诉 AI 要做什么,如:把工作区里的 report.csv 汇总成本周数据报告并写入 result.md" />
          </n-form-item>
          <n-form-item label="定时设置(留空 = 创建后立即执行一次,最小间隔 1 小时)">
            <CronPicker v-model:value="form.cron" />
          </n-form-item>
          <n-form-item label="agent">
            <n-input v-model:value="form.agent" placeholder="chat" />
          </n-form-item>
          <n-form-item label="权限策略">
            <n-switch v-model:value="form.autoApprove" />
            <span class="approve-hint">{{ form.autoApprove ? '自动批准未显式拒绝的权限(--auto,非交互运行推荐)' : '默认拒绝需确认的权限' }}</span>
          </n-form-item>
          <div v-if="formError" class="form-error">{{ formError }}</div>
        </n-form>
      </div>
      <template #footer>
        <div class="form-footer">
          <n-button size="small" @click="modalShow = false">取消</n-button>
          <n-button size="small" type="primary" :loading="saving" @click="save">保存</n-button>
        </div>
      </template>
    </n-modal>
  </n-modal>

  <JobRunsModal v-if="runsJob" v-model:show="runsShow" :job-id="runsJob.id" :job-name="runsJob.name" />
</template>

<style scoped>
.auto-panel {
  display: flex; flex-direction: column; gap: 8px;
  height: 70vh; min-height: 360px;
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 2px 0 8px; border-bottom: 1px solid var(--border-soft); flex-shrink: 0;
}
.panel-head .hint { font-size: 12px; color: var(--text-3); }
.head-actions { display: flex; align-items: center; gap: 4px; }
.job-error { color: var(--err); font-size: 12px; padding: 8px 0; }
.job-empty { color: var(--text-3); font-size: 12px; padding: 16px 0; text-align: center; line-height: 1.8; }
.job-empty-sub { font-size: 11px; color: var(--text-3); opacity: 0.8; }

/* 列表区占满剩余高度并内部滚动,分页固定底部 */
.job-body { flex: 1; min-height: 0; overflow-y: auto; }

/* 与模板库 tpl-item 一致:图标块 + 中间(标题行+描述+元信息) + 右侧操作 */
.job-list { display: flex; flex-direction: column; gap: 8px; }
.job-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.job-item.disabled { opacity: 0.55; }
.job-icon {
  width: 36px; height: 36px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; background: var(--accent-soft);
}
.job-icon.icon-success { background: rgba(52, 211, 153, .15); }
.job-icon.icon-error { background: rgba(239, 68, 68, .15); }
.job-icon.icon-warning { background: rgba(245, 158, 11, .15); }
.job-icon.icon-primary { background: var(--accent-soft); }
.job-info { flex: 1; min-width: 0; }
.job-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; min-width: 0; }
.job-name {
  font-size: 14px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.job-status { margin: 0; flex-shrink: 0; }
.cron-chip {
  display: inline-block; font-size: 10px; color: var(--accent-text);
  background: var(--surface-hover); border: 1px solid var(--border); border-radius: 4px;
  padding: 1px 5px; font-family: monospace; flex-shrink: 0;
}
.once-chip { font-size: 10px; color: var(--text-3); flex-shrink: 0; }
.job-content {
  font-size: 12px; color: var(--text-3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 1; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap;
}
.job-sub { display: flex; gap: 12px; margin-top: 4px; }
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
.job-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.pager { display: flex; justify-content: center; padding: 8px 0 2px; }

.approve-hint { font-size: 12px; color: var(--text-3); margin-left: 10px; }
.form-error { color: var(--err); font-size: 12px; padding: 4px 0; }
.form-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>
