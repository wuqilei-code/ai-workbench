<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { fileApi } from '@/api/files'
import { extToLang } from './ext'
import { injectSvgLineFillFix } from '@/utils/svgFix'

/**
 * 交互式 HTML 预览:支持「页面(渲染执行)/代码(高亮源码)」两种视图切换。
 * - 页面视图:把工作区 .html 作为完整文档在 sandbox iframe(srcdoc)中渲染。
 *   相对资源(img/src/href)在 load 阶段被预取并替换为 blob URL,
 *   因此即使 srcdoc 无 base URL,相对图片/样式/脚本也能正常加载;
 *   拉取走 fileApi.fetchBlob(JWT header),安全且不暴露 cookie。
 * - 代码视图:高亮显示源码(可复制/审查)。
 * 安全:iframe 用 sandbox="allow-scripts allow-popups"(无 allow-same-origin),
 * 脚本运行在无源孤立环境,无法访问宿主应用同源数据。
 */
const { t } = useI18n()
const props = defineProps<{ path: string }>()

const mode = ref<'page' | 'code'>('page')
const raw = ref('')
const pageDoc = ref('')
const loading = ref(true)
const error = ref<string | null>(null)
/** 响应式断点宽度(null=自适应窗口),用于模拟手机/平板/桌面页面布局 */
const bp = ref<number | null>(null)
/** 禁用脚本开关(sandbox 去掉 allow-scripts,调试纯静态布局用) */
const scriptsOff = ref(false)
/** 刷新:递增 key 强制 iframe 重载 */
const frameKey = ref(0)
/** 本页预取产生的 blob URL,组件销毁时统一回收 */
const blobUrls: string[] = []

const codeLang = computed(() => extToLang('html'))

const sandboxAttr = computed(() => {
  const base = 'allow-popups allow-forms allow-modals'
  return scriptsOff.value ? base : `allow-scripts ${base}`
})

const holderW = computed(() => (bp.value ? `${bp.value}px` : '100%'))

/** 相对路径判定:不以 / http data # 开头的路径 */
function isRelative(url: string): boolean {
  return !!url && !url.startsWith('/') && !url.startsWith('http') && !url.startsWith('data:') && !url.startsWith('#') && !url.startsWith('javascript:')
}

/** 从 HTML 相对路径构造后端 raw 端点 URL */
function rawUrl(rel: string): string {
  const base = props.path.split('/').slice(0, -1).join('/')
  const full = base ? `${base}/${rel}` : rel
  return fileApi.rawUrl(full)
}

/** 扫描 HTML 文本,预取所有相对资源并替换为 blob URL。
 *  处理 <img src>、<link href>、<script src>、<video/src>、<audio/src>、<source/src>。 */
async function resolveResources(html: string): Promise<string> {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  // 收集需要替换的元素
  const tags: { el: Element; attr: string }[] = []
  doc.querySelectorAll('img, link, script, video, audio, source, object[data]').forEach((el) => {
    if (el.tagName === 'SCRIPT') { tags.push({ el, attr: 'src' }); return }
    if (el.tagName === 'LINK')   { tags.push({ el, attr: 'href' }); return }
    if (el.tagName === 'OBJECT') { tags.push({ el, attr: 'data' }); return }
    ;['src', 'poster', 'href'].forEach((a) => { if (el.hasAttribute(a)) tags.push({ el, attr: a }) })
  })
  if (!tags.length) return html

  // 逐个 fetch 并替换(失败静默跳过,保留原路径)
  const replacements = await Promise.allSettled(
    tags.map(async ({ el, attr }) => {
      const orig = el.getAttribute(attr)
      if (!orig || !isRelative(orig)) return null
      try {
        const blob = await fileApi.fetchBlob(rawUrl(orig))
        const url = URL.createObjectURL(blob)
        blobUrls.push(url)
        return { orig, url }
      } catch { return null }
    }),
  )
  let result = html
  for (const r of replacements) {
    if (r.status === 'fulfilled' && r.value) {
      // 用全局替换替换属性值
      result = result.split(r.value.orig).join(r.value.url)
    }
  }
  return result
}

