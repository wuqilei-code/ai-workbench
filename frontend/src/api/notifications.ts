import { http, unwrap } from './http'
import type { NotificationInfo } from '@/types'

/** 系统消息中心接口。 */
export const notificationApi = {
  list: (limit = 50) => http.get<NotificationInfo[]>('/notifications', { params: { limit } }).then(unwrap),
  unreadCount: () => http.get<number>('/notifications/unread-count').then(unwrap),
  markRead: (id: number) => http.post(`/notifications/${id}/read`),
  markAllRead: () => http.post('/notifications/read-all'),
}
