<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { fileApi } from '@/api/files'
import { useMessage } from 'naive-ui'
import { extToKind, extToLang, EDITABLE_KINDS, BINARY_KINDS, fmtSize } from './ext'
import PreviewText from './PreviewText.vue'
import PreviewCode from './PreviewCode.vue'
import PreviewMarkdown from './PreviewMarkdown.vue'
import PreviewImage from './PreviewImage.vue'
import PreviewPdf from './PreviewPdf.vue'
import PreviewHtml from './PreviewHtml.vue'
import PreviewDocx from './PreviewDocx.vue'
import PreviewXlsx from './PreviewXlsx.vue'
import PreviewPptx from './PreviewPptx.vue'
import PreviewExcalidraw from './PreviewExcalidraw.vue'
import PreviewUnsupported from './PreviewUnsupported.vue'

const { t } = useI18n()
const props = withDefaults(defineProps<{ path: string; textContent?: string; size?: number; /** 隐藏工具栏左侧文件信息(由外层 modal 标题提供) */ hideFileInfo?: boolean }>(), {
  hideFileInfo: false,
})

const msg = useMessage()
const kind = computed(() => extToKind(props.path))
const lang = computed(() => extToLang(props.path))
const editable = computed(() => EDITABLE_KINDS.has(kind.value))
const isBinary = computed(() => BINARY_KINDS.has(kind.value))

const loading = ref(false)
const error = ref<string | null>(null)
const blobUrl = ref<string | null>(null)
const blob = ref<Blob | null>(null)
const localText = ref('')
const editMode = ref(false)
const editableText = ref('')
/** 保存/下载进行中 */
const saving = ref(false)
const downloading = ref(false)
const isFullscreen = ref(false)

function toggleFullscreen() {
  const el = document.querySelector('.file-preview') as HTMLElement | null
  if (!el) return
  if (document.fullscreenElement) document.exitFullscreen()
  else el.requestFullscreen?.()
}
function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement
}

const displayText = computed(() => props.textContent ?? localText.value)
const safeBlobUrl = computed(() => blobUrl.value ?? '')
const safeBlob = computed(() => blob.value ?? new Blob())

const kindIcon = computed(() => {
  const map: Record<string, string> = {
    image: '🖼', pdf: '📄', html: '🌐', markdown: '📝', code: '💻',
    text: '📃', docx: '📘', xlsx: '📊', pptx: '📙', audio: '🎵', video: '🎬',
    excalidraw: '✏️', unsupported: '📦',
  }
  return map[kind.value] || '📄'
})
/** 文本类内联预览控制:
 *  - 代码/文档的渲染字符上限更小,避免大文件布局与高亮卡顿;
 *  - 已知字节大小超过 MAX_INLINE_BYTES 时默认不加载全文,由用户手动选择预览。 */
const MAX_TEXT_CHARS = 300_000
const MAX_CODE_CHARS = 150_000
const MAX_INLINE_BYTES = 1_000_000

const charCap = computed(() => {
  const k = kind.value
  return (k === 'code' || k === 'markdown' || k === 'excalidraw') ? MAX_CODE_CHARS : MAX_TEXT_CHARS
})
const truncated = computed(() => displayText.value.length > charCap.value)
const viewText = computed(() => (truncated.value ? displayText.value.slice(0, charCap.value) : displayText.value))

/** 超大文本/代码文件:默认不加载(避免浏览器卡死),用户可手动选择预览 */
const forcePreview = ref(false)
const tooLarge = computed(() =>
  !isBinary.value &&
  props.textContent == null &&
  (props.size ?? 0) > MAX_INLINE_BYTES &&
  !forcePreview.value,
)
function previewAnyway() {
  forcePreview.value = true
  load()
}
function capLabel(): string {
  return charCap.value >= 1000 ? `${Math.round(charCap.value / 1000)}K` : String(charCap.value)
}

