<script setup lang="ts">
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import { fileApi } from '@/api/files'
import type { MessageVo } from '@/types'

const props = defineProps<{ message: MessageVo; streaming?: boolean }>()

const isUser = computed(() => props.message.role === 'user')

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
</script>

<template>
  <div class="msg" :class="isUser ? 'user' : 'assistant'">
    <div class="avatar">{{ isUser ? '我' : 'AI' }}</div>
    <div class="bubble">
      <div v-if="message._pending" class="status">发送中…</div>
      <div v-else-if="message.status === 'error'" class="status err">发送失败</div>
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
    </div>
  </div>
</template>

<style scoped>
.msg { display: flex; gap: 10px; margin-bottom: 16px; }
.msg.user { flex-direction: row-reverse; }
.avatar {
  width: 28px; height: 28px; border-radius: 6px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; background: #2f2f35;
}
.msg.user .avatar { background: #3a5a8c; }
.bubble {
  max-width: 75%; padding: 10px 14px; border-radius: 10px;
  background: #26262a; line-height: 1.6; word-break: break-word;
}
.msg.user .bubble { background: #2c4a7c; }
.content :deep(p) { margin: 6px 0; }
.content :deep(pre.hljs) { background: #18181c; border-radius: 6px; padding: 10px; overflow-x: auto; }
.content :deep(code) { font-family: ui-monospace, monospace; font-size: 13px; }
.content :deep(pre code) { background: none; }
.content :deep(code:not(pre code)) { background: #18181c; padding: 2px 5px; border-radius: 4px; }
.status { font-size: 12px; color: #888; margin-bottom: 4px; }
.status.err { color: #e06c75; }
.cursor { animation: blink 1s steps(2) infinite; color: #56b6c2; }
@keyframes blink { 50% { opacity: 0; } }

.att-list { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
.att-link {
  display: inline-flex; align-items: center; gap: 3px;
  font-size: 12px; padding: 3px 9px; border-radius: 10px;
  background: rgba(0, 0, 0, 0.25); color: #d8e6f8;
  border: 1px solid rgba(255, 255, 255, 0.18); cursor: pointer;
  text-decoration: none; max-width: 220px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap;
}
.att-link:hover { background: rgba(0, 0, 0, 0.4); }
</style>
