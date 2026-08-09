import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

const LS_KEY = 'aiw.theme'

export const useThemeStore = defineStore('theme', () => {
  const saved = (() => {
    try {
      const v = localStorage.getItem(LS_KEY)
      return v === 'light' ? 'light' : 'dark'
    } catch {
      return 'dark'
    }
  })()

  const mode = ref<'dark' | 'light'>(saved)

  /** 同步 HTML data-theme(供 CSS 变量切换) */
  function apply() {
    document.documentElement.setAttribute('data-theme', mode.value)
  }

  function toggle() {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  }

  watch(mode, (v) => {
    localStorage.setItem(LS_KEY, v)
    apply()
  })

  apply()

  return { mode, toggle }
})
