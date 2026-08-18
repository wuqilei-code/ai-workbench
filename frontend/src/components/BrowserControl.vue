<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useMessage } from 'naive-ui'
import { browserLive, browserAction } from '@/api/browser'

/**
 * 共享浏览器实时面板 + 用户接管:
 * - 实时显示 agent(或用户自己)当前浏览器页面的截图流(约 1.5s/帧);
 * - 点击画面 → 在对应坐标执行点击;滚轮 → 滚动;地址栏 → 跳转;输入框 → 向当前聚焦元素输入。
 * 与 AI 共享同一浏览器上下文(agent 容器内 playwright-mcp, PLAYWRIGHT_MCP_ISOLATED=false + --shared-browser-context)。
 */

const msg = useMessage()

/** playwright-mcp 无头浏览器默认视口(CSS px),点击坐标按此换算 */
const VIEW_W = 1280
const VIEW_H = 720

const imageSrc = ref('')
const stateText = ref('连接中…')
const stateOk = ref(false)
const url = ref('')
const typing = ref('')
const busy = ref(false)

let live: { close: () => void } | null = null

onMounted(() => {
  live = browserLive({
    onFrame: (image) => {
      imageSrc.value = `data:image/png;base64,${image}`
      stateText.value = ''
      stateOk.value = true
    },
    onState: (state, message) => {
      if (state === 'no-agent') {
        stateText.value = 'agent 未启动,请先在聊天中发起会话'
        stateOk.value = false
      } else if (state === 'error') {
        stateText.value = message || '浏览器未就绪'
        stateOk.value = false
      } else if (state === 'empty') {
        stateText.value = ''
        stateOk.value = true
      }
    },
  })
})

onBeforeUnmount(() => {
  live?.close()
  live = null
})

async function run(tool: string, args: Record<string, unknown>) {
  if (busy.value) return
  busy.value = true
  try {
    await browserAction(tool, args)
  } catch (e: any) {
    msg.error(`操作失败: ${e?.message || e}`)
  } finally {
    busy.value = false
  }
}

function onImgClick(e: MouseEvent) {
  const el = e.currentTarget as HTMLImageElement
  const rect = el.getBoundingClientRect()
  const x = Math.round(((e.clientX - rect.left) / rect.width) * VIEW_W)
  const y = Math.round(((e.clientY - rect.top) / rect.height) * VIEW_H)
  run('browser_mouse_click_xy', { x, y })
}

function onImgWheel(e: WheelEvent) {
  const deltaY = Math.max(-1000, Math.min(1000, e.deltaY))
  run('browser_mouse_wheel', { deltaX: 0, deltaY })
}

function go() {
  const u = url.value.trim()
  if (!u) return
  let target = u
  if (!/^https?:\/\//i.test(u) && !/^about:/i.test(u) && !/^data:/i.test(u)) {
    target = 'https://' + u
  }
  run('browser_navigate', { url: target })
}

function back() {
  run('browser_navigate_back', {})
}

function forward() {
  run('browser_run_code_unsafe', {
    code: 'async (page) => { await page.goForward(); return "ok"; }',
  })
}

function reload() {
  run('browser_run_code_unsafe', {
    code: 'async (page) => { await page.reload(); return "ok"; }',
  })
}

function sendText() {
  const t = typing.value
  if (!t) return
  run('browser_run_code_unsafe', {
    code: `async (page) => { await page.keyboard.type(${JSON.stringify(t)}); return "typed"; }`,
  })
  typing.value = ''
}

function pressEnter() {
  run('browser_run_code_unsafe', {
    code: 'async (page) => { await page.keyboard.press("Enter"); return "ok"; }',
  })
}
</script>

<template>
  <div class="browser-control">
    <div class="bc-toolbar">
      <n-button size="tiny" :disabled="busy" @click="back" title="后退">← 后退</n-button>
      <n-button size="tiny" :disabled="busy" @click="forward" title="前进">前进 →</n-button>
      <n-button size="tiny" :disabled="busy" @click="reload" title="刷新">刷新</n-button>
      <n-input
        v-model:value="url"
        size="tiny"
        placeholder="输入网址后回车"
        class="bc-url"
        @keyup.enter="go"
        @blur="go"
      />
      <n-button size="tiny" type="primary" :disabled="busy" @click="go">前往</n-button>
    </div>

    <div class="bc-stage" :class="{ 'bc-empty': !imageSrc }">
      <img
        v-if="imageSrc"
        :src="imageSrc"
        alt="浏览器实时画面"
        class="bc-img"
        @click="onImgClick"
        @wheel.prevent="onImgWheel"
        @dragstart.prevent
      />
      <div v-else class="bc-placeholder">{{ stateText }}</div>
      <div v-if="imageSrc && !stateOk" class="bc-badge">{{ stateText }}</div>
    </div>

    <div class="bc-type">
      <n-input
        v-model:value="typing"
        size="tiny"
        placeholder="输入文字(先点击页面中的输入框定位光标)"
        @keyup.enter="sendText"
      />
      <n-button size="tiny" type="primary" :disabled="busy" @click="sendText">输入</n-button>
      <n-button size="tiny" :disabled="busy" @click="pressEnter">回车</n-button>
    </div>

    <div class="bc-hint">点击画面可在对应位置执行点击;滚轮滚动页面;与 AI 共享同一浏览器。</div>
  </div>
</template>

<style scoped>
.browser-control {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  padding: 4px;
}
.bc-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
}
.bc-url {
  flex: 1;
  min-width: 0;
}
.bc-stage {
  position: relative;
  flex: 1;
  min-height: 200px;
  border: 1px solid var(--border-soft);
  border-radius: 6px;
  background: var(--surface);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.bc-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  cursor: crosshair;
  user-select: none;
}
.bc-empty {
  cursor: default;
}
.bc-placeholder {
  color: var(--text-3);
  font-size: 12px;
  text-align: center;
  padding: 12px;
}
.bc-badge {
  position: absolute;
  left: 8px;
  bottom: 8px;
  max-width: calc(100% - 16px);
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--warn);
  background: var(--surface-hover);
  border: 1px solid var(--border-soft);
}
.bc-type {
  display: flex;
  align-items: center;
  gap: 4px;
}
.bc-hint {
  font-size: 11px;
  color: var(--text-3);
}
</style>
