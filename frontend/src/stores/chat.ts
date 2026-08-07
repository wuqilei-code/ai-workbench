import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatApi } from '@/api/chat'
import { modelApi } from '@/api/admin'
import { subscribeSession, type SseHandlers } from '@/api/sse'
import { useAuthStore } from './auth'
import { buildTurns, buildFlow, summarizeToolCall } from '@/utils/turns'
import type {
  SessionVo,
  MessageVo,
  AgentItem,
  ProviderVo,
  StepParts,
  ToolCall,
  PendingQuestion,
  PendingPermission,
  PendingPermissionV1,
  PendingPermissionV2,
  QuestionItem,
  ChangeSummary,
} from '@/types'

interface StreamHandle { close: () => void }

/**
 * 从首条用户消息派生会话标题:剥离附件块、压缩空白、截断长度。
 * 用作用户首问的简短概述(非 AI 摘要),替代默认 "New Chat"。
 */
function deriveTitle(text: string): string {
  // 剥离 "📎 已上传..." 提示及其后的 [附件] 列表块
  let t = text.replace(/📎[\s\S]*$/, '').trim()
  // 兜底:移除散落的 [附件] path 行
  t = t.replace(/^\[附件\]\s*\S+$/gm, '').trim()
  // 压缩空白(换行→空格)
  t = t.replace(/\s+/g, ' ').trim()
  if (t) return t.length > 30 ? t.slice(0, 30) + '…' : t
  // 纯附件(无文本):用第一个附件文件名作标题
  const m = text.match(/\[附件\]\s*(\S+)/)
  if (m) {
    const name = m[1].split(/[\\/]/).pop() || m[1]
    return name.length > 30 ? name.slice(0, 30) + '…' : name
  }
  return ''
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionVo[]>([])
  const agents = ref<AgentItem[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<MessageVo[]>([])
  /** AI 变更总结卡片(role=summary 的消息与 SSE 事件聚合) */
  const summaries = ref<ChangeSummary[]>([])
  const streamingText = ref('')
  const streamingMessageId = ref<string | null>(null)
  const isStreaming = ref(false)
  const connected = ref(false)

  /** 静默看门狗:跟踪 AI 运行时长,让 UI 能显示"AI 正在执行操作(已运行 Xs)"提示。
   *  <p>
   *  注意:本看门狗**不再自动释放 isStreaming**。AI 执行耗时操作(如 bash 生成文件、长思考)时,
   *  30s+ 无 token 是正常的,自动释放会让用户误以为 AI 已结束 → 发送下条消息打断任务。
   *  isStreaming 仅由以下信号释放:session.idle(正常结束)/ session.error / 用户手动「解除阻塞」。
   *  真卡死(进程崩溃无事件)时用户可手动点「解除阻塞」中断。 */
  const lastStreamActivityAt = ref<number>(Date.now())
  /** 本轮生成开始时间(用于显示"已运行 Xs");0 表示未在生成 */
  const streamStartAt = ref<number>(0)
  /** 心跳计数器:每 5s +1,用于让 streamingStuckSeconds 依赖它以实现"每 5s 重新计算运行秒数"
   *  (Date.now() 不是响应式的,必须引入一个响应式依赖才能让 computed 周期性刷新) */
  const _watchdogTick = ref<number>(0)
  let _watchdogTimer: ReturnType<typeof setInterval> | null = null
  function touchStreamActivity() { lastStreamActivityAt.value = Date.now() }
  function startWatchdog() {
    if (_watchdogTimer) return
    _watchdogTimer = setInterval(() => {
      _watchdogTick.value++ // 触发 streamingStuckSeconds 重新计算,让"运行中"banner 秒数刷新
    }, 5_000)
  }
  /** 显式释放流式阻塞:用于"卡住"时,用户点击"解除阻塞并发送"。
   *  会先调用 abort(向 opencode 发送终止信号)再兜底 finishStreaming,确保不会产生竞争。 */
  async function forceReleaseStreaming() {
    try { if (currentSessionId.value) await abort() } catch (_) {}
    finishStreaming()
  }

  /** 恢复流式状态:用于用户提交 question/permission 回复后。
   *  <p>
   *  question.asked/permission.asked 会把 isStreaming 置 false 且不更新 lastStreamActivityAt。
   *  若用户读问题+选答案超过 30s,提交后看门狗会因 lastStreamActivityAt 过期而立即 finishStreaming(),
   *  把 isStreaming 重新置 false → 前端不显示"思考中" → 用户以为"提交后没继续回答"。
   *  此方法同时重置 isStreaming + touchStreamActivity,确保看门狗不会误触发。 */
  function resumeStreaming() {
    isStreaming.value = true
    streamingText.value = ''
    streamingMessageId.value = null
    touchStreamActivity()
    streamStartAt.value = Date.now()
  }

  const availableModels = ref<ProviderVo[]>([])
  const selectedProviderId = ref<string | null>(null)
  const selectedModelId = ref<string | null>(null)

  const currentSession = computed(() =>
    sessions.value.find((s) => s.id === currentSessionId.value) || null,
  )

  /** 由 messages 派生的 Turn 列表(只依赖 messages,不依赖流式文本) */
  const turns = computed(() => buildTurns(messages.value))

  /** 轮 + 变更总结按时间交错的有序渲染流:让总结卡片紧跟所属轮,而非堆到底部 */
  const flow = computed(() => buildFlow(turns.value, summaries.value))

  /** 用户手动折叠覆盖:turnId → 是否展开思考区(true 展开 / false 折叠);undefined 未覆盖 */
  const manualCollapseOverride = ref<Record<string, boolean>>({})

  function setCollapse(turnId: string, expanded: boolean) {
    manualCollapseOverride.value = { ...manualCollapseOverride.value, [turnId]: expanded }
  }

  let stream: StreamHandle | null = null

  // ---------- 模型 ----------

  async function loadModels() {
    try {
      availableModels.value = await modelApi.available()
      // 默认选第一个可用模型
      if (!selectedModelId.value && availableModels.value.length) {
        const p = availableModels.value[0]
        if (p.models.length) {
          selectedProviderId.value = p.providerId
          selectedModelId.value = p.models[0].modelId
        }
      }
    } catch (e) {
      // 模型未配置时静默
    }
  }

  function selectModel(providerId: string, modelId: string) {
    selectedProviderId.value = providerId
    selectedModelId.value = modelId
  }

  // ---------- 会话 ----------

  async function loadSessions() {
    sessions.value = await chatApi.listSessions()
  }

  async function loadAgents() {
    if (agents.value.length) return
    try {
      agents.value = await chatApi.listAgents()
    } catch (e) {
      console.error('[chat] load agents failed', e)
    }
  }

  async function createSession(title?: string, agent?: string) {
    const s = await chatApi.createSession({
      title,
      agent,
      providerId: selectedProviderId.value || undefined,
      modelId: selectedModelId.value || undefined,
    })
    sessions.value.unshift(s)
    await selectSession(s.id)
    return s
  }

  /** 切换当前会话 agent(openocode 侧 switchAgent + 本地持久化) */
  async function switchAgent(agent: string) {
    if (!currentSessionId.value) return
    const updated = await chatApi.updateSession(currentSessionId.value, { agent })
    const idx = sessions.value.findIndex((s) => s.id === currentSessionId.value)
    if (idx >= 0) sessions.value[idx] = updated
    return updated
  }

  async function selectSession(id: number) {
    if (currentSessionId.value === id && stream) return
    closeStream()
    currentSessionId.value = id
    messages.value = []
    summaries.value = []
    streamingText.value = ''
    streamingMessageId.value = null
    isStreaming.value = false
    manualCollapseOverride.value = {}
    try {
      messages.value = await chatApi.listMessages(id)
      hydrateSummaries()
      hydratePendingState()
    } catch (e) {
      messages.value = []
    }
    subscribe()
    startWatchdog()
    // 预热 opencode 进程:提前拉起,避免首条消息冷启动阻塞数秒(后端幂等 + 15min 空闲回收)
    chatApi.warmup(id).catch((e) => console.debug('[chat] warmup skipped', e))
  }

  async function deleteSession(id: number) {
    await chatApi.deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      closeStream()
      currentSessionId.value = null
      messages.value = []
    }
  }

  // ---------- 发消息 + SSE ----------

  function subscribe() {
    if (!currentSessionId.value) return
    const auth = useAuthStore()
    connected.value = false
    const handlers: SseHandlers = {
      onOpen: () => { connected.value = true },
      onMessage: (type, data) => handleEvent(type, data),
      onError: () => { connected.value = false },
    }
    stream = subscribeSession(currentSessionId.value, auth.token, handlers)
  }

  function closeStream() {
    if (stream) { stream.close(); stream = null }
    connected.value = false
  }

  async function sendMessage(text: string) {
    if (!currentSessionId.value || !text.trim() || isStreaming.value) return
    const isFirstMessage = messages.value.length === 0
    // 乐观显示用户消息
    const optimistic: MessageVo = {
      id: -Date.now(),
      role: 'user',
      content: text,
      status: 'pending',
      _pending: true,
    }
    messages.value.push(optimistic)
    streamingText.value = ''
    streamingMessageId.value = null
    isStreaming.value = true
    touchStreamActivity()
    streamStartAt.value = Date.now()

    // 首条用户消息:并行更新标题(不依赖 sendMessage 的 await,
    // 避免 sendMessage 异常/超时/lazy-rebind 阻断标题更新)
    if (isFirstMessage) {
      const title = deriveTitle(text)
      if (title) {
        chatApi.updateSession(currentSessionId.value, { title })
          .then((updated) => {
            const idx = sessions.value.findIndex((s) => s.id === currentSessionId.value)
            if (idx >= 0) sessions.value[idx] = updated
          })
          .catch((e) => console.error('[chat] auto-title update failed', e))
      }
    }

    try {
      await chatApi.sendMessage(currentSessionId.value, {
        text,
        providerId: selectedProviderId.value || undefined,
        modelId: selectedModelId.value || undefined,
      })
    } catch (e: any) {
      isStreaming.value = false
      optimistic.status = 'error'
      throw e
    }
  }

  /** 触发 slash 命令(fire-and-forget):乐观显示用户消息,流式回复走 SSE,与 sendMessage 同 UX。 */
  async function runCommand(command: string, args: string) {
    if (!currentSessionId.value) return
    const displayText = '/' + command + (args ? ' ' + args : '')
    const optimistic: MessageVo = {
      id: -Date.now(),
      role: 'user',
      content: displayText,
      status: 'pending',
      createdAt: new Date().toISOString(),
      _pending: true,
    }
    messages.value.push(optimistic)
    streamingText.value = ''
    streamingMessageId.value = null
    isStreaming.value = true
    touchStreamActivity()
    streamStartAt.value = Date.now()
    try {
      await chatApi.runCommand(currentSessionId.value, { command, arguments: args })
    } catch (e: any) {
      isStreaming.value = false
      optimistic.status = 'error'
      throw e
    }
  }

  async function abort() {
    if (!currentSessionId.value) return
    try {
      await chatApi.abort(currentSessionId.value)
    } finally {
      isStreaming.value = false
      streamStartAt.value = 0
    }
  }

  /**
   * 处理 opencode /global/event 经后端转发的事件。
   * <p>
   * opencode 1.18.x 的真实事件类型(经抓包确认):
   *   - message.updated         消息元信息 {info:{id,role,time,...}},**不含 parts/文本**
   *   - message.part.updated    part 定稿 {part:{type,text,messageID,time:{start,end}}};
   *                             text 类型 part 携带完整文本(用户消息文本也走这里)
   *   - message.part.delta      流式增量 {messageID,field:"text",delta}
   *   - session.status          {status:{type:"busy"|"idle"}}
   *   - session.idle            生成结束
   * 旧版监听的 session.next.text.* 在本版本中不存在,导致回复实时不显示。
   */
  function handleEvent(type: string, data: any) {
    // 诊断日志:追踪 SSE 事件流(尤其是 question 回复后的续生成事件)
    console.debug('[chat] SSE event:', type, 'isStreaming=', isStreaming.value,
      'streamingMsgId=', streamingMessageId.value, 'data=', data)
    switch (type) {
      case 'open':
        connected.value = true
        break

      // 流式增量文本(token by token)
      case 'message.part.delta': {
        if (data.field === 'text' && data.delta) {
          isStreaming.value = true
          if (!streamStartAt.value) streamStartAt.value = Date.now()
          touchStreamActivity()
          if (data.messageID) streamingMessageId.value = data.messageID
          streamingText.value += data.delta
        }
        break
      }

      // part 定稿:text 携带完整文本;tool/step-finish 携带工具调用与步骤结束标记
      case 'message.part.updated': {
        touchStreamActivity()
        const part = data.part || {}
        if (!part.messageID) break
        if (part.type === 'text') {
          const text = typeof part.text === 'string' ? part.text : ''
          if (text) {
            finalizeTextPart(part.messageID, text, part.time?.end != null)
          }
        } else if (part.type === 'tool') {
          upsertToolCall(part.messageID, part)
        } else if (part.type === 'step-finish') {
          upsertStepFinish(part.messageID, part.reason)
        }
        break
      }

      // 消息元信息(id/role/status),不含文本 → 只建壳/更新状态
      case 'message.updated': {
        touchStreamActivity()
        const info = data.info || {}
        upsertMessageMeta(info)
        break
      }

      case 'session.status':
        if (data.status?.type === 'idle') finishStreaming()
        else if (data.status?.type === 'busy') {
          isStreaming.value = true
          if (!streamStartAt.value) streamStartAt.value = Date.now()
          touchStreamActivity()
        }
        break
      case 'session.idle':
        finishStreaming()
        break

      // AI 变更总结卡片(由后端在快照后异步生成推送)
      case 'change.summary':
        summaries.value.push({
          summary: data.summary || '',
          files: data.files || [],
          at: data.at,
          sha: data.sha,
          parentSha: data.parentSha,
        })
        break

      case 'session.error': {
        // 生成出错:完整清理流式态(不 finalize 半截文本,避免把残片当完整答案),并展示可读失败文案。
        isStreaming.value = false
        streamStartAt.value = 0
        const errorMsg: string =
          (typeof data.error?.message === 'string' && data.error.message) ||
          (typeof data.error?.name === 'string' && data.error.name) ||
          '生成出错,请重试'
        const failedId = streamingMessageId.value
        streamingMessageId.value = null
        streamingText.value = ''
        // 出错时清掉未决 question/permission,避免残留确认框
        clearPendingQuestion(undefined)
        clearPendingPermission(undefined)
        markAssistantError(failedId, errorMsg)
        break
      }

      case 'question.asked':
      case 'question.v2.asked': {
        const version: 'v1' | 'v2' = type === 'question.v2.asked' ? 'v2' : 'v1'
        const requestId: string = data.id
        const questions: QuestionItem[] = data.questions || []
        const tool = data.tool
          ? { messageID: data.tool.messageID as string, callID: data.tool.callID as string | undefined }
          : undefined
        const pq: PendingQuestion = {
          requestId,
          questions,
          tool,
          version,
          _replies: questions.map(() => []),
          _replied: false,
        }
        attachPendingQuestion(data.tool?.messageID, pq)
        isStreaming.value = false
        break
      }
      case 'question.replied':
      case 'question.rejected':
      case 'question.v2.replied':
      case 'question.v2.rejected': {
        clearPendingQuestion(data.tool?.messageID)
        // 兜底恢复流式态:无论回答还是拒绝,AI 都会继续生成;
        // 若该事件先于下一段 part.delta/session.status 到达,先置位避免"提交后无响应"。
        resumeStreaming()
        break
      }

      case 'permission.asked': {
        const pp: PendingPermissionV1 = {
          requestId: data.id,
          version: 'v1',
          permission: data.permission || '',
          patterns: data.patterns || [],
          always: data.always,
          metadata: data.metadata,
          tool: data.tool
            ? { messageID: data.tool.messageID as string, callID: data.tool.callID as string | undefined }
            : undefined,
          _replied: false,
        }
        attachPendingPermission(data.tool?.messageID, pp)
        isStreaming.value = false
        break
      }
      case 'permission.v2.asked': {
        const pp: PendingPermissionV2 = {
          requestId: data.id,
          version: 'v2',
          action: data.action || '',
          resources: data.resources || [],
          save: data.save,
          metadata: data.metadata,
          source: data.source,
          tool: data.tool
            ? { messageID: data.tool.messageID as string, callID: data.tool.callID as string | undefined }
            : undefined,
          _replied: false,
        }
        attachPendingPermission(data.tool?.messageID, pp)
        isStreaming.value = false
        break
      }
      case 'permission.replied':
      case 'permission.v2.replied': {
        clearPendingPermission(data.tool?.messageID)
        // 同 question:允许/拒绝后 AI 都会继续生成,兜底恢复流式态。
        resumeStreaming()
        break
      }
    }
  }

  /** 消息元信息 upsert:只维护 id/role/status,不碰 content(文本由 part 事件填充) */
  function upsertMessageMeta(info: any) {
    const opencodeMessageId: string | undefined = info.id
    if (!opencodeMessageId) return
    const role = info.role || 'assistant'
    const completed = info.time?.completed != null
    const status = role === 'user' ? 'completed' : completed ? 'completed' : 'streaming'

    // 用户消息:关联到最后一个 _pending 乐观气泡(刚发送的那条),避免重复气泡
    if (role === 'user') {
      const pendingIdx = messages.value.findIndex(
        (m) => m._pending && m.role === 'user' && !m.opencodeMessageId,
      )
      if (pendingIdx >= 0) {
        messages.value[pendingIdx] = {
          ...messages.value[pendingIdx],
          opencodeMessageId,
          status: 'completed',
          _pending: false,
        }
        return
      }
    }

    const existIdx = messages.value.findIndex((m) => m.opencodeMessageId === opencodeMessageId)
    if (existIdx >= 0) {
      messages.value[existIdx] = { ...messages.value[existIdx], role, status }
    } else {
      // 建壳:content 暂为空,等待 message.part.updated 填充
      messages.value.push({
        id: -Date.now(),
        opencodeMessageId,
        role,
        content: '',
        status,
        createdAt: info.time?.created ? new Date(info.time.created).toISOString() : new Date().toISOString(),
      })
      // assistant 壳出现 → 标记为当前流式目标(思考中状态)
      if (role === 'assistant' && !completed) {
        streamingMessageId.value = opencodeMessageId
      }
    }
  }

  /** 把一段完整文本落到对应消息(以 opencodeMessageId 去重 upsert) */
  function finalizeTextPart(opencodeMessageId: string, text: string, completed: boolean) {
    const existIdx = messages.value.findIndex((m) => m.opencodeMessageId === opencodeMessageId)
    if (existIdx >= 0) {
      messages.value[existIdx] = {
        ...messages.value[existIdx],
        content: text,
        status: completed ? 'completed' : messages.value[existIdx].status,
      }
    } else {
      // 兜底:meta 尚未到达时 part 已到(part 事件不带 role,默认 assistant)
      messages.value.push({
        id: -Date.now(),
        opencodeMessageId,
        role: 'assistant',
        content: text,
        status: completed ? 'completed' : 'streaming',
        createdAt: new Date().toISOString(),
      })
    }

    // 若正是当前流式目标,清空流式气泡(由定稿消息取代)
    // 注意:不在此处把 isStreaming 置 false —— 多步任务中间步的文本也会定稿,
    // 若置 false 会导致思考区在步骤间闪烁折叠。isStreaming 仅由 session.idle 结束。
    if (streamingMessageId.value === opencodeMessageId) {
      streamingText.value = ''
      streamingMessageId.value = null
    }
  }

  /** 工具调用 part 定稿:按 partId 去重 upsert 到对应消息的 parts.toolCalls */
  function upsertToolCall(messageID: string, part: any) {
    const idx = messages.value.findIndex((m) => m.opencodeMessageId === messageID)
    const state = part.state || {}
    const tc: ToolCall = {
      partId: typeof part.id === 'string' ? part.id : undefined,
      tool: typeof part.tool === 'string' ? part.tool : 'tool',
      status: typeof state.status === 'string' ? state.status : 'running',
      input: state.input,
      output: typeof state.output === 'string' ? state.output : undefined,
    }
    if (idx >= 0) {
      const msg = messages.value[idx]
      const parts: StepParts = msg.parts ? { ...msg.parts } : { toolCalls: [] }
      const toolCalls = parts.toolCalls ? [...parts.toolCalls] : []
      const partId = tc.partId
      const existIdx = partId
        ? toolCalls.findIndex((t) => t.partId === partId)
        : -1
      if (existIdx >= 0) {
        toolCalls[existIdx] = { ...toolCalls[existIdx], ...tc }
      } else {
        toolCalls.push(tc)
      }
      parts.toolCalls = toolCalls
      messages.value[idx] = { ...msg, parts }
    } else {
      // 兜底:meta 未到时 tool part 已到 → 建壳
      messages.value.push({
        id: -Date.now(),
        opencodeMessageId: messageID,
        role: 'assistant',
        content: '',
        status: 'streaming',
        createdAt: new Date().toISOString(),
        parts: { toolCalls: [tc] },
      })
    }
  }

  /** 步骤结束 part:写 parts.stepFinish = {reason} */
  function upsertStepFinish(messageID: string, reason: string | undefined) {
    const idx = messages.value.findIndex((m) => m.opencodeMessageId === messageID)
    if (idx < 0) return
    const msg = messages.value[idx]
    const parts: StepParts = msg.parts ? { ...msg.parts } : { toolCalls: [] }
    parts.stepFinish = { reason: reason as any }
    messages.value[idx] = { ...msg, parts }
  }

  /** 找到要挂 pending 状态的目标 assistant 消息 index;找不到则创建新的 assistant 消息壳 */
  function findOrCreateAssistantMessageIndex(toolMessageID: string | undefined): number {
    if (toolMessageID) {
      const idx = messages.value.findIndex((m) => m.opencodeMessageId === toolMessageID)
      if (idx >= 0) return idx
    }
    let idx = -1
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'assistant') {
        idx = i
        break
      }
    }
    if (idx >= 0) return idx
    messages.value.push({
      id: -Date.now(),
      role: 'assistant',
      content: '',
      status: 'streaming',
      createdAt: new Date().toISOString(),
    })
    return messages.value.length - 1
  }

  function attachPendingQuestion(toolMessageID: string | undefined, pq: PendingQuestion) {
    const idx = findOrCreateAssistantMessageIndex(toolMessageID)
    const msg = messages.value[idx]
    const parts: StepParts = msg.parts ? { ...msg.parts } : {}
    parts.pendingQuestion = pq
    messages.value[idx] = { ...msg, parts }
  }

  function clearPendingQuestion(toolMessageID: string | undefined) {
    if (toolMessageID) {
      const idx = messages.value.findIndex((m) => m.opencodeMessageId === toolMessageID)
      if (idx >= 0) {
        const msg = messages.value[idx]
        if (msg.parts?.pendingQuestion) {
          const parts: StepParts = { ...msg.parts, pendingQuestion: { ...msg.parts.pendingQuestion, _replied: true } }
          messages.value[idx] = { ...msg, parts }
          setTimeout(() => {
            const m = messages.value[idx]
            if (m?.parts?.pendingQuestion) {
              messages.value[idx] = { ...m, parts: { ...m.parts, pendingQuestion: null } }
            }
          }, 100)
        }
        return
      }
    }
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const msg = messages.value[i]
      if (msg.role === 'assistant' && msg.parts?.pendingQuestion && !msg.parts.pendingQuestion._replied) {
        const parts: StepParts = { ...msg.parts, pendingQuestion: { ...msg.parts.pendingQuestion, _replied: true } }
        messages.value[i] = { ...msg, parts }
        const capturedIdx = i
        setTimeout(() => {
          const m = messages.value[capturedIdx]
          if (m?.parts?.pendingQuestion) {
            messages.value[capturedIdx] = { ...m, parts: { ...m.parts, pendingQuestion: null } }
          }
        }, 100)
        return
      }
    }
  }

  function attachPendingPermission(toolMessageID: string | undefined, pp: PendingPermission) {
    const idx = findOrCreateAssistantMessageIndex(toolMessageID)
    const msg = messages.value[idx]
    const parts: StepParts = msg.parts ? { ...msg.parts } : {}
    parts.pendingPermission = pp
    messages.value[idx] = { ...msg, parts }
  }

  function clearPendingPermission(toolMessageID: string | undefined) {
    if (toolMessageID) {
      const idx = messages.value.findIndex((m) => m.opencodeMessageId === toolMessageID)
      if (idx >= 0) {
        const msg = messages.value[idx]
        if (msg.parts?.pendingPermission) {
          const parts: StepParts = {
            ...msg.parts,
            pendingPermission: { ...msg.parts.pendingPermission, _replied: true },
          }
          messages.value[idx] = { ...msg, parts }
          setTimeout(() => {
            const m = messages.value[idx]
            if (m?.parts?.pendingPermission) {
              messages.value[idx] = { ...m, parts: { ...m.parts, pendingPermission: null } }
            }
          }, 100)
        }
        return
      }
    }
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const msg = messages.value[i]
      if (msg.role === 'assistant' && msg.parts?.pendingPermission && !msg.parts.pendingPermission._replied) {
        const parts: StepParts = {
          ...msg.parts,
          pendingPermission: { ...msg.parts.pendingPermission, _replied: true },
        }
        messages.value[i] = { ...msg, parts }
        const capturedIdx = i
        setTimeout(() => {
          const m = messages.value[capturedIdx]
          if (m?.parts?.pendingPermission) {
            messages.value[capturedIdx] = { ...m, parts: { ...m.parts, pendingPermission: null } }
          }
        }, 100)
        return
      }
    }
  }

  /** 从后端加载完 messages 后,为已有 pendingQuestion 初始化本地 _replies 字段 */
  function hydratePendingState() {
    for (let i = 0; i < messages.value.length; i++) {
      const msg = messages.value[i]
      if (!msg.parts) continue
      const pq = msg.parts.pendingQuestion
      if (pq && !pq._replied) {
        if (!pq._replies || pq._replies.length !== pq.questions.length) {
          pq._replies = pq.questions.map(() => [])
        }
      }
    }
  }

  /** 从历史消息里提取 role=summary 的 AI 变更总结卡片 */
  function hydrateSummaries() {
    summaries.value = messages.value
      .filter((m) => m.role === 'summary')
      .map((m) => ({
        summary: m.content,
        files: m.summaryFiles || [],
        at: m.createdAt,
        sha: m.summarySha,
        parentSha: m.summaryParentSha,
      }))
  }

  /** 会话空闲:生成结束。兜底落定未提交的流式文本 */
  function finishStreaming() {
    if (streamingMessageId.value && streamingText.value) {
      finalizeTextPart(streamingMessageId.value, streamingText.value, true)
    }
    isStreaming.value = false
    streamStartAt.value = 0
  }

  /** 生成出错:把目标 assistant 消息标记为 error 并展示错误文案;找不到目标时追加一条错误气泡。 */
  function markAssistantError(failedMessageId: string | null, errorMsg: string) {
    let idx = -1
    if (failedMessageId) {
      idx = messages.value.findIndex((m) => m.opencodeMessageId === failedMessageId)
    }
    if (idx < 0) {
      for (let i = messages.value.length - 1; i >= 0; i--) {
        const m = messages.value[i]
        if (m.role === 'assistant' && m.status !== 'completed') {
          idx = i
          break
        }
      }
    }
    if (idx >= 0) {
      messages.value[idx] = { ...messages.value[idx], status: 'error', error: errorMsg }
    } else {
      messages.value.push({
        id: -Date.now(),
        role: 'assistant',
        content: '',
        status: 'error',
        error: errorMsg,
        createdAt: new Date().toISOString(),
      })
    }
  }

  /** 当前会话是否存在未回复的 pending question/permission(AI 真的在等待用户输入)。
   *  <p>
   *  正常情况下 question.asked/permission.asked 会把 isStreaming 置 false,banner 不显示。
   *  此 computed 用于:(1) 兜底检测——若因竞态/事件结构差异导致 isStreaming 未置 false,
   *  仍能据此区分 banner 文案;(2) 让输入区知道当前是否有待答交互。
   *  <p>
   *  仅检查最后一个轮次:历史轮次里残留的 pending(DB 未清干净)早已失效,
   *  不应让 banner/输入区误判为"等待用户回答"。 */
  const hasPendingInteraction = computed(() => {
    const ts = turns.value
    if (!ts.length) return false
    const last = ts[ts.length - 1]
    const steps = [...last.steps]
    if (last.finalAnswer) steps.push(last.finalAnswer)
    return steps.some(s =>
      (s.message.parts?.pendingQuestion && !s.message.parts.pendingQuestion._replied) ||
      (s.message.parts?.pendingPermission && !s.message.parts.pendingPermission._replied),
    )
  })

  /** 当前"明确可知"的 AI 活动,用于状态条展示具体在做什么(而非笼统"无响应")。
   *  <p>
   *  优先:工具仍在执行中(有 status==='running' 的 tool call)→ 正在流式出 token → 其余视为思考。
   *  只有没有任何可观测活动时才返回 thinking;工具执行 30s+ 无 token 是正常的,不应视为卡住。 */
  type RunningActivity =
    | { kind: 'tool'; tool: string; arg: string }
    | { kind: 'streaming' }
    | { kind: 'thinking' }
    | null

  const runningActivity = computed<RunningActivity>(() => {
    if (!isStreaming.value) return null
    const ts = turns.value
    if (ts.length) {
      const last = ts[ts.length - 1]
      const steps = [...last.steps]
      if (last.finalAnswer) steps.push(last.finalAnswer)
      // 从后往前找仍在运行的工具
      for (let i = steps.length - 1; i >= 0; i--) {
        const tcs = steps[i].toolCalls || []
        for (let j = tcs.length - 1; j >= 0; j--) {
          if (tcs[j].status === 'running') {
            return { kind: 'tool', tool: tcs[j].tool, arg: summarizeToolCall(tcs[j]) }
          }
        }
      }
    }
    if (streamingText.value) return { kind: 'streaming' }
    return { kind: 'thinking' }
  })

  /** 本轮生成已运行秒数(用于状态条"已运行 Xs") */
  const streamingElapsedSeconds = computed(() => {
    void _watchdogTick.value
    if (!isStreaming.value || !streamStartAt.value) return 0
    return Math.max(0, Math.floor((Date.now() - streamStartAt.value) / 1000))
  })

  /** "无任何活动"的秒数:isStreaming=true 且 >15s 无 token/事件,且当前没有可解释的忙碌原因。
   *  <p>
   *  不再驱动警告横幅(UI 改为信息式状态条);仅用于:
   *   - 输入框允许"打断后发送"的快捷路径(长时间无响应时);
   *   - 状态条上 >60s 的温和提示"可点停止后重试"。 */
  const streamingStuckSeconds = computed(() => {
    void _watchdogTick.value
    if (!isStreaming.value) return 0
    // 有明确活动(工具执行中/正在出 token)不算"卡住"
    if (runningActivity.value?.kind === 'tool' || streamingText.value) return 0
    const s = Math.floor((Date.now() - lastStreamActivityAt.value) / 1000)
    return s > 15 ? s : 0
  })

  /** 本会话内被 AI 改过的文件路径集合(从变更总结的 files 汇总),供文件树高亮。 */
  const changedFilePaths = computed(() => {
    const set = new Set<string>()
    for (const s of summaries.value) {
      for (const f of s.files || []) set.add(f.path)
    }
    return set as ReadonlySet<string>
  })

  return {
    sessions, agents, currentSessionId, currentSession, messages, turns, flow, summaries, streamingText,
    streamingMessageId, isStreaming, streamingStuckSeconds, streamingElapsedSeconds, runningActivity,
    hasPendingInteraction, connected, changedFilePaths,
    availableModels, selectedProviderId, selectedModelId,
    manualCollapseOverride, setCollapse,
    loadModels, selectModel, loadSessions, loadAgents, createSession, switchAgent, selectSession,
    deleteSession, sendMessage, runCommand, abort, closeStream, forceReleaseStreaming, resumeStreaming,
  }
})
