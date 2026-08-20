/**
 * dsh SessionEvent → opencode SSE event 投影器。
 *
 * 后端 ChatMessageService / OpencodeInstanceEventConsumer 与前端 chat.ts 依赖的事件形状:
 *   message.part.delta      {messageID, partID, field:"text", delta}
 *   message.part.updated    {part:{type, messageID, ...}}
 *   message.updated         {info:{id, role, time:{created,completed}, tokens, providerID, modelID}, parts:[...]}
 *   session.status          {status:{type:"busy"|"idle"}}
 *   session.idle / session.error
 *
 * 消息 id 策略:assistant 消息用确定性合成 id `dsh-<sessionId>-t<turn>-s<step>`(dsh 每个 step 是一次
 * 独立模型调用,对应 opencode 的一条 assistant 消息;流式 delta 与最终 message.updated 全程一致);
 * user 消息直接用 dsh 真实 message.id。tool 部件用 dsh callId 去重。
 */

export function toEpochMs(t) {
  if (t == null) return undefined
  if (typeof t === 'number') return t
  const n = Date.parse(t)
  return Number.isNaN(n) ? undefined : n
}

export function extractText(blocks) {
  if (!Array.isArray(blocks)) return ''
  return blocks
    .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
    .map((b) => b.text)
    .join('')
}

export function mapUsage(usage) {
  if (!usage || typeof usage !== 'object') return null
  const t = {
    input: usage.inputTokens ?? 0,
    output: usage.outputTokens ?? 0,
    cache: { read: usage.cacheReadTokens ?? 0, write: usage.cacheWriteTokens ?? 0 },
  }
  if (usage.reasoningTokens) t.reasoning = usage.reasoningTokens
  return t
}

const IDLE_OK_KINDS = new Set(['completed', 'stopped', 'success', 'ok', 'max-tokens'])
const ERROR_KINDS = new Set(['error', 'aborted', 'cancelled', 'failed', 'tool-error', 'interrupted', 'blocked'])

/**
 * 单会话状态机:跟踪当前 turn/step 与块缓冲,把 dsh 事件流翻译成 opencode 事件列表。
 */
export class SessionTranslator {
  constructor(sessionId) {
    this.sessionId = sessionId
    this.turn = null
    this.step = null
    this.msgId = null
    this.blocks = new Map() // chunk 块 index -> {type, partId, callId, name, buffer}
    this.toolNames = new Map() // callId -> name
    this.toolInputs = new Map() // callId -> input
    this.turnTokens = null
    this.turnStartedAt = null
  }

  synthMsgId(turn, step) {
    return `dsh-${this.sessionId}-t${turn}-s${step}`
  }

  resetTurn() {
    this.blocks.clear()
    this.toolNames.clear()
    this.toolInputs.clear()
    this.turnTokens = null
    this.msgId = null
    this.step = null
  }

