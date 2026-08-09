<script setup lang="ts">
import { ref, nextTick, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import { useMessage } from 'naive-ui'
import type { CommandItem } from '@/types'

const chat = useChatStore()
const msg = useMessage()
const router = useRouter()
const text = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 供欢迎页/模板库等外部注入:把提示词填入输入框并聚焦(不自动发送,便于用户补充)。 */
function fill(prompt: string) {
  if (!prompt) return
  text.value = prompt
  nextTick(() => {
    onInput()
    inputRef.value?.focus()
    inputRef.value?.setSelectionRange(text.value.length, text.value.length)
  })
}

defineExpose({ fill })

/** ---- slash 命令菜单 ---- */
const commands = ref<CommandItem[]>([])
let commandsLoadedFor = -1
async function ensureCommands() {
  if (!chat.currentSessionId) return
  if (commandsLoadedFor === chat.currentSessionId) return
  try {
    commands.value = await chatApi.listCommands(chat.currentSessionId)
    commandsLoadedFor = chat.currentSessionId
  } catch (e: any) {
    console.error('[ChatInput] load commands failed', e)
  }
}

/** 输入首个 token(/cmd 名称) */
const firstToken = computed(() => {
  const t = text.value.trim()
  if (!t.startsWith('/')) return ''
  return t.split(/\s+/)[0]
})
/** 是否在输入命令名(尚未输入参数空格) */
const typingCommandName = computed(() => {
  const t = text.value.trim()
  return t.startsWith('/') && !t.includes(' ')
})
const filteredCommands = computed(() => {
  const prefix = firstToken.value.slice(1).toLowerCase()
  return commands.value.filter((c) => c.name.toLowerCase().startsWith(prefix))
})
const showCommandMenu = computed(() => typingCommandName.value && filteredCommands.value.length > 0)
const menuIndex = ref(0)
watch(showCommandMenu, (open) => {
  if (open) menuIndex.value = 0
})

function chooseCommand(c: CommandItem) {
  text.value = '/' + c.name + ' '
  menuIndex.value = 0
  nextTick(() => inputRef.value?.focus())
}

function onKeydown(e: KeyboardEvent) {
  if (showCommandMenu.value) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const n = filteredCommands.value.length
      menuIndex.value = (menuIndex.value + (e.key === 'ArrowDown' ? 1 : n - 1)) % n
      return
    }
    if (e.key === 'Enter') {
      e.preventDefault()
      const c = filteredCommands.value[menuIndex.value]
      if (c) chooseCommand(c)
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      text.value = ''
      return
    }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
    e.preventDefault()
    onSend()
  }
}


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

/** 通用上传:把一组 File 上传到工作区 uploads/ 并加入附件列表。返回成功数 */
async function uploadFiles(fileList: File[]) {
  if (!fileList.length) return 0
  uploading.value = true
  let ok = 0
  let fail = 0
  try {
    for (const f of fileList) {
      try {
        await fileApi.upload(UPLOAD_DIR, f)
        attachments.value.push({ name: f.name, path: `${UPLOAD_DIR}/${f.name}` })
        ok++
      } catch {
        fail++
      }
    }
    if (fail === 0 && ok > 0) msg.success(`已上传 ${ok} 个文件到工作区 ${UPLOAD_DIR}/`)
    else if (ok > 0) msg.warning(`上传完成:${ok} 成功,${fail} 失败`)
    else msg.error('上传失败,请重试')
  } finally {
    uploading.value = false
  }
  return ok
}

async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || !files.length) return
  await uploadFiles(Array.from(files))
  input.value = ''
}

/** 粘贴上传:在输入框粘贴图片/文件时,自动上传为附件(纯文本粘贴不受影响)。 */
async function onPaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items || !items.length) return
  const files: File[] = []
  for (const it of Array.from(items)) {
    if (it.kind === 'file') {
      const f = it.getAsFile()
      if (f) files.push(f)
    }
  }
  if (!files.length) return
  e.preventDefault() // 阻止浏览器默认把图片/文件文本插入输入框
  await uploadFiles(files)
  nextTick(() => inputRef.value?.focus())
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
  const note = ` 已上传以下文件到工作区,可使用相对路径读取:\n${fileList}`
  return t ? `${t}\n\n${note}` : note
}

