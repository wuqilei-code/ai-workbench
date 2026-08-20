import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': '*',
  'Access-Control-Allow-Methods': 'GET,POST,DELETE,OPTIONS',
}

function json(res, code, body) {
  const payload = JSON.stringify(body)
  res.writeHead(code, { ...CORS, 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) })
  res.end(payload)
}

function empty(res, code = 200) {
  res.writeHead(code, CORS)
  res.end()
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => {
      data += c
      if (data.length > 10 * 1024 * 1024) {
        reject(new Error('body too large'))
        req.destroy()
      }
    })
    req.on('end', () => {
      if (!data.trim()) return resolve({})
      try {
        resolve(JSON.parse(data))
      } catch {
        reject(new Error('invalid json body'))
      }
    })
    req.on('error', reject)
  })
}

const SEG = /^\/session\/([^/]+)$/
const PROMPT = /^\/session\/([^/]+)\/prompt_async$/
const ABORT = /^\/session\/([^/]+)\/abort$/
const SUMMARIZE = /^\/session\/([^/]+)\/summarize$/
const MESSAGE = /^\/session\/([^/]+)\/message$/
const QUESTION_REPLY = /^\/question\/([^/]+)\/reply$/
const QUESTION_REJECT = /^\/question\/([^/]+)\/reject$/
const PERMISSION_REPLY = /^\/permission\/([^/]+)\/reply$/
const API_AGENT = /^\/api\/session\/([^/]+)\/agent$/
const COMMAND = /^\/session\/([^/]+)\/command$/

/**
 * opencode 兼容 HTTP+SSE 服务器(无鉴权,容器网络内)。
 */
export class HttpServer {
  constructor({ getHealth, listSessions, getSession, createSession, prompt, abort, summarize, listMessages, subscribe }) {
    this.getHealth = getHealth
    this.listSessions = listSessions
    this.getSession = getSession
    this.createSession = createSession
    this.prompt = prompt
    this.abort = abort
    this.summarize = summarize
    this.listMessages = listMessages
    this.subscribe = subscribe
    this.clients = new Set()
    this.server = createServer((req, res) => this.handle(req, res))
  }

  listen(port, host = '0.0.0.0') {
    return new Promise((resolve) => this.server.listen(port, host, resolve))
  }

  broadcast(event) {
    const payload = JSON.stringify({ payload: { id: randomUUID(), type: event.type, properties: event.properties } })
    const frame = `event: ${event.type}\ndata: ${payload}\n\n`
    for (const res of this.clients) {
      if (res.writableEnded) continue
      try {
        res.write(frame)
      } catch {
        this.clients.delete(res)
      }
    }
  }

  close() {
    for (const res of [...this.clients]) {
      try {
        res.end()
      } catch {
        // ignore
      }
    }
    this.clients.clear()
    this.server.close()
  }

  async handle(req, res) {
    const url = new URL(req.url, 'http://localhost')
    const path = url.pathname
    const method = req.method

    if (method === 'OPTIONS') {
      res.writeHead(204, CORS)
      res.end()
      return
    }

    try {
      switch (true) {
        case method === 'GET' && path === '/global/health':
          return json(res, 200, this.getHealth())

        case method === 'GET' && path === '/global/event': {
          res.writeHead(200, {
            ...CORS,
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            Connection: 'keep-alive',
            'X-Accel-Buffering': 'no',
          })
          res.flushHeaders()
          this.clients.add(res)
          req.on('close', () => {
            this.clients.delete(res)
            try {
              res.end()
            } catch {
              // already ended
            }
          })
          this.subscribe?.(res)
          return
        }

        case method === 'GET' && path === '/session':
          return json(res, 200, this.listSessions())

        case method === 'POST' && path === '/session': {
          const body = await readBody(req)
          const session = this.createSession(body)
          return json(res, 200, session)
        }

        case method === 'GET' && SEG.test(path): {
          const session = this.getSession(path.match(SEG)[1])
          return session ? json(res, 200, session) : json(res, 404, { error: 'session not found' })
        }

        case method === 'DELETE' && SEG.test(path):
          this.getSession(path.match(SEG)[1]) // no-op: dsh sessions persist on disk
          return empty(res, 200)

        case method === 'POST' && PROMPT.test(path): {
          const id = path.match(PROMPT)[1]
          const body = await readBody(req)
          await this.prompt(id, body)
          return empty(res, 200)
        }

        case method === 'POST' && ABORT.test(path): {
          await this.abort(path.match(ABORT)[1])
          return json(res, 200, true)
        }

        case method === 'POST' && SUMMARIZE.test(path): {
          await this.summarize(path.match(SUMMARIZE)[1])
          return json(res, 200, true)
        }

        case method === 'GET' && MESSAGE.test(path): {
          const limit = Number(url.searchParams.get('limit')) || 30
          return json(res, 200, this.listMessages(path.match(MESSAGE)[1], limit))
        }

        case method === 'POST' && (QUESTION_REPLY.test(path) || QUESTION_REJECT.test(path) || PERMISSION_REPLY.test(path)):
          return empty(res, 200)

        case method === 'GET' && path === '/command':
          return json(res, 200, [])

        case method === 'GET' && path === '/agent':
          return json(res, 200, [])

        case method === 'POST' && API_AGENT.test(path):
          return empty(res, 204)

        case method === 'POST' && COMMAND.test(path):
          return empty(res, 200)

        default:
          return json(res, 404, { error: 'not found', path })
      }
    } catch (err) {
      return json(res, 500, { error: err.message })
    }
  }
}