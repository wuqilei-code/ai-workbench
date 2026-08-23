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
  ProviderVo,
  StepParts,
  ToolCall,
  PendingPermission,
  ChangeSummary,
} from '@/types'

interface StreamHandle { close: () => void }

/**
 * 从首条用户消息派生会话标题:剥离附件块、压缩空白、截断长度。
 */
function deriveTitle(text: string): string {
  let t = text.replace(/📎[\s\S]*$/, '').trim()
  t = t.replace(/^\[附件\]\s*\S+$/gm, '').trim()
  t = t.replace(/\s+/g, ' ').trim()
  if (t) return t.length > 30 ? t.slice(0, 30) + '…' : t
  const m = text.match(/\[附件\]\s*(\S+)/)
  if (m) {
    const name = m[1].split(/[\\/]/).pop() || m[1]
    return name.length > 30 ? name.slice(0, 30) + '…' : name
  }
  return ''
}

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<SessionVo[]>([])
  const archivedSessions = ref<SessionVo[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<MessageVo[]>([])
  const MESSAGE_PAGE = 60
  const hasOlder = ref(false)
  const loadingOlder = ref(false)
  const olderTopId = ref<number>(0)
  /** AI 变更总结(deepagents 轨道不再产生,恒为空) */
  const summaries = ref<ChangeSummary[]>([])

  const pendingFill = ref('')
  function fillInput(prompt: string) {
    pendingFill.value = prompt
  }
  const pendingAttachPath = ref('')
  function attachFile(path: string) {
    pendingAttachPath.value = path
  }

  const streamingText = ref('')
  const streamingMessageId = ref<string | null>(null)
  const isStreaming = ref(false)
  /** agent 调用浏览器(browser_*)工具的信号:每次自增,供 UI 自动唤出浏览器面板 */
  const browserToolSignal = ref(0)
  /** 最近一次 browser_* 工具调用时间戳(ms),UI 据此判断「agent 正在操作浏览器」 */
  const lastBrowserToolAt = ref(0)
  const connected = ref(false)
  const reconnecting = ref(false)
  const authExpired = ref(false)
  const interruptedByDisconnect = ref(false)
  const disconnectAborted = ref(false)
  let _disconnectAbortTimer: ReturnType<typeof setTimeout> | null = null
  let _interruptedMsgId: string | null = null
  let _interruptedText = ''
  const switching = ref(false)
  const reconnectCount = ref(0)
  const lastEventAt = ref(Date.now())
  let _heartbeatTimer: ReturnType<typeof setInterval> | null = null

  const lastStreamActivityAt = ref<number>(Date.now())
  const streamStartAt = ref<number>(0)
  const _watchdogTick = ref<number>(0)
  let _watchdogTimer: ReturnType<typeof setInterval> | null = null
  function touchStreamActivity() { lastStreamActivityAt.value = Date.now() }
  function startWatchdog() {
    if (_watchdogTimer) return
    _watchdogTimer = setInterval(() => { _watchdogTick.value++ }, 5_000)
  }
  function stopWatchdog() {
    if (_watchdogTimer) { clearInterval(_watchdogTimer); _watchdogTimer = null }
  }
  async function forceReleaseStreaming() {
    try { if (currentSessionId.value) await abort() } catch (_) {}
    finishStreaming()
  }
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

  const turns = computed(() => buildTurns(messages.value.filter((m) => !isHiddenMessage(m))))
  const flow = computed(() => buildFlow(turns.value, summaries.value))

  const manualCollapseOverride = ref<Record<string, boolean>>({})
  function setCollapse(turnId: string, expanded: boolean) {
    manualCollapseOverride.value = { ...manualCollapseOverride.value, [turnId]: expanded }
  }

  let stream: StreamHandle | null = null
  let _manualClose = false
  let _loadSeq = 0

  // ---------- 模型 ----------

  async function loadModels() {
    try {
      availableModels.value = await modelApi.available()
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

  async function loadArchivedSessions() {
    try {
      archivedSessions.value = await chatApi.listArchivedSessions()
    } catch (e) {
      archivedSessions.value = []
    }
  }

  const searchResults = ref<SessionVo[]>([])
  const searching = ref(false)
  let _searchTimer: ReturnType<typeof setTimeout> | null = null
  function searchSessions(q: string) {
    if (_searchTimer) clearTimeout(_searchTimer)
    const query = q.trim()
    if (!query) {
      searchResults.value = []
      searching.value = false
      return
    }
    searching.value = true
    _searchTimer = setTimeout(async () => {
      try {
        searchResults.value = await chatApi.searchSessions(query)
      } catch (e) {
        searchResults.value = []
      } finally {
        searching.value = false
      }
    }, 300)
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

  async function selectSession(id: number) {
    if (currentSessionId.value === id && stream) return
    const seq = ++_loadSeq
    closeStream()
    currentSessionId.value = id
    messages.value = []
    summaries.value = []
    streamingText.value = ''
    streamingMessageId.value = null
    isStreaming.value = false
    authExpired.value = false
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''
    switching.value = true
    manualCollapseOverride.value = {}
    try {
      const list = await chatApi.listMessages(id, { limit: MESSAGE_PAGE })
      if (seq !== _loadSeq) return
      messages.value = list
      const minId = list.length ? Math.min(...list.map((m) => m.id ?? 0)) : 0
      olderTopId.value = minId
      hasOlder.value = list.length >= MESSAGE_PAGE && minId > 0
      loadingOlder.value = false
    } catch (e) {
      if (seq !== _loadSeq) return
      messages.value = []
      hasOlder.value = false
      loadingOlder.value = false
    } finally {
      if (seq === _loadSeq) {
        setTimeout(() => { if (seq === _loadSeq) switching.value = false }, 300)
      }
    }
    subscribe()
    startWatchdog()
  }

  async function loadOlderMessages() {
    if (!currentSessionId.value || loadingOlder.value || !hasOlder.value) return
    loadingOlder.value = true
    try {
      const list = await chatApi.listMessages(currentSessionId.value, {
        limit: MESSAGE_PAGE,
        beforeId: olderTopId.value || undefined,
      })
      if (list.length === 0) {
        hasOlder.value = false
        return
      }
      const ids = new Set(messages.value.map((m) => m.agentMessageId).filter(Boolean))
      const fresh = list.filter((m) => !m.agentMessageId || !ids.has(m.agentMessageId))
      messages.value = [...fresh, ...messages.value]
      olderTopId.value = list[0].id ?? olderTopId.value
      hasOlder.value = list.length >= MESSAGE_PAGE
    } catch (e) {
      console.warn('[chat] load older messages failed', e)
    } finally {
      loadingOlder.value = false
    }
  }

  async function deleteSession(id: number) {
    await chatApi.deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    archivedSessions.value = archivedSessions.value.filter((s) => s.id !== id)
    if (currentSessionId.value === id) {
      closeStream()
      currentSessionId.value = null
      messages.value = []
    }
  }

  async function importSession(jsonText: string) {
    const s = await chatApi.importSession(jsonText)
    sessions.value.unshift(s)
    return s
  }

  async function pinSession(id: number, pinned: boolean) {
    const updated = await chatApi.updateSession(id, { pinned: pinned ? 1 : 0 })
    const idx = sessions.value.findIndex((s) => s.id === id)
    if (idx >= 0) sessions.value[idx] = updated
    sessions.value = [...sessions.value].sort((a, b) => {
      if ((a.pinned || 0) !== (b.pinned || 0)) return (b.pinned || 0) - (a.pinned || 0)
      return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
    })
    return updated
  }

  async function archiveSession(id: number) {
    const updated = await chatApi.updateSession(id, { archived: 1 })
    sessions.value = sessions.value.filter((s) => s.id !== id)
    archivedSessions.value.unshift(updated)
    return updated
  }

  async function unarchiveSession(id: number) {
    const updated = await chatApi.updateSession(id, { archived: 0 })
    archivedSessions.value = archivedSessions.value.filter((s) => s.id !== id)
    sessions.value.unshift(updated)
    return updated
  }

  async function renameSession(id: number, title: string) {
    const updated = await chatApi.updateSession(id, { title })
    const idx = sessions.value.findIndex((s) => s.id === id)
    if (idx >= 0) sessions.value[idx] = updated
    const aIdx = archivedSessions.value.findIndex((s) => s.id === id)
    if (aIdx >= 0) archivedSessions.value[aIdx] = updated
    return updated
  }

  async function clearSession(id: number) {
    await chatApi.clearMessages(id)
    if (currentSessionId.value === id) {
      messages.value = []
      summaries.value = []
      streamingText.value = ''
    }
  }

  async function exportSession(id: number, format: 'md' | 'json' | 'pdf' | 'docx' = 'md') {
    const res: any = await chatApi.exportSession(id, format)
    const blob = res?.data instanceof Blob ? res.data : new Blob([res.data], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    const s = currentSession ? currentSession.title : `会话${id}`
    const safe = (s || `session-${id}`).replace(/[\\/:*?"<>|]/g, '_').slice(0, 60)
    a.href = url
    a.download = `${safe}.${format}`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  /** 总结指定会话:拉取消息→拼摘要提示词→新开会话并发总结请求。 */
  async function summarizeSession(id: number, title: string): Promise<SessionVo> {
    const msgs = await chatApi.listMessages(id, { limit: 500 })
    const lines: string[] = []
    for (const m of msgs) {
      if (m.role !== 'user' && m.role !== 'assistant') continue
      const who = m.role === 'user' ? '用户' : 'AI'
      const content = (m.content || '').trim()
      if (!content) continue
      lines.push(`${who}:${content}`)
    }
    if (!lines.length) throw new Error('该会话暂无内容可总结')
    const transcript = lines.join('\n').slice(-60000)
    const prompt = [
      '请总结以下这段对话,输出三部分:',
      '1. 对话主题:本次对话围绕什么问题展开',
      '2. 关键结论:达成的结论、产出的结果或决定',
      '3. 待办/建议:尚待跟进的事项或后续建议',
      '',
      '--- 对话内容 ---',
      transcript,
      '',
      '请用 Markdown 分节输出,简洁清晰。',
    ].join('\n')
    const s = await createSession(`总结 · ${title.slice(0, 40)}`)
    await sendMessage(prompt)
    return s
  }

  // ---------- 发消息 + SSE ----------

  async function reconcileAfterReconnect() {
    if (!currentSessionId.value) return
    try {
      const fresh = (await chatApi.listMessages(currentSessionId.value, { limit: MESSAGE_PAGE })) as MessageVo[]
      const freshMinId = fresh.length ? Math.min(...fresh.map((m) => m.id ?? 0)) : 0
      const freshAgentIds = new Set(fresh.map((m) => m.agentMessageId).filter(Boolean))
      const freshLocalIds = new Set(fresh.map((m) => m.id))
      const localOlder = messages.value.filter(
        (m) => (m.id != null && m.id > 0 && m.id < freshMinId) && !freshLocalIds.has(m.id),
      )
      const localNewer = messages.value.filter((m) => {
        if (freshLocalIds.has(m.id)) return false
        if (m.agentMessageId != null && freshAgentIds.has(m.agentMessageId)) return false
        // 乐观 user 气泡与服务端已落库的同文消息互斥,避免断线对账后重复
        if (m.role === 'user' && !m.agentMessageId
          && fresh.some((f) => f.role === 'user' && f.content === m.content)) return false
        return m._pending || (m.id != null && m.id < 0) || !m.agentMessageId
      })
      messages.value = [...localOlder, ...fresh, ...localNewer]
      olderTopId.value = localOlder.length ? Math.min(...localOlder.map((m) => m.id ?? 0)) : freshMinId
      const anyStreaming = fresh.some((m) => m.status === 'streaming')
      if (isStreaming.value && !anyStreaming) {
        if (interruptedByDisconnect.value) abortStreamOnDisconnect()
        else finishStreaming()
      } else if (interruptedByDisconnect.value) {
        interruptedByDisconnect.value = false
        if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
      }
    } catch (e) {
      console.warn('[chat] reconcile after reconnect failed', e)
    }
  }

  function subscribe() {
    if (!currentSessionId.value) return
    const auth = useAuthStore()
    _manualClose = false
    connected.value = false
    reconnecting.value = false
    lastEventAt.value = Date.now()
    const handlers: SseHandlers = {
      onOpen: () => {
        connected.value = true
        reconnecting.value = false
        lastEventAt.value = Date.now()
        if (reconnectCount.value > 0) reconcileAfterReconnect()
      },
      onMessage: (type, data) => {
        lastEventAt.value = Date.now()
        handleEvent(type, data)
      },
      onError: () => {
        if (_manualClose) return
        connected.value = false
        reconnecting.value = true
        reconnectCount.value++
        if (isStreaming.value && !interruptedByDisconnect.value) {
          interruptedByDisconnect.value = true
          _interruptedMsgId = streamingMessageId.value
          _interruptedText = streamingText.value
          _disconnectAbortTimer = setTimeout(() => {
            _disconnectAbortTimer = null
            abortStreamOnDisconnect()
          }, 180_000)
        }
      },
      onAuthError: () => {
        authExpired.value = true
        reconnectCount.value = 0
        interruptedByDisconnect.value = false
        if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
        closeStream()
      },
    }
    stream = subscribeSession(currentSessionId.value, auth.token, handlers)
    startHeartbeat()
  }

  function closeStream() {
    _manualClose = true
    if (stream) { stream.close(); stream = null }
    connected.value = false
    stopHeartbeat()
    stopWatchdog()
  }

  function startHeartbeat() {
    stopHeartbeat()
    _heartbeatTimer = setInterval(() => {
      if (isStreaming.value && connected.value && Date.now() - lastEventAt.value > 45_000) {
        console.warn('[chat] heartbeat: no SSE event for 45s while streaming, force reconnect')
        reconnectCount.value++
        forceReconnect()
      }
    }, 10_000)
  }
  function stopHeartbeat() {
    if (_heartbeatTimer) { clearInterval(_heartbeatTimer); _heartbeatTimer = null }
  }

  function forceReconnect() {
    closeStream()
    subscribe()
  }

  async function sendMessage(text: string) {
    if (!currentSessionId.value || !text.trim() || isStreaming.value) return
    const isFirstMessage = messages.value.length === 0
    const optimistic: MessageVo = {
      id: -Date.now(),
      role: 'user',
      content: text,
      status: 'pending',
      _pending: true,
      createdAt: new Date().toISOString(),
    }
    messages.value.push(optimistic)
    streamingText.value = ''
    streamingMessageId.value = null
    isStreaming.value = true
    touchStreamActivity()
    streamStartAt.value = Date.now()
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''

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
      // POST 返回即代表用户消息已落库:解除本地气泡「发送中…」
      const idx = messages.value.findIndex((m) => m.id === optimistic.id)
      if (idx >= 0) messages.value[idx] = { ...messages.value[idx], _pending: false, status: 'completed' }
    } catch (e: any) {
      isStreaming.value = false
      const idx = messages.value.findIndex((m) => m.id === optimistic.id)
      if (idx >= 0) {
        messages.value[idx] = { ...messages.value[idx], _pending: false, status: 'error', error: '发送失败,请重试' }
      }
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
    reconcileAfterReconnect()
  }

  /**
   * 处理 deepagents 原生事件(经 AgentStreamBridge 原样直通 + 网关补帧)。
   * <p>
   * 事件契约:
   *   - start          {threadId} 一轮开始
   *   - message.start  {id}       本轮 assistant 消息(网关补充)
   *   - delta          {id, content}
   *   - tool           {id, name, args}  工具调用开始(running)
   *   - tool_result    {id, name, output} 工具完成
   *   - message.end    {id, status: completed|error|aborted}(网关补充)
   *   - error          {message}
   *   - done           {} 一轮结束
   */
  function handleEvent(type: string, data: any) {
    console.debug('[chat] SSE event:', type, 'isStreaming=', isStreaming.value,
      'streamingMsgId=', streamingMessageId.value, 'data=', data)
    switch (type) {
      case 'open':
        connected.value = true
        break

      case 'start': {
        isStreaming.value = true
        if (!streamStartAt.value) streamStartAt.value = Date.now()
        touchStreamActivity()
        break
      }

      case 'message.start': {
        const id: string = data.id
        if (id) {
          ensureAssistantShell(id)
          streamingMessageId.value = id
          streamingText.value = ''
        }
        touchStreamActivity()
        break
      }

      case 'delta': {
        if (isStreaming.value) touchStreamActivity()
        const id: string = data.id
        const content: string = data.content || ''
        if (!content) break
        if (id && id !== streamingMessageId.value) {
          ensureAssistantShell(id)
          streamingMessageId.value = id
          streamingText.value = ''
        }
        appendLiveTimeline('text', content)
        streamingText.value += content
        isStreaming.value = true
        break
      }

      case 'reason': {
        if (isStreaming.value) touchStreamActivity()
        const rc: string = data.content || ''
        if (!rc) break
        // 镜像进实时消息时间线,与工具/文本一起按执行顺序渲染(落库结构一致)
        appendLiveTimeline('reason', rc)
        isStreaming.value = true
        break
      }

      case 'tool': {
        isStreaming.value = true
        touchStreamActivity()
        const tc: ToolCall = {
          id: typeof data.id === 'string' ? data.id : undefined,
          name: typeof data.name === 'string' ? data.name : 'tool',
          args: data.args,
          status: 'running',
        }
        if (tc.name.startsWith('browser')) {
          browserToolSignal.value++
          lastBrowserToolAt.value = Date.now()
        }
        appendLiveTimeline('tool', undefined, tc.id)
        attachToolCall(tc)
        break
      }

      case 'tool_result': {
        isStreaming.value = true
        touchStreamActivity()
        resolveToolCall(typeof data.id === 'string' ? data.id : undefined, data.output != null ? String(data.output) : data.name)
        break
      }

      case 'message.end': {
        const id: string = data.id
        const status: string = data.status
        if (status === 'error') {
          markAssistantError(id, '本轮生成出错,请重试')
          streamingText.value = ''
          streamingMessageId.value = null
        } else {
          // completed / aborted:把流式文本定稿到该消息
          if (id && id === streamingMessageId.value && streamingText.value) {
            finalizeTextPart(id, streamingText.value, status === 'completed')
          }
          if (id) {
            const idx = messages.value.findIndex((m) => m.agentMessageId === id)
            if (idx >= 0 && messages.value[idx].status !== 'error') {
              messages.value[idx] = { ...messages.value[idx], status: status === 'completed' ? 'completed' : 'aborted' }
            }
          }
        }
        break
      }

      // 人工确认(HITL):危险操作待用户点击允许/拒绝;生成暂停等待
      case 'permission.asked': {
        const requestId: string = data.id
        if (!requestId) break
        suspendForPermission({
          requestId,
          tool: {
            name: data.name || 'tool',
            args: data.args,
            description: data.description || '',
          },
          allowed: data.allowed || ['approve', 'reject'],
          _replied: false,
        })
        break
      }

      case 'done':
        finishStreaming()
        break

      case 'error': {
        const msg = data.message || '生成出错,请重试'
        markAssistantError(streamingMessageId.value, msg)
        streamingText.value = ''
        streamingMessageId.value = null
        finishStreaming()
        break
      }
    }
  }

  /** 压缩产生的总结消息不展示(deepagents 恒 false;保留兼容历史 opencode 数据) */
  function isHiddenMessage(m: { compactionSummary?: boolean; role?: string; content?: string }): boolean {
    const text = (m.content || '').trim()
    if (m.role === 'user' && text) return false
    if (m.compactionSummary) return true
    if (m.role === 'user' && !text) return true
    if (text.startsWith('Continue if you have next steps')) return true
    return false
  }

  /** 建 assistant 消息壳(message.start 或 delta 先行到达时) */
  function ensureAssistantShell(id: string) {
    if (messages.value.find((m) => m.agentMessageId === id)) return
    messages.value.push({
      id: -Date.now(),
      agentMessageId: id,
      role: 'assistant',
      content: '',
      status: 'streaming',
      createdAt: new Date().toISOString(),
      parts: { toolCalls: [] },
      _streaming: true,
    })
  }

  /** 追加实时时间线条目(reason/text 合并同类尾段,tool 记 id),与网关落库结构一致 */
  function appendLiveTimeline(k: 'reason' | 'text' | 'tool', v?: string, toolId?: string) {
    const targetId = streamingMessageId.value
    let idx = targetId ? messages.value.findIndex((m) => m.agentMessageId === targetId) : -1
    if (idx < 0 && messages.value.length && messages.value[messages.value.length - 1].role === 'assistant') {
      idx = messages.value.length - 1
    }
    if (idx < 0) return
    const msg = messages.value[idx]
    const tl: { k: string; v?: string; id?: string }[] = Array.isArray((msg.parts as any)?.timeline)
      ? [...((msg.parts as any).timeline as unknown[])]
      : []
    const last = tl.length ? (tl[tl.length - 1] as { k: string; v?: string }) : null
    if ((k === 'reason' || k === 'text') && last && last.k === k) {
      tl[tl.length - 1] = { k, v: (last.v || '') + (v || '') }
    } else if (k === 'tool') {
      if (!toolId) return
      tl.push({ k, id: toolId })
    } else {
      tl.push({ k, v: v || '' })
    }
    messages.value[idx] = { ...msg, parts: { ...(msg.parts || {}), timeline: tl } }
  }

  /** 把流式文本落定到目标 assistant 消息 */
  function finalizeTextPart(id: string, text: string, completed: boolean) {    const idx = messages.value.findIndex((m) => m.agentMessageId === id)
    if (idx >= 0) {
      messages.value[idx] = {
        ...messages.value[idx],
        content: text,
        status: completed ? 'completed' : messages.value[idx].status,
        _streaming: completed ? false : messages.value[idx]._streaming,
      }
      if (completed && streamingMessageId.value === id) {
        streamingMessageId.value = null
      }
    } else {
      messages.value.push({
        id: -Date.now(),
        agentMessageId: id,
        role: 'assistant',
        content: text,
        status: completed ? 'completed' : 'streaming',
        createdAt: new Date().toISOString(),
        parts: { toolCalls: [] },
      })
    }
  }

  /** 把 running 工具调用挂到当前 assistant 消息的 parts.toolCalls */
  function attachToolCall(tc: ToolCall) {
    const targetId = streamingMessageId.value
    let idx = targetId ? messages.value.findIndex((m) => m.agentMessageId === targetId) : -1
    if (idx < 0) {
      for (let i = messages.value.length - 1; i >= 0; i--) {
        if (messages.value[i].role === 'assistant') { idx = i; break }
      }
    }
    if (idx < 0) {
      ensureAssistantShell(targetId || 'da-msg-' + Date.now())
      idx = messages.value.length - 1
    }
    const msg = messages.value[idx]
    const parts: StepParts = msg.parts ? { ...msg.parts } : { toolCalls: [] }
    const toolCalls = parts.toolCalls ? [...parts.toolCalls] : []
    const existIdx = tc.id ? toolCalls.findIndex((t) => t.id === tc.id) : -1
    if (existIdx >= 0) toolCalls[existIdx] = { ...toolCalls[existIdx], ...tc }
    else toolCalls.push(tc)
    parts.toolCalls = toolCalls
    messages.value[idx] = { ...msg, parts }
  }

  /** 把 tool_result 配对到 running 工具调用,标记完成并写入输出 */
  function resolveToolCall(id: string | undefined, output: string) {
    let idx = -1
    let tcIdx = -1
    if (id) {
      for (let i = messages.value.length - 1; i >= 0; i--) {
        const list = messages.value[i].parts?.toolCalls || []
        const j = list.findIndex((t) => t.id === id)
        if (j >= 0) { idx = i; tcIdx = j; break }
      }
    }
    if (idx < 0) {
      // 兼容缺 id:配到最后一个 running 工具
      for (let i = messages.value.length - 1; i >= 0; i--) {
        const list = messages.value[i].parts?.toolCalls || []
        for (let j = list.length - 1; j >= 0; j--) {
          if (list[j].status === 'running') { idx = i; tcIdx = j; break }
        }
        if (idx >= 0) break
      }
    }
    if (idx < 0 || tcIdx < 0) return
    const msg = messages.value[idx]
    const toolCalls = (msg.parts?.toolCalls ? [...msg.parts.toolCalls] : [])
    toolCalls[tcIdx] = { ...toolCalls[tcIdx], status: 'completed', output }
    messages.value[idx] = { ...msg, parts: { ...(msg.parts || {}), toolCalls } }
  }

  /** 生成出错:把目标 assistant 消息标记为 error;找不到则追加错误气泡 */
  function markAssistantError(failedMessageId: string | null, errorMsg: string) {
    let idx = -1
    if (failedMessageId) {
      idx = messages.value.findIndex((m) => m.agentMessageId === failedMessageId)
    }
    if (idx < 0) {
      for (let i = messages.value.length - 1; i >= 0; i--) {
        const m = messages.value[i]
        if (m.role === 'assistant' && m.status !== 'completed') { idx = i; break }
      }
    }
    if (idx >= 0) {
      messages.value[idx] = { ...messages.value[idx], status: 'error', error: errorMsg, _streaming: false }
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

  /** 会话空闲:生成结束,兜底落定未提交的流式文本 */
  function finishStreaming() {
    if (streamingMessageId.value && streamingText.value) {
      finalizeTextPart(streamingMessageId.value, streamingText.value, true)
    }
    // 兜底:清掉仍处于「发送中…」的用户气泡(正常已在 POST 返回后解除)
    for (let i = 0; i < messages.value.length; i++) {
      const m = messages.value[i]
      if (m.role === 'user' && m._pending) {
        messages.value[i] = { ...m, _pending: false, status: 'completed' }
      }
    }
    isStreaming.value = false
    streamStartAt.value = 0
  }

  // ---------- HITL 人工确认(危险操作) ----------

  /** 当前会话是否有待确认的权限请求(取最后一条带 pendingPermission 的 assistant 消息) */
  const pendingPermission = computed<PendingPermission | null>(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const pp = messages.value[i].parts?.pendingPermission
      if (pp && !pp._replied) return pp
    }
    return null
  })

  /** 挂起生成等待人工确认:把待确认请求挂到当前 assistant 消息,暂停流式态 */
  function suspendForPermission(pp: PendingPermission) {
    // 挂到当前助理消息(与流式目标一致;找不到则挂到最后一条 assistant)
    let idx = -1
    if (streamingMessageId.value) {
      idx = messages.value.findIndex((m) => m.agentMessageId === streamingMessageId.value)
    }
    if (idx < 0) {
      for (let i = messages.value.length - 1; i >= 0; i--) {
        if (messages.value[i].role === 'assistant') { idx = i; break }
      }
    }
    if (idx >= 0) {
      const msg = messages.value[idx]
      const parts: StepParts = msg.parts ? { ...msg.parts } : {}
      parts.pendingPermission = { ...pp, _replied: false }
      messages.value[idx] = { ...msg, parts }
    }
    isStreaming.value = false
    touchStreamActivity()
  }

  /** 用户处置确认:批准/拒绝 → 后端桥续跑被中断的生成;失败向上抛由 UI 提示 */
  async function replyPermission(requestId: string, decision: 'approve' | 'reject') {
    if (!currentSessionId.value) return false
    const pp = pendingPermission.value
    if (pp && pp.requestId === requestId) {
      pp._replied = true
    }
    const ok = await chatApi.replyPermission(currentSessionId.value, requestId, decision)
    // 后端可能仍在会话上等待 → 立即恢复流式态;真实事件到来后自然续播
    resumeStreaming()
    return ok
  }

  async function abortStreamOnDisconnect() {
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    if (!interruptedByDisconnect.value) return
    interruptedByDisconnect.value = false
    disconnectAborted.value = true
    try { if (currentSessionId.value) await chatApi.abort(currentSessionId.value) } catch (_) {}
    if (_interruptedMsgId && _interruptedText) {
      finalizeTextPart(_interruptedMsgId, _interruptedText, true)
    }
    markAssistantError(_interruptedMsgId, '连接已断开，本轮回答已中止。请检查网络后重新提问。')
    isStreaming.value = false
    streamStartAt.value = 0
    streamingText.value = ''
    streamingMessageId.value = null
    _interruptedMsgId = null
    _interruptedText = ''
  }

  /** 关闭断线兜底横幅 */
  function dismissDisconnectAbort() {
    disconnectAborted.value = false
  }

  /** 当前会话是否存在待处理交互(HITL 权限确认等):让输入区/横幅准确定位"AI 在等人" */
  const hasPendingInteraction = computed(() => pendingPermission.value != null)

  type RunningActivity =
    | { kind: 'tool'; tool: string; arg: string }
    | { kind: 'streaming' }
    | { kind: 'thinking' }
    | null

  const runningActivity = computed<RunningActivity>(() => {
    if (!isStreaming.value) return null
    const ts = turns.value
    if (ts.length) {
      const lastT = ts[ts.length - 1]
      const steps = [...lastT.steps]
      if (lastT.finalAnswer) steps.push(lastT.finalAnswer)
      for (let i = steps.length - 1; i >= 0; i--) {
        const tcs = steps[i].toolCalls || []
        for (let j = tcs.length - 1; j >= 0; j--) {
          if (tcs[j].status === 'running') {
            return { kind: 'tool', tool: tcs[j].name, arg: summarizeToolCall(tcs[j]) }
          }
        }
      }
    }
    if (streamingText.value) return { kind: 'streaming' }
    return { kind: 'thinking' }
  })

  const streamingElapsedSeconds = computed(() => {
    void _watchdogTick.value
    if (!isStreaming.value || !streamStartAt.value) return 0
    return Math.max(0, Math.floor((Date.now() - streamStartAt.value) / 1000))
  })

  const streamingStuckSeconds = computed(() => {
    void _watchdogTick.value
    if (!isStreaming.value) return 0
    if (runningActivity.value?.kind === 'tool' || streamingText.value) return 0
    const s = Math.floor((Date.now() - lastStreamActivityAt.value) / 1000)
    return s > 15 ? s : 0
  })

  /** 本会话内被 AI 改过的文件路径集合(deepagents 无变更总结,恒空) */
  const changedFilePaths = computed(() => new Set<string>())

  return {
    sessions, archivedSessions, currentSessionId, currentSession, messages, turns, flow, summaries,
    streamingText, streamingMessageId, isStreaming, streamingStuckSeconds, streamingElapsedSeconds, runningActivity,
    browserToolSignal, lastBrowserToolAt,
    hasPendingInteraction, connected, reconnecting, forceReconnect,
    authExpired, switching, changedFilePaths,
    disconnectAborted, dismissDisconnectAbort,
    hasOlder, loadingOlder, loadOlderMessages,
    searchResults, searching, searchSessions,
    availableModels, selectedProviderId, selectedModelId,
    pendingFill, fillInput, pendingAttachPath, attachFile,
    manualCollapseOverride, setCollapse,
    loadModels, selectModel, loadSessions, loadArchivedSessions, createSession, selectSession,
    deleteSession, pinSession, archiveSession, unarchiveSession, renameSession, clearSession, exportSession,
    importSession,
    summarizeSession,
    sendMessage, abort, closeStream, forceReleaseStreaming, resumeStreaming,
    pendingPermission, replyPermission,
    reconcileAfterReconnect,
  }
})