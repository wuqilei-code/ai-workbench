import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'

/**
 * Minimal JSON-RPC 2.0 client for a DeepSeek Harness SDK runtime subprocess.
 *
 * Wire: newline-delimited JSON-RPC over the child's stdio.
 *   request:     {"jsonrpc":"2.0","id":n,"method":"...","params":{...}}
 *   response:    {"jsonrpc":"2.0","id":n,"result":{...}} | {"jsonrpc":"2.0","id":n,"error":{...}}
 *   notification:{"jsonrpc":"2.0","method":"session.event","params":{...}}
 *
 * Methods: initialize / session/prompt / shutdown.
 * Notifications: session.event, session.status, subagent.started, subagent.finished.
 */
export class RuntimeClient {
  constructor({ command, args, env, cwd, onEvent, onStatus, onSubagent }) {
    this.command = command
    this.args = args
    this.env = env
    this.cwd = cwd
    this.onEvent = onEvent
    this.onStatus = onStatus
    this.onSubagent = onSubagent
    this.child = null
    this.rl = null
    this.nextId = 1
    this.pending = new Map()
    this.runtimeInfo = null
    this.alive = false
    this.stderrTail = []
  }

  start() {
    const child = spawn(this.command, this.args, {
      cwd: this.cwd,
      env: this.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.child = child
    child.stderr.setEncoding('utf8')
    child.stderr.on('data', (c) => {
      this.stderrTail.push(c)
      if (this.stderrTail.length > 40) this.stderrTail.shift()
    })
    this.rl = createInterface({ input: child.stdout })
    this.rl.on('line', (line) => this.onLine(line))
    child.once('exit', (code) => {
      this.alive = false
      this.failAll(new Error(`dsh runtime exited (code=${code})`))
    })
    child.stdin.on('error', () => {})
    this.alive = true
  }

  onLine(line) {
    const trimmed = line.trim()
    if (!trimmed) return
    let msg
    try {
      msg = JSON.parse(trimmed)
    } catch {
      return
    }
    if (msg && typeof msg.method === 'string') {
      this.notify(msg.method, msg.params)
      return
    }
    if (msg && typeof msg.id !== 'undefined') {
      const p = this.pending.get(msg.id)
      if (!p) return
      this.pending.delete(msg.id)
      if (msg.error) {
        p.reject(new Error(`dsh runtime error: ${JSON.stringify(msg.error)}`))
      } else {
        p.resolve(msg.result)
      }
    }
  }

  notify(method, params) {
    switch (method) {
      case 'session.event':
        if (this.onEvent) this.onEvent(params)
        break
      case 'session.status':
        if (this.onStatus) this.onStatus(params)
        break
      case 'subagent.started':
      case 'subagent.finished':
        if (this.onSubagent) this.onSubagent(method, params)
        break
      default:
        break
    }
  }

  request(method, params, timeoutMs = 30000) {
    return new Promise((resolve, reject) => {
      const id = this.nextId++
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`${method} timed out after ${timeoutMs}ms`))
      }, timeoutMs)
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v) },
        reject: (e) => { clearTimeout(timer); reject(e) },
      })
      this.child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params: params ?? {} }) + '\n')
    })
  }

  async initialize({ cwd, provider, model, maxTokens }) {
    const params = { cwd, provider, model }
    if (maxTokens) params.maxTokens = maxTokens
    const result = await this.request('initialize', params)
    if (!result || !result.serverInfo) {
      throw new Error(`initialize returned no server identity: ${JSON.stringify(result)}`)
    }
    this.runtimeInfo = result.serverInfo
    return result
  }

  async prompt(sessionId, contentBlocks) {
    const result = await this.request('session/prompt', { sessionId, contentBlocks })
    if (!result || typeof result.messageId !== 'string') {
      throw new Error(`session/prompt returned no messageId: ${JSON.stringify(result)}`)
    }
    return result.messageId
  }

  async shutdown() {
    try {
      await this.request('shutdown', {}, 2000)
    } catch {
      // best effort; kill below is authoritative
    }
  }

  kill() {
    if (this.child && !this.child.killed) {
      this.child.kill('SIGKILL')
    }
  }

  failAll(err) {
    for (const p of this.pending.values()) p.reject(err)
    this.pending.clear()
  }

  tailStderr() {
    return this.stderrTail.join('').slice(-2000)
  }
}