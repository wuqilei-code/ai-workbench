<script setup lang="ts">
import { computed, ref, nextTick } from 'vue'
import { useMessage } from 'naive-ui'
import { renderMarkdown } from '@/utils/markdown'
import { downloadMarkdown } from '@/utils/exportMarkdown'
import { useFavoritesStore } from '@/stores/favorites'
import { useChatStore } from '@/stores/chat'
import { fileApi } from '@/api/files'
import { fmtMessageTime } from '@/utils/time'
import TypeReveal from './TypeReveal.vue'
import StreamMarkdown from './StreamMarkdown.vue'
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

/** 用户消息中可能含 [附件] path 标记、[技能: name] 行标记 和 [skill:name] 内联标记。
 *  行标记抽出来渲染成 chip;内联标记在 markdown 渲染后替换为标签。 */
const parsed = computed(() => {
  const content = props.message.content || ''
  if (!isUser.value) return { text: content, attachments: [] as { path: string; name: string }[], skills: [] as string[] }
  const lines = content.split('\n')
  const textLines: string[] = []
  const attachments: { path: string; name: string }[] = []
  const skills: string[] = []
  for (const line of lines) {
    const mAtt = line.match(/^\[附件\]\s*(.+)$/)
    const mSkill = line.match(/^\[技能:\s*(.+)\]$/)
    if (mAtt) {
      const path = mAtt[1].trim()
      attachments.push({ path, name: path.split(/[\\/]/).pop() || path })
    } else if (mSkill) {
      skills.push(...mSkill[1].split(',').map((s) => s.trim()).filter(Boolean))
    } else {
      textLines.push(line)
    }
  }
  return { text: textLines.join('\n').trim(), attachments, skills }
})

/** 将文本中的 [skill:name] 内联标记替换为带样式的 HTML 标签 */
function renderSkillMarkers(text: string): string {
  return text.replace(/\[skill:([^\]]+)\]/g, (_, name) => {
    return `<span class="skill-tag-inline"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>${name}</span>`
  })
}