  /**
   * @param {object} evt dsh SessionEvent {type, seq, time, data}
   * @returns {Array<{type:string, properties:object}>} opencode 事件(可能为空)
   */
  translate(evt) {
    const type = evt.type
    const data = evt.data ?? {}
    switch (type) {
      case 'turn/start': {
        this.turn = data.turn
        this.resetTurn()
        this.turnStartedAt = toEpochMs(evt.time)
        return [{ type: 'session.status', properties: { sessionID: this.sessionId, status: { type: 'busy' } } }]
      }
      case 'user/message': {
        const msg = data
        if (!msg || msg.role !== 'user' || !msg.id) return []
        const textBlocks = Array.isArray(msg.content)
          ? msg.content.filter((b) => b && b.type === 'text')
          : []
        const created = toEpochMs(evt.time) ?? toEpochMs(msg.time)
        const out = []
        out.push({
          type: 'message.updated',
          properties: {
            sessionID: this.sessionId,
            info: { id: msg.id, role: 'user', time: { created } },
            parts: textBlocks.map((b) => ({ type: 'text', text: b.text ?? '' })),
          },
        })
        for (const b of textBlocks) {
          out.push({
            type: 'message.part.updated',
            properties: {
              sessionID: this.sessionId,
              part: { type: 'text', messageID: msg.id, text: b.text ?? '', time: { start: created } },
            },
          })
        }
        return out
      }
      case 'step/start': {
        this.step = data.step
        this.msgId = this.synthMsgId(data.turn ?? this.turn, data.step)
        return []
      }
      case 'assistant/chunk': {
        return this.translateChunk(data, evt)
      }
      case 'tool/call': {
        const callId = data.callId
        if (!callId) return []
        this.toolNames.set(callId, data.name ?? 'tool')
        this.toolInputs.set(callId, data.arguments ?? {})
        return [{
          type: 'message.part.updated',
          properties: {
            sessionID: this.sessionId,
            part: {
              type: 'tool',
              messageID: this.synthMsgId(data.turn ?? this.turn, data.step),
              id: callId,
              tool: data.name ?? 'tool',
              state: { status: 'running', input: data.arguments ?? {} },
              time: { start: toEpochMs(evt.time) },
            },
          },
        }]
      }
      case 'tool/result': {
        const msg = data.message
        if (!msg || !msg.source || !msg.source.callId) return []
        const callId = msg.source.callId
        const name = this.toolNames.get(callId) ?? 'tool'
        const input = this.toolInputs.get(callId)
        const out = extractText(msg.content)
        const state = { status: data.error ? 'error' : 'completed' }
        if (input !== undefined) state.input = input
        if (out) state.output = out
        return [{
          type: 'message.part.updated',
          properties: {
            sessionID: this.sessionId,
            part: {
              type: 'tool',
              messageID: this.synthMsgId(data.turn ?? this.turn, data.step),
              id: callId,
              tool: name,
              state,
              time: { end: toEpochMs(evt.time) },
            },
          },
        }]
      }
      case 'assistant/message': {
        const msg = data.message
        const msgId = this.synthMsgId(data.turn ?? this.turn, data.step)
        if (!msg) return []
        const text = extractText(msg.content)
        const created = this.turnStartedAt ?? toEpochMs(evt.time)
        const completed = toEpochMs(evt.time)
        const info = {
          id: msgId,
          role: 'assistant',
          time: { created, completed },
        }
        const tokens = mapUsage(data.usage) ?? this.turnTokens
        if (tokens) info.tokens = tokens
        if (msg.source && msg.source.kind === 'model') {
          if (msg.source.provider) info.providerID = msg.source.provider
          if (msg.source.model) info.modelID = msg.source.model
        }
        return [{
          type: 'message.updated',
          properties: {
            sessionID: this.sessionId,
            info,
            parts: text ? [{ type: 'text', text }] : [],
          },
        }]
      }
      case 'turn/end': {
        const reason = data.reason
        const kind = reason && typeof reason.kind === 'string' ? reason.kind : 'completed'
        this.turn = null
        const out = [{ type: 'session.status', properties: { sessionID: this.sessionId, status: { type: 'idle' } } }]
        if (ERROR_KINDS.has(kind) || (!IDLE_OK_KINDS.has(kind) && kind !== 'compacted')) {
          out.push({
            type: 'session.error',
            properties: {
              sessionID: this.sessionId,
              error: { name: kind, message: `dsh turn ended with reason: ${kind}` },
            },
          })
        } else {
          out.push({ type: 'session.idle', properties: { sessionID: this.sessionId } })
        }
        return out
      }
      default:
        return []
    }
  }

