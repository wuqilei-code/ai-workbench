<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useChatStore } from '@/stores/chat'
import { useFavoritesStore } from '@/stores/favorites'
import { renderMarkdown } from '@/utils/markdown'
import { isFileProducingTool, collectDeliverables, stripDeliverableMarkers } from '@/utils/turns'
import { fmtMessageTime } from '@/utils/time'
import { fileApi } from '@/api/files'
import JSZip from 'jszip'
import type { Step, Turn, ToolCall } from '@/types'
import ChatMessage from './ChatMessage.vue'
import DeliverableBar from './DeliverableBar.vue'
import FilePreview from './preview/FilePreview.vue'
import ToolCallItem from './ToolCallItem.vue'

const props = defineProps<{
  turn: Turn
  sessionId: number | null
}>()

const chat = useChatStore()
const msg = useMessage()
const fav = useFavoritesStore()

// ---------- 流式 / 活跃判定 ----------

/** 当前正在接收 token 的消息 id(可能为 null,如步骤间的工具执行) */
const activeMessageId = computed(() => chat.streamingMessageId)

/** 本 turn 是否为最后一个 turn(只有最后一个 turn 可能正在生成) */
const isLastTurn = computed(() => {
  const list = chat.turns
  return list.length > 0 && list[list.length - 1].id === props.turn.id
})

/** 本 turn 是否仍在生成中(isStreaming 稳定,仅 session.idle 结束) */
const turnInProgress = computed(() => chat.isStreaming && isLastTurn.value)

/** 本轮生成已运行秒数:秒数在走 = AI 仍在工作,用于安抚"以为断线/暂停"的疑虑 */
const runningSeconds = computed(() => chat.streamingElapsedSeconds)

/** 友好提示:有工具在跑就展示(即使已有消息壳,不再依赖 !activeMessageId)。
 *  - 内容生成/写文件类工具 → "正在生成/写入内容…"
 *  - 其它工具 → "正在执行 xxx…"
 *  - 无工具无文本(纯思考)→ "AI 正在处理中…" */
const showWorkingHint = computed(() =>
  turnInProgress.value &&
  (chat.runningActivity?.kind === 'tool' || !activeMessageId.value),
)

const pendingHint = computed(() => {
  const act = chat.runningActivity
  if (act?.kind === 'tool') {
    if (isFileProducingTool({ name: act.tool } as ToolCall)) {
      return act.arg ? `正在生成/写入内容:${act.arg},请稍候…` : '正在生成/写入内容,请稍候…'
    }
    return act.arg ? `正在执行 ${act.tool}(${act.arg}),请稍候…` : `正在执行 ${act.tool},请稍候…`
  }
  return 'AI 正在处理中,请稍候…'
})

/** 某个 step 是否正在流式接收文本(仅时间线最后一步算活跃) */
function isStepActive(step: Step | null): boolean {
  if (!step || !step.message.agentMessageId) return false
  if (!chat.isStreaming || step.message.agentMessageId !== activeMessageId.value) return false
  const steps = props.turn.steps
  const last = steps.length ? steps[steps.length - 1] : props.turn.finalAnswer
  return last === step || props.turn.finalAnswer === step
}

/** 取 step 的展示文本:统一用定稿/实时 narration(时间线已按序累积) */
function displayContent(step: Step | null): string {
  if (!step) return ''
  return step.narration || ''
}

// ---------- 推理段包裹卡片 ----------

/** 推理段展开状态:流式中的段自动展开、结束后自动折叠;用户点击后记入手动覆盖 */
const reasonOpen = ref<Record<string, boolean>>({})
const reasonKey = (i: number) => `${props.turn.id}-r${i}`
function isReasonOpen(step: Step, i: number): boolean {
  const manual = reasonOpen.value[reasonKey(i)]
  if (manual !== undefined) return manual
  return isStepActive(step)
}
function toggleReason(i: number) {
  const k = reasonKey(i)
  reasonOpen.value[k] = !isReasonOpen(props.turn.steps[i], i)
}