async function load() {
  error.value = null
  // unsupported 类型(如 pptx/ppt)无需加载内容,直接展示下载入口
  if (kind.value === 'unsupported') {
    loading.value = false
    return
  }
  // 大文件默认不加载,交给用户手动确认
  if (tooLarge.value) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    if (isBinary.value) {
      const b = await fileApi.fetchBlob(props.path)
      blob.value = b
      if (['image', 'pdf', 'audio', 'video'].includes(kind.value)) {
        blobUrl.value = URL.createObjectURL(b)
      }
    } else {
      if (props.textContent == null) {
        localText.value = await fileApi.content(props.path)
      }
      editableText.value = displayText.value
    }
  } catch (e: any) {
    error.value = e?.message || t('preview.loadFailed')
  } finally {
    loading.value = false
  }
}

function toggleEdit() {
  if (tooLarge.value) {
    msg.warning(t('preview.tooLarge'))
    return
  }
  if (truncated.value) {
    msg.warning(t('preview.tooLargeTruncated', { size: fmtSize(props.size ?? 0) }))
    return
  }
  if (!editMode.value) {
    editableText.value = displayText.value
  }
  editMode.value = !editMode.value
}

/** Ctrl/Cmd + S 快捷键保存 */
function onEditorKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    save()
  }
}

