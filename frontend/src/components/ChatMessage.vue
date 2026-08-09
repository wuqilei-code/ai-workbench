<script setup lang="ts">
import { computed, ref, nextTick } from 'vue'
import { useMessage } from 'naive-ui'
import { renderMarkdown } from '@/utils/markdown'
import { downloadMarkdown } from '@/utils/exportMarkdown'
import { useFavoritesStore } from '@/stores/favorites'
import { useChatStore } from '@/stores/chat'
import { fileApi } from '@/api/files'
import type { MessageVo } from '@/types'

const props = defineProps<{
  message: MessageVo
  streaming?: boolean
  /** 所在会话 DB id(收藏跳转用;缺省时隐藏收藏按钮) */
  sessionId?: number | null
  /** 所在 turn id(定位用) */
  turnId?: string
}>()
const msg = useMessage()
const fav = useFavoritesStore()
const chat = useChatStore()

const isUser = computed(() => props.message.role === 'user')

const starable = computed(() => !props.streaming && props.sessionId != null && !!props.message.id && props.message.id > 0)

const isStarred = computed(() =>
  starable.value ? fav.isFavorite(props.sessionId!, props.message.id) : false,
)

function toggleStar() {
  if (!starable.value) return
  const added = fav.toggle({
    sessionId: props.sessionId!,
    messageId: props.message.id,
    role: isUser.value ? 'user' : 'assistant',
    content: (props.message.content || '').slice(0, 500),
    turnId: props.turnId || '',
    sessionTitle: '',
  })
  msg.success(added ? '已收藏' : '已取消收藏')
}

/** 用户消息中可能含 [附件] path 标记(ChatInput 上传文件时拼接)。
 *  把这些行抽出来渲染成可下载的 chip,其余文本照常 markdown 渲染。 */
const parsed = computed(() => {
  const content = props.message.content || ''
  if (!isUser.value) return { text: content, attachments: [] as { path: string; name: string }[] }
  const lines = content.split('\n')
  const textLines: string[] = []
  const attachments: { path: string; name: string }[] = []
  for (const line of lines) {
    const m = line.match(/^\[附件\]\s*(.+)$/)
    if (m) {
      const path = m[1].trim()
      attachments.push({ path, name: path.split(/[\\/]/).pop() || path })
    } else {
      textLines.push(line)
    }
  }
  return { text: textLines.join('\n').trim(), attachments }
})

const html = computed(() => renderMarkdown(parsed.value.text))

async function downloadAttachment(path: string) {
  try {
    await fileApi.download(path)
  } catch (e: any) {
    console.error('[ChatMessage] download attachment failed', e)
  }
}

async function copyContent() {
  const text = props.message.content || ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    msg.success('已复制')
  } catch {
    msg.error('复制失败')
  }
}

function exportAsMarkdown() {
  const text = props.message.content || ''
  if (!text) return
  downloadMarkdown(text)
}

// ---------- 编辑消息重发(不改历史,编辑后作为新消息发送) ----------

const editing = ref(false)
const editText = ref('')
const editInputRef = ref<HTMLTextAreaElement | null>(null)

/** 用户消息、非流式、非 pending、非 error 时可编辑 */
const editable = computed(
  () => isUser.value && !props.streaming && !props.message._pending && props.message.status !== 'error',
)

function startEdit() {
  if (!editable.value) return
  editText.value = props.message.content || ''
  editing.value = true
  nextTick(() => {
    editInputRef.value?.focus()
    editInputRef.value?.setSelectionRange(editText.value.length, editText.value.length)
  })
}
function cancelEdit() {
  editing.value = false
  editText.value = ''
}
async function submitEdit() {
  const text = editText.value.trim()
  if (!text) {
    msg.warning('内容不能为空')
    return
  }
  if (chat.isStreaming) {
    msg.info('AI 正在处理中,请稍后再试')
    return
  }
  editing.value = false
  editText.value = ''
  try {
    await chat.sendMessage(text)
  } catch (e: any) {
    msg.error(e?.message || '发送失败')
  }
}
</script>