/** 已挂载的推理正文容器(用于流式吸底滚动) */
const reasonBodies = new Map<string, HTMLElement>()
function setReasonBody(key: string, el: unknown) {
  if (el) reasonBodies.set(key, el as HTMLElement)
  else reasonBodies.delete(key)
}
/** 活跃推理段内容增长时,把展开的推理框滚到底部 */
watch(
  () => props.turn.steps.find((s) => s.kind === 'reason' && isStepActive(s))?.narration.length ?? 0,
  async () => {
    await nextTick()
    for (const [key, el] of reasonBodies) {
      const idx = Number(key.slice(key.lastIndexOf('-r') + 2))
      const st = props.turn.steps[idx]
      if (!st || !isReasonOpen(st, idx)) continue
      el.scrollTop = el.scrollHeight
    }
  },
)

// ---------- 简单模式(单条 assistant 无 tool)----------

const simpleMode = computed(
  () =>
    props.turn.steps.length === 0 &&
    !!props.turn.finalAnswer &&
    props.turn.finalAnswer.toolCalls.length === 0,
)

const simpleMessage = computed(() => {
  const fa = props.turn.finalAnswer
  if (!fa) return null
  // 剥离 [结果文件] 标记行,避免 simpleMode 正文里显示标记(标记只在 DeliverableBar 展示)
  const raw = isStepActive(fa) ? (chat.streamingText || '') : (fa.message.content || '')
  return { ...fa.message, content: stripDeliverableMarkers(raw) }
})

async function copyFinalAnswer() {
  const fa = props.turn.finalAnswer
  if (!fa) return
  const text = (fa.message.content || '').trim()
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    msg.success('已复制')
  } catch {
    msg.error('复制失败')
  }
}

/** 导出为 .md 文件:文件名取会话标题或时间戳 */
function exportFinalAnswerAsMarkdown() {
  const fa = props.turn.finalAnswer
  if (!fa) return
  const text = stripDeliverableMarkers(fa.message.content || '').trim()
  if (!text) return
  downloadMarkdown(text, props.sessionId ?? undefined)
}

// ---------- 收藏最终答案 ----------

const faMessageId = computed(() => props.turn.finalAnswer?.message.id ?? 0)

/** 最终答案消息时间(多步气泡顶部) */
const faTime = computed(() => props.turn.finalAnswer?.message.createdAt || '')

const faStarred = computed(() =>
  props.sessionId != null && faMessageId.value > 0
    ? fav.isFavorite(props.sessionId, faMessageId.value)
    : false,
)

function toggleFaStar() {
  if (props.sessionId == null || faMessageId.value <= 0) return
  const added = fav.toggle({
    sessionId: props.sessionId,
    messageId: faMessageId.value,
    role: 'assistant',
    content: stripDeliverableMarkers(props.turn.finalAnswer?.message.content || '').slice(0, 500),
    turnId: props.turn.id,
    sessionTitle: '',
  })
  msg.success(added ? '已收藏' : '已取消收藏')
}

// ---------- 建议追问(回答定稿后展示可点击的继续提问) ----------/** 基于用户原始问题 + 回答内容,启发式生成 2-3 个继续提问的示例。 */
const followUpQuestions = computed<string[]>(() => {
  if (turnInProgress.value) return []
  const fa = props.turn.finalAnswer
  if (!fa || !(fa.message.content || '').trim()) return []
  const userQ = props.turn.userMessage?.content || ''
  const q = userQ.trim()
  const asksWhy = /为什么|为何|原因/.test(q)
  const asksHow = /怎么|如何|怎么做|步骤/.test(q)
  const asksWhat = /是什么|区别|对比|哪个/.test(q)
  const out: string[] = []
  if (asksWhy) out.push('那背后的原理是什么,能用大白话解释吗?')
  else if (asksHow) out.push('可以给一个完整的操作步骤/示例吗?')
  if (asksWhat) out.push('那它们对比起来有哪些关键区别?')
  out.push('能不能举个具体的例子?')
  out.push('帮我用更简单的说法总结一下要点')
  return out.slice(0, 3)
})

