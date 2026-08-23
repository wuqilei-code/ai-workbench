// 共享类型(对齐后端 VO,deepagents 单轨)

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
  /** 执行轨道外部会话 id("da-" 前缀,SSE 订阅 key) */
  agentSessionId: string
  title: string
  agent: string
  /** 会话勾选启用的自定义技能名(空=仅内置技能) */
  skills?: string[]
  providerId: string | null
  modelId: string | null
  archived: number
  pinned: number
  createdAt: string
  updatedAt: string
}

export interface MessageVo {
  id: number
  /** 执行轨道外部消息 id(assistant 为 "da-msg-...",user 为 "da-user-...") */
  agentMessageId?: string
  role: string
  content: string
  /** 结构化 parts:{toolCalls:[{id,name,args,output,status}]};旧消息为 null */
  parts?: StepParts | null
  /** 1=压缩产生的总结消息(不展示,仅占位;deepagents 轨道恒为 0) */
  compactionSummary?: boolean
  status: string
  createdAt: string
  /** 前端本地态 */
  _pending?: boolean
  _streaming?: boolean
  /** 前端本地态:生成失败时展示的错误文案 */
  error?: string
}

/** 工具调用(后端持久化形状:assistant 一轮一个 message,parts.toolCalls 为这轮的调用序列) */
export interface ToolCall {
  /** 工具调用 id(agent-service 侧 stable call id;tool → tool_result 配对) */
  id?: string
  name: string
  status: 'running' | 'completed' | 'error'
  /** 工具入参(后端原样透传) */
  args?: any
  /** 工具输出(截断 2000 字符) */
  output?: string
  /** 结构化输出物(deepagents 写文件类工具给出):工作区相对路径,前端结果文件块优先用它 */
  outputs?: { path: string }[]
}

/** HITL 人工确认请求(危险操作需用户点击允许/拒绝) */
export interface PendingPermission {
  requestId: string
  tool?: { name: string; args?: any; description?: string } | null
  /** 允许的决策(approve/reject) */
  allowed?: string[]
  /** 前端本地态:已处置 */
  _replied?: boolean
}

/** 消息的 parts JSON 容器(对齐后端落库结构) */
export interface StepParts {
  toolCalls?: ToolCall[]
  /** 待确认的权限请求(危险操作 HITL) */
  pendingPermission?: PendingPermission | null
}

/** 内置全局技能信息(只读展示) */
export interface SkillInfo {
  name: string
  description: string
}

/** 用户技能(user_skill:自建/安装;安装款只读) */
export interface UserSkill {
  id: number
  name: string
  description?: string
  content?: string
  /** 附加文件(SKILL.md 之外的脚本/数据/参考) */
  files?: SkillFileVo[]
  /** 0=私有 1=公开 */
  visibility: number
  /** self=自建 installed=从市场安装 */
  source: 'self' | 'installed'
  createdAt?: string
  updatedAt?: string
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

/** Turn 内的一个步骤(一次 assistant 回复 = 一步) */
export interface Step {
  message: MessageVo
  narration: string
  toolCalls: ToolCall[]
  /** 段落来源:reason=模型推理段(reasoning_content),text=正文解说;缺省视为 text */
  kind?: 'text' | 'reason'
}

/** 一个用户任务 = 一条 user 消息 + 其后连续的 assistant 消息 */
export interface Turn {
  id: string
  userMessage: MessageVo | null
  steps: Step[]
  finalAnswer: Step | null
}

/** AI 变更总结卡片(deepagents 轨道不再产生,保留形状供 flow 渲染空数组) */
export interface ChangeSummary {
  summary: string
  files: { status: string; path: string }[]
  at?: string
  sha?: string
  parentSha?: string
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