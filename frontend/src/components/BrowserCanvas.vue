<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { browserStop, sandboxInfo, type SandboxInfo } from '../api/browser'
import { useDialog } from 'naive-ui'

/**
 * AIO 沙箱实时视图(浏览器 VNC / VSCode / 终端):
 * - 浏览器:嵌入 AIO 内置 Chromium 的 VNC 实时画面(noVNC),用户可直接在画面上点击/输入接管,
 *   AI 通过 CDP 驱动的也是同一个浏览器,双方共享上下文。
 * - VSCode:嵌入沙箱自带 code-server,可直接查看/编辑工作区代码。
 * - 终端:嵌入沙箱自带 WebTerminal。
 * 三个视图都是对同一个沙箱容器的 iframe,文件系统共享。
 */

const emit = defineEmits<{ (e: 'stopped'): void }>()
const dialog = useDialog()

// autoActive / follow 为历史兼容属性(父组件仍会传入);VNC 模式下 iframe 常驻渲染
withDefaults(
  defineProps<{ autoActive?: boolean; follow?: boolean }>(),
  { autoActive: true, follow: false },
)

type ViewKey = 'browser' | 'editor' | 'terminal'
const view = ref<ViewKey>('browser')
const info = ref<SandboxInfo | null>(null)
const loading = ref(true)
const errorText = ref('')

let retryTimer: ReturnType<typeof setTimeout> | null = null
let attempts = 0

const TABS: { key: ViewKey; label: string; icon: string }[] = [
  { key: 'browser', label: '浏览器', icon: '🌐' },
  { key: 'editor', label: 'VSCode', icon: '🖥' },
  { key: 'terminal', label: '终端', icon: '⌨' },
]

const currentUrl = computed(() => {
  const d = info.value
  if (!d) return ''
  if (view.value === 'editor') return d.code_server_url || ''
  if (view.value === 'terminal') return d.terminal_url || ''
  return d.vnc_url || ''
})

async function loadInfo() {
  try {
    const data = await sandboxInfo()
    if (data.vnc_url) {
      info.value = data
      loading.value = false
      errorText.value = ''
      return
    }
    errorText.value = data.error || '沙箱启动中,首次约需 10~30 秒…'
  } catch {
    errorText.value = '无法连接沙箱服务,重试中…'
  }
  attempts += 1
  if (attempts < 20) {
    retryTimer = setTimeout(loadInfo, 3000)
  } else {
    loading.value = false
    errorText.value = errorText.value || '沙箱启动超时,请稍后重试。'
  }
}

function closeBrowser() {
  dialog.warning({
    title: '关闭浏览器',
    content: '将停止沙箱容器并释放资源,下次使用时自动重建。确定关闭吗?',
    positiveText: '关闭',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await browserStop()
      } catch { /* 后端失败也按已停处理,避免面板卡死 */ }
      if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
      emit('stopped')
    },
  })
}

onMounted(() => { loadInfo() })

onBeforeUnmount(() => {
  if (retryTimer) { clearTimeout(retryTimer); retryTimer = null }
})
</script>

<template>
  <div class="bc-root">
    <div class="bc-tabbar">
      <div class="bc-tabs">
        <button
          v-for="t in TABS"
          :key="t.key"
          class="bc-tab"
          :class="{ 'bc-tab-active': view === t.key }"
          :disabled="!info"
          @click="view = t.key"
        >
          <span class="bc-tab-icon">{{ t.icon }}</span>{{ t.label }}
        </button>
      </div>
      <div v-if="info" class="bc-live">● 实时</div>
      <button class="bc-tabbar-btn bc-power" title="关闭浏览器(停止沙箱容器并释放资源)" @click="closeBrowser">⏻</button>
    </div>

    <div class="bc-stage">
      <iframe
        v-if="currentUrl"
        :key="view"
        :src="currentUrl"
        class="bc-frame"
        frameborder="0"
        allow="clipboard-read; clipboard-write"
      ></iframe>
      <div v-else class="bc-placeholder">
        <div class="bc-spinner" v-if="loading"></div>
        <div class="bc-msg">{{ errorText || '沙箱启动中…' }}</div>
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
.bc-tabbar {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  padding: 0 8px;
  background: var(--bg-soft, #1e1f22);
  border-bottom: 1px solid var(--border-soft, #333);
  flex-shrink: 0;
}
.bc-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}
.bc-tab {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 12px;
  border: none;
  border-radius: 7px;
  font-size: 12px;
  color: var(--text-2, #bbb);
  background: transparent;
  cursor: pointer;
  white-space: nowrap;
  transition: background .12s, color .12s;
}
.bc-tab:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.07);
  color: #eee;
}
.bc-tab:disabled {
  opacity: 0.45;
  cursor: default;
}
.bc-tab-active {
  color: #fff;
  background: var(--accent-soft, rgba(88, 156, 255, 0.18));
  box-shadow: inset 0 0 0 1px var(--accent-o40, rgba(88, 156, 255, 0.28));
}
.bc-tab-icon {
  font-size: 13px;
}
.bc-live {
  flex-shrink: 0;
  padding: 3px 10px;
  border-radius: 11px;
  font-size: 11px;
  color: #7fe0a0;
  background: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(127, 224, 160, 0.35);
  user-select: none;
}
.bc-tabbar-btn {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 7px;
  background: transparent;
  color: var(--text-2, #aaa);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  transition: background .12s, color .12s, transform .05s;
}
.bc-tabbar-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}
.bc-power:hover {
  color: #ff7b72;
  background: rgba(255, 123, 114, 0.12);
}
.bc-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  margin: 6px;
  border-radius: 8px;
  border: 1px solid var(--border-soft, #333);
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