async function onSend() {
  const payload = buildPayload()
  if (!payload) return
  if (chat.isStreaming) {
    if (chat.streamingStuckSeconds > 0) {
      await chat.forceReleaseStreaming()
    } else {
      msg.info('AI 正在处理中,可点「停止」中断后再发送')
      return
    }
  }
  if (!attachments.value.length && payload.startsWith('/')) {
    await ensureCommands()
    const [head, ...rest] = payload.split(' ')
    const cmd = commands.value.find((c) => c.name === head.slice(1))
    if (cmd) {
      text.value = ''
      attachments.value = []
      await chat.runCommand(cmd.name, rest.join(' ').trim())
      await nextTick()
      inputRef.value?.focus()
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
  if (text.value.trim().startsWith('/')) ensureCommands()
}

async function onAbort() {
  if (chat.streamingStuckSeconds > 0) await chat.forceReleaseStreaming()
  else await chat.abort()
}

/** ---- 底部工具栏:模型选择 ---- */
const modelOptions = computed(() => {
  const opts: { label: string; value: string }[] = []
  for (const p of chat.availableModels) {
    for (const m of p.models) {
      opts.push({
        label: `${p.name} / ${m.name}`,
        value: `${p.providerId}::${m.modelId}`,
      })
    }
  }
  return opts
})
const modelValue = computed({
  get: () => {
    if (chat.selectedProviderId && chat.selectedModelId)
      return `${chat.selectedProviderId}::${chat.selectedModelId}`
    return null
  },
  set: (v: string | null) => {
    if (!v) return
    const [pid, mid] = v.split('::')
    chat.selectModel(pid, mid)
  },
})

/** ---- 底部工具栏:模式(agent)选择 ---- */
const agentMenuOpen = ref(false)
const agentOptions = computed(() =>
  chat.agents.map((a) => ({ label: a.name, key: a.name })),
)
async function onAgentMenuShow(show: boolean) {
  if (show) {
    await chat.loadAgents()
    agentMenuOpen.value = true
  } else {
    agentMenuOpen.value = false
  }
}
async function onAgentSelect(key: string) {
  try {
    await chat.switchAgent(key)
    msg.success(`已切换到 ${key}`)
  } catch (e: any) {
    msg.error(e.message || '切换失败')
  }
}

/** ---- 会话 token 进度环:300K token 满环(适配大上下文模型) ---- */
const TOKEN_LIMIT = 300_000
const statsOpen = ref(false)
const stats = computed(() => {
  const msgs = chat.messages
  let user = 0, assistant = 0, tool = 0, chars = 0
  let toolCalls = 0
  for (const m of msgs) {
    if (m.role === 'user') user++
    else if (m.role === 'assistant') assistant++
    else if (m.role === 'tool') tool++
    chars += (m.content || '').length
    toolCalls += m.parts?.toolCalls?.length || 0
    for (const tc of m.parts?.toolCalls || []) {
      if (tc.input != null) chars += JSON.stringify(tc.input).length
      if (typeof tc.output === 'string') chars += tc.output.length
    }
  }
  return { total: msgs.length, user, assistant, tool, toolCalls, chars, estTokens: Math.ceil(chars / 2) }
})
/** 进度 0~1;>=300K 满环 */
const tokenRatio = computed(() => Math.min(1, stats.value.estTokens / TOKEN_LIMIT))
/** 环色:低占用柔和蓝,>60% 变琥珀,>85% 变红(提示该开新会话了) */
const ringColor = computed(() => {
  const r = tokenRatio.value
  if (r >= 0.85) return 'var(--err)'
  if (r >= 0.6) return 'var(--warn)'
  return 'var(--accent)'
})
const ringNearFull = computed(() => tokenRatio.value >= 0.85)

/** 建议新开会话 */
async function startNewChat() {
  try {
    const s = await chat.createSession('New Chat')
    await router.push(`/session/${s.id}`)
  } catch (e: any) {
    msg.error(e.message || '新建会话失败')
  }
}
</script>

<template>
  <div class="chat-input">
    <!-- slash 命令菜单 -->
    <div v-if="showCommandMenu" class="cmd-menu">
      <div
        v-for="(c, i) in filteredCommands"
        :key="c.name"
        class="cmd-item"
        :class="{ active: i === menuIndex }"
        @mouseenter="menuIndex = i"
        @mousedown.prevent="chooseCommand(c)"
      >
        <span class="cmd-name">/{{ c.name }}</span>
        <span class="cmd-desc">{{ c.description || '' }}</span>
      </div>
    </div>

    <!-- 附件 chip 列表 -->
    <div v-if="attachments.length" class="attachments">
      <span v-for="(a, i) in attachments" :key="a.path" class="att-chip">
        <span class="att-name" :title="a.path">{{ a.name }}</span>
        <span class="att-remove" @click="removeAttachment(i)"></span>
      </span>
    </div>

    <input ref="fileInputRef" type="file" multiple class="hidden-input" @change="onFileChange" />

    <!-- 输入框:无边框,全宽 -->
    <textarea
      ref="inputRef"
      v-model="text"
      class="textarea"
      rows="1"
      :placeholder="chat.isStreaming
        ? (hasPending ? '上方有待答问题,作答后发送;或直接输入以继续…' : 'AI 正在处理中,可点「停止」中断…')
        : '随便问点什么, / 可查看命令, @ 可添加上下文...'"
      @keydown="onKeydown"
      @input="onInput"
      @paste="onPaste"
    />

    <!-- 底部工具栏: +附件 | 模型 | 模式 | 发送 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <!-- 会话 token 进度环:点击查看详情 -->
        <button
          class="token-ring-btn"
          :class="{ full: ringNearFull }"
          :title="`会话约 ${stats.estTokens.toLocaleString()} token / ${TOKEN_LIMIT.toLocaleString()},点击查看详情${ringNearFull ? ';建议新开会话' : ''}`"
          @click="statsOpen = true"
        >
          <svg class="token-ring" width="30" height="30" viewBox="0 0 36 36">
            <circle class="ring-bg" cx="18" cy="18" r="15" />
            <circle
              class="ring-fg"
              cx="18" cy="18" r="15"
              :style="{
                strokeDasharray: `${2 * Math.PI * 15}`,
                strokeDashoffset: `${2 * Math.PI * 15 * (1 - tokenRatio)}`,
                stroke: ringColor,
              }"
            />
            <text x="18" y="21" class="ring-text" :style="{ fill: ringColor }">{{ Math.round(tokenRatio * 100) }}%</text>
          </svg>
        </button>

        <!-- 附件按钮 -->
        <button class="tool-btn" title="选择附件" @click="pickFile">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
        </button>

        <!-- 模型选择 -->
        <n-select
          v-model:value="modelValue"
          :options="modelOptions"
          placeholder="选择模型"
          size="tiny"
          class="toolbar-select"
          :disabled="!modelOptions.length"
        />

        <!-- 模式(agent)选择 -->
        <n-dropdown
          :options="agentOptions"
          :show="agentMenuOpen"
          trigger="click"
          placement="top-start"
          @update:show="onAgentMenuShow"
          @select="onAgentSelect"
        >
          <button class="tool-btn tool-btn-text" title="选择模式">
            {{ chat.currentSession?.agent || 'Default' }}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
          </button>
        </n-dropdown>
      </div>

      <div class="toolbar-right">
        <template v-if="chat.isStreaming">
          <button class="send-btn send-btn-stop" title="停止" @click="onAbort">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
          </button>
        </template>
        <button v-else class="send-btn" :class="{ disabled: !canSend }" :disabled="!canSend" title="发送 (Enter)" @click="onSend">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="5 12 12 5 19 12"/></svg>
        </button>
      </div>
    </div>

    <!-- 会话统计详情 -->
    <n-modal
      v-model:show="statsOpen"
      preset="card"
      title="会话统计"
      :style="{ width: '440px' }"
      :bordered="false"
    >
      <div class="stats-grid">
        <div class="stat"><span class="k">总消息数</span><span class="v">{{ stats.total }}</span></div>
        <div class="stat"><span class="k">用户消息</span><span class="v">{{ stats.user }}</span></div>
        <div class="stat"><span class="k">AI 消息</span><span class="v">{{ stats.assistant }}</span></div>
        <div class="stat"><span class="k">工具消息</span><span class="v">{{ stats.tool }}</span></div>
        <div class="stat"><span class="k">工具调用次数</span><span class="v">{{ stats.toolCalls }}</span></div>
        <div class="stat"><span class="k">总字符数</span><span class="v">{{ stats.chars }}</span></div>
        <div class="stat"><span class="k">约计 token</span><span class="v">{{ stats.estTokens }}</span></div>
        <div class="stat"><span class="k">token 进度</span><span class="v">{{ Math.round(tokenRatio * 100) }}%</span></div>
      </div>
      <p class="stats-note">token 数为粗略估算（总字符数 / 2），仅供参考。300K 满环。</p>
      <div v-if="ringNearFull" class="stats-warn">
        <span>会话上下文已接近上限，建议新开会话以释放上下文。</span>
        <n-button size="small" type="primary" @click="startNewChat">新开会话</n-button>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
.chat-input {
  position: relative;
  border-top: 1px solid var(--border-soft);
  padding: 12px 16px 12px;
  background: var(--bg-elev);
}

/* 命令菜单 */
.cmd-menu {
  position: absolute; bottom: 100%; left: 16px; right: 16px; z-index: 20;
  margin-bottom: 6px; max-height: 260px; overflow-y: auto;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-sm); box-shadow: var(--shadow);
}
.cmd-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px; cursor: pointer; font-size: 13px;
}
.cmd-item.active, .cmd-item:hover { background: var(--accent-soft); }
.cmd-name { color: var(--accent-text); font-family: ui-monospace, monospace; flex-shrink: 0; }
.cmd-desc {
  color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* 附件 */
.attachments {
  display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px;
}
.att-chip {
  display: inline-flex; align-items: center; gap: 4px;
  background: var(--accent-soft); color: var(--accent-text);
  border: 1px solid rgba(107, 138, 253, 0.35);
  font-size: 12px; padding: 3px 8px; border-radius: 10px; max-width: 240px;
}
.att-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.att-remove { cursor: pointer; opacity: .7; padding: 0 2px; }
.att-remove:hover { opacity: 1; color: var(--err); }

/* 输入框:柔和、无生硬边框 */
.textarea {
  width: 100%; resize: none;
  padding: 12px 14px; border-radius: var(--radius);
  border: 1px solid var(--border); background: var(--surface); color: var(--text);
  font-size: 14px; line-height: 1.6; outline: none;
  max-height: 200px; overflow-y: auto;
  box-sizing: border-box;
  transition: border-color .15s, background .15s;
}
.textarea::placeholder { color: var(--text-3); }
.textarea:focus { border-color: var(--accent); background: var(--surface-hover); }
.hidden-input { display: none; }

/* 底部工具栏 */
.toolbar {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 10px; gap: 8px;
}
.toolbar-left {
  display: flex; align-items: center; gap: 6px; flex: 1; min-width: 0;
}
.toolbar-right { flex-shrink: 0; }

/* 工具按钮(通用):柔和描边 */
.tool-btn {
  display: inline-flex; align-items: center; gap: 4px;
  background: transparent; border: 1px solid var(--border); border-radius: var(--radius-sm);
  color: var(--text-3); cursor: pointer; padding: 5px 10px;
  font-size: 13px; line-height: 1; white-space: nowrap;
  transition: background .15s, color .15s, border-color .15s;
}
.tool-btn:hover { background: var(--surface-hover); color: var(--text-2); border-color: var(--border); }
.tool-btn svg { flex-shrink: 0; }
.tool-btn-text { padding: 5px 10px; }

/* 模型下拉(紧凑) */
.toolbar-select {
  max-width: 200px;
}
.toolbar-select :deep(.n-base-selection) {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 13px;
  min-height: 28px;
}
.toolbar-select :deep(.n-base-selection:hover) {
  border-color: var(--border);
  background: var(--surface-hover);
}

/* 发送按钮:圆形渐变 */
.send-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 50%;
  background: linear-gradient(135deg, #6b8afd, #7d6bfd); border: none; cursor: pointer;
  color: #fff; transition: filter .15s, opacity .15s;
  box-shadow: 0 2px 8px rgba(107, 138, 253, 0.3);
}
.send-btn:hover:not(.disabled) { filter: brightness(1.12); }
.send-btn.disabled { background: var(--surface-hover); color: var(--text-3); cursor: default; box-shadow: none; }
.send-btn-stop { background: linear-gradient(135deg, #e47a86, #d96a78); box-shadow: 0 2px 8px rgba(228, 122, 134, 0.3); }
.send-btn-stop:hover { filter: brightness(1.1); }

/* token 进度环 */
.token-ring-btn {
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; border: none; padding: 0; cursor: pointer;
  border-radius: 50%;
}
.token-ring-btn:hover { filter: brightness(1.2); }
.token-ring-btn.full { animation: ring-pulse 2s ease-in-out infinite; }
@keyframes ring-pulse {
  0%, 100% { filter: brightness(1); }
  50% { filter: brightness(1.25); }
}
.token-ring { display: block; }
.ring-bg { fill: none; stroke: var(--border); stroke-width: 3; }
.ring-fg {
  fill: none; stroke-width: 3; stroke-linecap: round;
  transform: rotate(-90deg); transform-origin: 18px 18px;
  transition: stroke-dashoffset .3s ease, stroke .3s ease;
}
.ring-text {
  font-size: 9px; font-weight: 600; text-anchor: middle;
  font-family: ui-monospace, monospace;
}

/* 统计弹窗 */
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.stat {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 12px 8px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.stat .k { font-size: 12px; color: var(--text-3); }
.stat .v { font-size: 18px; font-weight: 600; color: var(--accent-text); }
.stats-note { color: var(--text-3); font-size: 12px; margin-top: 12px; }
.stats-warn {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  margin-top: 12px; padding: 10px 12px; border-radius: var(--radius-sm);
  background: rgba(228, 122, 134, 0.1); border: 1px solid rgba(228, 122, 134, 0.3);
  color: var(--err); font-size: 13px;
}
</style>