  translateChunk(data, evt) {
    const chunk = data.chunk
    if (!chunk || typeof chunk !== 'object') return []
    const out = []
    const t = chunk.type
    switch (t) {
      case 'block-start': {
        const { index, blockType } = chunk
        if (blockType === 'text') {
          this.blocks.set(index, { type: 'text', partId: `${this.msgId}-b${index}`, buffer: '' })
        } else if (blockType === 'reasoning') {
          const partId = `${this.msgId}-r${index}`
          this.blocks.set(index, { type: 'reasoning', partId, buffer: '' })
          out.push({
            type: 'message.part.updated',
            properties: {
              sessionID: this.sessionId,
              part: {
                type: 'reasoning',
                messageID: this.msgId,
                id: partId,
                text: '',
                time: { start: toEpochMs(evt.time) },
              },
            },
          })
        } else if (blockType === 'tool-call') {
          this.blocks.set(index, { type: 'tool-call', partId: null, callId: null, name: null, buffer: '' })
        }
        break
      }
      case 'text-delta': {
        const block = this.blocks.get(chunk.index)
        if (block && block.type === 'text') {
          out.push({
            type: 'message.part.delta',
            properties: {
              messageID: this.msgId,
              partID: block.partId,
              field: 'text',
              delta: chunk.text ?? '',
            },
          })
        }
        break
      }
      case 'reasoning-delta': {
        const block = this.blocks.get(chunk.index)
        if (block && block.type === 'reasoning') {
          out.push({
            type: 'message.part.delta',
            properties: {
              messageID: this.msgId,
              partID: block.partId,
              field: 'text',
              delta: chunk.text ?? '',
            },
          })
        }
        break
      }
      case 'tool-call-delta': {
        const block = this.blocks.get(chunk.index)
        if (!block || block.type !== 'tool-call') break
        if (chunk.id != null) block.callId = chunk.id
        if (typeof chunk.name === 'string' && chunk.name) block.name = chunk.name
        if (typeof chunk.argumentsDelta === 'string') block.buffer += chunk.argumentsDelta
        // 首次拿到 callId+name → 发出 running 工具部件(供前端实时显示)
        if (block.callId && block.name && !block.partId) {
          block.partId = block.callId
          this.toolNames.set(block.callId, block.name)
          this.toolInputs.set(block.callId, block.buffer || {})
          out.push({
            type: 'message.part.updated',
            properties: {
              sessionID: this.sessionId,
              part: {
                type: 'tool',
                messageID: this.msgId,
                id: block.callId,
                tool: block.name,
                state: { status: 'running', input: block.buffer || {} },
                time: { start: toEpochMs(evt.time) },
              },
            },
          })
        }
        break
      }
      case 'block-end': {
        const block = chunk.block
        const st = this.blocks.get(chunk.index)
        if (!block || !st) break
        if (block.type === 'text') {
          out.push({
            type: 'message.part.updated',
            properties: {
              sessionID: this.sessionId,
              part: {
                type: 'text',
                messageID: this.msgId,
                text: block.text ?? '',
                time: { end: toEpochMs(evt.time) },
              },
            },
          })
        } else if (block.type === 'reasoning') {
          out.push({
            type: 'message.part.updated',
            properties: {
              sessionID: this.sessionId,
              part: {
                type: 'reasoning',
                messageID: this.msgId,
                id: st.partId,
                text: block.text ?? '',
                time: { end: toEpochMs(evt.time) },
              },
            },
          })
        } else if (block.type === 'tool-call') {
          if (block.id != null && !st.callId) st.callId = block.id
          const callId = st.callId
          const name = st.name ?? block.name ?? this.toolNames.get(callId) ?? 'tool'
          if (callId) {
            this.toolNames.set(callId, name)
            this.toolInputs.set(callId, block.arguments ?? st.buffer ?? {})
            out.push({
              type: 'message.part.updated',
              properties: {
                sessionID: this.sessionId,
                part: {
                  type: 'tool',
                  messageID: this.msgId,
                  id: callId,
                  tool: name,
                  state: { status: 'running', input: block.arguments ?? st.buffer ?? {} },
                  time: { end: toEpochMs(evt.time) },
                },
              },
            })
          }
        }
        break
      }
      case 'usage': {
        const usage = chunk.usage
        if (usage && typeof usage === 'object') {
          const acc = this.turnTokens ?? { input: 0, output: 0, cache: { read: 0, write: 0 }, reasoning: 0 }
          acc.input += usage.inputTokens ?? 0
          acc.output += usage.outputTokens ?? 0
          acc.cache.read += usage.cacheReadTokens ?? 0
          acc.cache.write += usage.cacheWriteTokens ?? 0
          acc.reasoning += usage.reasoningTokens ?? 0
          this.turnTokens = acc
        }
        break
      }
      case 'finish': {
        const kind = chunk.reason && typeof chunk.reason === 'object' ? chunk.reason.kind : chunk.reason
        out.push({
          type: 'message.part.updated',
          properties: {
            sessionID: this.sessionId,
            part: { type: 'step-finish', messageID: this.msgId, reason: kind ?? 'stop' },
          },
        })
        break
      }
      default:
        break
    }
    return out
  }

  /** runtime session.status 通知('running'|'idle') → opencode session.status/idle。 */
  status(status) {
    if (status === 'running') {
      return [{ type: 'session.status', properties: { sessionID: this.sessionId, status: { type: 'busy' } } }]
    }
    // turn 已由 turn/end 事件收尾 → 只同步心跳,不补发 session.idle。
    // (避免每轮对话后端触发两次 git 快照:turn/end 一次 + 运行时 idle 通知一次)
    if (this.turn === null) {
      return [{ type: 'session.status', properties: { sessionID: this.sessionId, status: { type: 'idle' } } }]
    }
    return [
      { type: 'session.status', properties: { sessionID: this.sessionId, status: { type: 'idle' } } },
      { type: 'session.idle', properties: { sessionID: this.sessionId } },
    ]
  }

  /** abort 后补发的会话结束事件。 */
  aborted() {
    return [
      { type: 'session.status', properties: { sessionID: this.sessionId, status: { type: 'idle' } } },
      { type: 'session.idle', properties: { sessionID: this.sessionId } },
    ]
  }
}