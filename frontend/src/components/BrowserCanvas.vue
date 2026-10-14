<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { sandboxInfo, type SandboxInfo } from '../api/browser'

/**
 * AIO 沙箱浏览器实时视图:嵌入沙箱内置 Chromium 的 VNC 实时画面(noVNC)。
 * 用户可直接在画面上点击/输入接管;AI 通过 CDP 驱动的也是同一个浏览器,双方共享上下文。
 * 仅保留浏览器视图(VSCode / 终端已移除)。
 */

const { t } = useI18n()

// autoActive / follow 为历史兼容属性(父组件仍会传入);VNC 模式下 iframe 常驻渲染
withDefaults(
  defineProps<{ autoActive?: boolean; follow?: boolean }>(),
  { autoActive: true, follow: false },
)

const info = ref<SandboxInfo | null>(null)
const loading = ref(true)
const errorText = ref('')

let retryTimer: ReturnType<typeof setTimeout> | null = null
let attempts = 0

async function loadInfo() {
  try {
    const data = await sandboxInfo()
    if (data.vnc_url) {
      info.value = data
      loading.value = false
      errorText.value = ''
      return
    }
    errorText.value = data.error || t('browser.startFailed')
  } catch {
    errorText.value = t('browser.loadFailed')
  }
  attempts += 1
  if (attempts < 20) {
    retryTimer = setTimeout(loadInfo, 3000)
  } else {
    loading.value = false
    errorText.value = errorText.value || t('browser.timeout')
  }
}

onMounted(() => { loadInfo() })

onBeforeUnmount(() => {
  if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
})
</script>

<template>
  <div class="bc-root">
    <div class="bc-stage">
      <iframe
        v-if="info?.vnc_url"
        :src="info.vnc_url"
        class="bc-frame"
        frameborder="0"
        allow="clipboard-read; clipboard-write"
      ></iframe>
      <div v-else class="bc-placeholder">
        <div class="bc-spinner" v-if="loading"></div>
        <div class="bc-msg">{{ errorText || t('browser.placeholder') }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.bc-root {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.bc-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  background: #0a0b0d;
  overflow: hidden;
}
.bc-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: none;
  background: #0a0b0d;
}
.bc-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: #999;
  font-size: 12px;
  text-align: center;
  padding: 12px;
}
.bc-spinner {
  width: 26px;
  height: 26px;
  border: 3px solid rgba(255, 255, 255, 0.15);
  border-top-color: var(--accent-text, #5aa2ff);
  border-radius: 50%;
  animation: bc-spin 0.9s linear infinite;
}
@keyframes bc-spin {
  to { transform: rotate(360deg); }
}
</style>
