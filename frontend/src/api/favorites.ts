import { http, unwrap } from './http'

export interface FavoriteItemVo {
  id: number
  sessionId: number
  messageId: number
  role: 'user' | 'assistant'
  content: string
  turnId: string
  sessionTitle: string
  createdAt: number
}

export const favoriteApi = {
  list: () => http.get<FavoriteItemVo[]>('/my/favorites').then(unwrap),
  toggle: (data: {
    sessionId: number
    messageId: number
    role: string
    content: string
    turnId: string
    sessionTitle: string
  }) => http.post<boolean>('/my/favorites', data).then(unwrap),
  remove: (sessionId: number, messageId: number) =>
    http.delete('/my/favorites', { params: { sessionId, messageId } }),
}
