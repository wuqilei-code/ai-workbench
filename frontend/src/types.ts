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
  /** 前端本地态:AI 变更总结附带的信息 */
  summaryFiles?: { status: string; path: string }[]
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
  pendingQuestion?: PendingQuestion | null
  pendingPermission?: PendingPermission | null
}

/** Turn 内的一个步骤(中间思考步或最终答案) */
export interface Step {
  message: MessageVo
  narration: string
  toolCalls: ToolCall[]
  finishReason?: string
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
}