function askFollowUp(text: string) {
  chat.fillInput(text)
}

// ---------- 重新生成回答 ----------

/** 重新生成:用本 turn 的用户问题再次触发 AI 回答(追加新一轮)。 */
async function regenerateAnswer() {
  const userText = props.turn.userMessage?.content
  if (!userText || !chat.currentSessionId) {
    msg.warning('缺少可重新生成的问题')
    return
  }
  if (chat.isStreaming) {
    msg.info('AI 正在处理中,请稍后再试')
    return
  }
  try {
    await chat.sendMessage(userText)
  } catch (e: any) {
    msg.error(e?.message || '重新生成失败')
  }
}

const canRegenerate = computed(
  () =>
    !!props.turn.userMessage &&
    !!props.turn.finalAnswer &&
    !turnInProgress.value &&
    chat.currentSessionId != null,
)

/** 本 turn 的结果文件清单(仅 turn 定稿后展示)。
 *  流式期间(turnInProgress)返回空,避免半截标记 `[结果` 闪烁。
 *  来源:[结果文件] 标记行 + 已完成的写文件类工具调用(双重兜底,见 collectDeliverables)。 */
const deliverables = computed(() =>
  turnInProgress.value ? [] : collectDeliverables(props.turn),
)

// ---------- HITL 人工确认(危险操作) ----------

/** 本 turn 内待用户允许/拒绝的权限请求(挂在其 assistant 消息的 parts.pendingPermission 上) */
const turnPending = computed(() => {
  const steps: Step[] = [...props.turn.steps]
  if (props.turn.finalAnswer) steps.push(props.turn.finalAnswer)
  for (const s of steps) {
    const pp = s.message.parts?.pendingPermission
    if (pp && !pp._replied) return pp
  }
  return null
})

const permSubmitting = ref(false)
async function onPerm(decision: 'approve' | 'reject') {
  const pp = turnPending.value
  if (!pp || permSubmitting.value) return
  if (!props.sessionId) {
    msg.error('会话未就绪,请刷新后重试')
    return
  }
  permSubmitting.value = true
  try {
    const ok = await chat.replyPermission(pp.requestId, decision)
    if (!ok) msg.warning('确认已失效或生成已中断,可能需要重新发送')
    else if (pp) pp._replied = true
  } catch (e: any) {
    msg.error(e?.response?.data?.message || e?.message || '提交确认失败,请重试')
  } finally {
    permSubmitting.value = false
  }
}

/** 权限请求的展示用参数摘要(截断,单行) */
const permArgsText = computed(() => {
  const args = turnPending.value?.tool?.args
  if (args == null) return ''
  try {
    return JSON.stringify(args).slice(0, 240)
  } catch {
    return String(args).slice(0, 240)
  }
})

// ---------- 折叠控制(受控 expanded-names)----------

const expandedNames = computed<string[]>(() => {
  const ov = chat.manualCollapseOverride[props.turn.id]
  if (ov !== undefined) return ov ? ['thinking'] : []
  // 流式期间展开(推理/步骤按序滚动),完成后折叠;用户可点头部手动展开/收起
  if (turnInProgress.value) return ['thinking']
  return []
})

function handleExpand(names: string[] | null) {
  chat.setCollapse(props.turn.id, (names || []).includes('thinking'))
}

// ---------- 工具 chip 状态 ----------

/** 下载工具产出的文件(chip 上的 📥 按钮) */
const downloadingPath = ref<string | null>(null)
async function downloadFile(path: string) {
  if (downloadingPath.value !== null) return
  downloadingPath.value = path
  try {
    await fileApi.download(path)
  } catch (e: any) {
    msg.error(e?.message || '下载失败')
  } finally {
    downloadingPath.value = null
  }
}

// ---------- 文件预览 ----------
const previewPath = ref<string | null>(null)
function previewFile(path: string) {
  previewPath.value = path
}
function closePreview() {
  previewPath.value = null
}

