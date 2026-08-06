<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useChatStore } from '@/stores/chat'
import { renderMarkdown } from '@/utils/markdown'
import { summarizeToolCall, extractFilePath, isFileProducingTool, extractDeliverables, stripDeliverableMarkers } from '@/utils/turns'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import JSZip from 'jszip'
import type { Step, Turn, ToolCall, PendingQuestion, PendingPermission } from '@/types'
import ChatMessage from './ChatMessage.vue'
import DeliverableBar from './DeliverableBar.vue'
import FilePreview from './preview/FilePreview.vue'

const props = defineProps<{
  turn: Turn
  sessionId: number | null
}>()

const chat = useChatStore()
const msg = useMessage()

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
  !pendingQuestionHost.value &&
  !pendingPermissionHost.value &&
  (chat.runningActivity?.kind === 'tool' || !activeMessageId.value),
)

const pendingHint = computed(() => {
  const act = chat.runningActivity
  if (act?.kind === 'tool') {
    if (isFileProducingTool({ tool: act.tool } as ToolCall)) {
      return act.arg ? `正在生成/写入内容:${act.arg},请稍候…` : '正在生成/写入内容,请稍候…'
    }
    return act.arg ? `正在执行 ${act.tool}(${act.arg}),请稍候…` : `正在执行 ${act.tool},请稍候…`
  }
  return 'AI 正在处理中,请稍候…'
})

/** 内容生成/写文件类工具刚开始执行时,弹一次轻提示第一时间安抚用户(去重防连弹) */
let lastHintedTool = ''
watch(() => chat.runningActivity, (act) => {
  if (!act || act.kind !== 'tool') return
  const key = `${act.tool}|${act.arg}`
  if (key === lastHintedTool) return
  lastHintedTool = key
  if (isFileProducingTool({ tool: act.tool } as ToolCall)) {
    msg.info('AI 正在生成/写入内容,可能需要一点时间,请稍候…')
  }
})

/** 某个 step 是否正在流式接收文本 */
function isStepActive(step: Step | null): boolean {
  if (!step || !step.message.opencodeMessageId) return false
  return chat.isStreaming && step.message.opencodeMessageId === activeMessageId.value
}

/** 取 step 的展示文本:活跃流式步用 streamingText,否则用定稿 narration */
function displayContent(step: Step | null): string {
  if (!step) return ''
  if (isStepActive(step)) return chat.streamingText || ''
  return step.narration || ''
}

// ---------- 简单模式(单条 assistant 无 tool)----------

const simpleMode = computed(
  () =>
    props.turn.steps.length === 0 &&
    !!props.turn.finalAnswer &&
    props.turn.finalAnswer.toolCalls.length === 0 &&
    !pendingQuestionHost.value &&
    !pendingPermissionHost.value,
)

const simpleMessage = computed(() => {
  const fa = props.turn.finalAnswer
  if (!fa) return null
  // 剥离 [结果文件] 标记行,避免 simpleMode 正文里显示标记(标记只在 DeliverableBar 展示)
  const raw = isStepActive(fa) ? (chat.streamingText || '') : (fa.message.content || '')
  return { ...fa.message, content: stripDeliverableMarkers(raw) }
})

/** 本 turn 的结果文件清单(仅 turn 定稿后展示)。
 *  流式期间(turnInProgress)返回空,避免半截标记 `[结果` 闪烁;正则要求整行完整才匹配是第二道保险。
 *  历史 turn 的 turnInProgress 为 false,自动渲染卡片(前提:AI 当时用了标记)。 */
const deliverables = computed(() =>
  turnInProgress.value ? [] : extractDeliverables(props.turn),
)

// ---------- 折叠控制(受控 expanded-names)----------

const expandedNames = computed<string[]>(() => {
  const ov = chat.manualCollapseOverride[props.turn.id]
  if (ov !== undefined) return ov ? ['thinking'] : []
  return turnInProgress.value ? ['thinking'] : []
})

function handleExpand(names: string[] | null) {
  chat.setCollapse(props.turn.id, (names || []).includes('thinking'))
}

