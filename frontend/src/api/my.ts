import { http, unwrap } from './http'

export interface MyStatsOverview {
  totalSessions: number
  totalMessages: number
  userMessages: number
  assistantMessages: number
  inputTokens: number
  outputTokens: number
  reasoningTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  toolCalls: number
  filesChanged: number
  compactionCount: number
  unreadNotifications: number
  avgMessagesPerSession: number
  activeDays: number
  currentStreak: number
  weekMessages: number
  prevWeekMessages: number
  weekOutputTokens: number
  prevWeekOutputTokens: number
  totalEstimatedCost: number
  totalTasks: number
  enabledTasks: number
  totalRuns: number
  successRuns: number
  failedRuns: number
  installedSkills: number
  publishedSkills: number
  publishedSkillInstalls: number
  privateMcps: number
  myTemplates: number
  favorites: number
}

export interface MyDailyPoint {
  date: string
  messages: number
  inputTokens: number
  outputTokens: number
  toolCalls: number
  sessions: number
  filesChanged: number
}

export interface MyNamedStat {
  name: string
  count: number
}

export interface MyModelStat {
  model: string
  sessions: number
  inputTokens: number
  outputTokens: number
  reasoningTokens: number
  estimatedCost: number
}

export interface MyStats {
  overview: MyStatsOverview
  daily: MyDailyPoint[]
  agents: MyNamedStat[]
  models: MyModelStat[]
}

export const myApi = {
  stats: (days = 14) => http.get<MyStats>('/my/stats', { params: { days } }).then(unwrap),
}