/** 一键下载本 turn 全部结果文件:打包成 zip 一次下载,绕开浏览器多文件下载拦截。
 *  复用 fileApi.fetchBlob 拿每个文件 blob → JSZip 内存打包 → 单次 a.click() 触发下载。 */
const downloadingAll = ref(false)
async function downloadAllFiles() {
  if (downloadingAll.value) return
  const items = deliverables.value
  if (!items.length) return
  downloadingAll.value = true
  try {
    const zip = new JSZip()
    let failed = 0
    for (const item of items) {
      try {
        const blob = await fileApi.fetchBlob(item.path)
        // 用相对路径作为 zip 内路径,保留子目录结构,避免同名冲突;去掉前导 / \
        const zipPath = item.path.replace(/^[/\\]+/, '')
        zip.file(zipPath, blob)
      } catch (e: any) {
        failed++
        msg.error(`${item.name} 获取失败: ${e?.message || ''}`)
      }
    }
    if (failed === items.length) {
      msg.error('全部文件获取失败,无法打包')
      return
    }
    const zipBlob = await zip.generateAsync({ type: 'blob' })
    const url = URL.createObjectURL(zipBlob)
    const a = document.createElement('a')
    a.href = url
    const ts = new Date().toISOString().slice(0, 16).replace(/[-T:]/g, '')
    a.download = `结果文件_${ts}.zip`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
    if (failed === 0) {
      msg.success(`已打包下载 ${items.length} 个文件`)
    } else {
      msg.warning(`已打包 ${items.length - failed}/${items.length} 个文件,${failed} 个失败`)
    }
  } catch (e: any) {
    msg.error(`打包失败: ${e?.message || ''}`)
  } finally {
    downloadingAll.value = false
  }
}

/** 步数 = 全部工具调用次数 + 有解说的思考段数(deepagents 一轮一条消息挂全部工具,不能按消息数算) */
const thinkingStepCount = computed(() => {
  let n = 0
  for (const s of props.turn.steps) {
    n += s.toolCalls?.length ?? 0
    if ((s.narration || '').trim()) n += 1
  }
  const fa = props.turn.finalAnswer
  if (fa) n += fa.toolCalls?.length ?? 0
  return Math.max(n, 1)
})

const thinkingHeader = computed(() =>
  turnInProgress.value
    ? `思考中… 已执行 ${thinkingStepCount.value} 步`
    : `已折叠 ${thinkingStepCount.value} 步操作`,
)

function stepHtml(step: Step): string {
  return renderMarkdown(displayContent(step))
}

// (deepagents 无提问/权限环节:原 pending question/permission 收集与提交逻辑已移除)
</script>

