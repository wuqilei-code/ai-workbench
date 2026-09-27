import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { chatApi } from '@/api/chat'
import { modelApi } from '@/api/admin'
import { subscribeSession, type SseHandlers } from '@/api/sse'
import { useAuthStore } from './auth'
import { i18n } from '@/i18n'

/** store 内(非组件上下文)取翻译函数;`t` 已被 sanitize() 用作局部变量,故用 `tr`。 */
const tr = (key: string) => i18n.global.t(key)
import { buildTurns, buildFlow, summarizeToolCall } from '@/utils/turns'
import type {
  SessionVo,
  MessageVo,
  ProviderVo,
  StepParts,
  ToolCall,
  PendingPermission,
} from '@/types'

interface StreamHandle { close: () => void }

/** 流式时间线调试日志:默认关闭;控制台执行 `localStorage.setItem('aiwb_debug','1')` 后刷新可开启。 */
const DEBUG_TIMELINE = typeof localStorage !== 'undefined' && localStorage.getItem('aiwb_debug') === '1'
function dbg(...args: unknown[]) {
  if (DEBUG_TIMELINE) dbg(...args)
}

/**
 * 从首条用户消息派生会话标题:剥离附件块、压缩空白、截断长度。
 */
function deriveTitle(text: string): string {
  let t = text.replace(/📎[\s\S]*$/, '').trim()
  t = t.replace(/^\[附件\]\s*\S+$/gm, '').trim()
  t = t.replace(/^\[技能:.*\]$/gm, '').trim()
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
  /** 草稿态:已进入欢迎页但尚未创建会话,首次发送消息时才创建 */
  const draft = ref(false)
  const messages = ref<MessageVo[]>([])
  const MESSAGE_PAGE = 60
  const hasOlder = ref(false)
  const loadingOlder = ref(false)
  const olderTopId = ref<number>(0)

  const pendingFill = ref('')
  function fillInput(prompt: string) {
    pendingFill.value = prompt
  }
  const pendingAttachPath = ref('')
  /** 与 pendingAttachPath 搭配的提示词(如「做同款」附件+回填);为空则用默认文件提示词 */
  const pendingAttachPrompt = ref('')
  /** 挂载该附件前,先移除同前缀的旧附件(如「做同款」重复点击只保留最新模板) */
  const pendingAttachReplace = ref('')
  function attachFile(path: string, prompt = '', replacePrefix = '') {
    pendingAttachPath.value = path
    pendingAttachPrompt.value = prompt
    pendingAttachReplace.value = replacePrefix
  }

  const streamingText = ref('')
  /** 正在流式接收的推理段摘要(对应 reason 事件):实时累积,避免把 reasoning 逐个写进
   *  timeline 触发 buildTurns 全量重算导致的"空白很久后内容一次性出现"。仅在与文本/工具
   *  的边界处才定稿合并进时间线,保底刷新/断线恢复后的历史重建。 */
  const liveReasoning = ref('')
  const streamingMessageId = ref<string | null>(null)
  const isStreaming = ref(false)
  /** 上下文用量(deepagents 同口径,后端 usage 事件回传):tokens=占用,limit=压缩阈值。
   *  切换会话/刷新后从最后一条 assistant 消息的 parts 恢复;无数据时为 null(前端回退字符估算)。 */
  const contextUsage = ref<{ tokens: number; limit: number | null } | null>(null)
  /** 正在流式生成的 dynamic-ui 组件(pure_show_widget 参数逐块到达,实时累积) */
  const streamingWidgetCode = ref<{ id: string; code: string } | null>(null)
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
  /** SSE 会话代际计数器:每次切换会话时递增,用于过滤旧会话的残留事件,
   *  防止 A 会话的 SSE delta/tool 事件污染 B 会话的消息列表。 */
  let _sseGeneration = 0
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
  const flow = computed(() => buildFlow(turns.value))

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

  /** 会话列表分页:每页 SESSIONS_PAGE 条,滚动到底由侧边栏的 IntersectionObserver 自动续页。 */
  const SESSIONS_PAGE = 20
  const sessionsHasMore = ref(false)
  const sessionsLoadingMore = ref(false)

  async function loadSessions() {
    const list = await chatApi.listSessions(SESSIONS_PAGE, 0)
    sessions.value = list
    sessionsHasMore.value = list.length >= SESSIONS_PAGE
  }

  /** 追加下一页会话(侧边栏滚动到底自动触发)。 */
  async function loadMoreSessions() {
    if (sessionsLoadingMore.value || !sessionsHasMore.value) return
    sessionsLoadingMore.value = true
    try {
      const list = await chatApi.listSessions(SESSIONS_PAGE, sessions.value.length)
      const seen = new Set(sessions.value.map((s) => s.id))
      let added = 0
      for (const s of list) if (!seen.has(s.id)) { sessions.value.push(s); added++ }
      // 无进展(整页都是重复项,如排序在两次请求间变动)就停止,否则 offset 不前进会反复取同一页
      sessionsHasMore.value = added > 0 && list.length >= SESSIONS_PAGE
    } finally {
      sessionsLoadingMore.value = false
    }
  }

  /** 归档列表同样分页(此前不传 limit,后端会全量返回,归档多了就是慢查询)。 */
  const archivedHasMore = ref(false)
  const archivedLoadingMore = ref(false)

  async function loadArchivedSessions() {
    try {
      const list = await chatApi.listArchivedSessions(SESSIONS_PAGE, 0)
      archivedSessions.value = list
      archivedHasMore.value = list.length >= SESSIONS_PAGE
    } catch (e) {
      archivedSessions.value = []
      archivedHasMore.value = false
    }
  }

  async function loadMoreArchived() {
    if (archivedLoadingMore.value || !archivedHasMore.value) return
    archivedLoadingMore.value = true
    try {
      const list = await chatApi.listArchivedSessions(SESSIONS_PAGE, archivedSessions.value.length)
      const seen = new Set(archivedSessions.value.map((s) => s.id))
      let added = 0
      for (const s of list) if (!seen.has(s.id)) { archivedSessions.value.push(s); added++ }
      archivedHasMore.value = added > 0 && list.length >= SESSIONS_PAGE
    } finally {
      archivedLoadingMore.value = false
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

  /** 进入草稿态(欢迎页):不创建会话,首次发送消息时才落库 */
  function startDraft() {
    closeStream()
    _sseGeneration++
    currentSessionId.value = null
    messages.value = []
    streamingText.value = ''
    liveReasoning.value = ''
    streamingMessageId.value = null
    isStreaming.value = false
    contextUsage.value = null
    switching.value = false
    draft.value = true
  }

  async function createSession(title?: string, agent?: string) {
    const s = await chatApi.createSession({
      title,
      agent,
      providerId: selectedProviderId.value || undefined,
      modelId: selectedModelId.value || undefined,
    })
    draft.value = false
    sessions.value.unshift(s)
    await selectSession(s.id)
    return s
  }

  async function selectSession(id: number) {
    if (currentSessionId.value === id && stream) return
    const seq = ++_loadSeq
    // 递增代际计数器:后续到达的旧会话 SSE 事件会被 handleEvent 过滤掉
    _sseGeneration++
    closeStream()
    currentSessionId.value = id
    draft.value = false
    messages.value = []
    streamingText.value = ''
    liveReasoning.value = ''
    streamingMessageId.value = null
    isStreaming.value = false
    contextUsage.value = null
    authExpired.value = false
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''
    reconnectCount.value = 0
    switching.value = true
    manualCollapseOverride.value = {}
    try {
      const list = await chatApi.listMessages(id, { limit: MESSAGE_PAGE })
      if (seq !== _loadSeq) return
      messages.value = list
      restoreContextUsage()
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

  /** 从最后一条带用量数据的 assistant 消息恢复进度圆环(刷新/切会话后;旧消息无此键则保持估算) */
  function restoreContextUsage() {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role !== 'assistant') continue
      const tokens = m.parts?.contextTokens
      if (typeof tokens === 'number' && tokens > 0) {
        const limit = m.parts?.contextLimit
        contextUsage.value = { tokens, limit: typeof limit === 'number' && limit > 0 ? limit : null }
        return
      }
    }
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
      streamingText.value = ''
      liveReasoning.value = ''
    }
  }

  async function exportSession(id: number, format: 'md' | 'json' | 'pdf' | 'docx' = 'md') {
    const res: any = await chatApi.exportSession(id, format)
    const blob = res?.data instanceof Blob ? res.data : new Blob([res.data], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    const s = currentSession.value ? currentSession.value.title : `会话${id}`
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
    if (!lines.length) throw new Error(tr('chatStore.noContent'))
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
        // 标记事件所属的会话代际:handleEvent 据此过滤旧会话残留事件
        if (data && typeof data === 'object') data._sseGen = _sseGeneration
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
    if (!text.trim() || isStreaming.value) return
    // 草稿态/无会话:首次发送时才创建会话
    if (!currentSessionId.value) {
      const s = await createSession(deriveTitle(text) || '新对话')
      if (!s) return
    }
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
    // 预建 assistant 占位壳:message.start 可能延迟数秒才到,期间若不渲染
    // AI Workbench 头部会出现"发完问题后回答区空白一下"的观感。
    // 占位壳无 agentMessageId,真实 message.start 到达时被 ensureAssistantShell 复用改名。
    messages.value.push({
      id: -Date.now() - 1,
      role: 'assistant',
      content: '',
      status: 'streaming',
      createdAt: new Date().toISOString(),
      parts: { toolCalls: [] },
      _streaming: true,
      _placeholder: true,
    })
    streamingText.value = ''
    liveReasoning.value = ''
    streamingMessageId.value = null
    isStreaming.value = true
    touchStreamActivity()
    streamStartAt.value = Date.now()
    _turnStartMs = Date.now()
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''

    if (isFirstMessage) {
      const title = deriveTitle(text)
      if (title) {
        chatApi.updateSession(currentSessionId.value!, { title })
          .then((updated) => {
            const idx = sessions.value.findIndex((s) => s.id === currentSessionId.value)
            if (idx >= 0) sessions.value[idx] = updated
          })
          .catch((e) => console.error('[chat] auto-title update failed', e))
      }
    }

    try {
      await chatApi.sendMessage(currentSessionId.value!, {
        text,
        providerId: selectedProviderId.value || undefined,
        modelId: selectedModelId.value || undefined,
      })
      // POST 返回即代表用户消息已落库:解除本地气泡「发送中…」
      const idx = messages.value.findIndex((m) => m.id === optimistic.id)
      if (idx >= 0) messages.value[idx] = { ...messages.value[idx], _pending: false, status: 'completed' }
    } catch (e: any) {
      isStreaming.value = false
      removeUnclaimedPlaceholder()
      const idx = messages.value.findIndex((m) => m.id === optimistic.id)
      if (idx >= 0) {
        messages.value[idx] = { ...messages.value[idx], _pending: false, status: 'error', error: tr('chatStore.sendFailed') }
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
    // 过滤旧会话残留事件:切换会话后旧 SSE 连接可能仍有 delta/tool 等事件在途,
    // 不检查会污染新会话的消息列表(步骤重复、文本重叠)。
    const evGen = data?._sseGen
    if (evGen != null && evGen !== _sseGeneration) return
    dbg('[chat] SSE event:', type, 'isStreaming=', isStreaming.value,
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
        dbg('[timeline] event message.start', { id, prevStreamingMsgId: streamingMessageId.value })
        if (id) {
          ensureAssistantShell(id)
          streamingMessageId.value = id
          streamingText.value = ''
          liveReasoning.value = ''
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
          dbg('[timeline] delta new msg id', { oldId: streamingMessageId.value, newId: id })
          ensureAssistantShell(id)
          streamingMessageId.value = id
          streamingText.value = ''
        }
        streamingText.value += content
        isStreaming.value = true
        break
      }

      case 'reason': {
        if (isStreaming.value) touchStreamActivity()
        const rc: string = data.content || ''
        if (!rc) break
        dbg('[timeline] reason chunk', { len: rc.length, liveReasoningLen: liveReasoning.value.length, tail: rc.slice(-60) })
        // 推理段独立累积到 liveReasoning(轻量纯标量),让"思考"块随 reason 逐块实时可见;
        // 不再逐个写 messages.timeline,避免 buildTurns 全量重算阻塞主线程致内容成批跳出。
        // 在与工具/文本/结束的边界处才由 flushLiveReasoning 定稿合并进时间线(落库结构一致)。
        liveReasoning.value += rc
        isStreaming.value = true
        ensureLiveReasonStep()
        break
      }

      case 'tool': {
        isStreaming.value = true
        touchStreamActivity()
        dbg('[timeline] event tool', { id: data.id, name: data.name, streamingTextLen: streamingText.value.length, liveReasoningLen: liveReasoning.value.length })
        flushLiveSteps()
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
        resolveToolCall(
          typeof data.id === 'string' ? data.id : undefined,
          data.output != null ? String(data.output) : data.name,
          Array.isArray(data.outputs) ? data.outputs : undefined,
        )
        // 流式组件定稿:outputs 已写入步骤,同一帧内由文件版组件无缝接管
        if (streamingWidgetCode.value && data.id === streamingWidgetCode.value.id) {
          streamingWidgetCode.value = null
        }
        break
      }

      // dynamic-ui 组件参数流:widget_code 逐块到达,实时累积供前端逐块渲染
      case 'widget_delta': {
        if (isStreaming.value) touchStreamActivity()
        const frag: string = typeof data.delta === 'string' ? data.delta : ''
        if (!frag) break
        const id = typeof data.id === 'string' ? data.id : 'live'
        if (streamingWidgetCode.value && streamingWidgetCode.value.id !== id && data.id) {
          streamingWidgetCode.value = { id, code: '' }
        } else if (!streamingWidgetCode.value) {
          streamingWidgetCode.value = { id, code: '' }
        }
        streamingWidgetCode.value!.code += frag
        isStreaming.value = true
        break
      }

      // agent 向用户提问(ask_user 工具触发)
      case 'question': {
        const requestId: string = data.id || ''
        const question: string = data.question || tr('chatStore.needMoreInfo')
        if (!requestId) break
        suspendForPermission({
          requestId,
          tool: {
            name: 'ask_user',
            args: { question },
            description: question,
          },
          allowed: ['reply'],
          _replied: false,
        })
        break
      }

      // deepagents 会话压缩触发:在时间线上插入一条系统提示,前台即时可见
      case 'summary': {
        isStreaming.value = true
        touchStreamActivity()
        flushLiveSteps()
        appendLiveTimeline('system', data.message || tr('chatStore.historyCompact'))
        break
      }

      // 上下文用量(后端用 deepagents 同款口径统计):驱动输入框进度圆环
      case 'usage': {
        if (typeof data.tokens === 'number') {
          contextUsage.value = {
            tokens: data.tokens,
            limit: typeof data.limit === 'number' ? data.limit : null,
          }
        }
        break
      }

      case 'message.end': {
        const id: string = data.id
        const status: string = data.status
        const finalText = streamingText.value
        dbg('[timeline] event message.end', { id, status, finalTextLen: finalText.length, streamingMsgId: streamingMessageId.value })
        // 边界处一次性把实时缓冲(reason+text)定稿进时间线并清空,保证历史结构与后端落库一致
        flushLiveSteps()
        if (status === 'error') {
          markAssistantError(id, tr('chatStore.genError'))
          streamingText.value = ''
          streamingMessageId.value = null
        } else {
          // completed / aborted:把最终文本定稿到该消息(文本已随 flushLiveSteps 并入时间线)
          if (id && id === streamingMessageId.value && finalText) {
            dbg('[timeline] message.end finalizing text part', { id, finalTextLen: finalText.length })
            finalizeTextPart(id, finalText, status === 'completed')
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
        dbg('[timeline] event done', { streamingMsgId: streamingMessageId.value, streamingTextLen: streamingText.value.length })
        finishStreaming()
        break

      case 'error': {
        const msg = data.message || tr('chatStore.genErrorShort')
        const id = streamingMessageId.value
        const pendingText = streamingText.value
        // 先把已流式产出(reason+正文)定稿进时间线再标记错误:否则总结文本会随
        // streamingText 清空丢失,造成"输出完成后总结消失"(刷新后从后端时间线才能恢复)。
        flushLiveSteps()
        if (id && pendingText) {
          finalizeTextPart(id, pendingText, false)
        }
        markAssistantError(id, msg)
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
    // 复用发送时预建的占位壳(无 agentMessageId 的 streaming assistant),避免重复气泡
    const phIdx = messages.value.findIndex(
      (m) => m.role === 'assistant' && m._placeholder && !m.agentMessageId,
    )
    if (phIdx >= 0) {
      const ph = messages.value[phIdx]
      messages.value[phIdx] = { ...ph, agentMessageId: id, _placeholder: false } as any
      return
    }
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

  /** 当前流式消息需先有一个 reason 时间线条目,使 buildTurns 能生成"思考"步骤承接
   *  liveReasoning(推理刚开始、时间线尚无内容时只此一次;后续段落因夹了 tool 而新增)。 */
  function ensureLiveReasonStep() {
    const targetId = streamingMessageId.value
    let idx = targetId ? messages.value.findIndex((m) => m.agentMessageId === targetId) : -1
    if (idx < 0 && messages.value.length && messages.value[messages.value.length - 1].role === 'assistant') {
      idx = messages.value.length - 1
    }
    if (idx < 0) return
    const msg = messages.value[idx]
    const tl = (
      Array.isArray((msg.parts as any)?.timeline) ? [...(msg.parts as any).timeline] : []
    ) as { k: string; v?: string }[]
    const last = tl.length ? tl[tl.length - 1] : null
    if (last && (last.k === 'reason' || last.k === 'text')) return
    // 用当前已累积的 liveReasoning 作为占位文本:stepsFromParts 的 flush 会丢弃空文本步骤,
    // 若 v 为空则该占位"思考"步骤根本不出现,导致 buildTurns 回退为空 finalAnswer,
    // 思考内容直到边界 flush 前都渲染不出来。写入非空值即可让"思考"步骤立即存在,
    // 渲染时 displayContent 直接读 liveReasoning(实时)而非此占位 v。
    dbg('[timeline] ensureLiveReasonStep ADD', { tlLen: tl.length, lastK: last?.k, liveReasoningLen: liveReasoning.value.length })
    tl.push({ k: 'reason', v: liveReasoning.value })
    messages.value[idx] = { ...msg, parts: { ...(msg.parts || {}), timeline: tl } } as any
  }

  /** 把 liveReasoning 定稿进当前消息时间线最后一个 reason 段并清空 live 缓冲。
   *  仅在工具开始/消息结束等边界调用一次,保证 live 推理实时可见的同时定稿历史结构。
   *  注意:ensureLiveReasonStep 已预埋以 liveReasoning 前缀作为占位的 reason 段;这里须
   *  【覆盖】而非追加,否则会把占位前缀与完整内容叠加造成首块重复。 */
  function flushLiveReasoning() {
    const txt = liveReasoning.value
    liveReasoning.value = ''
    if (!txt) { dbg('[timeline] flushReasoning: empty, skip'); return }
    const targetId = streamingMessageId.value
    let idx = targetId ? messages.value.findIndex((m) => m.agentMessageId === targetId) : -1
    if (idx < 0 && messages.value.length && messages.value[messages.value.length - 1].role === 'assistant') {
      idx = messages.value.length - 1
    }
    if (idx < 0) { dbg('[timeline] flushReasoning: no target msg'); return }
    const msg = messages.value[idx]
    const tl: { k: string; v?: string }[] = Array.isArray((msg.parts as any)?.timeline)
      ? ([...(((msg.parts as any).timeline) as { k: string; v?: string }[])])
      : []
    const last = tl.length ? tl[tl.length - 1] : null
    if (last && last.k === 'reason') {
      dbg('[timeline] flushReasoning MERGE into existing', { prevLen: (last.v || '').length, addedLen: txt.length, tail: txt.slice(-60) })
      tl[tl.length - 1] = { k: 'reason', v: txt }
    } else {
      dbg('[timeline] flushReasoning NEW entry', { tlLen: tl.length, txtLen: txt.length, tail: txt.slice(-60) })
      tl.push({ k: 'reason', v: txt })
    }
    messages.value[idx] = { ...msg, parts: { ...(msg.parts || {}), timeline: tl } }
  }

  /** 流式边界(工具开始/消息结束/生成收尾)把所有实时缓冲按实际发生顺序定稿进时间线并清空。
   *  liveReasoning / streamingText 在流式期间是轻量纯标量,不触发 buildTurns 全量重算;
   *  仅在此处一次性合并,既保证逐块实时可见,又保证刷新/断线后的历史重建与后端落库一致。 */
  function flushLiveSteps() {
    dbg('[timeline] flushLiveSteps called', { streamingTextLen: streamingText.value.length, liveReasoningLen: liveReasoning.value.length })
    flushLiveReasoning()
    if (streamingText.value) {
      dbg('[timeline] flushLiveSteps appending text', { len: streamingText.value.length, tail: streamingText.value.slice(-80) })
      appendLiveTimeline('text', streamingText.value)
      streamingText.value = ''
    }
  }

  /** 追加实时时间线条目(reason/text 合并同类尾段,tool 记 id,system 独立成条),与网关落库结构一致 */
  function appendLiveTimeline(k: 'reason' | 'text' | 'tool' | 'system', v?: string, toolId?: string) {
    const targetId = streamingMessageId.value
    let idx = targetId ? messages.value.findIndex((m) => m.agentMessageId === targetId) : -1
    if (idx < 0 && messages.value.length && messages.value[messages.value.length - 1].role === 'assistant') {
      idx = messages.value.length - 1
    }
    if (idx < 0) { dbg('[timeline] append skipped: no target msg', { k, targetId }); return }
    const msg = messages.value[idx]
    const tl: { k: string; v?: string; id?: string }[] = Array.isArray((msg.parts as any)?.timeline)
      ? ([...(((msg.parts as any).timeline) as { k: string; v?: string; id?: string }[])])
      : []
    const last = tl.length ? (tl[tl.length - 1] as { k: string; v?: string }) : null
    const prevLen = tl.length
    if ((k === 'reason' || k === 'text') && last && last.k === k) {
      const oldLen = (last.v || '').length
      tl[tl.length - 1] = { k, v: (last.v || '') + (v || '') }
      dbg('[timeline] append MERGE', { k, prevLen, oldContentLen: oldLen, addedLen: (v || '').length, newContentLen: (last.v || '').length + (v || '').length, tail: (v || '').slice(-60) })
    } else if (k === 'tool') {
      if (!toolId) return
      tl.push({ k, id: toolId })
      dbg('[timeline] append TOOL', { k: 'tool', toolId, prevLen, newLen: tl.length })
    } else {
      tl.push({ k, v: v || '' })
      dbg('[timeline] append NEW', { k, prevLen, newLen: tl.length, contentLen: (v || '').length, tail: (v || '').slice(-60) })
    }
    messages.value[idx] = { ...msg, parts: { ...(msg.parts || {}), timeline: tl } }
  }

  /** 把流式文本落定到目标 assistant 消息 */
  function finalizeTextPart(id: string, text: string, completed: boolean) {
    dbg('[timeline] finalizeTextPart', { id, textLen: text.length, completed, tail: text.slice(-80) })
    const idx = messages.value.findIndex((m) => m.agentMessageId === id)
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
      dbg('[timeline] finalizeTextPart creating new msg', { id })
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
  function resolveToolCall(id: string | undefined, output: string, outputs?: { path: string }[]) {
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
    toolCalls[tcIdx] = { ...toolCalls[tcIdx], status: 'completed', output, ...(outputs?.length ? { outputs } : {}) }
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
    dbg('[timeline] finishStreaming called', { streamingMsgId: streamingMessageId.value, streamingTextLen: streamingText.value.length, liveReasoningLen: liveReasoning.value.length })
    // 记录本轮耗时(结束时刻 - 开始时刻)到对应 assistant 消息,供头部"已完成 Xs"展示
    recordTurnElapsed()
    flushLiveReasoning()
    if (streamingMessageId.value && streamingText.value) {
      dbg('[timeline] finishStreaming finalizing leftover text', { len: streamingText.value.length })
      appendLiveTimeline('text', streamingText.value)
      finalizeTextPart(streamingMessageId.value, streamingText.value, true)
      streamingText.value = ''
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
    streamingWidgetCode.value = null
    removeUnclaimedPlaceholder()
  }

  /** 清理未被复用的占位壳(本轮未产出任何 assistant 内容时),避免残留空气泡 */
  function removeUnclaimedPlaceholder() {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role === 'assistant' && m._placeholder && !m.agentMessageId && !(m as any).parts?.timeline?.length) {
        messages.value.splice(i, 1)
      }
    }
  }

  /**
   * 本轮生成耗时(秒),按 assistant 消息 agentMessageId 索引。
   * 纯前端态:刷新后不保留(后端未落库耗时字段),仅在当前会话页面内展示。
   */
  const turnElapsed = ref<Record<string, number>>({})
  /** 本轮开始时刻:sendMessage 记录,resumeStreaming 不重置,保证含 HITL 等待的整轮耗时 */
  let _turnStartMs = 0
  function recordTurnElapsed() {
    const id = streamingMessageId.value
    if (!id || !_turnStartMs) return
    const sec = Math.max(1, Math.round((Date.now() - _turnStartMs) / 1000))
    turnElapsed.value = { ...turnElapsed.value, [id]: sec }
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

  /** 清除指定消息中的 pendingPermission(本地+数据库),提问框立即消失且刷新不重现 */
  function dismissPendingPermission(requestId: string) {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.parts?.pendingPermission?.requestId === requestId) {
        const { pendingPermission: _, ...restParts } = m.parts || {}
        messages.value[i] = { ...m, parts: restParts as StepParts }
        break
      }
    }
    if (currentSessionId.value) {
      chatApi.clearPermission(currentSessionId.value, requestId).catch(() => {})
    }
  }

  /** 用户处置确认:批准/拒绝/回答提问 → 后端桥续跑被中断的生成;失败则清除提问框 */
  async function replyPermission(requestId: string, decision: 'approve' | 'reject' | 'reply', answer?: string) {
    if (!currentSessionId.value) return false
    const ok = await chatApi.replyPermission(currentSessionId.value, requestId, decision, answer)
    // 无论成功失败都清除本地提问框(成功时 agent 续跑会发新流;失败时直接消失)
    dismissPendingPermission(requestId)
    if (!ok) {
      isStreaming.value = false
      return ok
    }
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
    markAssistantError(_interruptedMsgId, tr('chatStore.interrupted'))
    isStreaming.value = false
    streamStartAt.value = 0
    streamingText.value = ''
    liveReasoning.value = ''
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
    sessions, archivedSessions, currentSessionId, currentSession, messages, turns, flow,
    draft, startDraft,
    streamingText, liveReasoning, streamingMessageId, isStreaming, streamingStuckSeconds, streamingElapsedSeconds, runningActivity,
    streamingWidgetCode, turnElapsed, contextUsage,
    browserToolSignal, lastBrowserToolAt,
    hasPendingInteraction, connected, reconnecting, forceReconnect,
    authExpired, switching, changedFilePaths,
    disconnectAborted, dismissDisconnectAbort,
    hasOlder, loadingOlder, loadOlderMessages,
    searchResults, searching, searchSessions,
    availableModels, selectedProviderId, selectedModelId,
    pendingFill, fillInput, pendingAttachPath, pendingAttachPrompt, pendingAttachReplace, attachFile,
    manualCollapseOverride, setCollapse,
    loadModels, selectModel, loadSessions, loadMoreSessions, sessionsHasMore, sessionsLoadingMore,
    loadArchivedSessions, loadMoreArchived, archivedHasMore, archivedLoadingMore,
    createSession, selectSession,
    deleteSession, pinSession, archiveSession, unarchiveSession, renameSession, clearSession, exportSession,
    importSession,
    summarizeSession,
    sendMessage, abort, closeStream, forceReleaseStreaming, resumeStreaming,
    pendingPermission, replyPermission,
    reconcileAfterReconnect,
  }
})
