import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { authApi } from '@/api/auth'
import type { UserVo } from '@/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const user = ref<UserVo | null>(null)

  const isAdmin = computed(() => user.value?.role === 'admin')
  const isLoggedIn = computed(() => !!token.value)

  function setAuth(t: string, u: UserVo) {
    token.value = t
    user.value = u
    localStorage.setItem('token', t)
  }

  async function login(usernameOrEmail: string, password: string) {
    const res = await authApi.login({ usernameOrEmail, password })
    setAuth(res.token, res.user)
    return res
  }

  async function register(username: string, email: string, password: string) {
    const res = await authApi.register({ username, email, password })
    setAuth(res.token, res.user)
    return res
  }

  async function fetchMe() {
    if (!token.value) return null
    try {
      user.value = await authApi.me()
      return user.value
    } catch {
      // 401 已由 http 拦截器处理(清 token + 跳登录);其它错误保留 token,允许重试
      return null
    }
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('token')
    location.href = '/login'
  }

  return { token, user, isAdmin, isLoggedIn, setAuth, login, register, fetchMe, logout }
})