<template>
  <!-- 简单模式:复用普通气泡 -->
  <template v-if="simpleMode && simpleMessage">
    <ChatMessage
      :message="simpleMessage"
      :streaming="isStepActive(turn.finalAnswer)"
      :session-id="props.sessionId"
      :turn-id="props.turn.id"
    />
    <DeliverableBar
      v-if="deliverables.length"
      :items="deliverables"
      :busy="downloadingAll"
      :downloading-path="downloadingPath"
      @preview="previewFile"
      @download="downloadFile"
      @downloadAll="downloadAllFiles"
    />
  </template>
  <!-- 多步模式:单气泡 = 可折叠思考区 + 最终答案 + pending UI -->
  <div v-else class="msg assistant">
    <div class="avatar">
      <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M12 2.5l1.9 5.9 6.1 1.9-5 4.4 1.5 6.3L12 17.5l-4.5 3.5 1.5-6.3-5-4.4 6.1-1.9L12 2.5z"/></svg>
    </div>
    <div class="bubble">
      <div class="msg-head">
        <span class="author">AI</span>
        <span v-if="faTime" class="msg-time">{{ fmtMessageTime(faTime) }}</span>
      </div>
      <!-- 思考过程(可折叠) -->
      <NCollapse
        v-if="turn.steps.length > 0 || (turn.finalAnswer && turn.finalAnswer.toolCalls.length)"
        :expanded-names="expandedNames"
        :display-directive="'show'"
        @update:expanded-names="handleExpand"
      >
        <NCollapseItem name="thinking">
          <template #header>
            <span class="think-header" :class="{ active: turnInProgress }">
              <span v-if="turnInProgress" class="think-spinner"><span class="think-ring" /></span>
              <span class="think-label">{{ thinkingHeader }}</span>
            </span>
          </template>
          <div v-for="(step, i) in turn.steps" :key="step.message.id ?? i" class="step">
            <!-- 推理段:独立包裹卡片,流式时限高滚动吸底,结束自动折叠,可点开回看 -->
            <div v-if="step.kind === 'reason' && displayContent(step)" class="reason-box">
              <div class="reason-head" @click.stop="toggleReason(i)">
                <span class="rc-tag">推理</span>
                <svg
                  class="rc-chev"
                  :class="{ open: isReasonOpen(step, i) }"
                  viewBox="0 0 24 24"
                  width="12"
                  height="12"
                ><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" /></svg>
              </div>
              <div
                v-show="isReasonOpen(step, i)"
                :ref="(el) => setReasonBody(reasonKey(i), el)"
                class="reason-body"
              >{{ step.narration }}<span v-if="isStepActive(step)" class="cursor">▋</span></div>
            </div>
            <div
              v-else-if="displayContent(step)"
              class="step-narration"
              v-html="stepHtml(step)"
            />
            <span v-if="isStepActive(step) && step.kind !== 'reason'" class="cursor">▋</span>
            <div v-if="step.toolCalls.length" class="tool-calls">
              <ToolCallItem
                v-for="tc in step.toolCalls"
                :key="tc.id || tc.name"
                :tc="tc"
                @download="downloadFile"
              />
            </div>
          </div>
          <div
            v-if="turn.finalAnswer && turn.finalAnswer.toolCalls.length"
            class="step"
          >
            <div class="tool-calls" v-if="turn.finalAnswer.toolCalls.length">
              <ToolCallItem
                v-for="tc in turn.finalAnswer.toolCalls"
                :key="tc.id || tc.name"
                :tc="tc"
                @download="downloadFile"
              />
            </div>
          </div>
        </NCollapseItem>
      </NCollapse>

      <!-- 最终答案 -->
      <div v-if="turn.finalAnswer" class="content">
        <div class="fa-tools">
          <button
            class="copy-btn star-btn"
            :class="{ on: faStarred }"
            :title="faStarred ? '取消收藏' : '收藏该回答'"
            @click="toggleFaStar"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" :fill="faStarred ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
          </button>
          <button
            class="copy-btn"
            title="复制回答"
            @click="copyFinalAnswer"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
          <button
            class="copy-btn export-btn"
            title="导出为 Markdown 文件"
            @click="exportFinalAnswerAsMarkdown"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
          <button
            v-if="canRegenerate"
            class="copy-btn regen-btn"
            title="重新生成回答"
            @click="regenerateAnswer"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
          </button>
        </div>
        <span
          v-if="displayContent(turn.finalAnswer)"
          v-html="renderMarkdown(stripDeliverableMarkers(displayContent(turn.finalAnswer)))"
        />
        <span v-if="isStepActive(turn.finalAnswer)" class="cursor">▋</span>
      </div>
      <div v-if="followUpQuestions.length" class="followups">
        <span class="fu-label">继续问:</span>
        <span
          v-for="(fu, i) in followUpQuestions"
          :key="i"
          class="fu-chip"
          @click="askFollowUp(fu)"
        >{{ fu }}</span>
      </div>
      <div v-else-if="showWorkingHint" class="pending">
        <n-spin size="small" />
        <span v-if="chat.streamingStuckSeconds > 60">仍未收到响应,可点「停止」后重试</span>
        <template v-else>
          <span>{{ pendingHint }}</span>
          <span v-if="runningSeconds > 0" class="pending-elapsed">已运行 {{ runningSeconds }}s</span>
        </template>
      </div>

      <!-- 人工确认:危险操作需用户允许/拒绝,生成在此暂停 -->
      <div v-if="turnPending" class="perm-block">
        <div class="perm-head">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 22v-5"/><path d="M9 8V2"/><path d="M15 8V2"/><path d="M18 8v5a4 4 0 0 1-4 4h-4a4 4 0 0 1-4-4V8Z"/></svg>
          <span class="perm-title">需要您的允许</span>
        </div>
        <div class="perm-body">
          <div class="perm-tool">
            <span class="perm-tool-name">{{ turnPending.tool?.name || 'tool' }}</span>
            <span v-if="turnPending.tool?.description" class="perm-desc">{{ turnPending.tool.description }}</span>
          </div>
          <pre v-if="permArgsText" class="perm-args">{{ permArgsText }}</pre>
          <div class="perm-actions">
            <n-button size="small" :loading="permSubmitting" :disabled="permSubmitting" @click="onPerm('reject')">
              拒绝
            </n-button>
            <n-button size="small" type="primary" :loading="permSubmitting" :disabled="permSubmitting" @click="onPerm('approve')">
              允许执行
            </n-button>
          </div>
        </div>
      </div>

      <!-- 结果文件区:AI 声明的交付文件,点击预览 -->
      <DeliverableBar
        v-if="deliverables.length"
        :items="deliverables"
        :busy="downloadingAll"
        @preview="previewFile"
        @download="downloadFile"
        @downloadAll="downloadAllFiles"
      />
    </div>
  </div>

  <!-- 文件预览弹窗 -->
  <n-modal
    :show="!!previewPath"
    preset="card"
    :title="previewPath ? previewPath.split(/[\\/]/).pop() : '预览'"
    :style="{ width: '86vw', maxWidth: '1280px' }"
    :mask-closable="true"
    :bordered="false"
    @update:show="(v) => { if (!v) closePreview() }"
  >
    <FilePreview
      v-if="previewPath"
      :path="previewPath"
    />
  </n-modal>
