<script setup lang="ts">
/**
 * 模板缩略图:用沙箱 iframe 渲染自包含 HTML 样板,等比缩放到容器宽度,裁剪为"文档预览缩略图"。
 * - 懒渲染:进入视口(含 200px 预取)后才挂 iframe,避免同屏大量 iframe 开销。
 * - 纯 CSS 等比缩放:iframe 固定 1000px 宽,scale = 容器宽/1000;高度按容器高度换算后裁剪。
 * - sandbox="" 禁用脚本,仅静态渲染,安全。
 */
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const props = withDefaults(defineProps<{ html?: string; height?: number }>(), { height: 240 })

const wrap = ref<HTMLElement | null>(null)
const visible = ref(false)
const ready = ref(false)
const scale = ref(0.2)
let io: IntersectionObserver | null = null
let ro: ResizeObserver | null = null

const FRAME_W = 1000

function measure() {
  const el = wrap.value
  if (!el) return
  const w = el.clientWidth
  if (w > 0) {
    scale.value = Math.max(0.05, w / FRAME_W)
    ready.value = true
  }
}

onMounted(async () => {
  await nextTick()
  measure()
  const el = wrap.value
  if (!el) return
  if (typeof IntersectionObserver !== 'undefined') {
    io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        visible.value = true
        io?.disconnect()
        io = null
        nextTick(measure)
      }
    }, { rootMargin: '200px' })
    io.observe(el)
  } else {
    visible.value = true
  }
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(measure)
    ro.observe(el)
  }
})

onBeforeUnmount(() => {
  io?.disconnect()
  ro?.disconnect()
})
</script>

<template>
  <div ref="wrap" class="tpl-thumb" :style="{ height: height + 'px' }">
    <iframe
      v-if="visible && html && ready"
      class="tpl-thumb-frame"
      :srcdoc="html"
      sandbox=""
      scrolling="no"
      tabindex="-1"
      :style="{ width: FRAME_W + 'px', height: Math.ceil(height / scale) + 'px', transform: `scale(${scale})` }"
    ></iframe>
    <div v-else class="tpl-thumb-ph">{{ html ? t('tplThumb2.preview') : t('tplThumb2.noPreview') }}</div>
  </div>
</template>

<style scoped>
.tpl-thumb {
  position: relative; overflow: hidden; background: #fff;
  border-radius: 10px; border: 1px solid var(--border-soft);
  width: 100%;
}
.tpl-thumb-frame {
  position: absolute; top: 0; left: 0; border: 0; background: #fff;
  transform-origin: top left; pointer-events: none;
}
.tpl-thumb-ph {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  color: var(--text-4); font-size: 12px; background: var(--bg-elev);
}
</style>
