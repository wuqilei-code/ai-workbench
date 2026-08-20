import { randomUUID } from 'node:crypto'
import { homedir } from 'node:os'
import path from 'node:path'
import { RuntimeClient } from './runtime-client.js'
import { SessionTranslator, extractText } from './translator.js'
import { Transcript } from './transcript.js'
import { HttpServer } from './http.js'

const DEFAULT_BIN = '/opt/aiworkbench/dsh/bin/dsh-jsonrpc-agent'
const DEFAULT_CORDIS = '/opt/aiworkbench/dsh/cordis.yml'
const DEFAULT_PROVIDER = 'deepseek-official'
const DEFAULT_MODEL = process.env.DSH_MODEL || 'deepseek-chat'
const DEFAULT_MAX_TOKENS_AS_SUCCESS = Number(process.env.DSH_MAX_TOKENS_AS_SUCCESS) || 20000

const env = (k, d) => (process.env[k] ? process.env[k] : d)

const sessions = new Map()
const translators = new Map()
const transcript = new Transcript()

function nowMs() {
  return Date.now()
}

function ensureSessionRecord(id, title = 'New session', agent = 'opencode', model = null) {
  let s = sessions.get(id)
  if (!s) {
    s = {
      id,
      title,
      agent,
      directory: process.cwd(),
      model: model ?? { id: DEFAULT_MODEL, providerID: env('DSH_PROVIDER', DEFAULT_PROVIDER) },
      time: { created: nowMs(), updated: nowMs(), archived: null },
    }
    sessions.set(id, s)
  }
  return s
}

let runtime = null

async function spawnRuntime() {
  const client = new RuntimeClient({
    command: env('DSH_RUNTIME_BIN', DEFAULT_BIN),
    args: [env('DSH_CORDIS_CONFIG', DEFAULT_CORDIS)],
    cwd: process.cwd(),
    env: process.env,
    onEvent: (params) => handleEvent(params),
    onStatus: (params) => handleStatus(params),
    onSubagent: () => {},
  })
  client.start()
  try {
    await client.initialize({
      cwd: process.cwd(),
      provider: env('DSH_PROVIDER', DEFAULT_PROVIDER),
      model: env('DSH_MODEL', DEFAULT_MODEL),
      maxTokens: env('DSH_MAX_TOKENS_AS_SUCCESS', ''),
    })
  } catch (err) {
    console.error(`[dsh-bridge] initialize failed: ${err.message}`)
    console.error(`[dsh-bridge] stderr tail:\n${client.tailStderr()}`)
    client.kill()
    throw err
  }
  return client
}

function sessionIdOf(p) {
  if (!p || typeof p !== 'object') return null
  return typeof p.sessionId === 'string' ? p.sessionId : null
}

function emit(events) {
  for (const evt of events) http.broadcast(evt)
}

function handleEvent(params) {
  const sessionId = sessionIdOf(params)
  if (!sessionId) return
  const tr = translators.get(sessionId) ?? new SessionTranslator(sessionId)
  translators.set(sessionId, tr)
  ensureSessionRecord(sessionId)
  let events
  try {
    events = tr.translate(params.event)
  } catch (err) {
    console.error(`[dsh-bridge] translate failed: ${err.stack ?? err.message}`)
    return
  }
  if (events.length) {
    const session = sessions.get(sessionId)
    for (const evt of events) {
      if (evt.type === 'message.updated') {
        const info = evt.properties.info
        const text = extractText(evt.properties.parts)
        if (info.role === 'user') {
          transcript.upsertUser(sessionId, info.id, evt.properties.parts ?? [], info.time?.created)
        } else if (info.role === 'assistant') {
          transcript.upsertAssistant(sessionId, info.id, text, info)
        }
      }
      http.broadcast(evt)
    }
    if (session) session.time.updated = nowMs()
  }
}

function handleStatus(params) {
  const sessionId = sessionIdOf(params)
  if (!sessionId) return
  const tr = translators.get(sessionId) ?? new SessionTranslator(sessionId)
  translators.set(sessionId, tr)
  try {
    emit(tr.status(params.status))
  } catch (err) {
    console.error(`[dsh-bridge] status handling failed: ${err.stack ?? err.message}`)
  }
}

const http = new HttpServer({
  getHealth: () => ({
    healthy: true,
    version: runtime?.runtimeInfo?.version ?? `dsh-bridge-${process.env.DSH_BRIDGE_VERSION ?? '0.1.0'}`,
  }),
  listSessions: () => Array.from(sessions.values()),
  getSession: (id) => sessions.get(id) ?? null,
  createSession: (body) => ensureSessionRecord(
    randomUUID(),
    body?.title ?? 'New session',
    body?.agent ?? 'opencode',
    body?.model ?? null,
  ),
  prompt: async (id, body) => {
    ensureSessionRecord(id)
    const parts = Array.isArray(body?.parts) ? body.parts : []
    const text = parts
      .filter((p) => p && (p.type === 'text' || p.type === 'input'))
      .map((p) => (typeof p.text === 'string' ? p.text : ''))
      .join('\n')
    if (!text.trim()) return
    const tr = translators.get(id) ?? new SessionTranslator(id)
    translators.set(id, tr)
    const messageId = await runtime.prompt(id, [{ type: 'text', text }])
    transcript.upsertUser(id, messageId ?? `dsh-user-${Date.now()}`, [{ type: 'text', text }], Date.now())
  },
  abort: async (id) => {
    const tr = translators.get(id) ?? new SessionTranslator(id)
    translators.set(id, tr)
    emit(tr.aborted())
    await restartRuntime()
    return true
  },
  summarize: async () => true,
  listMessages: (id, limit) => transcript.list(id, limit),
})

async function restartRuntime() {
  if (runtime) {
    try {
      runtime.kill()
    } catch {
      // ignore
    }
  }
  runtime = await spawnRuntime()
  console.log(`[dsh-bridge] runtime restarted, serverInfo=${JSON.stringify(runtime.runtimeInfo)}`)
}

async function main() {
  // DSH_SESSION_ROOT 默认落在用户 home(每容器用户唯一,天然隔离)
  if (!process.env.DSH_SESSION_ROOT) {
    process.env.DSH_SESSION_ROOT = path.join(homedir(), '.local', 'share', 'dsh')
  }
  if (!process.env.DSH_CWD) {
    process.env.DSH_CWD = process.cwd()
  }

  runtime = await spawnRuntime()
  console.log(`[dsh-bridge] dsh runtime ready: ${JSON.stringify(runtime.runtimeInfo)}`)

  const port = Number(process.env.PORT) || 4096
  await http.listen(port)
  console.log(`[dsh-bridge] listening on 0.0.0.0:${port}`)

  setInterval(() => {
    for (const res of http.clients) {
      try {
        res.write(': keepalive\n\n')
      } catch {
        // ignore
      }
    }
  }, 25000).unref()

  const shutdown = async () => {
    try {
      runtime?.kill()
    } catch {
      // ignore
    }
    http.close()
    process.exit(0)
  }
  process.on('SIGTERM', shutdown)
  process.on('SIGINT', shutdown)
}

main().catch((err) => {
  console.error(`[dsh-bridge] fatal: ${err.stack ?? err.message}`)
  process.exit(1)
})