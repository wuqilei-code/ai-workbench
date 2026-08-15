<script setup lang="ts">
import { computed, ref, nextTick } from 'vue'
import { useMessage } from 'naive-ui'
import { renderMarkdown } from '@/utils/markdown'
import { downloadMarkdown } from '@/utils/exportMarkdown'
import { useFavoritesStore } from '@/stores/favorites'
import { useChatStore } from '@/stores/chat'
import { fileApi } from '@/api/files'
import { fmtMessageTime } from '@/utils/time'
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

/** 消息时间:DB 存北京时间,LocalDateTime 序列化为无时区 ISO 字符串;按浏览器本地(+8)解析即正确 */
const messageTime = computed(() => fmtMessageTime(props.message.createdAt))

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

/** 附件下载中(以 path 为 key),行内 loading 并防重复点。 */
const downloading = ref<string | null>(null)

async function downloadAttachment(path: string) {
  if (downloading.value !== null) return
  downloading.value = path
  try {
    await fileApi.download(path)
  } catch (e: any) {
    console.error('[ChatMessage] download attachment failed', e)
    msg.error('附件下载失败,请重试')
  } finally {
    downloading.value = null
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
        <button v-if="starable" class="copy-btn star-btn" :class="{ on: isStarred }" :title="isStarred ? '取消收藏' : '收藏该消息'" @click="toggleStar">
          <svg width="14" height="14" viewBox="0 0 24 24" :fill="isStarred ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>
        </button>
        <button v-if="editable" class="copy-btn edit-btn" title="编辑并重新发送" @click="startEdit">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
        </button>
        <button class="copy-btn" title="复制内容" @click="copyContent">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
        </button>
        <button v-if="!isUser" class="copy-btn export-btn" title="导出为 Markdown 文件" @click="exportAsMarkdown">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </button>
      </div>
      <div v-if="message._pending" class="status">发送中…</div>
      <div v-else-if="message.status === 'error'" class="status err">{{ message.error || '发送失败' }}</div>
      <span v-if="messageTime" class="msg-time">{{ messageTime }}</span>
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
            :class="{ busy: downloading !== null }"
            :title="`下载 ${a.path}`"
            @click="downloadAttachment(a.path)"
          >{{ downloading === a.path ? '⏳ ' : '📎 ' }}{{ a.name }}</a>
        </div>
        <span v-if="streaming" class="cursor">▋</span>
      </template>
    </div>
  </div>
</template>

<style scoped>
.msg { display: flex; gap: 12px; margin-bottom: 20px; }
.msg.user { flex-direction: row-reverse; }
.avatar {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600; background: var(--surface);
  color: var(--text-3);
  border: 1px solid var(--border-soft);
}
.msg.user .avatar {
  background: var(--accent);
  color: #fff; border: none;
}
.msg.assistant .avatar {
  background: var(--surface-hover);
  color: var(--text-3);
}
.bubble {
  position: relative;
  max-width: 70%; padding: 10px 14px; border-radius: var(--radius);
  line-height: 1.6; word-break: break-word;
  background: transparent;
  border: none;
  box-shadow: none;
}
.bubble:hover {
  box-shadow: none;
}
.bubble-tools {
  position: absolute; top: 4px; right: 6px; opacity: 0; transition: opacity .15s;
  z-index: 10; display: flex; gap: 2px; background: transparent;
}
.bubble:hover .bubble-tools { opacity: 1; }
.bubble-tools .copy-btn { pointer-events: auto; }
/* 消息时间:气泡内顶部左侧,弱化,与右上悬浮工具避免遮挡 */
.msg-time {
  display: block; font-size: 11px; color: var(--text-3); opacity: .7;
  margin-bottom: 4px;
}
.copy-btn {
  background: transparent; border: none; cursor: pointer;
  padding: 4px; border-radius: 4px; line-height: 1;
  color: var(--text-3); display: inline-flex; align-items: center; justify-content: center;
}
.copy-btn:hover { background: var(--surface-hover); color: var(--text-2); }
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
.msg.user .bubble {
  background: var(--user-bubble-bg);
  border-radius: 12px 12px 2px 12px;
}
.msg.assistant .bubble {
  background: var(--ai-bubble-bg);
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  max-width: 92%;
  padding: 12px 14px;
}
.content :deep(p) { margin: 6px 0; }
.content :deep(a.preview-link) {
  display: inline-block; margin: 2px 0;
  padding: 5px 12px; border-radius: 6px;
  background: var(--accent-soft); color: var(--accent);
  border: 1px solid var(--border); text-decoration: none; font-size: 13px;
}
.content :deep(a.preview-link:hover) { background: var(--accent); color: #fff; }
.content :deep(pre.hljs) { background: #1e1e1e; border-radius: var(--radius-sm); padding: 12px; overflow-x: auto; }
.content :deep(code) { font-family: ui-monospace, monospace; font-size: 13px; }
.content :deep(pre code) { background: none; }
.content :deep(code:not(pre code)) { background: var(--surface-hover); padding: 2px 5px; border-radius: 4px; }
.status { font-size: 12px; color: var(--text-3); margin-bottom: 4px; }
.status.err { color: var(--err); }
.cursor { animation: blink 1s steps(2) infinite; color: var(--accent); }
@keyframes blink { 50% { opacity: 0; } }

.att-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.att-link {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 12px; padding: 3px 9px; border-radius: 6px;
  background: var(--surface-hover); color: var(--accent);
  border: 1px solid var(--border-soft); cursor: pointer;
  text-decoration: none; max-width: 220px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.att-link:hover { background: var(--accent); color: #fff; border-color: var(--accent); }
.att-link.busy { opacity: .55; pointer-events: none; }
</style>
