<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/chat'
import type { TaskInfo } from '@/types'

const msg = useMessage()
const emit = defineEmits<{ (e: 'open-session', target: { sessionId: number; messageId?: number }): void }>()
const tasks = ref<TaskInfo[]>([])
const loading = ref(false)
const error = ref('')
const expandedId = ref<string | null>(null)
const detailLogs = ref<Record<string, string>>({})
const detailLoading = ref<Record<string, boolean>>({})

/** 当前是否还有运行/等待中的任务(决定轮询节奏) */
const hasActive = computed(() => tasks.value.some((t) => t.status === 'running' || t.status === 'pending'))

let timer: ReturnType<typeof setInterval> | null = null

async function refresh() {
  try {
    error.value = ''
    const list = await chatApi.listTasks()
    tasks.value = list
    // 若正在查看的任务还运行着,顺带刷新日志
    if (expandedId.value) {
      const t = list.find((x) => x.id === expandedId.value)
      if (t && t.status === 'running') await loadLog(expandedId.value)
    }
  } catch (e: any) {
    error.value = e?.message || '加载任务失败'
  } finally {
    loading.value = false
  }
}

function startPolling() {
  stopPolling()
  // 有空闲任务时 5s 轮询,否则 15s 兜底(面板打开期间也保持低频率刷新)
  timer = setInterval(refresh, hasActive.value ? 5000 : 15000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

async function toggle(id: string) {
  expandedId.value = expandedId.value === id ? null : id
  if (expandedId.value) {
    await loadLog(expandedId.value)
  }
}

async function loadLog(id: string) {
  if (detailLogs.value[id] !== undefined && tasks.value.find((t) => t.id === id)?.status !== 'running') return
  detailLoading.value[id] = true
  try {
    detailLogs.value[id] = await chatApi.taskLog(id, 12000)
  } catch (e: any) {
    detailLogs.value[id] = '(读取日志失败: ' + (e?.message || 'unknown') + ')'
  } finally {
    delete detailLoading.value[id]
  }
}

function formatTime(ms: number): string {
  if (!ms) return '-'
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function formatDuration(sec?: number): string {
  if (sec == null) return '-'
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

/** 会话展示文案:标题 + 推测标记;无归属时提示。 */
function sessionLabel(t: TaskInfo): string {
  const base = t.sessionTitle ? t.sessionTitle : t.sessionId ? `会话 #${t.sessionId}` : ''
  if (!base) return t.sessionConfirmed ? '会话已删除' : '未关联会话'
  return t.sessionConfirmed ? base : `${base}(推测)`
}

function openSession(t: TaskInfo) {
  if (t.sessionId) emit('open-session', { sessionId: t.sessionId, messageId: t.messageId })
}

function statusType(t: TaskInfo): 'default' | 'primary' | 'success' | 'warning' | 'error' {
  switch (t.status) {
    case 'running': return 'warning'
    case 'failed': return 'error'
    case 'delivered': return 'success'
    case 'done': return 'success'
    default: return 'default'
  }
}

async function onCleanup(t: TaskInfo) {
  try {
    await chatApi.cleanupTask(t.id)
    msg.success(`已清理任务「${t.id}」的运行文件`)
    if (expandedId.value === t.id) expandedId.value = null
    delete detailLogs.value[t.id]
    await refresh()
  } catch (e: any) {
    msg.error(e?.message || '清理失败')
  }
}

onMounted(() => {
  loading.value = true
  refresh()
  startPolling()
})
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="task-panel">
    <div class="panel-head">
      <span class="hint">{{ hasActive ? '运行中,自动刷新' : '已空闲' }} · {{ tasks.length }} 个任务</span>
      <n-button size="tiny" quaternary circle title="刷新" @click="refresh">↻</n-button>
    </div>

    <n-spin :show="loading" size="small" style="display: block; margin: 24px auto">
      <div v-if="error" class="task-error">{{ error }}</div>
      <div v-else-if="!tasks.length" class="task-empty">暂无后台任务</div>
      <div v-else class="task-list">
        <div class="task-grid task-head">
          <span class="c-status">状态</span>
          <span class="c-task">任务</span>
          <span class="c-session">来源会话</span>
          <span class="c-time">时间 / 时长</span>
          <span></span>
        </div>
        <div v-for="t in tasks" :key="t.id" class="task-item" :class="{ active: expandedId === t.id }">
          <div class="task-grid task-main" @click="toggle(t.id)">
            <span class="c-status">
              <n-tag size="tiny" :type="statusType(t)" :bordered="false" class="status-tag">
                <template v-if="t.status === 'running'" #icon><n-spin :size="10" class="mini-spin" /></template>
                {{ t.statusText }}
              </n-tag>
            </span>
            <span class="c-task task-id" :title="t.id">{{ t.id }}</span>
            <span class="c-session">
              <a v-if="t.sessionId" class="session-link" :title="sessionLabel(t)" @click.stop="openSession(t)">{{ sessionLabel(t) }}</a>
              <span v-else class="session-none">{{ sessionLabel(t) }}</span>
            </span>
            <span class="c-time">
              <span class="t-time" :title="t.delivered ? '已交付' : undefined">{{ formatTime(t.startedAt) }}</span>
              <span v-if="t.runningSeconds != null" class="t-dur">{{ formatDuration(t.runningSeconds) }}</span>
            </span>
            <span class="caret">{{ expandedId === t.id ? '▾' : '▸' }}</span>
          </div>
          <div v-if="expandedId === t.id" class="task-detail">
            <div v-if="t.doneContent" class="task-done">{{ t.doneContent }}</div>
            <div class="log-box">
              <div v-if="detailLoading[t.id]" class="log-loading">加载日志…</div>
              <pre v-else-if="detailLogs[t.id] !== undefined" class="log-text">{{ detailLogs[t.id] || '(日志为空)' }}</pre>
            </div>
            <div class="task-actions">
              <n-button size="tiny" quaternary @click="loadLog(t.id)">刷新日志</n-button>
              <n-button size="tiny" quaternary type="error" @click="onCleanup(t)">清理</n-button>
            </div>
          </div>
        </div>
      </div>
    </n-spin>
  </div>
</template>

<style scoped>
.task-panel { display: flex; flex-direction: column; gap: 8px; }
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 2px 0 8px; border-bottom: 1px solid var(--border-soft);
}
.panel-head .hint { font-size: 12px; color: var(--text-3); }
.task-error { color: var(--err); font-size: 12px; padding: 8px 0; }
.task-empty { color: var(--text-3); font-size: 12px; padding: 16px 0; text-align: center; }

.task-list { display: flex; flex-direction: column; gap: 4px; }
.task-grid {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr) minmax(0, 1.4fr) 84px 14px;
  gap: 8px; align-items: center;
}
.task-head {
  padding: 4px 10px; font-size: 11px; color: var(--text-3);
  border-bottom: 1px solid var(--border-soft);
}
.task-head .c-time { white-space: nowrap; }
.task-item {
  border: 1px solid var(--border); border-radius: var(--radius-sm); background: var(--surface); overflow: hidden;
}
.task-item.active { border-color: var(--accent); }
.task-main { padding: 7px 10px; cursor: pointer; }
.task-main:hover { background: var(--surface-hover); }
.c-status { display: flex; min-width: 0; }
.status-tag {
  max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.c-task { min-width: 0; }
.task-id {
  font-size: 12px; font-weight: 600; color: var(--warn); font-family: monospace;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.c-session { min-width: 0; }
.session-link {
  font-size: 12px; color: var(--accent-text); cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block;
}
.session-link:hover { color: var(--accent-text); text-decoration: underline; }
.session-none { font-size: 12px; color: var(--text-3); }
.c-time { min-width: 0; display: flex; flex-direction: column; line-height: 1.25; }
.t-time { font-size: 11px; color: var(--text-2); white-space: nowrap; }
.t-dur { font-size: 10px; color: var(--text-3); white-space: nowrap; }
.caret { color: var(--text-3); font-size: 11px; text-align: right; }
.mini-spin { margin-right: 4px; }

.task-detail { border-top: 1px solid var(--border-soft); padding: 8px 10px; }
.task-done { font-size: 12px; color: var(--text-2); margin-bottom: 6px; word-break: break-all; }
.log-box {
  background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-sm);
  max-height: 260px; overflow-y: auto;
}
.log-loading { color: var(--text-3); font-size: 12px; padding: 8px; }
.log-text {
  margin: 0; padding: 8px; font-size: 11px; line-height: 1.5; color: var(--ok);
  font-family: Consolas, Menlo, monospace; white-space: pre-wrap; word-break: break-all;
}
.task-actions { display: flex; justify-content: flex-end; gap: 4px; padding-top: 6px; }
</style>