/** charset 注入(保留原有逻辑) */
function injectCharset(html: string): string {
  let body = html
  if (!/<meta\s+charset/i.test(body)) {
    body = body.replace(/<head([^>]*)>/i, `<head$1><meta charset="utf-8">`) ||
           `<meta charset="utf-8">` + body
  } else {
    body = body.replace(/(<meta\s+charset\s*=\s*["']?)[^"'\s>]+/i, '$1utf-8')
  }
  return body
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const text = await fileApi.content(props.path)
    if (!text && text !== '') throw new Error('empty')
    const html = String(text)
    raw.value = html
    const resolved = await resolveResources(html)
    pageDoc.value = injectSvgLineFillFix(injectCharset(resolved))
  } catch (e: any) {
    error.value = e?.message || t('preview.loadFailed')
  } finally {
    loading.value = false
  }
}

watch(() => props.path, () => {
  frameKey.value++
  load()
}, { immediate: true })

onBeforeUnmount(() => {
  blobUrls.forEach((u) => URL.revokeObjectURL(u))
})

function reload() {
  frameKey.value++
}

function toggleMode() {
  mode.value = mode.value === 'page' ? 'code' : 'page'
}
</script>

<template>
  <div class="preview-html">
    <div class="ph-toolbar">
      <span class="ph-path" :title="path">{{ path }}</span>
      <div class="ph-spacer" />
      <button
        class="ph-tool"
        :class="{ on: scriptsOff }"
        :title="scriptsOff ? t('preview.scriptsOffTitle') : t('preview.scriptsOnTitle')"
        @click="scriptsOff = !scriptsOff"
      >{{ t('preview.scriptsLabel') }}{{ scriptsOff ? t('preview.stateOff') : t('preview.stateOn') }}</button>
      <button class="ph-tool" :title="t('preview.reload')" @click="reload">{{ t('preview.reload') }}</button>
      <div class="ph-bps">
        <button class="ph-bp" :class="{ on: bp === null }" @click="bp = null">{{ t('preview.bpAuto') }}</button>
        <button class="ph-bp" :class="{ on: bp === 1280 }" @click="bp = 1280">{{ t('preview.bpDesktop') }}</button>
        <button class="ph-bp" :class="{ on: bp === 768 }" @click="bp = 768">{{ t('preview.bpTablet') }}</button>
        <button class="ph-bp" :class="{ on: bp === 390 }" @click="bp = 390">{{ t('preview.bpMobile') }}</button>
      </div>
      <div class="ph-modes">
        <button class="ph-mode" :class="{ on: mode === 'page' }" @click="mode = 'page'">{{ t('preview.modePage') }}</button>
      <button class="ph-mode" :class="{ on: mode === 'code' }" @click="mode = 'code'">{{ t('preview.modeCode') }}</button>
      </div>
    </div>
    <div class="ph-body">
      <div v-if="mode === 'page'" class="ph-holder">
        <iframe
          :key="'frame-' + frameKey"
          class="ph-frame"
          :srcdoc="pageDoc"
          :sandbox="sandboxAttr"
          :style="{ width: holderW, height: '100%' }"
          :title="t('preview.htmlPreviewTitle')"
        />
      </div>
      <n-spin v-else :show="loading">
        <div v-if="error" class="ph-error">{{ error }}</div>
        <PreviewCode v-else-if="raw !== ''" :content="raw" :lang="codeLang" />
        <div v-else class="ph-empty">{{ t('preview.htmlEmpty') }}</div>
      </n-spin>
    </div>
  </div>
</template>

<style scoped>
.preview-html {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.ph-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border-soft, #333);
  flex: 0 0 auto;
  flex-wrap: wrap;
}
.ph-path {
  font-size: 12px;
  color: var(--text-dim, #999);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 30%;
}
.ph-spacer { flex: 1; }
.ph-tool, .ph-bp, .ph-mode {
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-dim, #aaa);
  background: transparent;
  border: 1px solid var(--border-soft, #444);
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
}
.ph-tool:hover, .ph-bp:hover, .ph-mode:hover {
  color: var(--text);
  border-color: var(--primary, #7c8cff);
}
.ph-tool.on, .ph-bp.on, .ph-mode.on {
  color: var(--primary, #7c8cff);
  border-color: var(--primary, #7c8cff);
  background: color-mix(in srgb, var(--primary, #7c8cff) 12%, transparent);
}
.ph-bps { display: inline-flex; gap: 4px; }
.ph-modes { display: inline-flex; border: 1px solid var(--border-soft, #444); border-radius: 6px; overflow: hidden; }
.ph-mode { border: none; border-radius: 0; }
.ph-mode + .ph-mode { border-left: 1px solid var(--border-soft, #444); }
.ph-body { flex: 1; min-height: 0; }
.ph-holder {
  height: 100%;
  overflow: auto;
  display: flex;
  justify-content: center;
  background: var(--surface, #20242b);
}
.ph-frame {
  display: block;
  flex: 0 0 auto;
  border: none;
  background: var(--bg-elev, #1d2026);
  box-shadow: 0 0 12px rgba(0, 0, 0, 0.4);
  overflow: hidden;
}
.ph-error { padding: 24px; color: #e6a23c; text-align: center; }
.ph-empty { padding: 24px; color: #999; text-align: center; }
</style>
