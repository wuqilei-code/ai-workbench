import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'highlight.js/styles/github-dark.css'
import 'markstream-vue/index.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import { useAuthStore } from './stores/auth'
import { initCodeCopy } from './utils/codeCopy'
import { preloadCodeBlockRuntime } from 'markstream-vue'

async function bootstrap() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)
  app.use(i18n)

  // 未捕获的组件异常:记录日志(渲染层的兜底由 ErrorBoundary 处理)
  app.config.errorHandler = (err, _instance, info) => {
    console.error('[vue error]', info, err)
  }
  // 未处理的 Promise 异常(如 SSE/异步回调):记录,避免静默失败
  window.addEventListener('unhandledrejection', (e) => {
    console.error('[unhandledrejection]', e.reason)
  })

  // 代码块复制按钮:全局事件委托(一次注册)
  initCodeCopy()

  // 预热 markstream 代码块高亮运行时(stream-diffs),避免首屏代码块出现加载等待
  preloadCodeBlockRuntime().catch(() => {})

  // 恢复登录态(若有 token)
  const auth = useAuthStore()
  if (auth.isLoggedIn) {
    await auth.fetchMe().catch(() => {})
  }

  app.mount('#app')
}

bootstrap()
