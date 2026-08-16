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
  /** 已归档会话列表(侧边栏"归档"视图) */
  const archivedSessions = ref<SessionVo[]>([])
  const agents = ref<AgentItem[]>([])
  const currentSessionId = ref<number | null>(null)
  const messages = ref<MessageVo[]>([])
  /** 消息分页:单页条数(进会话初载与每次向上翻页加载的条数) */
  const MESSAGE_PAGE = 60
  /** 是否还有更早的历史未加载(滚动到顶时触发 loadOlderMessages) */
  const hasOlder = ref(false)
  /** 正在加载更早分页(防止重复触发) */
  const loadingOlder = ref(false)
  /** 最近一次向上翻页加载到的消息 id 上限(= 当前 messages 最小 id);增量追平用 */
  const olderTopId = ref<number>(0)
  /** 最近一次 AI 变更总结(role=summary 的消息与 SSE 事件聚合) */
  const summaries = ref<ChangeSummary[]>([])
  /** 会话最近一次自动压缩时间(触发后前端展示「已压缩」提示) */
  const compactedAt = ref<number | null>(null)
  /** 压缩进行中(后端检测到 compaction agent 消息后推送 session.compacting;完成/session.compacted 后清) */
  const compacting = ref(false)
  /** 压缩中提示的兜底超时(防止 session.compacted 丢失时提示一直挂着) */
  let _compactingTimer: ReturnType<typeof setTimeout> | null = null
  /** 当前会话的压缩点:最近一条"已完成压缩"标记消息的 id;其前历史已总结,不参与 token 估算。
   *  从当前会话消息派生(压缩标记随消息持久化在各自会话里),
   *  切换会话/重载后按各会话单独记录,圆环只需查询该值即可。 */
  const compactCutoffId = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role === 'compaction' && m.status === 'completed') return m.id ?? 0
    }
    return 0
  })

  /**
   * 标记会话已压缩:刷新消息列表,并把 token 环的统计起点推到当前最后一条消息。
   * 主动压缩(summarize 接口)与 opencode 推送 session.compacted 事件都走这里。
   * 注意:主动压缩请求返回时压缩仍在后台异步进行,但此时上下文已确定要释放,
   * 直接把 cutoff 设成现有最后一条 id 即可让圆环立即回落;若之后又收到
   * session.compacted 事件会再次刷新,幂等。
   */
  function markCompacted() {
    compactedAt.value = Date.now()
    if (!currentSessionId.value) return
    // 只刷新最新分页(与列表一致);保留已向上翻页加载的更早历史与本地更新消息
    chatApi.listMessages(currentSessionId.value, { limit: MESSAGE_PAGE })
      .then((fresh) => {
        const freshMinId = fresh.length ? Math.min(...fresh.map((m) => m.id ?? 0)) : 0
        const freshIds = new Set(fresh.map((m) => m.opencodeMessageId).filter(Boolean))
        const freshLocalIds = new Set(fresh.map((m) => m.id))
        // 本地已加载但不在最新分页里的消息:更早历史(localOlder)+ 更新的本地态(localNewer)。
        // 已出现在 fresh 里(按 id 或 opencodeMessageId 命中)的本地消息必须剔除,否则刷新后重复。
        const localOlder = messages.value.filter(
          (m) => (m.id != null && m.id > 0 && m.id < freshMinId) && !freshLocalIds.has(m.id),
        )
        const localNewer = messages.value.filter((m) => {
          if (freshLocalIds.has(m.id)) return false
          if (m.opencodeMessageId != null && freshIds.has(m.opencodeMessageId)) return false
          return m._pending || (m.id != null && m.id < 0) || !m.opencodeMessageId
        })
        messages.value = [...localOlder, ...fresh, ...localNewer]
        olderTopId.value = localOlder.length ? Math.min(...localOlder.map((m) => m.id ?? 0)) : freshMinId
        hydrateSummaries(); hydratePendingState()
      })
      .catch((e) => console.warn('[chat] refresh after compact failed', e))
  }

  /** 建议追问/外部注入的待填提示词:ChatInput 监听后填入输入框(仅消费一次) */
  const pendingFill = ref('')

  function fillInput(prompt: string) {
    pendingFill.value = prompt
  }

  /** 待挂载到输入框的文件路径(文件树右键「问 AI」等触发;ChatInput 消费后清空) */
  const pendingAttachPath = ref('')

  function attachFile(path: string) {
    pendingAttachPath.value = path
  }
  const streamingText = ref('')
  const streamingMessageId = ref<string | null>(null)
  const isStreaming = ref(false)
  /** 记录思维链(reasoning)part 的 partID,用于 delta 事件分流:reasoning 增量不进正文 streamingText,而是累积到 parts.reasoning.text */
  const reasoningPartIds = new Set<string>()
  /** 当前正在流式输出思维链的 messageID(null=当前无活跃 reasoning);供 ReasoningBlock 判流式态,step 完成后自动收起 */
  const streamingReasoningMsgId = ref<string | null>(null)
  /** agent 实例冷启动中(后端 dispatch session.starting):首个真实事件到来即清除 */
  const agentStarting = ref(false)
  /**
   * 前端兜底定时器:发消息后若后端冷启动事件(session.starting)未及时到达(如容器已热、
   * isReady 返回 true 不 dispatch),在 1.5s 后仍无任何真实事件时也置 agentStarting=true,
   * 避免冷启动期空白无提示;首个真实事件(session.starting / part.delta / part.updated / message.updated / session.status)到来即清除。
   */
  let _agentStartTimer: ReturnType<typeof setTimeout> | null = null
  function armAgentStartingFallback() {
    if (_agentStartTimer) return
    _agentStartTimer = setTimeout(() => {
      _agentStartTimer = null
      if (isStreaming.value) agentStarting.value = true
    }, 1500)
  }
  function clearAgentStartingFallback() {
    if (_agentStartTimer) { clearTimeout(_agentStartTimer); _agentStartTimer = null }
  }
  const connected = ref(false)
  /** 连接断开且正在自动重连(UI 提示用) */
  const reconnecting = ref(false)
  /** 登录已过期(SSE 返回 401/403):停止重连,提示用户重新登录 */
  const authExpired = ref(false)
  /** 断线期间正在生成:本轮被连接断开中断(用于兜底中止 + 重连恢复判定) */
  const interruptedByDisconnect = ref(false)
  /** 断线兜底已触发:本轮因连接断开被中止(UI 展示兜底横幅,可手动关闭) */
  const disconnectAborted = ref(false)
  /** 断线兜底定时器:断线超时未恢复 → 中止本轮并展示兜底提示 */
  let _disconnectAbortTimer: ReturnType<typeof setTimeout> | null = null
  /** 断线瞬间的流式现场快照(兜底中止时定稿/标记用) */
  let _interruptedMsgId: string | null = null
  let _interruptedText = ''
  /** 会话切换/加载进行中(避免切换时 UI 闪现旧会话) */
  const switching = ref(false)
  /** 累计断线重连次数;>0 时重连成功需做一次消息对账恢复(补回断线期间丢失的事件) */
  const reconnectCount = ref(0)
  /** 最近一次收到 SSE 事件的时间(心跳看门狗用,检测"僵尸连接") */
  const lastEventAt = ref(Date.now())
  let _heartbeatTimer: ReturnType<typeof setInterval> | null = null

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
  function stopWatchdog() {
    if (_watchdogTimer) { clearInterval(_watchdogTimer); _watchdogTimer = null }
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

  /** 由 messages 派生的 Turn 列表(只依赖 messages,不依赖流式文本)。压缩/占位/Continue 消息不参与渲染。 */
  const turns = computed(() => buildTurns(messages.value.filter((m) => !isHiddenMessage(m))))

  /** 轮 + 变更总结按时间交错的有序渲染流:让总结卡片紧跟所属轮,而非堆到底部 */
  const flow = computed(() => buildFlow(turns.value, summaries.value))

  /** 用户手动折叠覆盖:turnId → 是否展开思考区(true 展开 / false 折叠);undefined 未覆盖 */
  const manualCollapseOverride = ref<Record<string, boolean>>({})

  function setCollapse(turnId: string, expanded: boolean) {
    manualCollapseOverride.value = { ...manualCollapseOverride.value, [turnId]: expanded }
  }

  let stream: StreamHandle | null = null
  /** 是否主动关闭(切换会话/停止);主动关闭触发的 onerror 不应计入重连状态 */
  let _manualClose = false
  /** 会话加载序号:快速切换 A→B 时,丢弃过期的 listMessages 返回,防止旧数据覆盖新会话 */
  let _loadSeq = 0

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

  async function loadArchivedSessions() {
    try {
      archivedSessions.value = await chatApi.listArchivedSessions()
    } catch (e) {
      archivedSessions.value = []
    }
  }

  /** 会话搜索结果(标题或消息内容命中,含归档) */
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
    const seq = ++_loadSeq
    closeStream()
    currentSessionId.value = id
    messages.value = []
    summaries.value = []
    streamingText.value = ''
    streamingMessageId.value = null
    isStreaming.value = false
    clearAgentStartingFallback()
    agentStarting.value = false
    authExpired.value = false
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''
    switching.value = true
    manualCollapseOverride.value = {}
    compacting.value = false
    if (_compactingTimer) { clearTimeout(_compactingTimer); _compactingTimer = null }
    try {
      // 初载只取最新一页(分页):滚动到顶再向上加载更早;返回条数满页说明可能还有更早
      const list = await chatApi.listMessages(id, { limit: MESSAGE_PAGE })
      // 若期间又切换了会话,丢弃本次结果,避免旧数据覆盖新会话
      if (seq !== _loadSeq) return
      messages.value = list
      const minId = list.length ? Math.min(...list.map((m) => m.id ?? 0)) : 0
      olderTopId.value = minId
      hasOlder.value = list.length >= MESSAGE_PAGE && minId > 0
      loadingOlder.value = false
      hydrateSummaries()
      hydratePendingState()
    } catch (e) {
      if (seq !== _loadSeq) return
      messages.value = []
      hasOlder.value = false
      loadingOlder.value = false
    } finally {
      if (seq === _loadSeq) {
        // 遮罩保持到列表渲染并滚到底后再摘:列表在此前被 isNewSession 短暂卸载,
        // 消息就绪后才重新挂载并滚动;延迟摘遮罩可盖住这段"顶部闪现",渲染完再展示。
        setTimeout(() => { if (seq === _loadSeq) switching.value = false }, 300)
      }
    }
    subscribe()
    startWatchdog()
    // 预热 opencode 进程:提前拉起,避免首条消息冷启动阻塞数秒(后端幂等 + 15min 空闲回收)
    chatApi.warmup(id).catch((e) => console.debug('[chat] warmup skipped', e))
  }

  /**
   * 向上翻页:加载早于当前已加载最早消息的一页(beforeId 游标),前插到 messages 头部。
   * 滚动到虚拟列表顶部时由 ChatView 触发;返回空说明已到最早,置 hasOlder=false。
   */
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
      const ids = new Set(messages.value.map((m) => m.opencodeMessageId).filter(Boolean))
      const fresh = list.filter((m) => !m.opencodeMessageId || !ids.has(m.opencodeMessageId))
      messages.value = [...fresh, ...messages.value]
      olderTopId.value = list[0].id ?? olderTopId.value
      hasOlder.value = list.length >= MESSAGE_PAGE
      hydrateSummaries()
      hydratePendingState()
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

  /** 导入会话(从导出的 JSON):创建新会话并恢复消息,插入会话列表最前 */
  async function importSession(jsonText: string) {
    const s = await chatApi.importSession(jsonText)
    sessions.value.unshift(s)
    return s
  }

  /** 置顶/取消置顶 */
  async function pinSession(id: number, pinned: boolean) {
    const updated = await chatApi.updateSession(id, { pinned: pinned ? 1 : 0 })
    const idx = sessions.value.findIndex((s) => s.id === id)
    if (idx >= 0) sessions.value[idx] = updated
    // 置顶会话排前:重新按 pinned→updatedAt 排序
    sessions.value = [...sessions.value].sort((a, b) => {
      if ((a.pinned || 0) !== (b.pinned || 0)) return (b.pinned || 0) - (a.pinned || 0)
      return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
    })
    return updated
  }

  /** 归档会话(移出主列表) */
  async function archiveSession(id: number) {
    const updated = await chatApi.updateSession(id, { archived: 1 })
    sessions.value = sessions.value.filter((s) => s.id !== id)
    archivedSessions.value.unshift(updated)
    return updated
  }

  /** 取消归档(从归档视图移回主列表) */
  async function unarchiveSession(id: number) {
    const updated = await chatApi.updateSession(id, { archived: 0 })
    archivedSessions.value = archivedSessions.value.filter((s) => s.id !== id)
    sessions.value.unshift(updated)
    return updated
  }

  /** 重命名会话标题 */
  async function renameSession(id: number, title: string) {
    const updated = await chatApi.updateSession(id, { title })
    const idx = sessions.value.findIndex((s) => s.id === id)
    if (idx >= 0) sessions.value[idx] = updated
    const aIdx = archivedSessions.value.findIndex((s) => s.id === id)
    if (aIdx >= 0) archivedSessions.value[aIdx] = updated
    return updated
  }

  /** 清空当前会话消息(本地 MySQL 镜像;会话保留) */
  async function clearSession(id: number) {
    await chatApi.clearMessages(id)
    if (currentSessionId.value === id) {
      messages.value = []
      summaries.value = []
      streamingText.value = ''
    }
  }

  /** 导出会话(触发浏览器下载)。format: md | json | pdf | docx */
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

  /** 总结指定会话:拉取消息→拼摘要提示词→新开一个会话并把总结请求发过去。
   *  结果展示在新会话里,不污染原会话。 */
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

  /** 重连成功后对账:从后端重拉消息,补回断线期间丢失的事件。
   *  若本应在流式但对账后发现没有任何消息处于 streaming,且无未决交互
   *  → 说明断线期间生成已结束(session.idle 丢失),收尾流式态。 */
  async function reconcileAfterReconnect() {
    if (!currentSessionId.value) return
    try {
      // 只对账最新分页,保留已向上翻页加载的更早历史(避免覆盖丢失)
      const fresh = (await chatApi.listMessages(currentSessionId.value, { limit: MESSAGE_PAGE })) as MessageVo[]
      const freshMinId = fresh.length ? Math.min(...fresh.map((m) => m.id ?? 0)) : 0
      const freshIds = new Set(fresh.map((m) => m.opencodeMessageId).filter(Boolean))
      const freshLocalIds = new Set(fresh.map((m) => m.id))
      const localOlder = messages.value.filter(
        (m) => (m.id != null && m.id > 0 && m.id < freshMinId) && !freshLocalIds.has(m.id),
      )
      const localNewer = messages.value.filter((m) => {
        if (freshLocalIds.has(m.id)) return false
        if (m.opencodeMessageId != null && freshIds.has(m.opencodeMessageId)) return false
        return m._pending || (m.id != null && m.id < 0) || !m.opencodeMessageId
      })
      messages.value = [...localOlder, ...fresh, ...localNewer]
      olderTopId.value = localOlder.length ? Math.min(...localOlder.map((m) => m.id ?? 0)) : freshMinId
      hydrateSummaries()
      hydratePendingState()
      // 断线期间 agent 实际在等待权限/问题确认(事件丢失导致 isStreaming 未置 false):
      // 恢复出未决交互后立刻收尾流式态,否则心跳看门狗会反复 forceReconnect、输入区误判"处理中"。
      if (hasPendingInteraction.value) {
        isStreaming.value = false
        interruptedByDisconnect.value = false
        if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
      }
      const anyStreaming = fresh.some((m) => m.status === 'streaming')
      if (isStreaming.value && !anyStreaming && !hasPendingInteraction.value) {
        // 断线期间本轮已结束(或事件丢失):若本轮由断线中断,中止并展示兜底提示;
        // 否则正常收尾(把已落定的流式文本定稿)。
        if (interruptedByDisconnect.value) abortStreamOnDisconnect()
        else finishStreaming()
      } else if (interruptedByDisconnect.value) {
        // 断线兜底取消:连接已恢复且生成仍在继续,事件流重建,正常续播
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
        // 断线重连成功后做一次对账,恢复丢失的事件状态
        if (reconnectCount.value > 0) reconcileAfterReconnect()
      },
      onMessage: (type, data) => {
        lastEventAt.value = Date.now()
        handleEvent(type, data)
      },
      onError: () => {
        // 主动关闭(closeStream)触发的 abort 不计入重连状态
        if (_manualClose) return
        connected.value = false
        reconnecting.value = true
        reconnectCount.value++
        // 流式中断线:记录现场 + 启动兜底定时器(30s 未恢复 → 中止本轮并展示兜底提示)。
        // 只在首次断线时记录/定时,重连期间的后续 onError 不重置倒计时。
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
        // token 失效:停止重连(Sse 层已终止 poll),停止心跳看门狗,提示登录过期
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

  /** 心跳看门狗:连接"健在"但流式中长时间无事件(>45s)→ 视为僵尸连接,强制重连。
   *  工具长执行通常也有 part 事件;45s 无任何事件同时 isStreaming 基本可断定断线。 */
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

  /** 立即断开并重建 SSE 连接(手动重连按钮 / 心跳看门狗触发)。 */
  function forceReconnect() {
    closeStream()
    subscribe()
  }

  async function sendMessage(text: string) {
    if (!currentSessionId.value || !text.trim() || isStreaming.value) return
    // 用户开始新一轮对话 → 压缩必然已结束;清掉"正在压缩"状态,避免后续消息事件被吞
    if (compacting.value) {
      compacting.value = false
      if (_compactingTimer) { clearTimeout(_compactingTimer); _compactingTimer = null }
    }
    const isFirstMessage = messages.value.length === 0
    // 乐观显示用户消息
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
    armAgentStartingFallback()
    // 新一轮生成:重置断线兜底现场(旧中止流程不再影响新轮)
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''

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
      clearAgentStartingFallback()
      agentStarting.value = false
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
    armAgentStartingFallback()
    // 新一轮生成:重置断线兜底现场
    interruptedByDisconnect.value = false
    disconnectAborted.value = false
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    _interruptedMsgId = null
    _interruptedText = ''
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
      // 压缩被中断:清除"正在压缩"状态与兜底定时器
      compacting.value = false
      if (_compactingTimer) { clearTimeout(_compactingTimer); _compactingTimer = null }
    }
    // 刷新消息:拉取后端插入的"已中断"标记记录,前端即时渲染分割线
    reconcileAfterReconnect()
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

      // agent 实例冷启动中(后端在 sendUserMessage 检测到未就绪时 dispatch)
      case 'session.starting':
        clearAgentStartingFallback()
        agentStarting.value = true
        break

      // 流式增量文本(token by token)
      case 'message.part.delta': {
        clearAgentStartingFallback()
        agentStarting.value = false
        // 压缩中:忽略压缩 agent 的内容流(不展示在对话里;完成由 session.compacted 刷新)
        if (compacting.value) break
        if (data.field === 'text' && data.delta) {
          // 思维链增量:不进正文 streamingText,而是累积到对应消息的 parts.reasoning
          if (data.partID && reasoningPartIds.has(data.partID)) {
            isStreaming.value = true
            touchStreamActivity()
            if (data.messageID) appendReasoningDelta(data.messageID, data.partID, data.delta)
            break
          }
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
        clearAgentStartingFallback()
        agentStarting.value = false
        touchStreamActivity()
        // 压缩中:忽略压缩 agent 的 part 内容(不建壳/不落文本)
        if (compacting.value) break
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
        } else if (part.type === 'reasoning') {
          if (typeof part.id === 'string') reasoningPartIds.add(part.id)
          upsertReasoning(part.messageID, part)
        }
        break
      }

      // 消息元信息(id/role/status),不含文本 → 只建壳/更新状态
      case 'message.updated': {
        clearAgentStartingFallback()
        agentStarting.value = false
        touchStreamActivity()
        if (compacting.value) break
        const info = data.info || {}
        upsertMessageMeta(info)
        break
      }

      case 'session.status':
        clearAgentStartingFallback()
        agentStarting.value = false
        // 压缩期间:压缩 agent 的 busy/idle 心跳不代表用户对话在生成,忽略,
        // 否则 busy 会把 isStreaming 置 true,压缩结束后残留"AI 正在处理中"。
        if (compacting.value) break
        if (data.status?.type === 'idle') finishStreaming()
        else if (data.status?.type === 'busy') {
          isStreaming.value = true
          if (!streamStartAt.value) streamStartAt.value = Date.now()
          touchStreamActivity()
        }
        break
      case 'session.idle':
        if (compacting.value) break
        finishStreaming()
        break

      // 压缩进行中:compaction agent 开始生成 summary 消息 → 显示"正在压缩"提示
      case 'session.compacting': {
        // 压缩完成(compacted)后短时间内迟到的 compacting 事件(同一次压缩的多个 part 触发)忽略,
        // 避免"压缩中"状态被反复拉起、永不结束。
        if (compactedAt.value && Date.now() - compactedAt.value < 5000) break
        compacting.value = true
        if (_compactingTimer) clearTimeout(_compactingTimer)
        _compactingTimer = setTimeout(() => { compacting.value = false }, 120_000)
        break
      }

      // 会话被压缩(自动超限,或主动点「压缩会话」后 opencode 推送):提示用户 + 刷新消息列表
      case 'session.compacted': {
        compacting.value = false
        console.log('[chat] compacting: OFF (compacted)')
        if (_compactingTimer) { clearTimeout(_compactingTimer); _compactingTimer = null }
        // 压缩完成:结束流式态,避免残留"AI 正在处理中"提示
        finishStreaming()
        markCompacted()
        break
      }

      // AI 变更总结卡片(由后端在快照后异步生成推送)
      case 'change.summary':
        // 按 sha 去重:后端可能因重复 idle/重连重放推送同一 summary,已存在相同 sha 则跳过。
        // (刷新时 hydrateSummaries 用 DB 消息重建全量,天然无重复;此处防御性去重避免实时重复)
        if (data.sha && summaries.value.some((s) => s.sha === data.sha)) {
          console.debug('[chat] change.summary duplicate sha ignored:', data.sha)
          break
        }
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
        // 关键:按 requestId 清除,而不是按 tool.messageID。
        // opencode 对一条工具命令会做**串行**权限检查(如 external_directory → bash),每个 ask 独立 requestId;
        // 若按 toolMessageID 清除,第二个权限的 pendingPermission 会被第一个的 replied 事件误清,
        // 用户永远看不到第二个请求 → agent 死等该权限 → 整轮卡死(历史事故)。
        clearPendingPermissionById(data.id)
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
    // opencode 在消息定稿时携带真实 token 用量(info.tokens),实时回填供圆环统计;
    // 建壳阶段 tokens 全为 0,须忽略,避免用零覆盖已有真实值。
    const tokens = info.tokens && typeof info.tokens === 'object'
      && ((info.tokens.input ?? 0) > 0 || (info.tokens.output ?? 0) > 0)
      ? info.tokens : undefined

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
      messages.value[existIdx] = {
        ...messages.value[existIdx], role, status,
        tokens: tokens ?? messages.value[existIdx].tokens,
        compactionSummary: isCompactionMsg(info) ? true : messages.value[existIdx].compactionSummary,
      }
    } else {
      // 建壳:content 暂为空,等待 message.part.updated 填充
      messages.value.push({
        id: -Date.now(),
        opencodeMessageId,
        role,
        content: '',
        status,
        tokens,
        createdAt: info.time?.created ? new Date(info.time.created).toISOString() : new Date().toISOString(),
        compactionSummary: isCompactionMsg(info) ? true : undefined,
      })
      // assistant 壳出现 → 标记为当前流式目标(思考中状态);压缩总结消息除外(不渲染)
      if (role === 'assistant' && !completed && !isCompactionMsg(info)) {
        streamingMessageId.value = opencodeMessageId
      }
    }
  }

  /** 压缩产生的消息不展示:compaction agent 总结 / 压缩占位空 user 消息 / auto 注入的 Continue。
   *  compactionSummary 为后端标记;但 user 消息若已有真实内容(压缩占位被填充为真实对话)
   *  则不再隐藏——避免误杀压缩后用户发的新消息。 */
  function isHiddenMessage(m: { compactionSummary?: boolean; role?: string; content?: string }): boolean {
    const text = (m.content || '').trim()
    if (m.role === 'user' && text) {
      // 有真实内容的用户消息永远展示(即使被标记为压缩相关)
      return false
    }
    if (m.compactionSummary) return true
    if (m.role === 'user' && !text) return true
    if (text.startsWith('Continue if you have next steps')) return true
    return false
  }

  /** opencode compaction agent 生成的总结消息(mode=compaction 或 summary 标记):不展示 */
  function isCompactionMsg(info: any): boolean {
    if (!info || info.role !== 'assistant') return false
    return info.mode === 'compaction' || info.summary === true
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
    // 工具执行过程中 opencode 把流式过程输出放在 state.metadata.output(完整结果在 state.output);
    // 两者都进 tc.output,使运行中的过程内容实时显示在折叠框里,完成后自动替换为最终结果。
    const runningOutput =
      typeof state.metadata?.output === 'string' && state.metadata.output ? state.metadata.output : undefined
    const tc: ToolCall = {
      partId: typeof part.id === 'string' ? part.id : undefined,
      tool: typeof part.tool === 'string' ? part.tool : 'tool',
      status: typeof state.status === 'string' ? state.status : 'running',
      input: state.input,
      output: typeof state.output === 'string' && state.output ? state.output : runningOutput,
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
    // 该 step 结束 → 其思维链也已完成,清活跃标记让 ReasoningBlock 自动收起
    if (streamingReasoningMsgId.value === messageID) {
      streamingReasoningMsgId.value = null
    }
  }

  /** 思维链 part:写 parts.reasoning = {text}(streaming 期间为累计文本,完成时定稿) */
  function upsertReasoning(messageID: string, part: any) {
    const idx = messages.value.findIndex((m) => m.opencodeMessageId === messageID)
    if (idx < 0) return
    const msg = messages.value[idx]
    const parts: StepParts = msg.parts ? { ...msg.parts } : { toolCalls: [] }
    parts.reasoning = { text: typeof part.text === 'string' ? part.text : '' }
    messages.value[idx] = { ...msg, parts }
  }

  /** 思维链流式增量:逐 token 累积到 parts.reasoning.text(通过 δ 推理→appending→滚动,非正文→隐藏) */
  function appendReasoningDelta(messageID: string, partID: string, delta: string) {
    streamingReasoningMsgId.value = messageID
    const idx = messages.value.findIndex((m) => m.opencodeMessageId === messageID)
    if (idx < 0) return
    const msg = messages.value[idx]
    const parts: StepParts = msg.parts ? { ...msg.parts } : { toolCalls: [] }
    const prev = parts.reasoning?.text || ''
    parts.reasoning = { text: prev + delta }
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
          const targetId = toolMessageID
          setTimeout(() => {
            // 会话可能已切换:按 messageId 重新定位,避免用旧 index 误改新会话消息
            const mi = messages.value.findIndex((m) => m.opencodeMessageId === targetId)
            if (mi < 0) return
            const m = messages.value[mi]
            if (m?.parts?.pendingQuestion) {
              messages.value[mi] = { ...m, parts: { ...m.parts, pendingQuestion: null } }
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
        const targetId = msg.opencodeMessageId
        const targetIdx = i
        setTimeout(() => {
          // 同样按 messageId 定位,防会话切换误伤
          const mi = targetId
            ? messages.value.findIndex((m) => m.opencodeMessageId === targetId)
            : (messages.value[targetIdx]?.opencodeMessageId === targetId ? targetIdx : -1)
          if (mi < 0) return
          const m = messages.value[mi]
          if (m?.parts?.pendingQuestion) {
            messages.value[mi] = { ...m, parts: { ...m.parts, pendingQuestion: null } }
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
    // 多个并发权限各自独立:按 requestId 去重追加,不互相覆盖
    const list = parts.pendingPermissions ? [...parts.pendingPermissions] : []
    const exist = list.findIndex((p) => p.requestId === pp.requestId)
    if (exist >= 0) list[exist] = pp
    else list.push(pp)
    parts.pendingPermissions = list
    messages.value[idx] = { ...msg, parts }
  }

  function clearPendingPermission(toolMessageID: string | undefined) {
    if (toolMessageID) {
      const idx = messages.value.findIndex((m) => m.opencodeMessageId === toolMessageID)
      if (idx >= 0) {
        const msg = messages.value[idx]
        if (msg.parts?.pendingPermissions?.length) {
          const parts: StepParts = {
            ...msg.parts,
            pendingPermissions: msg.parts.pendingPermissions.map((p) => ({ ...p, _replied: true })),
          }
          messages.value[idx] = { ...msg, parts }
          const targetId = toolMessageID
          setTimeout(() => {
            const mi = messages.value.findIndex((m) => m.opencodeMessageId === targetId)
            if (mi < 0) return
            const m = messages.value[mi]
            if (m?.parts?.pendingPermissions) {
              messages.value[mi] = { ...m, parts: { ...m.parts, pendingPermissions: null as any } }
            }
          }, 100)
        }
        return
      }
    }
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const msg = messages.value[i]
      if (msg.role === 'assistant' && msg.parts?.pendingPermissions?.length) {
        const parts: StepParts = {
          ...msg.parts,
          pendingPermissions: msg.parts.pendingPermissions.map((p) => ({ ...p, _replied: true })),
        }
        messages.value[i] = { ...msg, parts }
        const targetId = msg.opencodeMessageId
        const targetIdx = i
        setTimeout(() => {
          const mi = targetId
            ? messages.value.findIndex((m) => m.opencodeMessageId === targetId)
            : (messages.value[targetIdx]?.opencodeMessageId === targetId ? targetIdx : -1)
          if (mi < 0) return
          const m = messages.value[mi]
          if (m?.parts?.pendingPermissions) {
            messages.value[mi] = { ...m, parts: { ...m.parts, pendingPermissions: null as any } }
          }
        }, 100)
        return
      }
    }
  }

  /** 按 requestId 清除 pendingPermission(多个权限的 ask 各自独立,只清被回复的那个)。
   *  见 permission.replied 处理说明:同一 tool call 的多个权限请求不得互相误清。 */
  function clearPendingPermissionById(requestId: string | undefined) {
    if (!requestId) {
      clearPendingPermission(undefined)
      return
    }
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const msg = messages.value[i]
      const list = msg.parts?.pendingPermissions
      if (msg.role === 'assistant' && list && list.some((p) => p.requestId === requestId)) {
        const parts: StepParts = {
          ...msg.parts,
          pendingPermissions: list.map((p) => (p.requestId === requestId ? { ...p, _replied: true } : p)),
        }
        messages.value[i] = { ...msg, parts }
        const targetId = msg.opencodeMessageId
        const targetRequestId = requestId
        const targetIdx = i
        setTimeout(() => {
          const mi = targetId
            ? messages.value.findIndex((m) => m.opencodeMessageId === targetId)
            : (messages.value[targetIdx]?.opencodeMessageId === targetId ? targetIdx : -1)
          if (mi < 0) return
          const m = messages.value[mi]
          if (m?.parts?.pendingPermissions) {
            const rest = m.parts.pendingPermissions.filter((p) => p.requestId !== targetRequestId)
            messages.value[mi] = { ...m, parts: { ...m.parts, pendingPermissions: rest.length ? rest : (null as any) } }
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
    // 本轮结束:清理 reasoning partID 集合,避免长会话中无限增长
    reasoningPartIds.clear()
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

  /**
   * 断线兜底:连接断开且未在宽限期内恢复(或重连后发现本轮已结束)时,中止本轮生成并展示兜底提示。
   *  <p>
   *  背景:isStreaming=true 期间断线,SSE 层会无限重连,若一直连不上,UI 会永远卡在
   *  "AI 正在处理中",没有任何兜底。此函数由 onError 记录的现场触发:
   *  中止后端生成(事件已丢失,继续跑浪费资源)→ 已产生的部分文本先定稿保留 →
   *  清理流式态 → 把当前 assistant 消息标记为错误并展示"连接已断开"兜底文案。
   */
  async function abortStreamOnDisconnect() {
    if (_disconnectAbortTimer) { clearTimeout(_disconnectAbortTimer); _disconnectAbortTimer = null }
    if (!interruptedByDisconnect.value) return
    interruptedByDisconnect.value = false
    // 重连对账后若发现仍有未决权限/问题(agent 正等待用户确认),绝不 abort 后端,
    // 否则会杀死本应展示的授权/提问弹窗;此时已恢复连接,正常续播即可。
    if (hasPendingInteraction.value) {
      disconnectAborted.value = false
      return
    }
    disconnectAborted.value = true
    try { if (currentSessionId.value) await chatApi.abort(currentSessionId.value) } catch (_) {}
    // 已有部分输出:先定稿到消息,避免用户丢失已生成内容
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
    clearPendingQuestion(undefined)
    clearPendingPermission(undefined)
    reasoningPartIds.clear()
  }

  /** 关闭断线兜底横幅 */
  function dismissDisconnectAbort() {
    disconnectAborted.value = false
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
      (s.message.parts?.pendingPermissions?.some(p => !p._replied)),
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
    sessions, archivedSessions, agents, currentSessionId, currentSession, messages, turns, flow, summaries, compactedAt, compactCutoffId, compacting, streamingText,
    streamingMessageId, isStreaming, streamingStuckSeconds, streamingElapsedSeconds, runningActivity,
    streamingReasoningMsgId,
    hasPendingInteraction, connected, reconnecting, forceReconnect, agentStarting,
    authExpired, switching, changedFilePaths,
    disconnectAborted, dismissDisconnectAbort,
    hasOlder, loadingOlder, loadOlderMessages,
    searchResults, searching, searchSessions,
    availableModels, selectedProviderId, selectedModelId,
    pendingFill, fillInput, pendingAttachPath, attachFile,
    manualCollapseOverride, setCollapse,
    loadModels, selectModel, loadSessions, loadArchivedSessions, loadAgents, createSession, switchAgent, selectSession,
    deleteSession, pinSession, archiveSession, unarchiveSession, renameSession, clearSession, exportSession,
    importSession,
    summarizeSession, markCompacted,
    sendMessage, runCommand, abort, closeStream, forceReleaseStreaming, resumeStreaming,
    reconcileAfterReconnect,
  }
})
