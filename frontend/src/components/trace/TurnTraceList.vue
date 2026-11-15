<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { chatApi, type LlmCallVo } from '@/api/chat'
import { useThemeStore } from '@/stores/theme'
import CodeEditor from '@/components/CodeEditor.vue'

/**
 * 某一轮对话的模型调用记录(放在该轮回答下方)。
 * 一轮里 agent 可能调用模型 N 次,这里按调用列成可折叠列表:
 * 行 = 一次调用,展开后是请求/响应原文(带行号与语法高亮的只读 JSON 编辑器)。
 */
const props = withDefaults(defineProps<{
  /** 这一轮 assistant 消息的外部 id */
  agentMessageId: string
  /** 本轮调用次数(来自 parts.llmCalls,用于折叠状态的计数) */
  count: number
  /** 受控展开状态(由回答下方的操作按钮控制);不传则组件内部维护 */
  show?: boolean
  /** 是否显示自带的折叠头(嵌在操作按钮下方时传 false) */
  header?: boolean
}>(), { show: undefined, header: true })

const emit = defineEmits<{ 'update:show': [v: boolean] }>()

const { t } = useI18n()
const msg = useMessage()
const theme = useThemeStore()
const isDark = computed(() => theme.mode === 'dark')

const innerOpen = ref(false)
const open = computed({
  get: () => props.show ?? innerOpen.value,
  set: (v: boolean) => {
    if (props.show === undefined) innerOpen.value = v
    emit('update:show', v)
  },
})
const calls = ref<LlmCallVo[]>([])
const loading = ref(false)
const expandedId = ref<number | null>(null)
const side = ref<'request' | 'response'>('request')
const payload = ref('')
const payloadLoading = ref(false)
const payloadCache = new Map<string, { text: string; omitted: number }>()
/** 请求报文是否显示完整内容(默认只看相对上一次调用的新增部分) */
const fullMode = ref(false)
/** 增量视图省略的公共前缀消息数(用于提示与切换按钮) */
const deltaCount = ref(0)

const totalInput = computed(() => calls.value.reduce((n, c) => n + (c.inputTokens || 0), 0))
const totalOutput = computed(() => calls.value.reduce((n, c) => n + (c.outputTokens || 0), 0))
const totalTools = computed(() => calls.value.reduce((n, c) => n + (c.toolCalls || 0), 0))
const maxLatency = computed(() => Math.max(1, ...calls.value.map((c) => c.latencyMs || 0)))

interface TimelineCall {
  call: LlmCallVo
  /** 调用开始/结束时间(毫秒时间戳;无时间数据时为 NaN) */
  start: number
  end: number
  /** 与上一次调用之间用于执行工具的空档 */
  gapBeforeMs: number | null
}

/** 时间线:调用 + 调用之间工具执行空档 */
const timeline = computed<TimelineCall[]>(() => {
  const out: TimelineCall[] = []
  let prevEnd: number | null = null
  for (const c of calls.value) {
    const end = Date.parse(c.createdAt)
    const start = Number.isFinite(end) ? end - (c.latencyMs || 0) : NaN
    const gap = prevEnd != null && Number.isFinite(start) && start - prevEnd > 500
      ? start - prevEnd
      : null
    out.push({ call: c, start, end, gapBeforeMs: gap })
    if (Number.isFinite(end)) prevEnd = end
  }
  return out
})

/** 本轮墙钟总耗时(首次调用开始 → 最后一次调用结束,含工具执行空档) */
const spanMs = computed(() => {
  const valid = timeline.value.filter((t) => Number.isFinite(t.start) && Number.isFinite(t.end))
  if (!valid.length) return 0
  const start = Math.min(...valid.map((t) => t.start))
  const end = Math.max(...valid.map((t) => t.end))
  return Math.max(0, end - start)
})

/** 单次调用耗时相对本轮最慢调用画条形图 */
function barPct(c: LlmCallVo): number {
  return Math.max(6, Math.round(((c.latencyMs || 0) / maxLatency.value) * 100))
}

/** 首 token 在总耗时里的占比(条形图内层深色段) */
function ttftPct(c: LlmCallVo): number {
  if (!c.ttftMs || !c.latencyMs) return 0
  return Math.min(100, Math.round((c.ttftMs / c.latencyMs) * 100))
}

/** 原文超出存储上限被截断时返回省略条数,否则 null */
const truncatedInfo = computed<number | null>(() => {
  if (!payload.value) return null
  try {
    const o = JSON.parse(payload.value)
    if (o && o.truncated) return Number(o.omittedMessages) || 0
  } catch {
    // 非 JSON(或已截断成非法 JSON)时不提示
  }
  return null
})