const html = computed(() => renderSkillMarkers(renderMarkdown(parsed.value.text)))

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
    <div class="avatar">
      <svg v-if="isUser" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
      <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="currentColor" stroke="none"><path d="M12 2.5l1.9 5.9 6.1 1.9-5 4.4 1.5 6.3L12 17.5l-4.5 3.5 1.5-6.3-5-4.4 6.1-1.9L12 2.5z"/></svg>
    </div>
    <div class="msg-body">
      <div class="msg-head">
        <span class="author">{{ isUser ? '你' : 'AI' }}</span>
        <span v-if="messageTime" class="msg-time">{{ messageTime }}</span>
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
        <div class="content-area" :class="{ card: isUser }">
          <!-- AI 消息:markstream 流式渲染(平滑增量 + 代码块高亮 + 复制按钮) -->
          <StreamMarkdown v-if="!isUser && parsed.text" :content="parsed.text" :final="!streaming" />
          <!-- 用户消息:保持原 markdown 渲染(含 [skill:] 技能标签) -->
          <div v-else-if="parsed.text" class="content">
            <TypeReveal v-if="streaming" :text="parsed.text" active />
            <div v-else v-html="html" />
          </div>
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
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.msg {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 26px;
  padding: 0 4px;
}
.msg.user { flex-direction: row-reverse; }
.avatar {
  width: 32px; height: 32px; border-radius: 10px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  margin-top: 1px;
}
.msg.user .avatar {
  background: var(--accent-soft);
  color: var(--accent-text);
  border: 1px solid var(--border-soft);
}
.msg.assistant .avatar {
  background: var(--accent);
  color: var(--accent-contrast);
}
/* Trae 风格:assistant 回答不带头像/作者行,与多步时间线一致 */
.msg.assistant .avatar { display: none; }
.msg.assistant .author { display: none; }
.msg.assistant .msg-body { flex: 1; min-width: 0; position: relative; }
.msg.user .msg-body { display: flex; flex-direction: column; align-items: flex-end; }
.msg-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
  min-height: 20px;
}
.msg.user .msg-head { flex-direction: row-reverse; }
.author { font-size: 13px; font-weight: 600; color: var(--text); }
.msg-time { font-size: 11px; color: var(--text-3); }
.bubble-tools {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: auto;
  opacity: 0;
  transition: opacity .15s;
}
.msg.user .bubble-tools { margin-left: 0; }
.msg:hover .bubble-tools { opacity: 1; }
.copy-btn {
  background: transparent; border: none; cursor: pointer;
  padding: 4px; border-radius: 4px; line-height: 1;
  color: var(--text-3); display: inline-flex; align-items: center; justify-content: center;
}
.copy-btn:hover { background: var(--surface-hover); color: var(--text-2); }
.star-btn.on { color: #f0b429; }
.content-area { min-width: 0; max-width: 100%; }
.msg.user .content-area { max-width: 78%; }
.msg.assistant .content-area { max-width: 96%; }
.content-area.card {
  background: var(--user-bubble-bg);
  border: 1px solid var(--border-soft);
  border-radius: 16px 16px 4px 16px;
  padding: 11px 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  transition: background var(--motion-fast) var(--ease-standard), border-color var(--motion-fast) var(--ease-standard);
}
.msg.user .content-area.card:hover {
  border-color: var(--border);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}
.content { font-size: 14px; line-height: 1.8; letter-spacing: 0.01em; color: var(--text); }
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
.content :deep(p) { margin: 8px 0; }
.content :deep(p:first-child) { margin-top: 0; }
.content :deep(p:last-child) { margin-bottom: 0; }
.content :deep(ul),
.content :deep(ol) { padding-left: 20px; margin: 8px 0; }
.content :deep(li) { margin: 5px 0; line-height: 1.75; }
.content :deep(li > p) { margin: 2px 0; }
.content :deep(hr) {
  border: none;
  border-top: 1px solid var(--border-soft);
  margin: 14px 0;
}
.content :deep(a.preview-link) {
  display: inline-block; margin: 2px 0;
  padding: 5px 12px; border-radius: 6px;
  background: var(--accent-soft); color: var(--accent);
  border: 1px solid var(--border); text-decoration: none; font-size: 13px;
}
.content :deep(a.preview-link:hover) { background: var(--accent); color: var(--accent-contrast); }
.content :deep(pre.hljs) { background: var(--code-bg); border-radius: var(--radius-sm); padding: 12px; overflow-x: auto; }
.content :deep(code) { font-family: ui-monospace, monospace; font-size: 13px; }
.content :deep(pre code) { background: none; }
.content :deep(code:not(pre code)) { background: var(--surface-hover); padding: 2px 5px; border-radius: 4px; }
.content :deep(table) {
  border-collapse: collapse;
  margin: 10px 0;
  width: 100%;
  display: block;
  overflow-x: auto;
  font-size: 13px;
}
.content :deep(th),
.content :deep(td) {
  border: 1px solid var(--border);
  padding: 6px 12px;
  text-align: left;
  vertical-align: top;
}
.content :deep(th) {
  background: var(--surface-hover);
  font-weight: 600;
  color: var(--text);
  white-space: nowrap;
}
.content :deep(td) { color: var(--text-2); }
.content :deep(tr:nth-child(even) td) { background: var(--surface); }
.status { font-size: 12px; color: var(--text-3); margin-bottom: 4px; }
.status.err { color: var(--err); }
.cursor {
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 2px;
  vertical-align: text-bottom;
  color: transparent;
  border-radius: 1px;
  background: linear-gradient(180deg, transparent, var(--accent), transparent);
  box-shadow: 0 0 6px var(--accent), 0 0 14px var(--accent);
  animation: cursor-glow 1s ease-in-out infinite;
}
@keyframes cursor-glow { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

.att-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.att-link {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 12px; padding: 3px 9px; border-radius: 6px;
  background: var(--surface-hover); color: var(--accent);
  border: 1px solid var(--border-soft); cursor: pointer;
  text-decoration: none; max-width: 220px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.att-link:hover { background: var(--accent); color: var(--accent-contrast); border-color: var(--accent); }
.att-link.busy { opacity: .55; pointer-events: none; }

/* 消息中的技能标签(内联) */
:deep(.skill-tag-inline) {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--fill-1);
  color: var(--accent-text);
  border: 1px solid var(--border);
  font-size: 12px; font-weight: 600;
  padding: 2px 8px; border-radius: 6px;
  vertical-align: middle; margin: 0 2px;
  letter-spacing: .02em;
}
:deep(.skill-tag-inline svg) { opacity: .7; flex-shrink: 0; }

/* 触屏:工具按钮常显(hover 不生效) */
@media (hover: none) {
  .bubble-tools { opacity: 1; }
}

/* 移动端:消息布局收紧,用户气泡放宽 */
@media (max-width: 768px) {
  .msg { gap: 8px; margin-bottom: 18px; padding: 0 2px; }
  .avatar { width: 26px; height: 26px; border-radius: 8px; }
  .msg-head { min-height: 18px; margin-bottom: 4px; }
  .msg.user .content-area { max-width: 88%; }
  .msg.assistant .content-area { max-width: 100%; }
  .content { font-size: 14px; }
}
</style>
