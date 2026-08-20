/**
 * 每会话内存 transcript,用于 GET /session/{id}/message 回放。
 * 数据形状对齐 OpencodeMessageDto:{id, sessionID, role, parts, time:{created,completed}, tokens, providerID, modelID}
 */

export class Transcript {
  constructor() {
    this.sessions = new Map() // sessionId -> Map(messageId -> message)
    this.order = new Map() // sessionId -> [messageId, ...]
  }

  ensure(sessionId) {
    if (!this.sessions.has(sessionId)) {
      this.sessions.set(sessionId, new Map())
      this.order.set(sessionId, [])
    }
    return this.sessions.get(sessionId)
  }

  put(sessionId, message) {
    const map = this.ensure(sessionId)
    const order = this.order.get(sessionId)
    const merged = map.get(message.id) ? { ...map.get(message.id), ...message } : message
    if (!map.has(message.id)) order.push(message.id)
    map.set(message.id, merged)
    return merged
  }

  /** user/message 事件:写入用户消息。 */
  upsertUser(sessionId, id, textBlocks, created) {
    const msg = {
      id,
      sessionID: sessionId,
      role: 'user',
      parts: textBlocks.map((b) => ({ type: 'text', text: b.text ?? '' })),
      time: { created },
    }
    return this.put(sessionId, msg)
  }

  /** assistant/message 事件:写入/更新 assistant 消息(保留早期 deltas 之外的信息)。 */
  upsertAssistant(sessionId, id, text, info) {
    const msg = {
      id,
      sessionID: sessionId,
      role: 'assistant',
      parts: text ? [{ type: 'text', text }] : [],
      time: info.time,
      tokens: info.tokens ?? undefined,
      providerID: info.providerID,
      modelID: info.modelID,
    }
    return this.put(sessionId, msg)
  }

  list(sessionId, limit) {
    const map = this.sessions.get(sessionId)
    if (!map) return []
    const order = this.order.get(sessionId) ?? []
    const ids = limit > 0 ? order.slice(-limit) : order
    return ids
      .map((id) => map.get(id))
      .filter((m) => m && typeof m === 'object')
      .sort((a, b) => (a.time?.created ?? 0) - (b.time?.created ?? 0))
  }
}