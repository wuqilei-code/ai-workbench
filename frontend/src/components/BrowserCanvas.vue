<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { chatApi } from '@/api/chat'

/**
 * 共享浏览器实时画面(仅供查看 + 接管):
 * - 后端每帧推送 JPEG 二进制(WebSocket),解码后直接画到 Canvas 展示(保证可用性);
 * - 只经技能(browser-operate)唤出,无地址栏/后退前进/标签页管理;
 * - 在画面上点击/滚轮/键盘直接发送接管事件,与 AI 共享同一浏览器上下文。
 */

const props = withDefaults(
  defineProps<{ autoActive?: boolean; follow?: boolean }>(),
  { autoActive: true, follow: false },
)

const canvasRef = ref<HTMLCanvasElement | null>(null)
const stateText = ref('连接中…')
const stateOk = ref(false)
const connected = ref(false)
const clock = ref(0)

// 非响应式:每帧都更新也不触发 Vue 重渲染(实时指示靠 clock 每秒刷新一次读取)
let lastFrameAt = 0

let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null
let warmupTimer: ReturnType<typeof setInterval> | null = null

// 拖拽状态
let dragging = false
let dragActive = false  // 已越过 5px 抖动阈值,算真正的拖拽
let pressX = 0
let pressY = 0
let lastDragSend = 0

function getToken(): string {
  return localStorage.getItem('token') || ''
}

function getWsUrl(): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${proto}//${location.host}/ws/browser?token=${getToken()}`
}

function connect() {
  if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
  ws = new WebSocket(getWsUrl())
  ws.binaryType = 'arraybuffer'

  ws.onopen = () => {
    connected.value = true
    stateText.value = ''
    stateOk.value = true
    stopWarmupRetry()
  }

  ws.onmessage = (ev) => {
    if (ev.data instanceof ArrayBuffer) {
      const bytes = new Uint8Array(ev.data)
      const x = (bytes[0] << 8) | bytes[1]
      const y = (bytes[2] << 8) | bytes[3]
      const jpeg = bytes.slice(8)
      onRect(x, y, jpeg.buffer.slice(jpeg.byteOffset, jpeg.byteOffset + jpeg.byteLength))
      lastFrameAt = Date.now()
      stateText.value = ''
      stateOk.value = true
      stopWarmupRetry()
      return
    }
    try {
      const data = JSON.parse(ev.data)
      if (data.type === 'state') {
        handleState(data.state || '')
      } else if (data.type === 'error') {
        stateText.value = data.message || '浏览器未就绪'
        stateOk.value = false
      } else if (data.type === 'frame-meta') {
        const canvas = canvasRef.value
        if (canvas && (canvas.width !== data.width || canvas.height !== data.height)) {
          canvas.width = data.width
          canvas.height = data.height
        }
      }
    } catch { /* ignore parse errors */ }
  }

  ws.onclose = () => {
    connected.value = false
    stateOk.value = false
    stateText.value = '连接断开，重连中…'
    scheduleReconnect()
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connect() }, 2000)
}

function handleState(state: string) {
  if (state === 'no-agent') {
    stateText.value = '浏览器引擎启动中,首次约需 10~30 秒…'
    stateOk.value = false
    warmup()
    // 兜底重试:即使后端 warmup 未触发/失败,周期重试直至就绪
    if (!warmupTimer) {
      warmupTimer = setInterval(() => { warmup() }, 15000)
    }
  } else if (state === 'empty') {
    stateText.value = ''
    stateOk.value = true
  }
}

/** 启动/预热该用户 agent 容器:浏览器 daemon 就绪后帧流自动推回 */
function warmup() {
  chatApi.warmupMine().catch(() => {})
}

function stopWarmupRetry() {
  if (warmupTimer) {
    clearInterval(warmupTimer)
    warmupTimer = null
  }
}

/** 脏矩形绘制:收到某区域 JPEG,解码后贴到画布对应位置(其余区域保留)。 */
function onRect(x: number, y: number, buf: ArrayBuffer) {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const blob = new Blob([buf], { type: 'image/jpeg' })
  if ('createImageBitmap' in window) {
    createImageBitmap(blob)
      .then((bmp) => {
        ctx.drawImage(bmp, x, y)
        bmp.close()
      })
      .catch(() => {})
  } else {
    const url2 = URL.createObjectURL(blob)
    const img = new Image()
    img.onload = () => {
      ctx.drawImage(img, x, y)
      URL.revokeObjectURL(url2)
    }
    img.src = url2
  }
}

function sendWs(obj: Record<string, unknown>) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj))
}

function canvasCoords(e: MouseEvent): { x: number; y: number } {
  const canvas = canvasRef.value
  if (!canvas) return { x: 0, y: 0 }
  const rect = canvas.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0 || canvas.width <= 0 || canvas.height <= 0) {
    return { x: 0, y: 0 }
  }
  // canvas 以 object-fit:contain 居中显示在盒内,须按实际内容区(去黑边)映射
  const s = Math.min(rect.width / canvas.width, rect.height / canvas.height)
  const cw = canvas.width * s
  const ch = canvas.height * s
  const left = rect.left + (rect.width - cw) / 2
  const top = rect.top + (rect.height - ch) / 2
  const x = Math.round((e.clientX - left) * (canvas.width / cw))
  const y = Math.round((e.clientY - top) * (canvas.height / ch))
  // 指针拖出画布时钳制到画布范围内,避免坐标越界
  return {
    x: Math.max(0, Math.min(canvas.width - 1, x)),
    y: Math.max(0, Math.min(canvas.height - 1, y)),
  }
}