</template>

<style scoped>
.msg {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 26px;
  padding: 0 4px;
}
.followups { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 8px; }
.fu-label { font-size: 12px; color: var(--text-3); }
.fu-chip {
  font-size: 12px; color: var(--text-2);
  background: var(--surface); border: 1px solid var(--border-soft);
  border-radius: 6px; padding: 4px 10px; cursor: pointer;
  transition: border-color .15s, color .15s;
}
.fu-chip:hover { border-color: var(--accent); color: var(--accent); }
.avatar {
  width: 32px; height: 32px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent); color: #fff;
  margin-top: 1px;
}
.bubble {
  position: relative;
  flex: 1;
  min-width: 0;
  background: transparent;
  line-height: 1.6; word-break: break-word;
  border: none;
  box-shadow: none;
}
.msg-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  min-height: 20px;
}

/* 人工确认卡:危险操作待用户允许/拒绝 */
.perm-block {
  max-width: 80%;
  margin-top: 10px;
  border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: var(--radius-sm);
  background: rgba(245, 158, 11, 0.06);
  overflow: hidden;
}
.perm-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: rgba(245, 158, 11, 0.12);
  color: var(--warn);
  font-size: 13px;
  font-weight: 600;
}
.perm-title { display: inline-flex; align-items: center; gap: 6px; }
.perm-body { padding: 8px 12px; }
.perm-tool { display: flex; flex-direction: column; gap: 2px; font-size: 13px; color: var(--text); }
.perm-tool-name { font-family: ui-monospace, monospace; font-weight: 600; color: var(--accent); }
.perm-desc { color: var(--text-2); font-size: 12px; }
.perm-args {
  margin: 6px 0 0;
  padding: 6px 8px;
  border-radius: var(--radius-sm);
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 120px;
  overflow: auto;
}
.perm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.author { font-size: 13px; font-weight: 600; color: var(--text); }
.msg-time { font-size: 11px; color: var(--text-3); }
.content { position: relative; font-size: 14px; line-height: 1.8; letter-spacing: 0.01em; color: var(--text); }
.content :deep(p) { margin: 8px 0; }
.content :deep(p:first-child) { margin-top: 0; }
.content :deep(p:last-child) { margin-bottom: 0; }
.content :deep(ul),
.content :deep(ol) { padding-left: 20px; margin: 8px 0; }
.content :deep(li) { margin: 5px 0; line-height: 1.75; }
.content :deep(li > p) { margin: 2px 0; }
.content :deep(hr) {
  border: none;
  border-top: 1px solid var(--border-soft);
  margin: 14px 0;
}
/* 最终答案工具条:水平排列,悬停显示 */
.fa-tools {
  position: absolute; top: -6px; right: -6px; z-index: 2;
  display: flex; align-items: center; gap: 2px;
  padding: 2px; border-radius: 6px;
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow);
  opacity: 0; transition: opacity .15s;
}
.content:hover .fa-tools { opacity: 1; }
/* 触屏:最终答案工具条常显 */
@media (hover: none) {
  .fa-tools { opacity: 1; }
}
.copy-btn {
  background: transparent; border: none; cursor: pointer;
  padding: 4px; border-radius: 4px; line-height: 1;
  color: var(--text-3); display: inline-flex; align-items: center; justify-content: center;
}
.copy-btn:hover { background: var(--surface-hover); color: var(--text-2); }
.copy-btn.star-btn.on { color: #f0b429; }
.copy-btn.regen-btn:hover { color: var(--accent); }
.content :deep(pre.hljs) { background: var(--code-bg); border-radius: var(--radius-sm); padding: 12px; overflow-x: auto; }
.content :deep(code) { font-family: ui-monospace, monospace; font-size: 13px; }
.content :deep(pre code) { background: none; }
.content :deep(code:not(pre code)) { background: var(--surface-hover); padding: 2px 5px; border-radius: 4px; }
.content :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
  display: block;
  overflow-x: auto;
  font-size: 13px;
}
.content :deep(th),
.content :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 12px;
  text-align: left;
  vertical-align: top;
}
.content :deep(th) {
  background: var(--surface-hover);
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
}
.content :deep(td) { color: var(--text-2); }
.content :deep(tr:nth-child(even) td) { background: var(--surface); }

