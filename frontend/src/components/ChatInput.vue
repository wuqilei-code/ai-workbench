<script setup lang="ts">
import { ref, nextTick, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import { useMessage, useDialog } from 'naive-ui'
import type { CommandItem } from '@/types'

const chat = useChatStore()
const msg = useMessage()
const dialog = useDialog()
const router = useRouter()
const text = ref('')
const inputRef = ref<HTMLTextAreaElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 交由父级打开「生成文档」「解读报错」弹窗 / 触发测试分析 */
const emit = defineEmits<{
  (e: 'openDocGen'): void
  (e: 'openErrorDiagnose'): void
  (e: 'openTestAnalysis'): void
}>()

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

/** 把工作区已有的文件作为附件挂到输入框(不重复上传;如已挂载则不重复)。 */
function attachFilePath(path: string) {
  if (!path) return
  const name = path.split(/[\\/]/).filter(Boolean).pop() || path
  if (attachments.value.some((a) => a.path === path)) return
  attachments.value.push({ name, path })
}

defineExpose({ fill, attachFilePath })

/** 监听 chat.pendingFill:建议追问等场景把提示词填入输入框(消费一次后清空) */
watch(() => chat.pendingFill, (v) => {
  if (!v) return
  fill(v)
  chat.pendingFill = ''
})

/** 监听 chat.pendingAttachPath:文件树右键「问 AI」等把文件挂到附件(消费一次后清空) */
watch(() => chat.pendingAttachPath, (v) => {
  if (!v) return
  attachFilePath(v)
  chat.pendingAttachPath = ''
  // 提示词汇总当前已挂载的全部文件,而不是只提最新一个
  const paths = attachments.value.map((a) => a.path)
  const list = paths.map((p) => `- ${p}`).join('\n')
  const header = paths.length === 1 ? `请看看工作区里的这个文件:${paths[0]}\n\n我需要你:` : `请看看工作区里的这些文件:\n${list}\n\n我需要你:`
  fill(header)
})

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
    msg.error('命令菜单加载失败,请重试')
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
/** 上传进度百分比(仅 zip 等大文件展示);null = 不在上传 */
const uploadProgress = ref<number | null>(null)

/** 数据文件扩展名:检测到这些文件时展示数据问答引导 */
const DATA_EXT = ['.csv', '.xlsx', '.xls', '.json', '.tsv', '.txt']
const dataFiles = computed(() =>
  attachments.value.filter((a) => {
    const lower = a.name.toLowerCase()
    return DATA_EXT.some((e) => lower.endsWith(e))
  }),
)
const dataSamples = computed(() => {
  if (!dataFiles.value.length) return []
  const samples: string[] = []
  // 每个数据文件各生成一条分析示例,移除/新增文件后自动同步
  for (const f of dataFiles.value) {
    samples.push(`分析 ${f.name} 的数据结构,告诉我有哪些字段`)
  }
  if (dataFiles.value.length === 1) {
    const first = dataFiles.value[0].name
    samples.push(`对 ${first} 做整体数据概览(行数/字段/常见值分布)`)
    samples.push(`从 ${first} 中找出最值得关注的规律或异常`)
    samples.push(`基于 ${first} 生成一份数据分析报告`)
  } else {
    samples.push('对比这几个数据文件,找出它们的差异和关联')
    samples.push('汇总所有数据文件,生成一份综合数据分析报告')
  }
  return samples
})
/** 点击示例:把示例问题填入输入框(附件仍在,发送时会带文件路径) */
function applySample(sample: string) {
  text.value = sample
  nextTick(() => {
    onInput()
    inputRef.value?.focus()
    inputRef.value?.setSelectionRange(text.value.length, text.value.length)
  })
}

/** hasPending:AI 真的在等用户输入(question/permission),用于占位文案与发送拦截 */
const hasPending = computed(() => chat.hasPendingInteraction)

const canSend = computed(() => !!(text.value.trim() || attachments.value.length))

function pickFile() {
  fileInputRef.value?.click()
}

/** 通用上传:把一组 File 上传到工作区 uploads/ 并加入附件列表。返回成功数。
 *  ZIP 项目包走 upload-zip 接口解压到 uploads/<zip名>/ 目录,附件指向该目录。 */
async function uploadFiles(fileList: File[]) {
  if (!fileList.length) return 0
  uploading.value = true
  let ok = 0
  let fail = 0
  try {
    for (const f of fileList) {
      try {
        const lower = f.name.toLowerCase()
        if (lower.endsWith('.zip')) {
          const base = f.name.replace(/\.zip$/i, '')
          const dir = `${UPLOAD_DIR}/${base}`
          uploadProgress.value = 0
          const count = await fileApi.uploadZip(dir, f, (pct) => { uploadProgress.value = pct })
          uploadProgress.value = null
          attachments.value.push({ name: f.name, path: dir })
          msg.success(`已解压 ${count} 个项目文件到工作区 ${dir}/`)
        } else {
          await fileApi.upload(UPLOAD_DIR, f)
          attachments.value.push({ name: f.name, path: `${UPLOAD_DIR}/${f.name}` })
        }
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
    uploadProgress.value = null
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

/** 拖拽上传:把拖入的文件(含 zip 项目包)上传为附件。 */
async function onDrop(e: DragEvent) {
  const files = e.dataTransfer?.files
  if (!files || !files.length) return
  await uploadFiles(Array.from(files))
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
  if (chat.compacting) {
    msg.info('正在压缩会话中,请稍候再发送')
    return
  }
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
      const lastText = text.value
      const lastAttachments = [...attachments.value]
      text.value = ''
      attachments.value = []
      try {
        await chat.runCommand(cmd.name, rest.join(' ').trim())
      } catch (e: any) {
        text.value = lastText
        attachments.value = lastAttachments
        msg.error(e?.message || '命令执行失败,已为你保留输入内容')
        await nextTick()
        resizeInput()
        inputRef.value?.focus()
        return
      }
      await nextTick()
      inputRef.value?.focus()
      return
    }
  }
  const lastText = text.value
  const lastAttachments = [...attachments.value]
  text.value = ''
  attachments.value = []
  try {
    await chat.sendMessage(payload)
  } catch (e: any) {
    // 发送失败:回填输入原文与附件,便于用户修正后重试,而不是丢失输入
    text.value = lastText
    attachments.value = lastAttachments
    msg.error(e?.message || '发送失败,已为你保留输入内容')
    await nextTick()
    resizeInput()
    inputRef.value?.focus()
    return
  }
  await nextTick()
  inputRef.value?.focus()
}

function onInput() {
  resizeInput()
  if (text.value.trim().startsWith('/')) ensureCommands()
}

/** 文本框自适应高度:内容变化(含 programmatic 清空)后重算,保证清空/发送后恢复默认一行高度 */
function resizeInput() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

/** 兜底:任何方式改变 text(模板填充/粘贴/发送清空/命令清空)都触发高度重算 */
watch(text, () => {
  nextTick().then(resizeInput)
})

async function onAbort() {
  if (chat.streamingStuckSeconds > 0) {
    await chat.forceReleaseStreaming()
    return
  }
  dialog.warning({
    title: '停止本轮生成',
    content: '停止后 AI 将立即中断。已执行的变更仍会保存在工作区,结束后仍可查看这次的变更总结。确定停止？',
    positiveText: '停止',
    negativeText: '继续生成',
    onPositiveClick: () => chat.abort(),
  })
}

/** 主动压缩会话:调用 opencode summarize,总结早期内容释放上下文。 */
async function onSummarize() {
  if (!chat.currentSessionId) return
  dialog.warning({
    title: '压缩会话',
    content: '将自动总结早期对话内容以释放上下文,最近 2 轮对话保留完整。确定压缩?',
    positiveText: '压缩',
    negativeText: '取消',
    onPositiveClick: async () => {
      if (chat.compacting) return
      try {
        await chatApi.summarizeSession(chat.currentSessionId!)
        // 请求返回即压缩已触发(opencode 后台异步总结):立即把 token 环的统计起点
        // 推到当前最后一条消息,让圆环即时回落;若之后有 session.compacted 事件再幂等刷新。
        chat.markCompacted()
        // 显示"正在压缩"提示;compaction agent 生成 summary 消息时后端会推 session.compacting
        // 覆盖(幂等),压缩完成(session.compacted)后自动消失。
        chat.compacting = true
        statsOpen.value = false
      } catch (e: any) {
        msg.error(e?.message || '压缩失败')
      }
    },
  })
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
    const label = modelOptions.value.find((o) => o.value === v)?.label
    msg.success(`已切换模型:${label || mid}`)
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

/** ---- 会话 token 进度环:优先用 opencode 真实 token 用量,估算兜底 ---- */
const statsOpen = ref(false)
const stats = computed(() => {
  const msgs = chat.messages
  // 压缩点:store 在 session.compacted 后设为当时最大消息 id;其前的历史已总结、字符不参与估算
  const cutoff = chat.compactCutoffId

  let user = 0, assistant = 0, tool = 0, chars = 0
  let toolCalls = 0
  for (const m of msgs) {
    // 跳过压缩产生的消息(占位/Continue/总结),不参与统计
    if (m.compactionSummary) continue
    if (m.role === 'user' && !(m.content || '').trim()) continue
    if ((m.content || '').trim().startsWith('Continue if you have next steps')) continue
    if (cutoff > 0 && (m.id ?? 0) <= cutoff) continue
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

  // 真实 token 用量(与 opencode web 一致):取最后一条带 tokens 的 assistant 消息,
  //    total = input + output + reasoning + cache.read + cache.write。
  for (let i = msgs.length - 1; i >= 0; i--) {
    const t = msgs[i].tokens
    if (!t || msgs[i].compactionSummary) continue
    const total = (t.input ?? 0) + (t.output ?? 0) + (t.reasoning ?? 0) + (t.cache?.read ?? 0) + (t.cache?.write ?? 0)
    return {
      total: msgs.length, user, assistant, tool, toolCalls, chars,
      estTokens: Math.max(0, total),
      real: true, tokens: t,
    }
  }

  // 估算兜底:chars/4(对齐 opencode),无真实 tokens
  return {
    total: msgs.length, user, assistant, tool, toolCalls, chars,
    estTokens: Math.ceil(chars / 4),
    real: false, tokens: null,
  }
})

/** 模型上下文窗口上限:取当前会话模型配置的 context_limit,缺省 300K(仅估算兜底用,真实 tokens 不需要它) */
const TOKEN_LIMIT = 300_000
const modelLimit = computed(() => {
  const s = chat.currentSession
  if (!s?.providerId || !s?.modelId) return TOKEN_LIMIT
  for (const p of chat.availableModels) {
    if (p.providerId !== s.providerId) continue
    for (const m of p.models) {
      if (m.modelId === s.modelId && m.contextLimit && m.contextLimit > 0) return m.contextLimit
    }
  }
  return TOKEN_LIMIT
})

/** 进度 0~1;>=模型窗口满环 */
const tokenRatio = computed(() => {
  if (stats.value.real) {
    const limit = modelLimit.value
    const t = stats.value.tokens!
    // 真实模式:上下文占用 = 输入 token(input 已含缓存读);output/reasoning 是响应,不占窗口
    const input = t.input ?? 0
    return Math.min(1, input / limit)
  }
  return Math.min(1, stats.value.estTokens / TOKEN_LIMIT)
})
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
  <div
    class="chat-input"
    @dragover.prevent
    @drop.prevent="onDrop"
  >
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

    <!-- 上传进度(zip 大文件或普通文件上传时显示) -->
    <div v-if="uploading || uploadProgress !== null" class="upload-progress">
      <span v-if="uploadProgress !== null" class="up-label">正在上传项目包… {{ uploadProgress }}%</span>
      <span v-else class="up-label">正在上传文件…</span>
      <n-progress v-if="uploadProgress !== null" :percentage="uploadProgress" :show-indicator="false" :height="6" />
      <div v-else class="up-indeterminate"><span class="up-bar"></span></div>
    </div>

    <!-- 附件 chip 列表 -->
    <div v-if="attachments.length" class="attachments">
      <span v-for="(a, i) in attachments" :key="a.path" class="att-chip">
        <span class="att-name" :title="a.path">{{ a.name }}</span>
        <span class="att-remove" @click="removeAttachment(i)"></span>
      </span>
    </div>

    <!-- 数据问答引导卡片:上传数据文件(CSV/Excel/JSON)后出现,示例提问一键填入 -->
    <div v-if="dataFiles.length" class="data-guide">
      <div class="dg-title">📊 已识别 {{ dataFiles.length }} 个数据文件,可以这样问:</div>
      <div class="dg-samples">
        <span
          v-for="s in dataSamples"
          :key="s"
          class="dg-sample"
          @click="applySample(s)"
        >{{ s }}</span>
      </div>
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
          :title="`${stats.real ? '会话上下文' : '会话约'} ${stats.estTokens.toLocaleString()} token / ${(stats.real ? modelLimit : TOKEN_LIMIT).toLocaleString()},点击查看详情${ringNearFull ? ';建议新开会话' : ''}`"
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
        <button class="tool-btn" title="选择附件" @click="pickFile" :disabled="uploading">
          <span v-if="uploading" class="tool-spinner"></span>
          <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
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

        <!-- 快捷工具:测试分析 / 生成文档 / 解读报错 / 我的MCP -->
        <button class="tool-btn tool-btn-text" title="源码测试分析:上传项目 zip 生成测试点/用例/脚本" @click="emit('openTestAnalysis')">🧪 测试</button>
        <button class="tool-btn tool-btn-text" title="一键生成文档(周报/会议纪要/邮件/分析报告)" @click="emit('openDocGen')">📄 生成文档</button>
        <button class="tool-btn tool-btn-text" title="粘贴报错信息,让 AI 解读" @click="emit('openErrorDiagnose')">🩺 解读报错</button>
        <button class="tool-btn tool-btn-text" title="配置我的 MCP Server(仅对我生效)" @click="router.push('/settings/mcps')">🔌 我的MCP</button>
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
        <template v-if="stats.real">
          <div class="stat"><span class="k">上下文 token(输入)</span><span class="v">{{ stats.tokens!.input }}</span></div>
          <div class="stat"><span class="k">输出 token</span><span class="v">{{ stats.tokens!.output }}</span></div>
          <div class="stat"><span class="k">推理 token</span><span class="v">{{ stats.tokens!.reasoning }}</span></div>
          <div class="stat"><span class="k">缓存读取/写入</span><span class="v">{{ stats.tokens!.cache?.read ?? 0 }} / {{ stats.tokens!.cache?.write ?? 0 }}</span></div>
        </template>
        <template v-else>
          <div class="stat"><span class="k">约计 token</span><span class="v">{{ stats.estTokens }}</span></div>
        </template>
        <div class="stat"><span class="k">token 进度</span><span class="v">{{ Math.round(tokenRatio * 100) }}%</span></div>
      </div>
      <p v-if="stats.real" class="stats-note">为模型真实返回的 token 用量(上限 {{ modelLimit.toLocaleString() }})。压缩后该值会随上下文释放而下降。</p>
      <p v-else class="stats-note">token 数为粗略估算（字符数 / 4），仅供参考。</p>
      <div class="stats-warn" :class="{ alert: ringNearFull }">
        <span>{{ ringNearFull ? '会话上下文已接近上限，可压缩会话以释放上下文，或新开会话。' : '上下文占用过高时，可压缩会话以释放空间。' }}</span>
        <div class="stats-actions">
          <n-button size="small" @click="onSummarize">压缩会话</n-button>
          <n-button size="small" type="primary" @click="startNewChat">新开会话</n-button>
        </div>
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
.upload-progress { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.up-label { font-size: 12px; color: var(--text-3); flex-shrink: 0; }
.upload-progress :deep(.n-progress) { flex: 1; }
.up-indeterminate { flex: 1; height: 6px; border-radius: 3px; background: var(--surface-hover); overflow: hidden; position: relative; }
.up-bar { position: absolute; top: 0; left: 0; height: 100%; width: 40%; border-radius: 3px; background: linear-gradient(90deg, transparent, var(--accent), transparent); animation: up-slide 1.2s ease-in-out infinite; }
@keyframes up-slide { 0% { left: -40%; } 100% { left: 100%; } }
.tool-spinner { width: 14px; height: 14px; border: 2px solid var(--text-3); border-top-color: transparent; border-radius: 50%; animation: up-spin .7s linear infinite; }
@keyframes up-spin { to { transform: rotate(360deg); } }
.tool-btn:disabled { opacity: .55; cursor: default; }
.tool-btn:disabled:hover { background: transparent; }
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
.att-remove { cursor: pointer; opacity: .7; padding: 0 4px; }
.att-remove::after { content: '×'; font-size: 13px; line-height: 1; }
.att-remove:hover { opacity: 1; color: var(--err); }

/* 数据问答引导卡片 */
.data-guide {
  background: var(--accent-soft);
  border: 1px solid rgba(107, 138, 253, 0.3);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  margin-bottom: 8px;
}
.dg-title { font-size: 12px; font-weight: 600; color: var(--accent-text); margin-bottom: 6px; }
.dg-samples { display: flex; flex-wrap: wrap; gap: 6px; }
.dg-sample {
  font-size: 12px; color: var(--text-2);
  background: var(--surface); border: 1px solid var(--border-soft);
  border-radius: 12px; padding: 3px 10px; cursor: pointer;
  transition: border-color .15s, color .15s;
}
.dg-sample:hover { border-color: var(--accent-text); color: var(--accent-text); }

/* 输入框:柔和、无生硬边框 */
.textarea {
  width: 100%; resize: none;
  padding: 12px 14px; border-radius: var(--radius);
  border: 1px solid var(--border); background: var(--surface); color: var(--text);
  font-size: 14px; line-height: 1.6; outline: none;
  max-height: 200px; overflow-y: auto;
  box-sizing: border-box;
  transition: border-color .2s, background .2s, box-shadow .2s;
}
.textarea::placeholder { color: var(--text-3); }
.textarea:hover { border-color: var(--border); }
.textarea:focus {
  border-color: var(--accent); background: var(--surface-hover);
  box-shadow: 0 0 0 3px var(--accent-soft), 0 4px 20px rgba(0, 0, 0, 0.1);
}
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
  display: flex; flex-direction: column; align-items: flex-start; gap: 8px;
  margin-top: 12px; padding: 10px 12px; border-radius: var(--radius-sm);
  background: rgba(107, 138, 253, 0.08); border: 1px solid rgba(107, 138, 253, 0.22);
  color: var(--text-2); font-size: 13px;
}
.stats-warn.alert {
  background: rgba(228, 122, 134, 0.1); border: 1px solid rgba(228, 122, 134, 0.3);
  color: var(--err);
}
.stats-actions { display: flex; gap: 8px; }
</style>
