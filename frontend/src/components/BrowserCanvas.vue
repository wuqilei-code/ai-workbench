<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { browserStop } from '../api/browser'
import { useDialog } from 'naive-ui'

/**
 * 共享浏览器实时画面(仅供查看 + 接管):
 * - 后端每帧推送 JPEG 二进制(WebSocket),解码后直接画到 Canvas 展示(保证可用性);
 * - 只经技能(browser-operate)唤出;标签栏支持切换/关闭/新建标签页与一键关闭浏览器;
 * - 在画面上点击/滚轮/键盘直接发送接管事件,与 AI 共享同一浏览器上下文。
 */

const props = withDefaults(
  defineProps<{ autoActive?: boolean; follow?: boolean }>(),
  { autoActive: true, follow: false },
)
const emit = defineEmits<{ (e: 'stopped'): void }>()
const dialog = useDialog()

interface TabInfo {
  id: string
  title?: string
  url?: string
  active?: boolean
}

const canvasRef = ref<HTMLCanvasElement | null>(null)
const stateText = ref('连接中…')
const stateOk = ref(false)
const connected = ref(false)
const clock = ref(0)
const tabs = ref<TabInfo[]>([])
/** 用户主动「关闭浏览器」后不再自动重连/重绘(等待父组件收起面板) */
let stoppedByUser = false

// 非响应式:每帧都更新也不触发 Vue 重渲染(实时指示靠 clock 每秒刷新一次读取)
let lastFrameAt = 0
// 保活自愈:WS 半开(网络静默断掉)时 onclose 不触发,画面会永远冻结;
// 周期 ping + 活跃超时强制重连
let lastActivityAt = Date.now()
let pingTimer: ReturnType<typeof setInterval> | null = null

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

function getWsUrl(): string {
  // WebSocket 鉴权改用同源 HttpOnly cookie(握手自动携带),不再把 JWT 放进 URL,
  // 避免令牌落入访问日志/浏览器历史。见 BrowserWebSocketHandler.authenticate。
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${proto}//${location.host}/ws/browser`
}

function connect() {
  if (stoppedByUser) return
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
      const w = (bytes[4] << 8) | bytes[5]
      const h = (bytes[6] << 8) | bytes[7]
      const jpeg = bytes.slice(8)
      onRect(x, y, w, h, jpeg.buffer.slice(jpeg.byteOffset, jpeg.byteOffset + jpeg.byteLength))
      lastFrameAt = Date.now()
      lastActivityAt = lastFrameAt
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
      } else if (data.type === 'tabs') {
        tabs.value = Array.isArray(data.tabs) ? data.tabs : []
      } else if (data.type === 'pong') {
        lastActivityAt = Date.now()
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
  if (stoppedByUser) return
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => { reconnectTimer = null; connect() }, 2000)
}

// ============ 标签栏:切换 / 关闭 / 新建 + 关闭浏览器 ============

function activateTab(id: string) {
  sendWs({ action: 'activate', id })
}

function closeTab(id: string) {
  // 至少保留一个标签页:全关会让 chromium 退出,整个浏览器栈失效
  if (tabs.value.length <= 1) return
  sendWs({ action: 'close', id })
}

function newTab() {
  sendWs({ action: 'newtab' })
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
      stoppedByUser = true
      tabs.value = []
      stateOk.value = false
      connected.value = false
      stateText.value = '浏览器已关闭'
      if (pingTimer) { clearInterval(pingTimer); pingTimer = null }
      if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
      if (warmupTimer) { clearInterval(warmupTimer); warmupTimer = null }
      if (ws) { try { ws.close() } catch { /* ignore */ } ws = null }
      emit('stopped')
    },
  })
}

function handleState(state: string) {
  if (state === 'no-agent') {
    stateText.value = '浏览器引擎启动中,首次约需 10~30 秒…'
    stateOk.value = false
  } else if (state === 'empty') {
    stateText.value = ''
    stateOk.value = true
  }
}

function stopWarmupRetry() {
  if (warmupTimer) {
    clearInterval(warmupTimer)
    warmupTimer = null
  }
}

/** 脏矩形绘制:收到某区域 JPEG,解码后贴到画布对应位置(其余区域保留)。
 *  越界保护:矩形超出当前画布(如视口被外部改动/元数据丢失),自动扩正画布,避免点击映射错位。
 *  注意:rw/rh 必须由调用方从帧头解析后传入——buf 已是纯 JPEG,再从中"解析"会读到随机字节,
 *  曾导致画布被撑到几万像素、画面缩成左上角一小块。 */
function onRect(x: number, y: number, rw: number, rh: number, buf: ArrayBuffer) {
  const canvas = canvasRef.value
  if (!canvas) return
  if (rw > 0 && rh > 0 && (x + rw > canvas.width || y + rh > canvas.height)) {
    canvas.width = Math.max(canvas.width, x + rw)
    canvas.height = Math.max(canvas.height, y + rh)
  }
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
  // 画面以 object-fit:fill 拉伸铺满整个画布盒(无黑边),直接线性映射整盒
  const x = Math.round((e.clientX - rect.left) * (canvas.width / rect.width))
  const y = Math.round((e.clientY - rect.top) * (canvas.height / rect.height))
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
  // 拖拽期间指针已离开面板且非跟随模式:现在才允许停推流
  if (!pointerInside && !props.follow) setLive(false)
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
  // 拖拽中移出面板不能停推流(验证码拖动常越界):否则画面冻结、像"没反应"
  if (!props.follow && !dragging) setLive(false)
}
watch(
  () => props.follow,
  (f) => {
    if (f) setLive(true)
    else if (!pointerInside) setLive(false)
  },
)

