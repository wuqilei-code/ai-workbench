<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage } from 'naive-ui'
import { sandboxInfo, importCookies, type SandboxInfo } from '../api/browser'

/**
 * AIO 沙箱浏览器实时视图:嵌入沙箱内置 Chromium 的 VNC 实时画面(noVNC)。
 * 用户可直接在画面上点击/输入接管;AI 通过 CDP 驱动的也是同一个浏览器,双方共享上下文。
 * 支持「导入登录态」:把本机浏览器导出的 cookie 注入沙箱,免登录访问目标站点。
 */

const { t } = useI18n()
const msg = useMessage()

// autoActive / follow 为历史兼容属性(父组件仍会传入);VNC 模式下 iframe 常驻渲染
withDefaults(
  defineProps<{ autoActive?: boolean; follow?: boolean }>(),
  { autoActive: true, follow: false },
)

const info = ref<SandboxInfo | null>(null)
const loading = ref(true)
const errorText = ref('')
const reloadKey = ref(0)

let retryTimer: ReturnType<typeof setTimeout> | null = null
let attempts = 0

// ---- 导入登录态(cookie) ----
const showImport = ref(false)
const importText = ref('')
const siteUrl = ref('')
const importing = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

function openImport() {
  importText.value = ''
  siteUrl.value = ''
  showImport.value = true
}

function onFile(ev: Event) {
  const f = (ev.target as HTMLInputElement).files?.[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => { importText.value = String(reader.result || '') }
  reader.readAsText(f)
  ;(ev.target as HTMLInputElement).value = ''
}

/** 把多种输入(JSON / Copy as cURL / Cookie 请求头)统一解析为 cookie 数组。 */
function parseCookiesInput(text: string, url: string): unknown[] {
  const t = text.trim()
  if (!t) throw new Error('empty')
  // 1) JSON(数组,或含 cookies 数组的对象)
  if (t[0] === '[' || t[0] === '{') {
    try {
      const d = JSON.parse(t)
      if (Array.isArray(d)) return d
      if (d && Array.isArray(d.cookies)) return d.cookies
    } catch { /* 不是 JSON,继续按 cURL / Cookie 头解析 */ }
  }
  // 2) cURL 或 Cookie 头
  let site = url.trim()
  let header = ''
  const looksCurl = /curl\s/i.test(t) || /-[Hh]\s+['"]?Cookie:/i.test(t) || /Cookie:\s*/i.test(t)
  if (looksCurl) {
    const cm = t.match(/Cookie:\s*([^\r\n'"]+)/i)
    if (cm) header = cm[1].trim()
  }
  if (!header) header = t.replace(/^\s*Cookie:\s*/i, '').trim()
  if (!header) throw new Error('no-cookie')
  // 域名:优先用 cURL 里的 URL
  const curlUrl = t.match(/https?:\/\/[^\s'"\\]+/)
  if (curlUrl) site = curlUrl[0]
  if (!site) throw new Error('need-url')
  let domain = ''
  let secure = true
  try {
    const u = new URL(site)
    domain = u.hostname
    secure = u.protocol === 'https:'
  } catch {
    domain = site.replace(/^https?:\/\//, '').split('/')[0]
  }
  if (!domain) throw new Error('need-url')
  return header.split(';').map((s) => s.trim()).filter(Boolean).map((pair) => {
    const i = pair.indexOf('=')
    const name = (i >= 0 ? pair.slice(0, i) : pair).trim()
    const value = i >= 0 ? pair.slice(i + 1).trim() : ''
    return { name, value, domain, path: '/', secure }
  })
}

async function doImport() {
  const text = importText.value.trim()
  if (!text) { msg.warning(t('browser.needCookies')); return }
  let cookies: unknown[]
  try {
    cookies = parseCookiesInput(text, siteUrl.value)
  } catch (e: any) {
    msg.warning(e?.message === 'need-url' ? t('browser.needUrl') : t('browser.invalidJson'))
    return
  }
  importing.value = true
  try {
    const res = await importCookies(cookies)
    if (res.status === 'ok') {
      const skip = (res.failed || 0) > 0 ? t('browser.importSkip', { failed: res.failed }) : ''
      msg.success(t('browser.importOk', { ok: res.ok || 0, skip }))
      showImport.value = false
      reloadKey.value++
    } else {
      msg.error(t('browser.importFail', { error: res.error || 'unknown' }))
    }
  } catch (e: any) {
    msg.error(t('browser.importFail', { error: e?.message || String(e) }))
  } finally {
    importing.value = false
  }
}

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
        :key="reloadKey"
        :src="info.vnc_url"
        class="bc-frame"
        frameborder="0"
        allow="clipboard-read; clipboard-write"
      ></iframe>
      <div v-else class="bc-placeholder">
        <div class="bc-spinner" v-if="loading"></div>
        <div class="bc-msg">{{ errorText || t('browser.placeholder') }}</div>
      </div>
      <button v-if="info?.vnc_url" class="bc-import-btn" @click="openImport">
        {{ t('browser.importCookies') }}
      </button>
    </div>

    <n-modal
      v-model:show="showImport"
      preset="card"
      :title="t('browser.importTitle')"
      style="width: 560px; max-width: 94vw"
    >
      <p class="bc-import-hint">{{ t('browser.importHint') }}</p>
      <div class="bc-import-field">
        <span class="bc-import-label">{{ t('browser.siteUrlLabel') }}</span>
        <n-input v-model:value="siteUrl" size="small" :placeholder="t('browser.siteUrlPlaceholder')" />
      </div>
      <n-input
        v-model:value="importText"
        type="textarea"
        :rows="8"
        :placeholder="t('browser.importPlaceholder')"
        style="font-family: ui-monospace, Menlo, Consolas, monospace"
      />
      <input ref="fileInput" type="file" accept=".json,application/json" style="display:none" @change="onFile" />
      <div class="bc-import-actions">
        <n-button size="small" @click="fileInput?.click()">{{ t('browser.chooseFile') }}</n-button>
        <div class="spacer" />
        <n-button size="small" @click="showImport = false">{{ t('common.cancel') }}</n-button>
        <n-button size="small" type="primary" :loading="importing" :disabled="importing" @click="doImport">
          {{ importing ? t('browser.importing') : t('browser.importCookies') }}
        </n-button>
      </div>
    </n-modal>
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
.bc-import-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 5;
  padding: 4px 10px;
  border-radius: 7px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(20, 22, 26, 0.72);
  color: #ddd;
  font-size: 12px;
  cursor: pointer;
  backdrop-filter: blur(4px);
  transition: background .12s, color .12s, border-color .12s;
}
.bc-import-btn:hover {
  background: rgba(40, 44, 52, 0.9);
  color: #fff;
  border-color: rgba(255, 255, 255, 0.3);
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
.bc-import-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.6;
}
.bc-import-field {
  margin-bottom: 8px;
}
.bc-import-label {
  display: block;
  font-size: 12px;
  color: var(--text-3);
  margin-bottom: 4px;
}
.bc-import-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.bc-import-actions .spacer { flex: 1; }
</style>
