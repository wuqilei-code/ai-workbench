<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'

const props = defineProps<{ content: string }>()

interface ExcalidrawElement {
  type: string
  x: number; y: number
  width: number; height: number
  angle?: number
  strokeColor?: string
  backgroundColor?: string
  fillStyle?: string
  strokeWidth?: number
  roughness?: number
  opacity?: number
  points?: number[][]
  text?: string
  fontSize?: number
  fontFamily?: number
  roundness?: { type: number }
}

const parsed = computed(() => {
  try {
    const data = JSON.parse(props.content)
    return (data.elements || []) as ExcalidrawElement[]
  } catch {
    return [] as ExcalidrawElement[]
  }
})

const viewBox = computed(() => {
  if (!parsed.value.length) return '0 0 800 600'
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  for (const el of parsed.value) {
    minX = Math.min(minX, el.x)
    minY = Math.min(minY, el.y)
    maxX = Math.max(maxX, el.x + (el.width || 0))
    maxY = Math.max(maxY, el.y + (el.height || 0))
  }
  const pad = 40
  return `${minX - pad} ${minY - pad} ${maxX - minX + pad * 2} ${maxY - minY + pad * 2}`
})

function elOpacity(el: ExcalidrawElement) {
  return (el.opacity ?? 100) / 100
}

function elFill(el: ExcalidrawElement) {
  if (!el.backgroundColor || el.backgroundColor === 'transparent') return 'none'
  return el.backgroundColor
}

function elStroke(el: ExcalidrawElement) {
  return el.strokeColor || '#1e1e1e'
}

function elStrokeW(el: ExcalidrawElement) {
  return el.strokeWidth || 2
}

function elTransform(el: ExcalidrawElement) {
  if (!el.angle) return ''
  const cx = el.x + (el.width || 0) / 2
  const cy = el.y + (el.height || 0) / 2
  return `rotate(${(el.angle * 180) / Math.PI} ${cx} ${cy})`
}

function pathD(el: ExcalidrawElement) {
  if (!el.points || !el.points.length) return ''
  return el.points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${el.x + p[0]} ${el.y + p[1]}`).join(' ')
}

function arrowHead(el: ExcalidrawElement) {
  if (!el.points || el.points.length < 2) return { x1: 0, y1: 0, x2: 0, y2: 0, angle: 0 }
  const pts = el.points
  const last = pts[pts.length - 1]
  const prev = pts[pts.length - 2]
  const x1 = el.x + prev[0], y1 = el.y + prev[1]
  const x2 = el.x + last[0], y2 = el.y + last[1]
  const angle = Math.atan2(y2 - y1, x2 - x1)
  return { x1, y1, x2, y2, angle }
}

function arrowHeadPath(el: ExcalidrawElement) {
  const { x2, y2, angle } = arrowHead(el)
  const len = 16
  const a1 = angle + Math.PI * 0.82
  const a2 = angle - Math.PI * 0.82
  return `M ${x2} ${y2} L ${x2 + len * Math.cos(a1)} ${y2 + len * Math.sin(a1)} M ${x2} ${y2} L ${x2 + len * Math.cos(a2)} ${y2 + len * Math.sin(a2)}`
}

function roundness(el: ExcalidrawElement) {
  return el.roundness?.type === 3 ? Math.min(el.width, el.height) * 0.1 : 0
}
</script>

<template>
  <div v-if="!parsed.length" class="exca-empty">
    <span>无法解析 Excalidraw 内容</span>
  </div>
  <svg v-else class="exca-svg" :viewBox="viewBox" xmlns="http://www.w3.org/2000/svg">
    <template v-for="(el, i) in parsed" :key="i">
      <!-- 矩形 -->
      <rect
        v-if="el.type === 'rectangle'"
        :x="el.x" :y="el.y" :width="el.width" :height="el.height"
        :rx="roundness(el)" :ry="roundness(el)"
        :fill="elFill(el)" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
        :opacity="elOpacity(el)" :transform="elTransform(el)"
      />
      <!-- 椭圆 -->
      <ellipse
        v-else-if="el.type === 'ellipse'"
        :cx="el.x + el.width / 2" :cy="el.y + el.height / 2"
        :rx="el.width / 2" :ry="el.height / 2"
        :fill="elFill(el)" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
        :opacity="elOpacity(el)" :transform="elTransform(el)"
      />
      <!-- 菱形 -->
      <polygon
        v-else-if="el.type === 'diamond'"
        :points="`${el.x + el.width/2},${el.y} ${el.x + el.width},${el.y + el.height/2} ${el.x + el.width/2},${el.y + el.height} ${el.x},${el.y + el.height/2}`"
        :fill="elFill(el)" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
        :opacity="elOpacity(el)" :transform="elTransform(el)"
      />
      <!-- 线条 -->
      <path
        v-else-if="el.type === 'line'"
        :d="pathD(el)" fill="none" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
        :opacity="elOpacity(el)" stroke-linecap="round" stroke-linejoin="round"
      />
      <!-- 箭头 -->
      <g v-else-if="el.type === 'arrow'" :opacity="elOpacity(el)" :transform="elTransform(el)">
        <path :d="pathD(el)" fill="none" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
          stroke-linecap="round" stroke-linejoin="round" />
        <path :d="arrowHeadPath(el)" fill="none" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
          stroke-linecap="round" stroke-linejoin="round" />
      </g>
      <!-- 文字 -->
      <text
        v-else-if="el.type === 'text'"
        :x="el.x" :y="el.y + (el.fontSize || 20)"
        :fill="elStroke(el)" :font-size="el.fontSize || 20" :font-family="el.fontFamily === 3 ? 'monospace' : 'sans-serif'"
        :opacity="elOpacity(el)" :transform="elTransform(el)"
      >{{ el.text }}</text>
      <!-- freedraw: 用 points 连线 -->
      <path
        v-else-if="el.type === 'freedraw'"
        :d="pathD(el)" fill="none" :stroke="elStroke(el)" :stroke-width="elStrokeW(el)"
        :opacity="elOpacity(el)" stroke-linecap="round" stroke-linejoin="round"
      />
    </template>
  </svg>
</template>

<style scoped>
.exca-empty {
  display: flex; align-items: center; justify-content: center;
  height: 200px; color: var(--text-3); font-size: 14px;
}
.exca-svg {
  width: 100%; height: 100%; min-height: 300px; max-height: 80vh;
  background: #fff; border-radius: 8px;
}
</style>
