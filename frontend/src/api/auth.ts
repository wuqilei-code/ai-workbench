import { http, unwrap } from './http'
import type { AuthResponse, UserVo } from '@/types'

export const authApi = {
  register: (data: { username: string; email: string; password: string }) =>
    http.post<AuthResponse>('/auth/register', data).then(unwrap),
  login: (data: { usernameOrEmail: string; password: string }) =>
    http.post<AuthResponse>('/auth/login', data).then(unwrap),
  me: () => http.get<UserVo>('/auth/me').then(unwrap),
}
