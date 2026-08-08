// 共享类型(对齐后端 VO)

export interface UserVo {
  id: number
  username: string
  email: string
  role: 'user' | 'admin'
}

export interface AuthResponse {
  token: string
  user: UserVo
}

export interface SessionVo {
  id: number
  opencodeSessionId: string
  title: string
  agent: string
  providerId: string | null
  modelId: string | null
  archived: number
  pinned: number
  createdAt: string
  updatedAt: string
}

export interface MessageVo {
  id: number
  opencodeMessageId?: string
  role: 'user' | 'assistant' | 'tool' | 'system' | 'summary'
  content: string
  /** 结构化 part:工具调用 & 步骤结束标记;旧消息为 null */
  parts?: StepParts | null
  status: string
  createdAt: string
  /** 前端本地态 */
  _pending?: boolean
  _streaming?: boolean
  /** 前端本地态:生成失败时展示的错误文案 */
  error?: string
  /** AI 变更总结附带的信息(role=summary 时由后端透传) */
  summaryFiles?: { status: string; path: string }[]
  /** AI 变更总结对应快照 commit sha(role=summary 时后端透传) */
  summarySha?: string
  /** 该快照的父提交 sha,用于展示"本轮(parent→sha)变更" */
  summaryParentSha?: string
}

/** agent 选择项(opencode GET /agent 裁剪) */
export interface AgentItem {
  name: string
  description?: string
  mode?: string
}

/** slash 命令菜单项(opencode GET /command 裁剪) */
export interface CommandItem {
  name: string
  description?: string
  agent?: string
  model?: string
  source?: string
  subtask?: boolean
  hints?: string[]
}

/** 内置全局技能信息(只读展示) */
export interface SkillInfo {
  name: string
  description: string
}

/** 后台任务状态项(「任务」面板展示) */
export interface TaskInfo {
  id: string
  status: 'pending' | 'running' | 'done' | 'failed' | 'delivered'
  statusText: string
  doneContent?: string
  delivered: boolean
  startedAt: number
  finishedAt?: number
  runningSeconds?: number
  logTail: string
  /** 来源会话 DB id(可点击跳转;无归属时缺省) */
  sessionId?: number
  /** 来源会话标题 */
  sessionTitle?: string
  /** 会话归属是否确定(sidecar/json 明确指定;否则为最近会话推测) */
  sessionConfirmed?: boolean
  /** 触发任务的用户消息 DB id(可跳转定位) */
  messageId?: number
}

/** 工具调用(part 级,同一 partId 的 pending→running→completed 共用) */
export interface ToolCall {
  partId?: string
  tool: string
  status: 'running' | 'completed' | 'error'
  input?: any
  output?: string
}

export interface QuestionOption {
  label: string
  description?: string
}

export interface QuestionItem {
  question: string
  header?: string
  options: QuestionOption[]
  multiple?: boolean
  custom?: boolean
}

export interface PendingQuestion {
  requestId: string
  questions: QuestionItem[]
  tool?: { messageID: string; callID?: string }
  version?: 'v1' | 'v2'
  _replies?: string[][]
  _replied?: boolean
}

export interface PendingPermissionV1 {
  requestId: string
  version: 'v1'
  permission: string
  patterns: string[]
  always?: string[]
  metadata?: Record<string, any>
  tool?: { messageID: string; callID?: string }
  _replied?: boolean
}

export interface PendingPermissionV2 {
  requestId: string
  version: 'v2'
  action: string
  resources: string[]
  save?: string[]
  metadata?: Record<string, any>
  source?: Record<string, any>
  tool?: { messageID: string; callID?: string }
  _replied?: boolean
}

export type PendingPermission = PendingPermissionV1 | PendingPermissionV2

/** 消息的 parts JSON 容器(对齐后端落库结构) */
export interface StepParts {
  toolCalls?: ToolCall[]
  stepFinish?: { reason?: 'tool-calls' | 'stop' } | null
  /** 模型思维链(reasoning part 的 text);模型不输出思维链则不存在 */
  reasoning?: { text?: string } | null
  pendingQuestion?: PendingQuestion | null
  pendingPermission?: PendingPermission | null
}

/** Turn 内的一个步骤(中间思考步或最终答案) */
export interface Step {
  message: MessageVo
  narration: string
  toolCalls: ToolCall[]
  finishReason?: string
  /** 该步的思维链文本(来自 parts.reasoning.text) */
  reasoning?: string
}

/** 一个用户任务 = 一条 user 消息 + 其后连续的 assistant 消息 */
export interface Turn {
  id: string
  userMessage: MessageVo | null
  steps: Step[]
  finalAnswer: Step | null
}

export interface ModelVo {
  id: number
  providerId: string
  modelId: string
  name: string
  enabled: number
}

export interface ProviderVo {
  id: number
  providerId: string
  name: string
  baseUrl: string | null
  npm: string | null
  apiKeyMasked: string
  envVarName: string
  enabled: number
  sortOrder: number
  models: ModelVo[]
}

export interface FileNode {
  name: string
  path: string
  directory: boolean
  size: number
  modified: number
  /** 创建时间(ms);文件系统不支持 birthtime 时等于 modified */
  created: number
}

/** 文件历史条目(对应后端 WorkspaceSnapshotService.FileHistoryEntry) */
export interface FileHistoryEntry {
  sha: string
  timestamp: number
  message: string
}

/** diff 单行 */
export interface DiffLine {
  kind: 'meta' | 'hunk' | 'add' | 'del' | 'ctx'
  content: string
}

/** 文件 diff 结果 */
export interface FileDiffResult {
  textDiff: boolean
  raw: string
  lines: DiffLine[]
}

/** AI 变更总结卡片(role=summary 消息 + SSE 事件) */
export interface ChangeSummary {
  summary: string
  files: { status: string; path: string }[]
  at?: string
  /** 触发该总结的快照 commit sha(点击卡片看 diff/回滚用) */
  sha?: string
  /** 该快照的父提交 sha,与 sha 组成"本轮变更"的 diff 范围 */
  parentSha?: string
}

/** 用量统计看板(admin) */
export interface UsageStats {
  overview: {
    totalUsers: number
    totalSessions: number
    totalMessages: number
    totalToolCalls: number
    todayMessages: number
    activeUsers7d: number
  }
  daily: { date: string; messages: number; users: number }[]
  topUsers: { userId: number; username: string; sessions: number; messages: number }[]
}