watch(
  () => props.agentMessageId,
  () => {
    calls.value = []
    expandedId.value = null
    payload.value = ''
    payloadCache.clear()
    fullMode.value = false
    deltaCount.value = 0
    if (open.value) void load()
  },
)

// 受控展开(由回答下方的操作按钮触发)时按需加载
watch(
  open,
  (v) => {
    if (v && !calls.value.length) void load()
  },
  { immediate: true },
)

function toggleOpen() {
  open.value = !open.value
}

async function load() {
  if (!props.agentMessageId) return
  loading.value = true
  try {
    calls.value = await chatApi.listLlmCalls(props.agentMessageId)
  } catch (e: any) {
    msg.error(e?.message || t('trace.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function toggleCall(c: LlmCallVo) {
  if (expandedId.value === c.id) {
    expandedId.value = null
    payload.value = ''
    return
  }
  expandedId.value = c.id
  side.value = c.hasRequest ? 'request' : 'response'
  fullMode.value = false
  deltaCount.value = 0
  await loadPayload()
}

async function loadPayload() {
  const cur = calls.value.find((c) => c.id === expandedId.value)
  if (!cur) return
  const useDelta = side.value === 'request' && !fullMode.value
  const key = `${cur.id}:${side.value}:${useDelta ? 'd' : 'f'}`
  const has = side.value === 'request' ? cur.hasRequest : cur.hasResponse
  if (!has) {
    payload.value = ''
    return
  }
  const cached = payloadCache.get(key)
  if (cached != null) {
    payload.value = cached.text
    if (useDelta) deltaCount.value = cached.omitted
    return
  }
  payloadLoading.value = true
  try {
    const r = await chatApi.llmCallPayload(cur.id, side.value, useDelta)
    payload.value = pretty(r.content)
    const omitted = useDelta ? r.omittedMessages || 0 : 0
    if (useDelta) deltaCount.value = omitted
    payloadCache.set(key, { text: payload.value, omitted })
  } catch (e: any) {
    payload.value = ''
    msg.error(e?.message || t('trace.loadFailed'))
  } finally {
    payloadLoading.value = false
  }
}

function switchSide(s: 'request' | 'response') {
  if (s === side.value) return
  side.value = s
  void loadPayload()
}

/** 在"只看新增"与"完整报文"之间切换 */
function toggleFull() {
  fullMode.value = !fullMode.value
  void loadPayload()
}

async function copyPayload() {
  if (!payload.value) return
  try {
    await navigator.clipboard.writeText(payload.value)
    msg.success(t('trace.copied'))
  } catch (e: any) {
    msg.error(e?.message || t('trace.copyFailed'))
  }
}

function pretty(text: string): string {
  if (!text) return ''
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

function fmtMs(ms: number | null): string {
  if (ms == null) return '-'
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms + 'ms'
}

function fmtNum(n: number): string {
  return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)
}

/** 时间线左侧时刻(HH:mm:ss) */
function fmtClock(ms: number): string {
  if (!Number.isFinite(ms)) return ''
  const d = new Date(ms)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
</script>

<template>
  <section v-if="count > 0 && (header || open)" class="trace-sec" :class="{ open, embedded: !header }">
    <button v-if="header" type="button" class="tt-head" @click="toggleOpen">
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><circle cx="3.5" cy="6" r="1"/><circle cx="3.5" cy="12" r="1"/><circle cx="3.5" cy="18" r="1"/></svg>
      <span class="tt-head-title">{{ t('trace.sectionTitle') }}</span>
      <span class="tt-head-count">{{ t('trace.callsShort', { n: count }) }}</span>
      <span v-if="open && calls.length" class="tt-head-sum" :title="t('trace.sumHint')">
        {{ t('trace.inputShort', { n: fmtNum(totalInput) }) }}
      </span>
      <svg class="tt-chev" :class="{ open }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
    </button>

    <div v-if="open" class="tt-body">
      <div v-if="loading" class="tt-empty">{{ t('trace.loading') }}</div>
      <div v-else-if="!calls.length" class="tt-empty">{{ t('trace.empty') }}</div>
      <div v-else class="tl-list">
        <!-- 本轮汇总:调用次数 / 墙钟耗时 / token / 工具次数 -->
        <div class="tl-summary">
          <span class="tl-sum-item strong">{{ t('trace.callsShort', { n: calls.length }) }}</span>
          <span v-if="spanMs > 0" class="tl-sum-item">{{ t('trace.totalTime') }} {{ fmtMs(spanMs) }}</span>
          <span class="tl-sum-item">{{ t('trace.inputShort', { n: fmtNum(totalInput) }) }}</span>
          <span class="tl-sum-item">{{ t('trace.outputShort', { n: fmtNum(totalOutput) }) }}</span>
          <span v-if="totalTools" class="tl-sum-item">{{ t('trace.toolCalls', { n: totalTools }) }}</span>
        </div>

        <template v-for="item in timeline" :key="item.call.id">
          <!-- 两次模型调用之间的工具执行空档 -->
          <div v-if="item.gapBeforeMs" class="tl-gap">
            <div class="tl-gap-time"></div>
            <div class="tl-gap-rail"><span class="tl-gap-dot"></span></div>
            <div class="tl-gap-label">{{ t('trace.toolGap', { time: fmtMs(item.gapBeforeMs) }) }}</div>
          </div>

          <article
            class="tl-item"
            :class="{ open: expandedId === item.call.id, err: item.call.status === 'error' }"
          >
            <time class="tl-time">{{ fmtClock(item.start) }}</time>
            <div class="tl-rail"><span class="tl-dot"></span></div>
            <div class="tl-content">
              <button type="button" class="tl-card" @click="toggleCall(item.call)">
                <div class="tl-card-head">
                  <span class="tl-idx">#{{ item.call.callIndex }}</span>
                  <span class="tl-model">{{ item.call.model || 'model' }}</span>
                  <span class="tl-finish" :class="item.call.status === 'error' ? 'err' : 'ok'">
                    {{ item.call.status === 'error' ? t('trace.failed') : (item.call.finishReason || 'ok') }}
                  </span>
                  <span class="tl-card-right">
                    <span class="tl-latency">{{ fmtMs(item.call.latencyMs) }}</span>
                    <svg class="tt-chev" :class="{ open: expandedId === item.call.id }" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
                  </span>
                </div>
                <div class="tl-meta">
                  <span>{{ fmtNum(item.call.inputTokens) }} in</span>
                  <span>{{ fmtNum(item.call.outputTokens) }} out</span>
                  <span v-if="item.call.ttftMs != null">{{ t('trace.ttft') }} {{ fmtMs(item.call.ttftMs) }}</span>
                  <span>{{ t('trace.messages', { n: item.call.messageCount }) }}</span>
                  <span v-if="item.call.toolCalls">{{ t('trace.toolCalls', { n: item.call.toolCalls }) }}</span>
                </div>
                <div class="tl-duration" :title="t('trace.latency') + ' ' + fmtMs(item.call.latencyMs)">
                  <span class="tl-duration-fill" :style="{ width: barPct(item.call) + '%' }">
                    <span
                      v-if="item.call.ttftMs != null"
                      class="tl-duration-ttft"
                      :style="{ width: ttftPct(item.call) + '%' }"
                    ></span>
                  </span>
                </div>
              </button>

              <div v-if="expandedId === item.call.id" class="tt-detail">
                <div class="tt-bar">
                  <span class="tt-meta">
                    <span>{{ t('trace.latency') }} {{ fmtMs(item.call.latencyMs) }}</span>
                    <span v-if="item.call.ttftMs != null">{{ t('trace.ttft') }} {{ fmtMs(item.call.ttftMs) }}</span>
                    <span>{{ t('trace.messages', { n: item.call.messageCount }) }}</span>
                    <span v-if="item.call.toolCalls">{{ t('trace.toolCalls', { n: item.call.toolCalls }) }}</span>
                    <span v-if="item.call.finishReason">finish: {{ item.call.finishReason }}</span>
                  </span>
                  <span class="tt-tabs">
                    <button
                      type="button"
                      class="tt-tab"
                      :class="{ active: side === 'request' }"
                      :disabled="!item.call.hasRequest"
                      @click="switchSide('request')"
                    >
                      {{ t('trace.request') }}
                    </button>
                    <button
                      type="button"
                      class="tt-tab"
                      :class="{ active: side === 'response' }"
                      :disabled="!item.call.hasResponse"
                      @click="switchSide('response')"
                    >
                      {{ t('trace.response') }}
                    </button>
                    <button type="button" class="tt-copy" :disabled="!payload" @click="copyPayload">
                      {{ t('trace.copy') }}
                    </button>
                  </span>
                </div>

                <div v-if="item.call.status === 'error'" class="tt-error">{{ item.call.error || t('trace.failed') }}</div>
                <div v-if="truncatedInfo !== null" class="tt-warn">
                  {{ t('trace.truncated', { n: truncatedInfo }) }}
                </div>
                <div v-if="side === 'request' && deltaCount > 0" class="tt-delta">
                  <span>{{ fullMode ? t('trace.fullPayload') : t('trace.deltaOmitted', { n: deltaCount }) }}</span>
                  <button type="button" class="tt-delta-btn" @click="toggleFull">
                    {{ fullMode ? t('trace.viewDelta') : t('trace.viewFull') }}
                  </button>
                </div>

                <div v-if="payloadLoading" class="tt-empty inline">{{ t('trace.loading') }}</div>
                <div v-else-if="payload" class="tt-code">
                  <CodeEditor :model-value="payload" language="json" read-only :is-dark="isDark" />
                </div>
                <div v-else class="tt-empty inline">{{ t('trace.noPayload') }}</div>
              </div>
            </div>
          </article>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.trace-sec {
  margin-top: 10px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg-elev);
}
/* 由回答下方的操作按钮控制时:去掉自带头部,内容贴着操作栏 */
.trace-sec.embedded { margin-top: 8px; }
.tt-head {
  width: 100%;
  display: flex; align-items: center; gap: 7px;
  padding: 7px 10px;
  border: 0; background: transparent; color: var(--text-3);
  font-family: inherit; font-size: 12px; cursor: pointer; text-align: left;
}
.tt-head:hover { color: var(--text-2); background: var(--surface-hover); }
.tt-head-title { color: var(--text-2); font-weight: 500; }
.tt-head-count {
  font-size: 10.5px; padding: 1px 6px; border-radius: 5px;
  background: var(--fill-0, rgba(127, 127, 127, 0.08));
  font-family: ui-monospace, monospace; color: var(--text-3);
}
.tt-head-sum {
  font-size: 10.5px; color: var(--text-4);
  font-family: ui-monospace, monospace;
}
.tt-head > .tt-chev { margin-left: auto; color: var(--text-4); transition: transform .15s ease; }
.tt-chev.open { transform: rotate(180deg); }

.tt-body { border-top: 1px solid var(--border-soft); }
.trace-sec.embedded .tt-body { border-top: 0; }

/* ---------- 调用时间线 ---------- */
.tl-list { position: relative; padding: 2px 0 4px; }
/* 竖向时间轴:位于时刻列与内容列之间的 rail 中心 */
.tl-list::before {
  content: '';
  position: absolute;
  left: 84px;
  top: 34px;
  bottom: 10px;
  width: 1px;
  background: var(--border-soft);
}
.tl-summary {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  padding: 1px 2px 10px;
}
.tl-sum-item {
  font-size: 10.5px; color: var(--text-4); padding: 2px 8px;
  border-radius: 5px; background: var(--fill-0, rgba(127, 127, 127, 0.08));
  font-family: ui-monospace, monospace;
}
.tl-sum-item.strong { color: var(--text-2); }

.tl-item {
  display: grid;
  grid-template-columns: 66px 16px minmax(0, 1fr);
  column-gap: 10px;
  padding-bottom: 6px;
}
.tl-time {
  font-family: ui-monospace, monospace; font-size: 10.5px;
  color: var(--text-4); text-align: right; padding-top: 12px;
}
.tl-rail { display: flex; justify-content: center; }
.tl-dot {
  width: 9px; height: 9px; margin-top: 12px; border-radius: 50%;
  background: var(--bg-elev); border: 1.5px solid var(--text-4);
  z-index: 1; box-sizing: border-box;
}
.tl-item.open .tl-dot { background: var(--accent); border-color: var(--accent); }
.tl-item.err .tl-dot { background: var(--err); border-color: var(--err); }
.tl-content { min-width: 0; }

.tl-card {
  width: 100%; text-align: left; cursor: pointer;
  padding: 7px 10px 8px;
  border: 1px solid var(--border-soft); border-radius: 8px;
  background: var(--bg-elev); color: var(--text-2); font-family: inherit;
  transition: border-color .15s ease, background .15s ease;
}
.tl-card:hover { border-color: color-mix(in srgb, var(--accent) 30%, var(--border-soft)); }
.tl-item.open .tl-card {
  background: color-mix(in srgb, var(--accent) 7%, var(--bg-elev));
  border-color: color-mix(in srgb, var(--accent) 35%, var(--border-soft));
}
.tl-card-head { display: flex; align-items: center; gap: 7px; }
.tl-idx {
  font-family: ui-monospace, monospace; font-size: 10.5px; color: var(--text-4);
}
.tl-model {
  font-size: 12px; color: var(--text); font-weight: 500;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.tl-finish { font-size: 10px; font-family: ui-monospace, monospace; }
.tl-finish.ok { color: var(--ok); }
.tl-finish.err { color: var(--err); }
.tl-card-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.tl-latency {
  font-family: ui-monospace, monospace; font-size: 10.5px; color: var(--text-3);
}
.tl-meta {
  display: flex; align-items: center; gap: 9px; flex-wrap: wrap;
  margin-top: 3px;
  font-family: ui-monospace, monospace; font-size: 10px; color: var(--text-4);
}
/* 耗时条:长度=相对本轮最慢调用;深色段=首 token 之前的等待 */
.tl-duration {
  margin-top: 6px; height: 3px; border-radius: 2px; overflow: hidden;
  background: var(--fill-0, rgba(127, 127, 127, 0.12));
}
.tl-duration-fill {
  display: block; height: 100%; border-radius: 2px; position: relative;
  background: color-mix(in srgb, var(--accent) 45%, transparent);
  transition: width .2s ease;
}
.tl-duration-ttft {
  position: absolute; left: 0; top: 0; bottom: 0;
  background: var(--accent); border-radius: 2px;
}

/* 工具执行空档 */
.tl-gap {
  display: grid;
  grid-template-columns: 66px 16px minmax(0, 1fr);
  column-gap: 10px; align-items: center;
  padding: 1px 0 5px;
}
.tl-gap-rail { display: flex; justify-content: center; }
.tl-gap-dot {
  width: 4px; height: 4px; border-radius: 50%;
  background: var(--text-4); opacity: .65;
}
.tl-gap-label {
  font-family: ui-monospace, monospace; font-size: 10px;
  color: var(--text-4); padding-left: 1px;
}

/* 展开:工具栏 + 只读 JSON 编辑器 */
.tt-detail {
  margin-top: 6px;
  border: 1px solid var(--border-soft);
  border-radius: 8px;
  overflow: hidden;
  background: var(--bg-0, rgba(0, 0, 0, .12));
}
.tt-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 6px 10px; border-bottom: 1px solid var(--border-soft);
}
.tt-meta { display: flex; gap: 10px; flex-wrap: wrap; min-width: 0; }
.tt-meta > span {
  font-family: ui-monospace, monospace; font-size: 10.5px; color: var(--text-4);
  white-space: nowrap;
}
.tt-tabs { margin-left: auto; display: flex; align-items: center; gap: 6px; }
.tt-tab, .tt-copy {
  padding: 2px 9px; border-radius: 6px; border: 1px solid var(--border-soft);
  background: transparent; color: var(--text-3); font-size: 11.5px; cursor: pointer;
}
.tt-tab:hover, .tt-copy:hover { color: var(--accent-text); border-color: var(--accent); }
.tt-tab.active { color: var(--accent-text); border-color: var(--accent); background: var(--accent-soft); }
.tt-tab:disabled, .tt-copy:disabled { opacity: .4; cursor: not-allowed; }
.tt-code { height: 380px; }
.tt-code :deep(.cm-editor) { font-size: 12.5px; }
.tt-code :deep(.cm-scroller) { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; line-height: 1.6; }

.tt-error {
  padding: 6px 10px; font-size: 11.5px; color: var(--err);
  background: color-mix(in srgb, var(--err) 10%, transparent);
  font-family: ui-monospace, monospace; white-space: pre-wrap; word-break: break-word;
}
.tt-warn {
  padding: 6px 10px; font-size: 11.5px; color: var(--warn);
  background: color-mix(in srgb, var(--warn) 10%, transparent);
  border-bottom: 1px solid var(--border-soft);
}
/* 请求报文:默认省略与上一次调用相同的缓存前缀 */
.tt-delta {
  display: flex; align-items: center; gap: 8px;
  padding: 5px 10px; font-size: 11px; color: var(--text-4);
  background: color-mix(in srgb, var(--accent) 5%, transparent);
  border-bottom: 1px solid var(--border-soft);
}
.tt-delta-btn {
  margin-left: auto; padding: 1px 8px; border-radius: 5px;
  border: 1px solid var(--border-soft); background: transparent;
  color: var(--text-3); font-size: 11px; cursor: pointer;
}
.tt-delta-btn:hover { color: var(--accent-text); border-color: var(--accent); }
.tt-empty {
  padding: 34px 0; text-align: center; color: var(--text-3); font-size: 12.5px;
}
.tt-empty.inline { padding: 22px 0; }

@media (max-width: 760px) {
  .tl-list::before { left: 14px; }
  .tl-item, .tl-gap { grid-template-columns: 0 16px minmax(0, 1fr); column-gap: 6px; }
  .tl-time, .tl-gap-time { display: none; }
  .tt-code { height: 300px; }
}
</style>
