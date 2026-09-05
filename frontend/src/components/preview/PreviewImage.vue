<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

/**
 * 图片预览增强版:
 * - 滚轮缩放(以中心为锚)、拖拽平移、双击 在「适应窗口 ⇄ 实际尺寸」间切换;
 * - 旋转(90° 步进)、重置、全屏查看;
 * - 状态栏展示 文件名 / 分辨率 / 当前缩放。
 * 图片走 blob URL,自包含的 SVG/PNG/JPG 均按纯图像渲染。
 */
const props = defineProps<{ src: string; path?: string }>()

const stageEl = ref<HTMLElement | null>(null)
const imgEl = ref<HTMLImageElement | null>(null)
const scale = ref(1)
const rotation = ref(0)
const tx = ref(0)
const ty = ref(0)
/** 'fit' 适应窗口 | 'actual' 实际尺寸 | 'custom' 用户手动缩放 */
const mode = ref<'fit' | 'actual' | 'custom'>('fit')
const info = ref<{ nw: number; nh: number } | null>(null)
const stageW = ref(0)
const stageH = ref(0)

let ro: ResizeObserver | null = null
let dragging = false
let dragStartX = 0
let dragStartY = 0
let dragOrigTx = 0
let dragOrigTy = 0

/** 旋转后参与 fit 计算的图片自然宽高(90/270 时宽高互换) */
function rotatedSize() {
  if (!info.value) return null
  const swap = rotation.value % 180 !== 0
  return { nw: swap ? info.value.nh : info.value.nw, nh: swap ? info.value.nw : info.value.nh }
}

function clampScale(s: number) {
  return Math.min(32, Math.max(0.02, s))
}

function applyFit() {
  const sz = rotatedSize()
  if (!sz || stageW.value <= 0 || stageH.value <= 0) return
  const s = Math.min((stageW.value - 24) / sz.nw, (stageH.value - 24) / sz.nh)
  scale.value = clampScale(s)
  tx.value = 0
  ty.value = 0
}

function measure() {
  const el = stageEl.value
  if (!el) return
  stageW.value = el.clientWidth
  stageH.value = el.clientHeight
  if (mode.value === 'fit') applyFit()
}

function onImgLoad() {
  const img = imgEl.value
  if (!img) return
  info.value = { nw: img.naturalWidth, nh: img.naturalHeight }
  if (mode.value === 'fit') applyFit()
}

function onWheel(e: WheelEvent) {
  e.preventDefault()
  const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1
  scale.value = clampScale(scale.value * factor)
  mode.value = 'custom'
}

function onPointerDown(e: PointerEvent) {
  dragging = true
  dragStartX = e.clientX
  dragStartY = e.clientY
  dragOrigTx = tx.value
  dragOrigTy = ty.value
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
}

function onPointerMove(e: PointerEvent) {
  if (!dragging) return
  tx.value = dragOrigTx + e.clientX - dragStartX
  ty.value = dragOrigTy + e.clientY - dragStartY
}

function onPointerUp() {
  dragging = false
}

function zoomIn() {
  scale.value = clampScale(scale.value * 1.25)
  mode.value = 'custom'
}

function zoomOut() {
  scale.value = clampScale(scale.value / 1.25)
  mode.value = 'custom'
}

function fit() {
  mode.value = 'fit'
  applyFit()
}

function actual() {
  mode.value = 'actual'
  scale.value = 1
  tx.value = 0
  ty.value = 0
}

function rotate() {
  rotation.value = (rotation.value + 90) % 360
  if (mode.value === 'fit') applyFit()
}

function reset() {
  rotation.value = 0
  fit()
}

async function toggleFull() {
  const el = stageEl.value
  if (!el) return
  if (document.fullscreenElement) await document.exitFullscreen()
  else await el.requestFullscreen?.()
}

const imgStyle = computed(() => ({
  transform: `translate(${tx.value}px, ${ty.value}px) rotate(${rotation.value}deg) scale(${scale.value})`,
  cursor: 'grab',
}))

onMounted(() => {
  ro = new ResizeObserver(measure)
  if (stageEl.value) ro.observe(stageEl.value)
  const img = imgEl.value
  if (img && img.naturalWidth) onImgLoad() // 缓存可能让 load 不触发
})
onBeforeUnmount(() => ro?.disconnect())
</script>

<template>
  <div class="preview-image">
    <div ref="stageEl" class="pi-stage" @wheel.prevent="onWheel">
      <img
        ref="imgEl"
        :src="src"
        :alt="path || ''"
        class="pi-img"
        :style="imgStyle"
        @load="onImgLoad"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
        @dblclick="mode === 'fit' ? actual() : fit()"
        draggable="false"
      />
    </div>
    <div class="pi-toolbar">
      <span class="pi-name" :title="path">{{ path }}</span>
      <span v-if="info" class="pi-info">{{ info.nw }} × {{ info.nh }}px · {{ (scale * 100).toFixed(0) }}%</span>
      <div class="pi-spacer" />
      <button class="pi-btn" title="缩小 (滚轮也行)" @click="zoomOut">−</button>
      <button class="pi-btn" title="放大 (滚轮也行)" @click="zoomIn">+</button>
      <button class="pi-btn" :class="{ on: mode === 'fit' }" title="适应窗口" @click="fit">适应</button>
      <button class="pi-btn" :class="{ on: mode === 'actual' }" title="实际尺寸 (双击切换)" @click="actual">实际</button>
      <button class="pi-btn" title="旋转 90°" @click="rotate">⟳</button>
      <button class="pi-btn" title="重置" @click="reset">重置</button>
      <button class="pi-btn" title="全屏查看" @click="toggleFull">全屏</button>
    </div>
  </div>
</template>

<style scoped>
.preview-image {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.pi-stage {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #18181c;
  position: relative;
  touch-action: none;
}
.pi-img {
  max-width: none;
  user-select: none;
  transform-origin: center center;
  box-shadow: 0 2px 18px rgba(0, 0, 0, 0.5);
  border-radius: 2px;
}
.pi-img:active { cursor: grabbing; }
.pi-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-top: 1px solid var(--border-soft, #333);
  flex: 0 0 auto;
  flex-wrap: wrap;
}
.pi-name {
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--text-dim, #999);
}
.pi-info { font-size: 12px; color: var(--text-dim, #999); white-space: nowrap; }
.pi-spacer { flex: 1; }
.pi-btn {
  font-size: 13px;
  line-height: 1.3;
  color: var(--text-dim, #aaa);
  background: transparent;
  border: 1px solid var(--border-soft, #444);
  border-radius: 4px;
  padding: 2px 10px;
  cursor: pointer;
}
.pi-btn:hover { color: var(--text); border-color: var(--primary, #7c8cff); }
.pi-btn.on {
  color: var(--primary, #7c8cff);
  border-color: var(--primary, #7c8cff);
  background: color-mix(in srgb, var(--primary, #7c8cff) 12%, transparent);
}
</style>