import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'highlight.js/styles/github-dark.css'
import 'markstream-vue/index.css'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'
import { initCodeCopy } from './utils/codeCopy'
import { preloadCodeBlockRuntime } from 'markstream-vue'

async function bootstrap() {
  const app = createApp(App)
  app.use(createPinia())
  app.use(router)

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