// ---------- 工具 chip 状态 ----------

function toolStatusClass(tc: ToolCall): string {
  if (tc.status === 'completed') return 'ok'
  if (tc.status === 'error') return 'err'
  return 'run'
}

function toolStatusIcon(tc: ToolCall): string {
  if (tc.status === 'completed') return '✓'
  if (tc.status === 'error') return '✗'
  return '…'
}

/** 下载工具产出的文件(chip 上的 📥 按钮) */
async function downloadFile(path: string) {
  try {
    await fileApi.download(path)
  } catch (e: any) {
    msg.error(e?.message || '下载失败')
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

const thinkingStepCount = computed(() => {
  const n = props.turn.steps.length
  const faTools = props.turn.finalAnswer?.toolCalls.length ?? 0
  return n + (faTools > 0 ? 1 : 0)
})

const thinkingHeader = computed(() =>
  turnInProgress.value
    ? `思考中… (${props.turn.steps.length} 步)`
    : `已折叠 ${props.turn.steps.length} 步思考`,
)

function stepHtml(step: Step): string {
  return renderMarkdown(displayContent(step))
}

// ---------- pending question / permission 收集 ----------

interface PendingQuestionHost {
  pq: PendingQuestion
}

interface PendingPermissionHost {
  pp: PendingPermission
}

const pendingQuestionHost = computed<PendingQuestionHost | null>(() => {
  // pending 仅对当前(最后一个)轮次有意义;历史轮次里的 pendingQuestion 早已失效,
  // 重新加载历史时 _replied 本地态会丢失,若不拦截会重复弹框。
  if (!isLastTurn.value) return null
  const allSteps: Step[] = [...props.turn.steps]
  if (props.turn.finalAnswer) allSteps.push(props.turn.finalAnswer)
  for (const s of allSteps) {
    const pq = s.message.parts?.pendingQuestion
    if (pq && !pq._replied) {
      return { pq }
    }
  }
  return null
})

const pendingPermissionHost = computed<PendingPermissionHost | null>(() => {
  // 同 pendingQuestionHost:仅最后一个轮次才展示,避免历史轮次残留的 pendingPermission 重复弹框
  if (!isLastTurn.value) return null
  const allSteps: Step[] = [...props.turn.steps]
  if (props.turn.finalAnswer) allSteps.push(props.turn.finalAnswer)
  for (const s of allSteps) {
    const pp = s.message.parts?.pendingPermission
    if (pp && !pp._replied) {
      return { pp }
    }
  }
  return null
})

// ---------- 本地选项状态 ----------

const singleSelected = ref<Record<number, string | null>>({})
const multiSelected = ref<Record<number, string[]>>({})
const customTexts = ref<Record<number, string>>({})
const submittingQuestion = ref(false)
const submittingPermission = ref(false)

function getSingleSelected(qIndex: number): string | null {
  return singleSelected.value[qIndex] ?? null
}
function setSingleSelected(qIndex: number, val: string | null) {
  singleSelected.value = { ...singleSelected.value, [qIndex]: val }
}
function getMultiSelected(qIndex: number): string[] {
  return multiSelected.value[qIndex] ?? []
}
function setMultiSelected(qIndex: number, val: string[]) {
  multiSelected.value = { ...multiSelected.value, [qIndex]: val }
}
function getCustomText(qIndex: number): string {
  return customTexts.value[qIndex] ?? ''
}
function setCustomText(qIndex: number, val: string) {
  customTexts.value = { ...customTexts.value, [qIndex]: val }
}

function collectAnswers(host: PendingQuestionHost): string[][] {
  const { pq } = host
  return pq.questions.map((q, i) => {
    const result: string[] = []
    if (q.multiple) {
      const arr = getMultiSelected(i)
      for (const label of arr) result.push(label)
    } else {
      const v = getSingleSelected(i)
      if (v) result.push(v)
    }
    if (q.custom) {
      const c = getCustomText(i).trim()
      if (c) result.push(c)
    }
    return result
  })
}

function clearStepPendingQuestion(requestId: string) {
  const allSteps: Step[] = [...props.turn.steps]
  if (props.turn.finalAnswer) allSteps.push(props.turn.finalAnswer)
  for (const s of allSteps) {
    if (s.message.parts?.pendingQuestion?.requestId === requestId) {
      if (s.message.parts) {
        ;(s.message.parts as any).pendingQuestion = null
      }
    }
  }
}

function clearStepPendingPermission(requestId: string) {
  const allSteps: Step[] = [...props.turn.steps]
  if (props.turn.finalAnswer) allSteps.push(props.turn.finalAnswer)
  for (const s of allSteps) {
    if (s.message.parts?.pendingPermission?.requestId === requestId) {
      if (s.message.parts) {
        ;(s.message.parts as any).pendingPermission = null
      }
    }
  }
}

async function submitQuestion(host: PendingQuestionHost) {
  console.log('[AssistantTurn] submitQuestion clicked', {
    sessionId: props.sessionId,
    requestId: host.pq.requestId,
    answers: collectAnswers(host),
  })
  if (!props.sessionId) {
    msg.error('会话未就绪,请刷新页面后重试')
    return
  }
  if (submittingQuestion.value) return
  const { pq } = host
  const answers = collectAnswers(host)
  submittingQuestion.value = true
  try {
    await chatApi.replyQuestion(props.sessionId, pq.requestId, answers)
    pq._replied = true
    msg.success('已提交回答,AI 继续生成中…')
    setTimeout(() => clearStepPendingQuestion(pq.requestId), 100)
    // 恢复流式状态:question.asked 时 isStreaming 被设为 false 且不更新活动时间,
    // 提交后用 resumeStreaming() 同时重置 isStreaming + 活动计时器,
    // 防止看门狗因 lastStreamActivityAt 过期而误触发 finishStreaming()
    chat.resumeStreaming()
  } catch (e: any) {
    console.error('[AssistantTurn] submitQuestion failed', e)
    msg.error(e?.response?.data?.message || e?.message || '提交回答失败,请重试')
  } finally {
    submittingQuestion.value = false
  }
}

async function rejectQuestion(host: PendingQuestionHost) {
  console.log('[AssistantTurn] rejectQuestion clicked', {
    sessionId: props.sessionId,
    requestId: host.pq.requestId,
  })
  if (!props.sessionId) {
    msg.error('会话未就绪,请刷新页面后重试')
    return
  }
  if (submittingQuestion.value) return
  const { pq } = host
  submittingQuestion.value = true
  try {
    await chatApi.rejectQuestion(props.sessionId, pq.requestId)
    pq._replied = true
    msg.info('已跳过问题')
    setTimeout(() => clearStepPendingQuestion(pq.requestId), 100)
  } catch (e: any) {
    console.error('[AssistantTurn] rejectQuestion failed', e)
    msg.error(e?.response?.data?.message || e?.message || '操作失败,请重试')
  } finally {
    submittingQuestion.value = false
  }
}

async function submitPermission(host: PendingPermissionHost, reply: 'once' | 'always' | 'reject') {
  console.log('[AssistantTurn] submitPermission clicked', {
    sessionId: props.sessionId,
    requestId: host.pp.requestId,
    reply,
  })
  if (!props.sessionId) {
    msg.error('会话未就绪,请刷新页面后重试')
    return
  }
  if (submittingPermission.value) return
  const { pp } = host
  submittingPermission.value = true
  try {
    await chatApi.replyPermission(props.sessionId, pp.requestId, reply)
    pp._replied = true
    msg.success(reply === 'reject' ? '已拒绝权限' : reply === 'always' ? '已授予权限' : '已允许本次')
    setTimeout(() => clearStepPendingPermission(pp.requestId), 100)
    // 恢复流式状态(同 submitQuestion,用 resumeStreaming 防看门狗误触发)
    chat.resumeStreaming()
  } catch (e: any) {
    console.error('[AssistantTurn] submitPermission failed', e)
    msg.error(e?.response?.data?.message || e?.message || '操作失败,请重试')
  } finally {
    submittingPermission.value = false
  }
}
</script>

<template>
  <!-- 简单模式:复用普通气泡 -->
  <template v-if="simpleMode && simpleMessage">
    <ChatMessage
      :message="simpleMessage"
      :streaming="isStepActive(turn.finalAnswer)"
    />
    <DeliverableBar
      v-if="deliverables.length"
      :items="deliverables"
      :busy="downloadingAll"
      @preview="previewFile"
      @download="downloadFile"
      @downloadAll="downloadAllFiles"
    />
  </template>
  <!-- 多步模式:单气泡 = 可折叠思考区 + 最终答案 + pending UI -->
  <div v-else class="msg assistant">
    <div class="avatar">AI</div>
    <div class="bubble">
      <!-- 思考过程(可折叠) -->
      <NCollapse
        v-if="turn.steps.length > 0 || (turn.finalAnswer && turn.finalAnswer.toolCalls.length)"
        :expanded-names="expandedNames"
        :display-directive="'show'"
        @update:expanded-names="handleExpand"
      >
        <NCollapseItem name="thinking" :title="thinkingHeader">
          <div v-for="(step, i) in turn.steps" :key="step.message.id ?? i" class="step">
            <div
              v-if="displayContent(step)"
              class="step-narration"
              v-html="stepHtml(step)"
            />
            <span v-if="isStepActive(step)" class="cursor">▋</span>
            <div v-if="step.toolCalls.length" class="tool-chips">
              <span
                v-for="tc in step.toolCalls"
                :key="tc.partId || tc.tool"
                class="chip"
                :class="toolStatusClass(tc)"
              >
                🔧 {{ tc.tool }} <span class="chip-arg">{{ summarizeToolCall(tc) }}</span>
                <span class="chip-status">{{ toolStatusIcon(tc) }}</span>
                <a
                  v-if="tc.status === 'completed' && isFileProducingTool(tc) && extractFilePath(tc)"
                  class="chip-download"
                  title="下载该文件"
                  @click.stop="downloadFile(extractFilePath(tc)!)"
                >📥</a>
              </span>
            </div>
          </div>
          <div
            v-if="turn.finalAnswer && turn.finalAnswer.toolCalls.length"
            class="step"
          >
            <div class="tool-chips">
              <span
                v-for="tc in turn.finalAnswer.toolCalls"
                :key="tc.partId || tc.tool"
                class="chip"
                :class="toolStatusClass(tc)"
              >
                🔧 {{ tc.tool }} <span class="chip-arg">{{ summarizeToolCall(tc) }}</span>
                <span class="chip-status">{{ toolStatusIcon(tc) }}</span>
                <a
                  v-if="tc.status === 'completed' && isFileProducingTool(tc) && extractFilePath(tc)"
                  class="chip-download"
                  title="下载该文件"
                  @click.stop="downloadFile(extractFilePath(tc)!)"
                >📥</a>
              </span>
            </div>
          </div>
        </NCollapseItem>
      </NCollapse>

      <!-- 最终答案 -->
      <div v-if="turn.finalAnswer" class="content">
        <span
          v-if="displayContent(turn.finalAnswer)"
          v-html="renderMarkdown(stripDeliverableMarkers(displayContent(turn.finalAnswer)))"
        />
        <span v-if="isStepActive(turn.finalAnswer)" class="cursor">▋</span>
      </div>
      <div v-else-if="showWorkingHint" class="pending">
        <n-spin size="small" />
        <span v-if="chat.streamingStuckSeconds > 60">仍未收到响应,可点「停止」后重试</span>
        <template v-else>
          <span>{{ pendingHint }}</span>
          <span v-if="runningSeconds > 0" class="pending-elapsed">已运行 {{ runningSeconds }}s</span>
        </template>
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

      <!-- Pending Question 区块 (自定义 div,不用 n-alert 避免 overflow) -->
      <div
        v-if="pendingQuestionHost"
        class="pending-block question-block"
      >
        <div class="pending-block-header">
          <span class="pending-icon">⚠️</span>
          <span class="pending-title">需要您回答</span>
        </div>
        <div class="pending-block-body">
          <div
            v-for="(q, qi) in pendingQuestionHost.pq.questions"
            :key="qi"
            class="question-item"
          >
            <div class="question-title">
              <strong v-if="q.header">{{ q.header }}</strong>
              <span v-if="q.header"> — </span>
              <span>{{ q.question }}</span>
              <span class="question-type-tag">
                ({{ q.multiple ? '可多选' : '单选' }})
              </span>
            </div>

            <template v-if="q.multiple">
              <n-checkbox-group
                :value="getMultiSelected(qi)"
                @update:value="(val: string[]) => setMultiSelected(qi, val)"
              >
                <div class="option-list">
                  <n-checkbox
                    v-for="opt in q.options"
                    :key="opt.label"
                    :value="opt.label"
                  >
                    <span>{{ opt.label }}</span>
                    <span v-if="opt.description" class="opt-desc">
                      — {{ opt.description }}
                    </span>
                  </n-checkbox>
                </div>
              </n-checkbox-group>
            </template>
            <template v-else>
              <n-radio-group
                :value="getSingleSelected(qi)"
                @update:value="(val: string | null) => setSingleSelected(qi, val)"
              >
                <div class="option-list">
                  <n-radio
                    v-for="opt in q.options"
                    :key="opt.label"
                    :value="opt.label"
                  >
                    <span>{{ opt.label }}</span>
                    <span v-if="opt.description" class="opt-desc">
                      — {{ opt.description }}
                    </span>
                  </n-radio>
                </div>
              </n-radio-group>
            </template>

            <div v-if="q.custom" class="custom-input-wrap">
              <n-input
                :value="getCustomText(qi)"
                @update:value="(val: string) => setCustomText(qi, val)"
                placeholder="其他/自定义（请输入）"
                clearable
              />
            </div>
          </div>

          <div class="pending-actions">
            <n-button
              quaternary
              size="small"
              :disabled="submittingQuestion"
              @click="rejectQuestion(pendingQuestionHost)"
            >
              拒绝/跳过
            </n-button>
            <n-button
              type="primary"
              size="small"
              :loading="submittingQuestion"
              @click="submitQuestion(pendingQuestionHost)"
            >
              提交回答
            </n-button>
          </div>
        </div>
      </div>

      <!-- Pending Permission 区块 (自定义 div) -->
      <div
        v-if="pendingPermissionHost"
        class="pending-block permission-block"
      >
        <div class="pending-block-header">
          <span class="pending-icon">🔒</span>
          <span class="pending-title">请求权限</span>
        </div>
        <div class="pending-block-body">
          <template v-if="pendingPermissionHost.pp.version === 'v1'">
            <div class="perm-title">
              {{ (pendingPermissionHost.pp as any).permission }}
            </div>
            <ul v-if="(pendingPermissionHost.pp as any).patterns?.length" class="perm-list">
              <li v-for="(p, pi) in (pendingPermissionHost.pp as any).patterns" :key="pi">
                {{ p }}
              </li>
            </ul>
          </template>
          <template v-else>
            <div class="perm-title">
              操作: <strong>{{ (pendingPermissionHost.pp as any).action }}</strong>
            </div>
            <ul v-if="(pendingPermissionHost.pp as any).resources?.length" class="perm-list">
              <li v-for="(r, ri) in (pendingPermissionHost.pp as any).resources" :key="ri">
                {{ r }}
              </li>
            </ul>
          </template>

          <div class="pending-actions">
            <n-button
              size="small"
              type="error"
              quaternary
              :disabled="submittingPermission"
              @click="submitPermission(pendingPermissionHost, 'reject')"
            >
              拒绝
            </n-button>
            <n-button
              size="small"
              :disabled="submittingPermission"
              @click="submitPermission(pendingPermissionHost, 'once')"
            >
              允许本次
            </n-button>
            <n-button
              size="small"
              type="primary"
              :loading="submittingPermission"
              @click="submitPermission(pendingPermissionHost, 'always')"
            >
              始终允许
            </n-button>
          </div>
        </div>
      </div>
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
.msg { display: flex; gap: 10px; margin-bottom: 16px; }
.avatar {
  width: 28px; height: 28px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; background: #2f2f35;
}
.bubble {
  max-width: 75%;
  min-width: 0;
  padding: 10px 14px; border-radius: 10px;
  background: #26262a; line-height: 1.6; word-break: break-word;
  overflow: hidden;
}
.content :deep(p) { margin: 6px 0; }
.content :deep(pre.hljs) { background: #18181c; border-radius: 6px; padding: 10px; overflow-x: auto; }
.content :deep(code) { font-family: ui-monospace, monospace; font-size: 13px; }
.content :deep(pre code) { background: none; }
.content :deep(code:not(pre code)) { background: #18181c; padding: 2px 5px; border-radius: 4px; }

/* 思考区 */
.step { padding: 4px 0; border-bottom: 1px dashed #333; }
.step:last-child { border-bottom: none; }
.step-narration {
  font-size: 13px; color: #9a9aa0; opacity: .85;
}
.step-narration :deep(p) { margin: 4px 0; }
.step-narration :deep(pre.hljs) { background: #18181c; border-radius: 6px; padding: 8px; overflow-x: auto; }
.step-narration :deep(code) { font-family: ui-monospace, monospace; font-size: 12px; }

.tool-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.chip {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; padding: 2px 8px; border-radius: 10px;
  background: #18181c; color: #b0b0b6; border: 1px solid #2a2a2e;
}
.chip-arg { color: #56b6c2; }
.chip .chip-status { font-weight: bold; }
.chip-download {
  margin-left: 2px; padding: 0 2px; cursor: pointer;
  font-size: 13px; line-height: 1; text-decoration: none;
  border-radius: 4px;
}
.chip-download:hover { background: #2a2a2e; }
.chip.run .chip-status { color: #888; }
.chip.ok .chip-status { color: #98c379; }
.chip.err .chip-status { color: #e06c75; }

.cursor { animation: blink 1s steps(2) infinite; color: #56b6c2; }
@keyframes blink { 50% { opacity: 0; } }

.pending { display: flex; align-items: center; gap: 8px; color: #888; font-size: 13px; padding: 4px 0; }
.pending-elapsed { color: #9aa3b2; font-size: 12px; }

/* ========== pending block (custom, replaces n-alert) ========== */
.pending-block {
  margin-top: 12px;
  border-radius: 8px;
  border: 1px solid #4a3d1f;
  background: #3a3320;
  overflow: hidden;
  width: 100%;
  min-width: 0;
}
.pending-block-header {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px;
  background: #4a3d1f;
  color: #e8c547;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid #5a4d2f;
}
.pending-block-body {
  padding: 12px;
  width: 100%;
  overflow: hidden;
}
.pending-icon { font-size: 16px; }
.pending-title { font-weight: 600; }

.question-block { border-color: #4a3d1f; }
.question-block .pending-block-header { background: #4a3d1f; color: #e8c547; }
.permission-block { border-color: #1f3d4a; }
.permission-block .pending-block-header { background: #1f3d4a; color: #56b6c2; }

.question-item {
  margin-bottom: 14px;
  width: 100%;
  overflow: hidden;
}
.question-item:last-child {
  margin-bottom: 10px;
}

.question-title {
  font-size: 14px;
  margin-bottom: 8px;
  line-height: 1.6;
  color: #e0d8b8;
  word-break: break-word;
}

.question-type-tag {
  color: #888;
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
  color: #999;
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
  color: #b8e0e8;
  word-break: break-word;
}

.perm-list {
  margin: 6px 0 8px 20px;
  padding: 0;
  font-size: 13px;
  color: #d0d0d6;
}

.perm-list li {
  margin: 2px 0;
  font-family: ui-monospace, monospace;
}

.pending-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
  flex-wrap: wrap;
  width: 100%;
}
</style>
