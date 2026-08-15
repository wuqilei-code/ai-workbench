// 共享类型(对齐后端 VO)

export interface UserVo {
  id: number
  username: string
  email: string
  role: 'user' | 'admin'
}

/** 管理端用户列表项 */
export interface AdminUserVo {
  id: number
  username: string
  email: string
  role: 'user' | 'admin'
  status: number
  uid: number | null
  createdAt: string
  updatedAt: string
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
  role: 'user' | 'assistant' | 'tool' | 'system' | 'summary' | 'compaction'
  content: string
  /** 结构化 part:工具调用 & 步骤结束标记;旧消息为 null */
  parts?: StepParts | null
  /** assistant 消息真实 token 用量 {input,output,reasoning,cache:{read,write}};无则 null */
  tokens?: { input: number; output: number; reasoning: number; cache: { read: number; write: number } } | null
  /** 压缩产生的总结消息(不展示,仅占位) */
  compactionSummary?: boolean
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

/** 技能附加文件(SKILL.md 之外的脚本/数据/参考) */
export interface SkillFileVo {
  path: string
  content: string
}

/** 技能广场条目 */
export interface MarketplaceSkillVo {
  id: number
  authorName: string
  name: string
  description: string
  category: string
  icon: string
  content?: string
  files?: SkillFileVo[]
  installs: number
  installed: boolean
  mine: boolean
  updatedAt: string
}

/** 技能广场分类(键 → 中文标签 + 默认图标) */
export const SKILL_CATEGORIES: { key: string; label: string; emoji: string }[] = [
  { key: 'dev', label: '开发', emoji: '💻' },
  { key: 'doc', label: '文档', emoji: '📄' },
  { key: 'data', label: '数据分析', emoji: '📊' },
  { key: 'writing', label: '写作', emoji: '✍️' },
  { key: 'office', label: '办公', emoji: '🗂️' },
  { key: 'other', label: '其他', emoji: '🧩' },
]
export function skillCategoryInfo(key: string) {
  return SKILL_CATEGORIES.find((c) => c.key === key) ?? SKILL_CATEGORIES[SKILL_CATEGORIES.length - 1]
}

/** 提示词模板项:内置只读,个人自建可编辑 */
export interface TemplateVo {
  id: number
  title: string
  category: string
  content: string
  /** emoji 图标 */
  icon?: string
  builtin: boolean
  /** 当前用户是否已启用(首页展示) */
  enabled: boolean
  updatedAt: string
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
  /** 模型上下文窗口 token 上限;进度环优先用真实 tokens / limit */
  contextLimit?: number | null
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

/** MCP Server 配置(local 命令进程 / remote HTTP/SSE URL) */
export interface McpVo {
  id: number
  name: string
  type: 'local' | 'remote'
  command: string | null
  url: string | null
  headers: string | null
  environment: string | null
  enabled: number
  sortOrder: number
  createdAt: string
  updatedAt: string
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

/** 系统消息中心项(右上角铃铛) */
export interface NotificationInfo {
  id: number
  type: string
  title: string
  content?: string | null
  link?: string | null
  read: boolean
  createdAt?: string | null
}

/** 定时任务项 */
export interface JobTaskInfo {
  id: number
  userId: number
  name: string
  prompt: string
  cron?: string | null
  agent: string
  autoApprove: boolean
  enabled: boolean
  status: 'pending' | 'queued' | 'running' | 'success' | 'failed' | 'timeout' | 'cancelled'
  statusText: string
  nextRunAt?: string | null
  queuedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  lastResult?: string | null
  lastError?: string | null
  runningSeconds?: number | null
}

/** 定时任务单次运行历史 */
export interface JobTaskRunInfo {
  id: number
  jobId: number
  status: 'running' | 'success' | 'failed' | 'timeout' | 'cancelled'
  statusText: string
  startedAt?: string | null
  finishedAt?: string | null
  exitCode?: number | null
  changedFiles?: number | null
  result?: string | null
  error?: string | null
  runLogPath?: string | null
  runningSeconds?: number | null
}
