<script setup lang="ts">
import { ref, nextTick, computed } from 'vue'
import { useChatStore } from '@/stores/chat'
import { fileApi } from '@/api/files'
import { useMessage } from 'naive-ui'

const chat = useChatStore()
const msg = useMessage()
const text = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 会话内上传:文件落入工作区 uploads/ 目录,并把相对路径拼到消息文本里,
 *  让 opencode 智能体(运行在同一 workspace)能按相对路径读取/操作该文件。 */
const UPLOAD_DIR = 'uploads'
interface Attachment { name: string; path: string }
const attachments = ref<Attachment[]>([])
const uploading = ref(false)

/** hasPending:AI 真的在等用户输入(question/permission),用于占位文案与发送拦截 */
const hasPending = computed(() => chat.hasPendingInteraction)

const canSend = computed(() => !!(text.value.trim() || attachments.value.length))

function pickFile() {
  fileInputRef.value?.click()
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || !files.length) return
  uploading.value = true
  try {
    let ok = 0
    let fail = 0
    for (const f of Array.from(files)) {
      try {
        await fileApi.upload(UPLOAD_DIR, f)
        attachments.value.push({ name: f.name, path: `${UPLOAD_DIR}/${f.name}` })
        ok++
      } catch {
        fail++
      }
    }
    if (fail === 0) msg.success(`已上传 ${ok} 个文件到工作区 ${UPLOAD_DIR}/`)
    else msg.warning(`上传完成:${ok} 成功,${fail} 失败`)
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function removeAttachment(i: number) {
  attachments.value.splice(i, 1)
}

/** 把附件信息拼接到用户消息文本,作为智能体可读的上下文。
 *  使用 [附件] path 标记,前端 ChatMessage 会把它渲染成可下载的 chip。 */
function buildPayload(): string {
  const t = text.value.trim()
  if (attachments.value.length === 0) return t
  const fileList = attachments.value.map((a) => `[附件] ${a.path}`).join('\n')
  const note = `📎 已上传以下文件到工作区,可使用相对路径读取:\n${fileList}`
  return t ? `${t}\n\n${note}` : note
}

async function onSend() {
  const payload = buildPayload()
  if (!payload) return
  if (chat.isStreaming) {
    if (chat.streamingStuckSeconds > 0) {
      // 长时间无活动(无工具在跑、无 token):解除阻塞后再发送
      await chat.forceReleaseStreaming()
    } else {
      // AI 正在正常工作中:不静默忽略,给出轻柔提示
      msg.info('AI 正在处理中,可点「停止」中断后再发送')
      return
    }
  }
  text.value = ''
  attachments.value = []
  await chat.sendMessage(payload)
  await nextTick()
  inputRef.value?.focus()
}

function onInput() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

async function onAbort() {
  // 长时间无活动:forceRelease 兜底清理本地流式状态;正常情况直接 abort
  if (chat.streamingStuckSeconds > 0) await chat.forceReleaseStreaming()
  else await chat.abort()
}
</script>

<template>
  <div class="chat-input">
    <!-- 附件 chip 列表 -->
    <div v-if="attachments.length" class="attachments">
      <span v-for="(a, i) in attachments" :key="a.path" class="att-chip">
        <span class="att-icon">📎</span>
        <span class="att-name" :title="a.path">{{ a.name }}</span>
        <span class="att-remove" @click="removeAttachment(i)">✕</span>
      </span>
    </div>

    <div class="input-row">
      <input ref="fileInputRef" type="file" multiple class="hidden-input" @change="onFileChange" />
      <n-button
        quaternary
        class="upload-btn"
        :loading="uploading"
        title="上传文件到工作区(智能体可读取操作)"
        @click="pickFile"
      >📎</n-button>
      <textarea
        ref="inputRef"
        v-model="text"
        class="textarea"
        rows="1"
        :placeholder="chat.isStreaming ? (hasPending ? '上方有待答问题,作答后发送;或直接输入以继续…' : 'AI 正在处理中,可点「停止」中断…') : '输入消息… (Enter 发送 / Shift+Enter 换行,📎 上传文件)'"
        @keydown.enter.exact.prevent="onSend"
        @input="onInput"
      />
      <template v-if="chat.isStreaming">
        <n-button type="error" @click="onAbort">停止</n-button>
      </template>
      <n-button v-else type="primary" :disabled="!canSend" @click="onSend">发送</n-button>
    </div>
  </div>
</template>

<style scoped>
.chat-input { border-top: 1px solid #2a2a2e; padding: 12px; background: #1a1a1e; }

.attachments {
  display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px;
}
.att-chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: #2c4a7c; color: #e0e8f0; border: 1px solid #3a5a8c;
  font-size: 12px; padding: 3px 8px; border-radius: 10px; max-width: 240px;
}
.att-icon { flex-shrink: 0; }
.att-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.att-remove { cursor: pointer; opacity: .7; padding: 0 2px; }
.att-remove:hover { opacity: 1; color: #ffb4b4; }

.input-row { display: flex; gap: 8px; align-items: flex-end; }
.hidden-input { display: none; }
.upload-btn { flex-shrink: 0; height: 40px; font-size: 18px; }
.textarea {
  flex: 1; resize: none; padding: 10px 12px; border-radius: 8px;
  border: 1px solid #333; background: #26262a; color: #e0e0e0;
  font-size: 14px; line-height: 1.5; outline: none; max-height: 200px; overflow-y: auto;
}
.textarea:focus { border-color: #3a5a8c; }
</style>