<template>
  <div class="msg" :class="isUser ? 'user' : 'assistant'">
    <div class="avatar">{{ isUser ? '我' : 'AI' }}</div>
    <div class="bubble">
      <div class="bubble-tools">
        <button v-if="starable" class="copy-btn star-btn" :class="{ on: isStarred }" :title="isStarred ? '取消收藏' : '收藏该消息'" @click="toggleStar">{{ isStarred ? '★' : '☆' }}</button>
        <button v-if="editable" class="copy-btn edit-btn" title="编辑并重新发送" @click="startEdit">✏️</button>
        <button class="copy-btn" title="复制内容" @click="copyContent">📋</button>
        <button v-if="!isUser" class="copy-btn export-btn" title="导出为 Markdown 文件" @click="exportAsMarkdown">⬇️</button>
      </div>
      <div v-if="message._pending" class="status">发送中…</div>
      <div v-else-if="message.status === 'error'" class="status err">{{ message.error || '发送失败' }}</div>
      <template v-if="editing">
        <textarea
          ref="editInputRef"
          v-model="editText"
          class="edit-box"
          rows="3"
          @keydown.enter.exact.prevent="submitEdit"
          @keydown.esc.prevent="cancelEdit"
        />
        <div class="edit-actions">
          <span class="edit-hint">Enter 发送 · Esc 取消</span>
          <n-button size="tiny" quaternary @click="cancelEdit">取消</n-button>
          <n-button size="tiny" type="primary" :disabled="!editText.trim()" @click="submitEdit">发送</n-button>
        </div>
      </template>
      <template v-else>
        <div v-if="parsed.text" class="content" v-html="html" />
        <div v-if="parsed.attachments.length" class="att-list">
          <a
            v-for="a in parsed.attachments"
            :key="a.path"
            class="att-link"
            :title="`下载 ${a.path}`"
            @click="downloadAttachment(a.path)"
          >📎 {{ a.name }}</a>
        </div>
        <span v-if="streaming" class="cursor">▋</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.msg { display: flex; gap: 10px; margin-bottom: 16px; }
.msg.user { flex-direction: row-reverse; }
.avatar {
  width: 30px; height: 30px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600; background: var(--surface);
  color: var(--text-3);
}
.msg.user .avatar { background: var(--accent-soft); color: var(--accent-text); }
.bubble {
  position: relative;
  max-width: 75%; padding: 12px 16px; border-radius: var(--radius);
  background: var(--surface); line-height: 1.6; word-break: break-word;
  border: 1px solid var(--border-soft);
}
.bubble-tools { position: absolute; top: 4px; right: 6px; opacity: 0; transition: opacity .15s; }
.bubble:hover .bubble-tools { opacity: 1; }
.copy-btn {
  background: transparent; border: none; cursor: pointer; font-size: 13px;
  padding: 2px 4px; border-radius: 4px; line-height: 1;
}
.copy-btn:hover { background: var(--surface-hover); }
.star-btn.on { color: #f0b429; }
.edit-box {
  width: 100%; box-sizing: border-box;
  background: var(--bg-elev); color: var(--text);
  border: 1px solid var(--border); border-radius: var(--radius-sm);
  padding: 8px 10px; font-size: 13px; line-height: 1.6;
  font-family: inherit; resize: vertical;
}
.edit-box:focus { outline: none; border-color: var(--accent); }
.edit-actions { display: flex; align-items: center; justify-content: flex-end; gap: 6px; margin-top: 6px; }
.edit-hint { font-size: 11px; color: var(--text-3); margin-right: auto; }
.msg.user .bubble { background: var(--accent-soft); border-color: rgba(107, 138, 253, 0.25); }
.content :deep(p) { margin: 6px 0; }
.content :deep(pre.hljs) { background: #16181d; border-radius: var(--radius-sm); padding: 10px; overflow-x: auto; }
.content :deep(code) { font-family: ui-monospace, monospace; font-size: 13px; }
.content :deep(pre code) { background: none; }
.content :deep(code:not(pre code)) { background: #16181d; padding: 2px 5px; border-radius: 4px; }
.status { font-size: 12px; color: var(--text-3); margin-bottom: 4px; }
.status.err { color: var(--err); }
.cursor { animation: blink 1s steps(2) infinite; color: var(--accent); }
@keyframes blink { 50% { opacity: 0; } }

.att-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.att-link {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 12px; padding: 3px 9px; border-radius: 10px;
  background: rgba(0, 0, 0, 0.2); color: var(--accent-text);
  border: 1px solid var(--border); cursor: pointer;
  text-decoration: none; max-width: 220px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.att-link:hover { background: rgba(0, 0, 0, 0.35); }
</style>