/* 思考区 */
.think-header {
  display: inline-flex; align-items: center; gap: 8px;
}
.think-spinner {
  position: relative; width: 13px; height: 13px; flex-shrink: 0;
  display: inline-flex;
}
.think-ring {
  position: absolute; inset: 0; border-radius: 50%;
  border: 2px solid var(--accent-soft);
  border-top-color: var(--accent);
  animation: think-spin .8s linear infinite;
}
@keyframes think-spin { to { transform: rotate(360deg); } }
.think-label {
  font-size: 13px; font-weight: 600; color: var(--text-3);
  transition: color .2s;
}
.think-header.active .think-label {
  color: var(--accent-text);
  animation: think-pulse 1.4s ease-in-out infinite;
}
@keyframes think-pulse { 50% { opacity: .6; } }

.step { padding: 4px 0; border-bottom: 1px dashed var(--border-soft); }
.step:last-child { border-bottom: none; }
.step-narration {
  font-size: 13px; color: var(--text-2); opacity: .85;
}
.step-narration :deep(p) { margin: 4px 0; }
/* 推理段包裹卡片:头部可点开合,正文限高滚动 */
.reason-box {
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-sm);
  margin: 2px 0;
  background: var(--surface-hover);
  overflow: hidden;
}
.reason-head {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 8px; font-size: 11px;
  color: var(--text-3); cursor: pointer; user-select: none;
}
.reason-head:hover { color: var(--text-2); }
.rc-chev { transition: transform .15s ease; opacity: .7; }
.rc-chev.open { transform: rotate(180deg); }
.reason-body {
  padding: 2px 10px 8px;
  font-size: 12px; line-height: 1.55;
  color: var(--text-3); font-style: italic;
  white-space: pre-wrap; word-break: break-word;
  max-height: 180px; overflow-y: auto;
}
.reason-body .cursor { margin-left: 2px; }
.rc-tag {
  display: inline-block; margin-right: 6px; padding: 0 6px;
  border-radius: 8px; font-size: 11px; font-style: normal;
  background: var(--surface-hover); color: var(--text-3);
}
.step-narration :deep(pre.hljs) { background: var(--code-bg); border-radius: var(--radius-sm); padding: 8px; overflow-x: auto; }
.step-narration :deep(code) { font-family: ui-monospace, monospace; font-size: 12px; }

