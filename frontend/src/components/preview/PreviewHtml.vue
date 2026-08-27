<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { fileApi } from '@/api/files'
import { extToLang } from './ext'

/**
 * 交互式 HTML 预览:支持「页面(渲染执行)/代码(高亮源码)」两种视图切换。
 * - 页面视图:把工作区 .html 作为完整文档在 sandbox iframe(srcdoc)中渲染,
 *   强制注入 <meta charset="utf-8"> 避免中文乱码;内嵌 <script> 正常执行。
 * - 代码视图:高亮显示源码(可复制/审查)。
 * 安全:iframe 用 sandbox="allow-scripts allow-popups"(无 allow-same-origin),
 * 脚本运行在无源孤立环境,无法访问宿主应用同源数据。
 */
const props = defineProps<{ path: string }>()

const mode = ref<'page' | 'code'>('page')
const raw = ref('')
const loading = ref(true)
const error = ref<string | null>(null)

const pageDoc = computed(() => {
  if (!raw.value) return ''
  let body = raw.value
  // 若文档自带头部,确保 charset 声明为 utf-8
  if (!/<meta\s+charset/i.test(body)) {
    body = body.replace(/<head([^>]*)>/i, `<head$1><meta charset="utf-8">`) ||
           `<meta charset="utf-8">` + body
  } else {
    body = body.replace(/(<meta\s+charset\s*=\s*["']?)[^"'\s>]+/i, '$1utf-8')
  }
  return body
})

const codeLang = computed(() => extToLang('html'))

async function load() {
  loading.value = true
  error.value = null
  try {
    const text = await fileApi.content(props.path)
    if (!text && text !== '') throw new Error('empty')
    raw.value = String(text)
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.path, load, { immediate: true })

function toggleMode() {
  mode.value = mode.value === 'page' ? 'code' : 'page'
}
</script>

<template>
  <div class="preview-html">
    <div class="ph-toolbar">
      <span class="ph-path">{{ path }}</span>
      <div class="ph-spacer" />
      <div class="ph-modes">
        <button class="ph-mode" :class="{ on: mode === 'page' }" @click="mode = 'page'">页面</button>
        <button class="ph-mode" :class="{ on: mode === 'code' }" @click="mode = 'code'">代码</button>
      </div>
    </div>
    <n-spin :show="loading" class="ph-body">
      <div v-if="error" class="ph-error">{{ error }}</div>
      <iframe
        v-else-if="mode === 'page' && pageDoc"
        :key="pageDoc"
        class="ph-frame"
        :srcdoc="pageDoc"
        sandbox="allow-scripts allow-popups allow-forms allow-modals"
        title="HTML 页面预览"
      />
      <PreviewCode v-else-if="mode === 'code' && raw !== ''" :content="raw" :lang="codeLang" />
      <div v-else class="ph-empty">加载中…</div>
    </n-spin>
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
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid var(--border-soft, #333);
  flex: 0 0 auto;
}
.ph-path {
  font-size: 12px;
  color: var(--text-dim, #999);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ph-spacer { flex: 1; }
.ph-modes { display: inline-flex; border: 1px solid var(--border-soft, #444); border-radius: 6px; overflow: hidden; }
.ph-mode {
  padding: 3px 12px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--text-dim, #aaa);
  background: transparent;
  border: none;
  cursor: pointer;
}
.ph-mode + .ph-mode { border-left: 1px solid var(--border-soft, #444); }
.ph-mode.on { color: var(--primary, #7c8cff); background: color-mix(in srgb, var(--primary, #7c8cff) 12%, transparent); }
.ph-body { flex: 1; min-height: 0; }
.ph-frame {
  display: block;
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}
.ph-error { padding: 24px; color: #e6a23c; text-align: center; }
.ph-empty { padding: 24px; color: #999; text-align: center; }
</style>