function onCanvasMouseDown(e: MouseEvent) {
  if (e.button !== 0) return
  const { x, y } = canvasCoords(e)
  dragging = true
  dragActive = false
  pressX = x
  pressY = y
  sendWs({ action: 'press', x, y })
  // 拖出画布后 mouseup 收不到:改用 window 级监听,确保一定释放
  window.addEventListener('mousemove', onWindowMouseMove)
  window.addEventListener('mouseup', onWindowMouseUp)
}

let lastHoverSend = 0
function onCanvasHover(e: MouseEvent) {
  if (dragging) return  // 拖动期间由 window 级监听处理
  const now = Date.now()
  if (now - lastHoverSend < 40) return  // ~25fps 悬停转发,避免高频淹没
  lastHoverSend = now
  const { x, y } = canvasCoords(e)
  sendWs({ action: 'move', x, y })
}

function onWindowMouseMove(e: MouseEvent) {
  if (!dragging) return
  e.preventDefault()
  const { x, y } = canvasCoords(e)
  const dx = x - pressX
  const dy = y - pressY
  if (!dragActive && dx * dx + dy * dy < 25) return  // 5px 抖动容忍:不打断点击
  dragActive = true
  const now = Date.now()
  if (now - lastDragSend < 30) return  // 节流,避免高频 mousemove 淹没
  lastDragSend = now
  sendWs({ action: 'drag', x, y })
}

function onWindowMouseUp(e: MouseEvent) {
  if (e.button !== 0 || !dragging) return
  dragging = false
  const { x, y } = canvasCoords(e)
  // 未拖动时回到按下点释放,保证点击命中按下的元素;拖动则在当前点释放
  sendWs({ action: 'release', x: dragActive ? x : pressX, y: dragActive ? y : pressY })
  window.removeEventListener('mousemove', onWindowMouseMove)
  window.removeEventListener('mouseup', onWindowMouseUp)
}

function onCanvasWheel(e: WheelEvent) {
  e.preventDefault()
  const { x, y } = canvasCoords(e)
  sendWs({ action: 'wheel', x, y, deltaY: Math.max(-1000, Math.min(1000, e.deltaY)) })
}

function onCanvasContextMenu(e: MouseEvent) {
  e.preventDefault()
  const { x, y } = canvasCoords(e)
  sendWs({ action: 'rightclick', x, y })
}

function onCanvasKeyDown(e: KeyboardEvent) {
  if (document.activeElement !== canvasRef.value) return
  e.preventDefault()
  const keyMap: Record<string, string> = {
    Enter: 'Enter', Backspace: 'Backspace', Tab: 'Tab',
    Escape: 'Escape', Delete: 'Delete', ArrowUp: 'ArrowUp',
    ArrowDown: 'ArrowDown', ArrowLeft: 'ArrowLeft', ArrowRight: 'ArrowRight',
    Home: 'Home', End: 'End', PageUp: 'PageUp', PageDown: 'PageDown',
    ' ': 'Space',
  }
  sendWs({ action: 'key', key: keyMap[e.key] || e.key })
}

// 按需推流:鼠标移入面板才推帧,移出即停(省流量/CPU);
// follow=true(agent 正在操作浏览器)时强制保持实时,鼠标移出也持续推帧
let pointerInside = false
function setLive(on: boolean) { sendWs({ action: 'active', on }) }
function onPanelEnter() {
  pointerInside = true
  setLive(true)
}
function onPanelLeave() {
  pointerInside = false
  if (!props.follow) setLive(false)
}
watch(
  () => props.follow,
  (f) => {
    if (f) setLive(true)
    else if (!pointerInside) setLive(false)
  },
)

onMounted(() => {
  // 打开面板即拉起 agent 容器,避免「no-agent」白屏等待
  warmup()
  connect()
  // 自动唤出模式:挂载即开始推流(不必等鼠标移入),让用户立即看到浏览器画面
  if (props.autoActive) {
    setTimeout(() => setLive(true), 300)
  }
  tickTimer = setInterval(() => { clock.value = Date.now() }, 1000)
})

onBeforeUnmount(() => {
  if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  if (warmupTimer) { clearInterval(warmupTimer); warmupTimer = null }
  if (ws) { ws.close(); ws = null }
})
</script>

<template>
  <div class="browser-canvas" @mouseenter="onPanelEnter" @mouseleave="onPanelLeave">
    <div class="bc-stage" :class="{ 'bc-empty': !stateOk }">
      <canvas
        ref="canvasRef"
        class="bc-canvas"
        tabindex="0"
        @mousedown="onCanvasMouseDown"
        @mousemove="onCanvasHover"
        @contextmenu.prevent="onCanvasContextMenu"
        @wheel.prevent="onCanvasWheel"
        @keydown="onCanvasKeyDown"
      ></canvas>
      <div v-if="!stateOk" class="bc-placeholder">{{ stateText }}</div>
      <div v-if="stateOk" class="bc-live" :class="{ 'bc-live-cold': clock - lastFrameAt > 8000 }">● 实时</div>
      <div v-if="stateOk && !connected" class="bc-badge">连接断开</div>
    </div>
  </div>
</template>

<style scoped>
.browser-canvas {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.bc-stage {
  position: relative;
  flex: 1;
  min-height: 0;
  background: #000;
  overflow: hidden;
}
.bc-canvas {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  cursor: default;
  outline: none;
  user-select: none;
  -webkit-user-select: none;
  touch-action: none;
}
.bc-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #999;
  font-size: 12px;
  text-align: center;
  padding: 12px;
  pointer-events: none;
}
.bc-live {
  position: absolute;
  right: 8px;
  bottom: 8px;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  color: #7fe0a0;
  background: rgba(0, 0, 0, 0.65);
  border: 1px solid var(--border-soft);
}
.bc-live-cold {
  color: #888;
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
  background: rgba(0, 0, 0, 0.6);
  border: 1px solid var(--border-soft);
}
</style>