.tool-calls { display: flex; flex-direction: column; gap: 6px; margin-top: 6px; }

/* 浏览器截图(步骤/最终答案内嵌,点击放大) */
.step-images { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.step-img {
  max-width: 320px; max-height: 400px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-soft);
  background: #fff;
  cursor: zoom-in;
  object-fit: contain;
}
.zoom-img { width: 100%; border-radius: 8px; background: #fff; }

.cursor { animation: blink 1s steps(2) infinite; color: var(--accent); }
@keyframes blink { 50% { opacity: 0; } }

.pending { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 13px; padding: 4px 0; }
.pending-elapsed { color: var(--text-2); font-size: 12px; }

/* ========== pending block (custom, replaces n-alert) ========== */
.pending-block {
  margin-top: 12px;
  border-radius: var(--radius);
  border: 1px solid rgba(245, 158, 11, 0.3);
  background: rgba(245, 158, 11, 0.05);
  overflow: hidden;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
.pending-block-header {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px;
  background: rgba(245, 158, 11, 0.08);
  color: var(--warn);
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid rgba(245, 158, 11, 0.15);
}
.pending-block-body {
  padding: 12px;
  width: 100%;
  overflow: hidden;
  box-sizing: border-box;
}
.pending-icon {
  font-size: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--accent);
}
.pending-title { font-weight: 600; }

.question-block { border-color: rgba(245, 158, 11, 0.3); }
.question-block .pending-block-header { background: rgba(245, 158, 11, 0.08); color: var(--warn); }
.permission-block { border-color: rgba(139, 92, 246, 0.3); }
.permission-block .pending-block-header { background: rgba(139, 92, 246, 0.08); color: var(--accent); }

.question-item {
  margin-bottom: 14px;
  width: 100%;
  overflow: hidden;
  box-sizing: border-box;
}
.question-item:last-child {
  margin-bottom: 10px;
}

.question-title {
  font-size: 14px;
  margin-bottom: 8px;
  line-height: 1.6;
  color: var(--text);
  word-break: break-word;
}

.question-type-tag {
  color: var(--text-3);
  font-size: 12px;
  margin-left: 6px;
}

.option-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.opt-desc {
  color: var(--text-2);
  font-size: 12px;
}

.custom-input-wrap {
  margin-top: 8px;
  width: 100%;
}

.perm-title {
  font-size: 14px;
  margin-bottom: 8px;
  line-height: 1.6;
  color: var(--accent);
  word-break: break-word;
  overflow-wrap: anywhere;
}

.perm-list {
  margin: 6px 0 8px 20px;
  padding: 0;
  font-size: 13px;
  color: var(--text-2);
}

.perm-list li {
  margin: 2px 0;
  font-family: ui-monospace, monospace;
  word-break: break-all;
  overflow-wrap: anywhere;
}

.pending-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
  width: 100%;
  box-sizing: border-box;
}

/* 移动端:消息布局收紧 */
@media (max-width: 768px) {
  .msg { gap: 8px; margin-bottom: 18px; padding: 0 2px; }
  .avatar { width: 26px; height: 26px; border-radius: 8px; }
  .msg-head { min-height: 18px; margin-bottom: 4px; }
}
</style>