onMounted(() => {
  // 打开面板即连 WebSocket:后端按需拉起浏览器容器,避免「no-agent」白屏等待
  connect()
  // 自动唤出模式:挂载即开始推流(不必等鼠标移入),让用户立即看到浏览器画面
  if (props.autoActive) {
    setTimeout(() => setLive(true), 300)
  }
  tickTimer = setInterval(() => { clock.value = Date.now() }, 1000)
  pingTimer = setInterval(() => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      sendWs({ action: 'ping' })
      // 20s 无任何帧/pong 视为半开死连,强制重连
      if (Date.now() - lastActivityAt > 20_000) {
        try { ws.close() } catch { /* ignore */ }
        ws = null
        connect()
      }
    }
  }, 10_000)
})

onBeforeUnmount(() => {
  if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
  if (pingTimer) { clearInterval(pingTimer); pingTimer = null }
  if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
  if (ws) { ws.close(); ws = null }
})
</script>

<template>
  <div class="browser-canvas" @mouseenter="onPanelEnter" @mouseleave="onPanelLeave">
    <div v-if="tabs.length > 0" class="bc-tabbar">
      <div class="bc-tabs">
        <div
          v-for="t in tabs"
          :key="t.id"
          class="bc-tab"
          :class="{ 'bc-tab-active': t.active }"
          :title="t.title || t.url || ''"
          @click="activateTab(t.id)"
        >
          <span class="bc-tab-label">{{ t.title || t.url || '新标签页' }}</span>
          <span
            v-if="tabs.length > 1"
            class="bc-tab-close"
            title="关闭标签页"
            @click.stop="closeTab(t.id)"
          >×</span>
        </div>
      </div>
      <button class="bc-tabbar-btn" title="新建标签页" @click="newTab">+</button>
      <button class="bc-tabbar-btn bc-power" title="关闭浏览器(停止沙箱容器并释放资源)" @click="closeBrowser">⏻</button>
    </div>
    <div class="bc-stage" :class="{ 'bc-empty': !stateOk }">
      <div class="bc-scroll">
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
      </div>
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
  margin: 6px;
  border-radius: 8px;
  border: 1px solid var(--border-soft, #333);
  background: #0a0b0d;
  overflow: hidden;
  box-shadow: inset 0 0 32px rgba(0, 0, 0, 0.45);
}
.bc-scroll {
  height: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  display: flex;
  align-items: center;
  scrollbar-width: thin;
}
.bc-canvas {
  display: block;
  height: 100%;
  width: auto;
  flex-shrink: 0;
  cursor: default;
  outline: none;
  user-select: none;
  -webkit-user-select: none;
  touch-action: none;
  background: #0a0b0d;
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
  right: 10px;
  bottom: 10px;
  padding: 3px 10px;
  border-radius: 11px;
  font-size: 11px;
  color: #7fe0a0;
  background: rgba(0, 0, 0, 0.72);
  border: 1px solid rgba(127, 224, 160, 0.35);
  animation: bc-pulse 2.4s ease-in-out infinite;
  user-select: none;
}
@keyframes bc-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}
.bc-live-cold {
  color: #888;
  border-color: var(--border-soft, #333);
  animation: none;
}
.bc-badge {
  position: absolute;
  left: 10px;
  bottom: 10px;
  max-width: calc(100% - 20px);
  padding: 3px 10px;
  border-radius: 6px;
  font-size: 11px;
  color: var(--warn);
  background: rgba(0, 0, 0, 0.7);
  border: 1px solid rgba(255, 193, 100, 0.35);
}
/* ============ 标签栏(浏览器窗口风格) ============ */
.bc-tabbar {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 38px;
  padding: 0 8px;
  background: var(--bg-soft, #1e1f22);
  border-bottom: 1px solid var(--border-soft, #333);
  flex-shrink: 0;
}
.bc-tabs {
  display: flex;
  align-items: center;
  gap: 3px;
  flex: 1;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}
.bc-tabs::-webkit-scrollbar {
  display: none;
}
.bc-tab {
  position: relative;
  display: flex;
  align-items: center;
  gap: 5px;
  max-width: 200px;
  padding: 4px 10px;
  border-radius: 7px;
  font-size: 12px;
  color: var(--text-2, #bbb);
  cursor: pointer;
  white-space: nowrap;
  user-select: none;
  transition: background .12s, color .12s;
}
.bc-tab:hover {
  background: rgba(255, 255, 255, 0.07);
  color: #eee;
}
.bc-tab-active {
  color: #fff;
  background: var(--accent-soft, rgba(88, 156, 255, 0.18));
  box-shadow: inset 0 0 0 1px var(--accent-o40, rgba(88, 156, 255, 0.28));
}
.bc-tab-active::before {
  content: '';
  flex-shrink: 0;
  width: 5px;
  height: 5px;
  margin-left: 1px;
  border-radius: 50%;
  background: var(--accent-text, #5aa2ff);
}
.bc-tab-label {
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
}
.bc-tab-close {
  flex-shrink: 0;
  padding: 0 3px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1;
  color: #999;
}
.bc-tab-close:hover {
  color: #ff7b72;
  background: rgba(255, 255, 255, 0.12);
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
.bc-tabbar-btn:active {
  transform: scale(0.94);
}
.bc-power:hover {
  color: #ff7b72;
  background: rgba(255, 123, 114, 0.12);
}
</style>