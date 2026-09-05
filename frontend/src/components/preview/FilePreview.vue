<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
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
import PreviewUnsupported from './PreviewUnsupported.vue'

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
    text: '📃', docx: '📘', xlsx: '📊', pptx: '📙', audio: '🎵', video: '🎬', unsupported: '📦',
  }
  return map[kind.value] || '📄'
})
/** 文本预览上限:超过部分截断展示(避免大文件高亮/渲染卡顿),提示需下载查看完整内容 */
const MAX_TEXT_CHARS = 1_500_000
const truncated = computed(() => displayText.value.length > MAX_TEXT_CHARS)
const viewText = computed(() => (truncated.value ? displayText.value.slice(0, MAX_TEXT_CHARS) : displayText.value))

async function load() {
  error.value = null
  // unsupported 类型(如 pptx/ppt)无需加载内容,直接展示下载入口
  if (kind.value === 'unsupported') {
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
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function toggleEdit() {
  if (truncated.value) {
    msg.warning(`文件过大(${fmtSize(props.size ?? 0)}),已截断预览,请下载后本地编辑`)
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
    msg.success('已保存')
    editMode.value = false
  } catch (e: any) {
    msg.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function download() {
  downloading.value = true
  try {
    await fileApi.download(props.path)
  } catch (e: any) {
    msg.error(e?.message || '下载失败')
  } finally {
    downloading.value = false
  }
}

watch(() => props.path, async () => {
  if (blobUrl.value) { URL.revokeObjectURL(blobUrl.value); blobUrl.value = null }
  blob.value = null
  localText.value = ''
  editMode.value = false
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
        <n-button v-if="editMode" size="tiny" type="primary" :loading="saving" @click="save">保存</n-button>
        <n-button size="tiny" quaternary @click="toggleEdit">{{ editMode ? '预览' : '编辑' }}</n-button>
      </template>
      <n-button size="tiny" quaternary :loading="downloading" @click="download">下载</n-button>
      <n-button size="tiny" quaternary @click="toggleFullscreen">{{ isFullscreen ? '退出全屏' : '全屏' }}</n-button>
    </div>

    <n-spin :show="loading" class="fp-body">
      <div v-if="error" class="fp-error-wrap">
        <PreviewUnsupported :path="path" :size="size" :reason="error" @download="download" />
      </div>

      <template v-else-if="editMode && editable">
        <textarea v-model="editableText" class="fp-editor" @keydown="onEditorKeydown" />
      </template>

      <template v-else>
        <div v-if="truncated" class="fp-truncated">
          文件较大,已截断仅显示前 {{ (MAX_TEXT_CHARS / 1000 / 1000).toFixed(1) }} MB,完整内容请下载查看。
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
  background: var(--accent-soft, rgba(139,92,246,0.12));
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
  background: var(--accent-soft, rgba(139,92,246,0.12));
  color: var(--accent-text, #A78BFA);
  padding: 0 5px; border-radius: 3px; font-size: 10px;
}
.fp-spacer { flex: 1; }
.fp-size-inline { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.fp-body { flex: 1; overflow: hidden; }
.fp-body :deep(.n-spin-content) { height: 100%; }
.fp-error-wrap { height: 100%; }
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
