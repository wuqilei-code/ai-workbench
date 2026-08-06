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

/** 当流式"卡住"(>15s 无活动)时:
 *  - 若用户已输入内容,点击"发送"会先解除阻塞(abort + finishStreaming)再真正发送;
 *  - 若用户未输入内容,展示"解除阻塞"按钮。
 *
 *  hasPending:AI 真的在等用户输入(question/permission)。
 *  正常情况下此时 isStreaming 已被置 false,banner 不显示;此标志仅作兜底区分文案。
 *  - 有 pending:文案引导用户去上方"提问区域"作答(而非误以为 AI 没问问题)。
 *  - 无 pending:AI 只是无响应(模型慢/工具执行中),文案如实说明。
 */
const isStuck = computed(() => chat.streamingStuckSeconds > 0)
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
    if (isStuck.value) {
      // 解除阻塞后再发
      await chat.forceReleaseStreaming()
      // 注意: 阻塞的当前 turn 实际正在等用户答案(而不是 session.idle)
      // 所以发送下一条用户消息让对话继续即可
    } else {
      // 还在正常流式中:忽略(避免竞争)
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
  if (isStuck.value) {
    await chat.forceReleaseStreaming()
  } else {
    chat.abort()
  }
}
</script>

<template>
  <div class="chat-input">
    <div class="activity" v-if="chat.activityLog.length">
      <div v-for="(a, i) in chat.activityLog" :key="i" class="act-line">{{ a }}</div>
    </div>
    <div v-if="isStuck" class="stuck-banner">
      <n-alert :type="hasPending ? 'info' : 'warning'" show-icon>
        <template #header>
          <span v-if="hasPending">AI 正在等待您的回答(已等待 {{ chat.streamingStuckSeconds }}s)</span>
          <span v-else>AI 暂时无响应(已空闲 {{ chat.streamingStuckSeconds }}s)</span>
        </template>
        <div class="stuck-tips">
          <template v-if="hasPending">
            AI 在上方提出了问题/权限请求,请滚动到 <b>提问区域</b> 作答后点「提交回答」;也可直接在下方输入框发送以继续。
          </template>
          <template v-else>
            AI 可能正在执行耗时操作(如运行命令)或模型响应较慢。请耐心等待,点 <b>解除阻塞</b> 可中断当前生成。
          </template>
        </div>
      </n-alert>
    </div>

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
        :placeholder="isStuck ? (hasPending ? '上方有待答问题,作答后发送;或直接输入以继续…' : 'AI 响应较慢,可输入新消息发送或点解除阻塞…') : '输入消息… (Enter 发送 / Shift+Enter 换行,📎 上传文件)'"
        @keydown.enter.exact.prevent="onSend"
        @input="onInput"
      />
      <template v-if="chat.isStreaming">
        <n-button v-if="isStuck" type="warning" @click="onAbort">解除阻塞</n-button>
        <n-button v-else type="error" @click="onAbort">停止</n-button>
      </template>
      <n-button v-else type="primary" :disabled="!canSend" @click="onSend">发送</n-button>
    </div>
  </div>
</template>

<style scoped>
.stuck-banner { margin-bottom: 10px; }
.stuck-tips { margin-top: 6px; font-size: 13px; color: #c8c8c8; }
</style>

<style scoped>
.chat-input { border-top: 1px solid #2a2a2e; padding: 12px; background: #1a1a1e; }
.activity { max-height: 100px; overflow-y: auto; margin-bottom: 8px; font-size: 12px; color: #888; }
.act-line { font-family: ui-monospace, monospace; padding: 2px 0; }

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