async function save() {
  saving.value = true
  try {
    await fileApi.write(props.path, editableText.value)
    localText.value = editableText.value
    msg.success(t('preview.saved'))
    editMode.value = false
  } catch (e: any) {
    msg.error(e?.message || t('preview.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function download() {
  downloading.value = true
  try {
    await fileApi.download(props.path)
  } catch (e: any) {
    msg.error(e?.message || t('preview.downloadFailed'))
  } finally {
    downloading.value = false
  }
}

watch(() => props.path, async () => {
  if (blobUrl.value) { URL.revokeObjectURL(blobUrl.value); blobUrl.value = null }
  blob.value = null
  localText.value = ''
  editMode.value = false
  forcePreview.value = false
  await load()
}, { immediate: true })

onMounted(() => {
  document.addEventListener('fullscreenchange', onFullscreenChange)
})
onBeforeUnmount(() => {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
})
</script>

<template>
  <div class="file-preview">
    <div class="fp-toolbar">
      <template v-if="!hideFileInfo">
        <span class="fp-icon" :class="'fp-icon--' + kind">{{ kindIcon }}</span>
        <div class="fp-name-col">
          <span class="fp-filename">{{ path.split(/[\\/]/).pop() }}</span>
          <span class="fp-meta">
            <span v-if="size" class="fp-size">{{ fmtSize(size) }}</span>
            <span v-if="lang" class="fp-lang">{{ lang }}</span>
          </span>
        </div>
      </template>
      <span v-else-if="size" class="fp-size-inline">{{ fmtSize(size) }}</span>
      <div class="fp-spacer" />
      <template v-if="editable && !error">
        <n-button v-if="editMode" size="tiny" type="primary" :loading="saving" @click="save">{{ t('preview.save') }}</n-button>
        <n-button size="tiny" quaternary @click="toggleEdit">{{ editMode ? t('preview.previewBtn') : t('preview.edit') }}</n-button>
      </template>
      <n-button size="tiny" quaternary :loading="downloading" @click="download">{{ t('preview.download') }}</n-button>
      <n-button size="tiny" quaternary @click="toggleFullscreen">{{ isFullscreen ? t('preview.exitFullscreen') : t('preview.fullscreen') }}</n-button>
    </div>

    <n-spin :show="loading" class="fp-body">
      <div v-if="error" class="fp-error-wrap">
        <PreviewUnsupported :path="path" :size="size" :reason="error" @download="download" />
      </div>

      <div v-else-if="tooLarge" class="fp-toolarge">
        <div class="fp-toolarge-ico">{{ kindIcon }}</div>
        <div class="fp-toolarge-title">{{ t('preview.tooLargeTitle') }}</div>
        <div class="fp-toolarge-desc">{{ t('preview.tooLargeDesc', { size: fmtSize(size ?? 0) }) }}</div>
        <div class="fp-toolarge-actions">
          <n-button size="small" type="primary" @click="previewAnyway">{{ t('preview.previewAnyway', { cap: capLabel() }) }}</n-button>
          <n-button size="small" quaternary @click="download">{{ t('preview.downloadFile') }}</n-button>
        </div>
      </div>

      <template v-else-if="editMode && editable">
        <textarea v-model="editableText" class="fp-editor" @keydown="onEditorKeydown" />
      </template>

      <template v-else>
        <div v-if="truncated" class="fp-truncated">
          {{ t('preview.tooLargeTruncated', { size: capLabel() }) }}
        </div>
        <PreviewText        v-if="kind === 'text'"      :content="viewText" />
        <PreviewCode         v-else-if="kind === 'code'"     :content="viewText" :lang="lang" />
        <PreviewMarkdown     v-else-if="kind === 'markdown'" :content="viewText" />
        <PreviewImage        v-else-if="kind === 'image'"    :src="safeBlobUrl" :path="path" />
        <PreviewPdf          v-else-if="kind === 'pdf'"      :src="safeBlobUrl" />
        <PreviewHtml         v-else-if="kind === 'html'"     :path="path" />
        <PreviewDocx         v-else-if="kind === 'docx'"     :blob="safeBlob" />
        <PreviewXlsx         v-else-if="kind === 'xlsx'"     :blob="safeBlob" :path="path" />
        <PreviewPptx         v-else-if="kind === 'pptx'"     :blob="safeBlob" />
        <PreviewExcalidraw   v-else-if="kind === 'excalidraw'" :content="viewText" />
        <div v-else-if="kind === 'audio'" class="media-wrap">
          <audio :src="safeBlobUrl" controls class="fp-audio" />
        </div>
        <div v-else-if="kind === 'video'" class="media-wrap">
          <video :src="safeBlobUrl" controls class="fp-video" />
        </div>
        <PreviewUnsupported  v-else :path="path" :size="size" @download="download" />
      </template>
    </n-spin>
  </div>
</template>

<style scoped>
.file-preview {
  display: flex; flex-direction: column;
  height: 80vh; overflow: hidden;
}
.fp-toolbar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 16px; border-bottom: 1px solid var(--border-soft, rgba(255,255,255,0.06));
  flex-shrink: 0; font-size: 12px;
  background: var(--surface, #20242b);
}
.fp-icon {
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 8px; font-size: 15px; flex-shrink: 0;
  background: var(--accent-soft, rgba(0,0,0,0.06));
}
.fp-name-col {
  display: flex; flex-direction: column; gap: 1px; min-width: 0;
}
.fp-filename {
  font-size: 13px; font-weight: 600; color: var(--text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fp-meta {
  display: flex; gap: 8px; font-size: 11px; color: var(--text-3);
}
.fp-lang {
  background: var(--accent-soft, rgba(0,0,0,0.06));
  color: var(--accent-text, #17181c);
  padding: 0 5px; border-radius: 3px; font-size: 10px;
}
.fp-spacer { flex: 1; }
.fp-size-inline { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.fp-body { flex: 1; overflow: hidden; }
.fp-body :deep(.n-spin-content) { height: 100%; }
.fp-error-wrap { height: 100%; }
.fp-toolarge {
  height: 100%; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 10px; text-align: center;
  color: var(--text-2); padding: 24px;
}
.fp-toolarge-ico { font-size: 40px; opacity: .8; }
.fp-toolarge-title { font-size: 15px; font-weight: 600; color: var(--text); }
.fp-toolarge-desc { font-size: 12.5px; color: var(--text-3); }
.fp-toolarge-actions { display: flex; gap: 10px; margin-top: 6px; }
.fp-truncated {
  padding: 8px 12px;
  font-size: 12px;
  color: #e6a23c;
  background: color-mix(in srgb, #e6a23c 10%, transparent);
  border-bottom: 1px solid color-mix(in srgb, #e6a23c 30%, transparent);
  flex-shrink: 0;
}
.fp-editor {
  width: 100%; height: 100%;
  background: var(--input-bg); color: var(--text);
  border: 0; padding: 12px;
  font-family: ui-monospace, monospace; font-size: 13px; line-height: 1.6;
  resize: none; outline: none;
}
.media-wrap {
  display: flex; align-items: center; justify-content: center;
  height: 100%; background: var(--bg-elev);
}
.fp-audio { width: 90%; }
.fp-video { max-width: 100%; max-height: 100%; }
/* 全屏模式 */
.file-preview:fullscreen {
  background: var(--bg, #14161a);
  z-index: 10000;
}
.file-preview:fullscreen .fp-toolbar {
  background: var(--surface, #20242b);
}
.file-preview:fullscreen .fp-body {
  flex: 1;
}
</style>
