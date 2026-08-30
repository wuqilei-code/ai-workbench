<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
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

const props = defineProps<{ path: string; textContent?: string; size?: number }>()

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

const displayText = computed(() => props.textContent ?? localText.value)
const safeBlobUrl = computed(() => blobUrl.value ?? '')
const safeBlob = computed(() => blob.value ?? new Blob())

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
  if (!editMode.value) {
    editableText.value = displayText.value
  }
  editMode.value = !editMode.value
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

onBeforeUnmount(() => {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value)
})
</script>

<template>
  <div class="file-preview">
    <div class="fp-toolbar">
      <span class="fp-path">{{ path }}</span>
      <span v-if="size" class="fp-size">{{ fmtSize(size) }}</span>
      <div class="fp-spacer" />
      <template v-if="editable && !error">
        <n-button v-if="editMode" size="tiny" type="primary" :loading="saving" @click="save">保存</n-button>
        <n-button size="tiny" quaternary @click="toggleEdit">{{ editMode ? '预览' : '编辑' }}</n-button>
      </template>
      <n-button size="tiny" quaternary :loading="downloading" @click="download">下载</n-button>
    </div>

    <n-spin :show="loading" class="fp-body">
      <div v-if="error" class="fp-error-wrap">
        <PreviewUnsupported :path="path" :size="size" :reason="error" @download="download" />
      </div>

      <template v-else-if="editMode && editable">
        <textarea v-model="editableText" class="fp-editor" />
      </template>

      <template v-else>
        <PreviewText        v-if="kind === 'text'"      :content="displayText" />
        <PreviewCode         v-else-if="kind === 'code'"     :content="displayText" :lang="lang" />
        <PreviewMarkdown     v-else-if="kind === 'markdown'" :content="displayText" />
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
  /* 明确视口高度,不依赖父容器(n-card__content)是否给出高度,避免 iframe/PDF 预览塌缩成很短一条 */
  height: 80vh; overflow: hidden;
}
.fp-toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 12px; border-bottom: 1px solid var(--border);
  flex-shrink: 0; font-size: 12px;
}
.fp-path { color: var(--text-3); word-break: break-all; font-family: ui-monospace, monospace; }
.fp-size { color: var(--text-3); flex-shrink: 0; }
.fp-spacer { flex: 1; }
.fp-body { flex: 1; overflow: hidden; }
.fp-body :deep(.n-spin-content) { height: 100%; }
.fp-error-wrap { height: 100%; }
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
</style>
