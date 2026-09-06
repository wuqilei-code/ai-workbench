<script setup lang="ts">
import { ref, nextTick, computed, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { fileApi } from '@/api/files'
import { chatApi } from '@/api/chat'
import { useMessage, useDialog } from 'naive-ui'

const chat = useChatStore()
const msg = useMessage()
const dialog = useDialog()
const router = useRouter()
const text = ref('')
const inputRef = ref<HTMLDivElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)

/** ---- slash 命令菜单 ---- */
interface SkillInfo { name: string; description: string }
const slashMenuOpen = ref(false)
const slashFilter = ref('')
const slashSelectedIdx = ref(0)
const allSkills = ref<SkillInfo[]>([])
const slashMenuRef = ref<HTMLDivElement | null>(null)
const slashItemEl = ref<HTMLElement | null>(null)
/** 已选择的技能标签(用于去重和同步显示) */
const selectedSkills = ref<SkillInfo[]>([])

/** 加载内置技能列表(只加载一次) */
async function loadSlashSkills() {
  if (allSkills.value.length) return
  try {
    allSkills.value = await chatApi.builtinSkills()
  } catch { /* ignore */ }
}

/** 过滤后的技能列表 */
const filteredSkills = computed(() => {
  const q = slashFilter.value.toLowerCase()
  if (!q) return allSkills.value
  return allSkills.value.filter(
    (s) => s.name.includes(q) || s.description.toLowerCase().includes(q)
  )
})

/** 打开斜杠菜单 */
function openSlashMenu() {
  slashMenuOpen.value = true
  slashFilter.value = ''
  slashSelectedIdx.value = 0
  loadSlashSkills()
}

/** 关闭斜杠菜单 */
function closeSlashMenu() {
  slashMenuOpen.value = false
  slashFilter.value = ''
  slashSelectedIdx.value = 0
}

/** 获取 contenteditable 的纯文本内容(去掉标签的 innerText) */
function getPlainText(): string {
  return inputRef.value?.innerText || ''
}

/** 从 contenteditable 提取纯文本:遍历 DOM,跳过可见技能标签,
 *  隐藏占位符 {skill:name} 转为内联标记 [skill:name],保持原始位置。 */
function extractContent(): { skills: string[]; text: string } {
  const el = inputRef.value
  if (!el) return { skills: [], text: text.value }
  const skills: string[] = []
  let out = ''
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      out += node.textContent || ''
      return
    }
    const elm = node as HTMLElement
    if (elm.classList?.contains('skill-tag-marker')) return
    if (elm.tagName === 'BR') { out += '\n'; return }
    for (const child of elm.childNodes) walk(child)
  }
  el.querySelectorAll('span[style*="display: none"], span[style*="display:none"]').forEach((span) => {
    const m = (span.textContent || '').match(/^\{skill:(.+)\}$/)
    if (m && !skills.includes(m[1])) skills.push(m[1])
  })
  walk(el)
  out = out.replace(/\{skill:([^}]+)\}/g, '[skill:$1]')
  return { skills, text: out }
}

/** 选择技能:在 contenteditable 中插入隐藏占位符 + 可见标签 */
function selectSkill(skill: SkillInfo) {
  if (selectedSkills.value.some((s) => s.name === skill.name)) {
    closeSlashMenu()
    nextTick(() => inputRef.value?.focus())
    return
  }
  const el = inputRef.value
  if (!el) return
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount) return
  const range = sel.getRangeAt(0)
  // 光标在文本节点中,且前面有 /filter(斜杠命令):把 range 起点扩到斜杠处,清除斜杠命令文本
  if (range.startContainer.nodeType === Node.TEXT_NODE) {
    const t = range.startContainer.textContent || ''
    const before = t.slice(0, range.startOffset)
    const lastSlash = before.lastIndexOf('/')
    if (lastSlash >= 0 && !/\s/.test(before.slice(lastSlash + 1))) {
      range.setStart(range.startContainer, lastSlash)
    }
  }
  range.deleteContents()
  // 用 DocumentFragment 一次性插入,避免多次 insertNode 导致顺序错乱
  const frag = document.createDocumentFragment()
  const hideSpan = document.createElement('span')
  hideSpan.style.display = 'none'
  hideSpan.textContent = `{skill:${skill.name}}`
  const tag = document.createElement('span')
  tag.className = 'skill-tag-marker'
  tag.contentEditable = 'false'
  tag.setAttribute('data-skill', skill.name)
  tag.innerHTML = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg><span>${skill.name}</span><span class="skill-tag-x">×</span>`
  tag.querySelector('.skill-tag-x')?.addEventListener('click', (ev) => {
    ev.stopPropagation()
    hideSpan.remove()
    tag.remove()
    selectedSkills.value = selectedSkills.value.filter((s) => s.name !== skill.name)
  })
  frag.appendChild(hideSpan)
  frag.appendChild(tag)
  range.insertNode(frag)
  // 光标移到标签后
  const newRange = document.createRange()
  newRange.setStartAfter(tag)
  newRange.collapse(true)
  sel.removeAllRanges()
  sel.addRange(newRange)
  selectedSkills.value.push(skill)
  closeSlashMenu()
  nextTick(() => inputRef.value?.focus())
}

/** 移除已选技能标签 */
function removeSkill(name: string) {
  const el = inputRef.value
  if (el) {
    el.querySelectorAll('.skill-tag-marker').forEach((span) => {
      if (span.getAttribute('data-skill') === name) {
        span.remove()
      }
    })
  }
  selectedSkills.value = selectedSkills.value.filter((s) => s.name !== name)
}

/** 检测输入中是否处于斜杠菜单状态 */
function checkSlashState() {
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount) { closeSlashMenu(); return }
  const range = sel.getRangeAt(0)
  const textNode = range.startContainer
  if (textNode.nodeType !== Node.TEXT_NODE) { closeSlashMenu(); return }
  const t = textNode.textContent || ''
  const cursorPos = range.startOffset
  const before = t.slice(0, cursorPos)
  // 找最后一个 /，提取过滤词
  const lastSlash = before.lastIndexOf('/')
  if (lastSlash < 0) { closeSlashMenu(); return }
  const filter = before.slice(lastSlash + 1)
  // 过滤词不能含空格（说明已经不是斜杠命令了）
  if (/\s/.test(filter)) { closeSlashMenu(); return }
  slashFilter.value = filter
  slashSelectedIdx.value = 0
  if (!slashMenuOpen.value) openSlashMenu()
}

/** 交由父级打开「生成文档」「解读报错」弹窗 / 触发测试分析 */
const emit = defineEmits<{
  (e: 'openDocGen'): void
  (e: 'openErrorDiagnose'): void
  (e: 'openTestAnalysis'): void
}>()

/** 供欢迎页/模板库等外部注入:把提示词填入输入框并聚焦(不自动发送,便于用户补充)。 */
function fill(prompt: string) {
  if (!prompt) return
  const el = inputRef.value
  if (el) {
    el.innerText = prompt
    text.value = prompt
  }
  nextTick(() => {
    onInput()
    inputRef.value?.focus()
    // 将光标移到末尾
    const sel = window.getSelection()
    if (sel && el) {
      const range = document.createRange()
      range.selectNodeContents(el)
      range.collapse(false)
      sel.removeAllRanges()
      sel.addRange(range)
    }
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

/** ---- slash 命令菜单(deepagents 无命令,已移除) ---- */

function onKeydown(e: KeyboardEvent) {
  // 退格键:删除光标前的技能标签(含隐藏占位符)
  if (e.key === 'Backspace' && !slashMenuOpen.value) {
    const sel = window.getSelection()
    if (sel && sel.rangeCount) {
      const range = sel.getRangeAt(0)
      if (!range.collapsed) return
      const node = range.startContainer
      // 辅助:删除标签及其关联的隐藏占位符
      function removeTagWithPlaceholder(tagEl: Element) {
        const name = tagEl.getAttribute('data-skill') || ''
        // 找同级前一个隐藏 span
        let prev = tagEl.previousSibling
        while (prev && prev.nodeType === Node.ELEMENT_NODE && (prev as HTMLElement).style?.display === 'none') {
          const p = prev
          prev = prev.previousSibling
          p.remove()
        }
        tagEl.remove()
        selectedSkills.value = selectedSkills.value.filter((s) => s.name !== name)
      }
      // 光标在空文本节点中,前一个是标签
      if (node.nodeType === Node.TEXT_NODE && (node.textContent || '') === '') {
        const prev = node.previousSibling
        if (prev?.nodeType === Node.ELEMENT_NODE && (prev as Element).classList?.contains('skill-tag-marker')) {
          e.preventDefault()
          removeTagWithPlaceholder(prev as Element)
          return
        }
      }
      // 光标在文本节点开头,前一个是标签
      if (node.nodeType === Node.TEXT_NODE && range.startOffset === 0) {
        const prev = node.previousSibling
        if (prev?.nodeType === Node.ELEMENT_NODE && (prev as Element).classList?.contains('skill-tag-marker')) {
          e.preventDefault()
          removeTagWithPlaceholder(prev as Element)
          return
        }
      }
      // 光标直接在 contenteditable 根节点
      if (node === inputRef.value) {
        const child = inputRef.value.childNodes[range.startOffset - 1]
        if (child?.nodeType === Node.ELEMENT_NODE && (child as Element).classList?.contains('skill-tag-marker')) {
          e.preventDefault()
          removeTagWithPlaceholder(child as Element)
          return
        }
      }
    }
  }
  // 斜杠菜单打开时:上下选择、Enter 确认、Escape 关闭
  if (slashMenuOpen.value) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      slashSelectedIdx.value = (slashSelectedIdx.value + 1) % filteredSkills.value.length
      return
    }
    if (e.key === 'ArrowUp') {
      e.preventDefault()
      slashSelectedIdx.value = (slashSelectedIdx.value - 1 + filteredSkills.value.length) % filteredSkills.value.length
      return
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      const skill = filteredSkills.value[slashSelectedIdx.value]
      if (skill) selectSkill(skill)
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      closeSlashMenu()
      return
    }
    if (e.key === 'Tab') {
      e.preventDefault()
      const skill = filteredSkills.value[slashSelectedIdx.value]
      if (skill) selectSkill(skill)
      return
    }
  }
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey) {
    e.preventDefault()
    onSend()
  }
}


/** 会话内上传:文件落入工作区 uploads/ 目录,并把相对路径拼到消息文本里,
 *  让 agent 智能体(运行在同一 workspace)能按相对路径读取/操作该文件。 */
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
  const el = inputRef.value
  if (el) {
    el.innerText = sample
    text.value = sample
  }
  nextTick(() => {
    onInput()
    inputRef.value?.focus()
    // 将光标移到末尾
    const sel = window.getSelection()
    if (sel && el) {
      const range = document.createRange()
      range.selectNodeContents(el)
      range.collapse(false)
      sel.removeAllRanges()
      sel.addRange(range)
    }
  })
}

/** hasPending:AI 真的在等用户输入(question/permission),用于占位文案与发送拦截 */
const hasPending = computed(() => chat.hasPendingInteraction)

const canSend = computed(() => {
  const t = getPlainText().trim()
  return !!(t || attachments.value.length || selectedSkills.value.length)
})

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
  if (files.length) {
    e.preventDefault()
    await uploadFiles(files)
    nextTick(() => inputRef.value?.focus())
    return
  }
  // 文本粘贴:检测 [skill:name] 标记,转为技能标签
  const text = e.clipboardData?.getData('text') || ''
  if (!text.includes('[skill:')) return
  e.preventDefault()
  const sel = window.getSelection()
  if (!sel || !sel.rangeCount) return
  const range = sel.getRangeAt(0)
  range.deleteContents()
  const skillRe = /\[skill:([^\]]+)\]/g
  const frag = document.createDocumentFragment()
  let lastIdx = 0
  let m: RegExpExecArray | null
  while ((m = skillRe.exec(text)) !== null) {
    if (m.index > lastIdx) frag.appendChild(document.createTextNode(text.slice(lastIdx, m.index)))
    const skillName = m[1]
    // 无论技能是否在内置列表中,都创建标签(粘贴回显场景不依赖列表已加载)
    if (!selectedSkills.value.some((s) => s.name === skillName)) {
      const hideSpan = document.createElement('span')
      hideSpan.style.display = 'none'
      hideSpan.textContent = `{skill:${skillName}}`
      const tag = document.createElement('span')
      tag.className = 'skill-tag-marker'
      tag.contentEditable = 'false'
      tag.setAttribute('data-skill', skillName)
      tag.innerHTML = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg><span>${skillName}</span><span class="skill-tag-x">×</span>`
      tag.querySelector('.skill-tag-x')?.addEventListener('click', (ev) => {
        ev.stopPropagation()
        hideSpan.remove()
        tag.remove()
        selectedSkills.value = selectedSkills.value.filter((s) => s.name !== skillName)
      })
      frag.appendChild(hideSpan)
      frag.appendChild(tag)
      selectedSkills.value.push({ name: skillName, description: '' })
    } else {
      frag.appendChild(document.createTextNode(m[0]))
    }
    lastIdx = skillRe.lastIndex
  }
  if (lastIdx < text.length) frag.appendChild(document.createTextNode(text.slice(lastIdx)))
  range.insertNode(frag)
  range.collapse(false)
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
  const { text: plainText } = extractContent()
  const t = plainText.trim()
  const parts: string[] = []
  if (t) parts.push(t)
  if (attachments.value.length) {
    const fileList = attachments.value.map((a) => `[附件] ${a.path}`).join('\n')
    parts.push(`已上传以下文件到工作区,可使用相对路径读取:\n${fileList}`)
  }
  return parts.join('\n\n')
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
  const lastText = text.value
  const lastAttachments = [...attachments.value]
  text.value = ''
  selectedSkills.value = []
  attachments.value = []
  // 清空 contenteditable
  if (inputRef.value) inputRef.value.innerText = ''
  try {
    await chat.sendMessage(payload)
  } catch (e: any) {
    // 发送失败:回填输入原文与附件,便于用户修正后重试,而不是丢失输入
    text.value = lastText
    attachments.value = lastAttachments
    if (inputRef.value) inputRef.value.innerText = lastText
    msg.error(e?.message || '发送失败,已为你保留输入内容')
    await nextTick()
    resizeInput()
    inputRef.value?.focus()
    return
  }
  await nextTick()
  resizeInput()
  inputRef.value?.focus()
}

function onInput() {
  resizeInput()
  // 同步 text ref
  text.value = getPlainText()
  checkSlashState()
}

/** 处理 contenteditable 内的点击(×关闭技能标签) */
function onEditorClick(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (target.classList.contains('skill-tag-x')) {
    const tag = target.closest('.skill-tag-marker') as HTMLElement | null
    if (tag) {
      const name = tag.getAttribute('data-skill') || ''
      // 删除关联的隐藏占位符
      let prev = tag.previousSibling
      while (prev && prev.nodeType === Node.ELEMENT_NODE && (prev as HTMLElement).style?.display === 'none') {
        const p = prev
        prev = prev.previousSibling
        p.remove()
      }
      tag.remove()
      selectedSkills.value = selectedSkills.value.filter((s) => s.name !== name)
    }
  }
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

/** 上下键切换时,让选中项滚动到可视区 */
watch(slashSelectedIdx, () => {
  nextTick(() => {
    slashItemEl.value?.scrollIntoView({ block: 'nearest' })
  })
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

/** ---- 会话 token 进度环:优先用后端 deepagents 同口径用量,回退字符估算 ---- */
const statsOpen = ref(false)
const stats = computed(() => {
  const msgs = chat.messages
  let user = 0, assistant = 0, tool = 0, chars = 0
  let toolCalls = 0
  for (const m of msgs) {
    if (m.role === 'user' && !(m.content || '').trim()) continue
    if (m.role === 'user') user++
    else if (m.role === 'assistant') assistant++
    else if (m.role === 'tool') tool++
    toolCalls += m.parts?.toolCalls?.length || 0
    chars += (m.content || '').length
    for (const tc of m.parts?.toolCalls || []) {
      if (tc.args != null) chars += JSON.stringify(tc.args).length
      if (typeof tc.output === 'string') chars += tc.output.length
    }
  }
  const estTokens = Math.ceil(chars / 4)
  return { total: user + assistant + tool, user, assistant, tool, toolCalls, chars, estTokens }
})

/** 模型上下文窗口上限:仅在后端用量未就绪时作估算兜底分母用 */
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

/** 圆环数据源:后端 usage 事件(=deepagents 判断压缩所用的同一 token 数,分母=压缩阈值)优先;
 *  尚未经过模型调用/旧会话无持久化时回退到字符估算(分母=模型窗口)。 */
const ring = computed(() => {
  const u = chat.contextUsage
  if (u) {
    const limit = u.limit ?? modelLimit.value
    return { tokens: u.tokens, limit, ratio: Math.min(1, u.tokens / limit), real: true }
  }
  const limit = modelLimit.value
  return { tokens: stats.value.estTokens, limit, ratio: Math.min(1, stats.value.estTokens / limit), real: false }
})

/** 进度 0~1;>=压缩阈值满环 */
const tokenRatio = computed(() => ring.value.ratio)
/** 环色:低占用柔和蓝,>60% 变琥珀,>85% 变红(接近自动压缩阈值,提示可新开会话) */
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

    <!-- 输入卡片:contenteditable + 发送按钮 -->
    <div class="input-card">
      <div
        ref="inputRef"
        class="textarea contenteditable"
        contenteditable="true"
        :data-placeholder="chat.isStreaming
          ? (hasPending ? '上方有待答问题,作答后发送;或直接输入以继续…' : 'AI 正在处理中,可点「停止」中断…')
          : '随便问点什么…'"
        @keydown="onKeydown"
        @input="onInput"
        @paste="onPaste"
        @focus="onInput"
        @click="onEditorClick"
      />
      <div class="card-side">
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

    <!-- slash 命令菜单 -->
    <div v-if="slashMenuOpen && filteredSkills.length" ref="slashMenuRef" class="slash-menu">
      <div class="slash-menu-header">
        <span class="slash-menu-icon">/</span>
        <span>选择技能</span>
        <span class="slash-menu-count">{{ filteredSkills.length }}</span>
      </div>
      <div class="slash-menu-list">
        <div
          v-for="(skill, idx) in filteredSkills"
          :key="skill.name"
          :ref="(el) => { if (el && idx === slashSelectedIdx) slashItemEl = el as HTMLElement }"
          class="slash-menu-item"
          :class="{ active: idx === slashSelectedIdx }"
          @mouseenter="slashSelectedIdx = idx"
          @mousedown.prevent
          @click.stop="selectSkill(skill)"
        >
          <svg class="slash-menu-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
          <span class="slash-menu-name">{{ skill.name }}</span>
          <span class="slash-menu-desc">{{ skill.description }}</span>
        </div>
      </div>
      <div class="slash-menu-footer">
        <span>↑↓ 导航</span>
        <span>Enter 选择</span>
        <span>Esc 关闭</span>
      </div>
    </div>

    <!-- 底部工具栏: +附件 | 模型 | 模式 | 发送 -->
    <div class="toolbar">
      <div class="toolbar-left">
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

        <!-- 快捷工具:生成文档 / 解读报错 -->
        <button class="tool-btn tool-btn-text tool-quick" title="一键生成文档(周报/会议纪要/邮件/分析报告)" @click="emit('openDocGen')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
          生成文档
        </button>
        <button class="tool-btn tool-btn-text tool-quick" title="粘贴报错信息,让 AI 解读" @click="emit('openErrorDiagnose')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4.8 2.3A.3.3 0 1 0 5 2H4a2 2 0 0 0-2 2v5a6 6 0 0 0 6 6a6 6 0 0 0 6-6V4a2 2 0 0 0-2-2h-1a.2.2 0 1 0 .3.3"/><path d="M8 15v1a6 6 0 0 0 6 6a6 6 0 0 0 6-6v-4"/><circle cx="20" cy="10" r="2"/></svg>
          解读报错
        </button>
      </div>

      <div class="toolbar-right">
        <!-- 会话 token 进度环:点击查看详情 -->
        <button
          class="token-ring-btn"
          :class="{ full: ringNearFull }"
          :title="`${ring.real ? '会话上下文' : '会话约'} ${ring.tokens.toLocaleString()} token / ${ring.limit.toLocaleString()},点击查看详情${ringNearFull ? ';建议新开会话' : ''}`"
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
      </div>
    </div>

    <!-- 会话统计详情 -->
    <n-modal
      v-model:show="statsOpen"
      preset="card"
      title="会话统计"
      :style="{ width: '440px', maxWidth: '92vw' }"
      :bordered="false"
    >
      <div class="stats-grid">
        <div class="stat"><span class="k">总消息数</span><span class="v">{{ stats.total }}</span></div>
        <div class="stat"><span class="k">用户消息</span><span class="v">{{ stats.user }}</span></div>
        <div class="stat"><span class="k">AI 消息</span><span class="v">{{ stats.assistant }}</span></div>
        <div class="stat"><span class="k">工具消息</span><span class="v">{{ stats.tool }}</span></div>
        <div class="stat"><span class="k">工具调用次数</span><span class="v">{{ stats.toolCalls }}</span></div>
        <div class="stat"><span class="k">总字符数</span><span class="v">{{ stats.chars }}</span></div>
        <div class="stat"><span class="k">上下文 token</span><span class="v">{{ ring.tokens }}</span></div>
        <div class="stat"><span class="k">压缩阈值</span><span class="v">{{ ring.limit }}</span></div>
        <div class="stat"><span class="k">token 进度</span><span class="v">{{ Math.round(tokenRatio * 100) }}%</span></div>
      </div>
      <p class="stats-note">
        {{ ring.real
          ? '上下文 token 与压缩阈值由后端按 deepagents 压缩判断同口径统计。'
          : '本轮尚未经过模型调用,数值为粗略估算(字符数 / 4),发送消息后自动校准。' }}
      </p>
      <div class="stats-warn" :class="{ alert: ringNearFull }">
        <span>{{ ringNearFull ? '会话上下文已接近上限，可新开会话。' : '上下文占用过高时，可新开会话。' }}</span>
        <div class="stats-actions">
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
  padding: 16px 20px;
  background: var(--chat-bg);
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
  background: var(--accent-soft); color: var(--accent);
  border: 1px solid rgba(139, 92, 246, 0.3);
  font-size: 12px; padding: 3px 8px; border-radius: 6px; max-width: 240px;
}
.att-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.att-remove { cursor: pointer; opacity: .7; padding: 0 4px; }
.att-remove::after { content: '×'; font-size: 13px; line-height: 1; }
.att-remove:hover { opacity: 1; color: var(--err); }

/* 数据问答引导卡片 */
.data-guide {
  background: var(--accent-soft);
  border: 1px solid rgba(139, 92, 246, 0.25);
  border-radius: var(--radius);
  padding: 10px 14px;
  margin-bottom: 10px;
}
.dg-title { font-size: 12px; font-weight: 600; color: var(--accent); margin-bottom: 6px; }
.dg-samples { display: flex; flex-wrap: wrap; gap: 6px; }
.dg-sample {
  font-size: 12px; color: var(--text-2);
  background: var(--surface); border: 1px solid var(--border-soft);
  border-radius: 6px; padding: 4px 10px; cursor: pointer;
  transition: border-color .15s, color .15s;
}
.dg-sample:hover { border-color: var(--accent); color: var(--accent); }

/* 输入卡片:圆角卡片 + 边框 + 阴影,聚焦高亮 */
.input-card {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid var(--input-border);
  background: var(--input-bg);
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05);
  transition: border-color .2s, box-shadow .2s;
}
.input-card:focus-within {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft), 0 4px 18px rgba(139, 92, 246, 0.12);
}

/* 输入框:无边框,由卡片提供边界 */
.textarea {
  flex: 1;
  resize: none;
  padding: 6px 4px;
  border: none;
  background: transparent;
  color: var(--text);
  font-size: 14px;
  line-height: 1.6;
  outline: none;
  max-height: 200px;
  overflow-y: auto;
  box-sizing: border-box;
  white-space: pre-wrap;
  word-break: break-word;
}
.textarea.contenteditable { min-height: 1.6em; }
.textarea.contenteditable:empty::before {
  content: attr(data-placeholder);
  color: var(--text-3);
  opacity: 0.7;
  pointer-events: none;
}
.textarea::placeholder { color: var(--text-3); opacity: 0.7; }
/* 技能标签标记(动态插入,需 :deep) */
:deep(.skill-tag-marker) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: linear-gradient(135deg, rgba(99, 102, 241, 0.15), rgba(139, 92, 246, 0.15));
  color: var(--accent, #6366f1);
  border: 1px solid rgba(139, 92, 246, 0.4);
  font-size: 12px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 6px;
  margin: 0 2px;
  vertical-align: middle;
  line-height: 1.4;
  cursor: default;
  user-select: none;
}
:deep(.skill-tag-marker svg) { opacity: 0.7; flex-shrink: 0; }
:deep(.skill-tag-x) {
  margin-left: 2px;
  cursor: pointer;
  opacity: 0.6;
  font-size: 14px;
  line-height: 1;
}
:deep(.skill-tag-x:hover) { opacity: 1; color: var(--err, #ef4444); }
.card-side {
  display: flex;
  align-items: flex-end;
  flex-shrink: 0;
  padding-bottom: 2px;
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
.tool-btn:hover { background: var(--surface-hover); color: var(--text); border-color: var(--border); }
.tool-btn svg { flex-shrink: 0; }
.tool-btn-text { padding: 5px 10px; }

/* 模型下拉(紧凑) */
.toolbar-select {
  max-width: 200px;
  flex-shrink: 0;
}
.toolbar-select :deep(.n-base-selection) {
  background: transparent;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  font-size: 13px;
  height: 28px;
  box-sizing: border-box;
}
.toolbar-select :deep(.n-base-selection:hover) {
  border-color: var(--border);
  background: var(--surface-hover);
}
.toolbar-select :deep(.n-base-selection-label) {
  height: 26px;
  line-height: 26px;
  padding: 0 8px;
}
.toolbar-select :deep(.n-base-selection .n-base-selection-label__placeholder),
.toolbar-select :deep(.n-base-selection .n-base-selection-label__render-label) {
  font-size: 13px;
}
.toolbar-select :deep(.n-base-suffix) {
  right: 8px;
}

/* 发送按钮:圆形 */
.send-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--accent); border: none; cursor: pointer;
  color: #fff; transition: filter .15s, opacity .15s, transform .15s;
}
.send-btn:hover:not(.disabled) { filter: brightness(1.1); transform: translateY(-1px); }
.send-btn.disabled { background: var(--surface-hover); color: var(--text-3); cursor: default; }
.send-btn-stop { background: var(--err); }
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
  padding: 12px 8px; border-radius: var(--radius);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.stat .k { font-size: 12px; color: var(--text-3); }
.stat .v { font-size: 18px; font-weight: 600; color: var(--accent); }
.stats-note { color: var(--text-3); font-size: 12px; margin-top: 12px; }
.stats-warn {
  display: flex; flex-direction: column; align-items: flex-start; gap: 8px;
  margin-top: 12px; padding: 10px 12px; border-radius: var(--radius);
  background: var(--accent-soft); border: 1px solid rgba(139, 92, 246, 0.2);
  color: var(--text-2); font-size: 13px;
}
.stats-warn.alert {
  background: rgba(239, 68, 68, 0.08); border: 1px solid rgba(239, 68, 68, 0.25);
  color: var(--err);
}
.stats-actions { display: flex; gap: 8px; }

/* 移动端:精简工具栏,收起快捷文本按钮,紧凑模型下拉 */
@media (max-width: 768px) {
  .chat-input { padding: 10px 10px; }
  .tool-quick { display: none; }
  .toolbar-select { max-width: 130px; }
  .toolbar { gap: 4px; }
  .toolbar-left { gap: 4px; }
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  /* iOS 聚焦 <16px 输入框会自动放大页面,移动端提到 16px 避免 */
  .textarea { font-size: 16px; }
}

/* slash 命令菜单 */
.slash-menu {
  position: absolute;
  bottom: 100%;
  left: 20px;
  right: 20px;
  max-height: 360px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
  box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.12);
  z-index: 100;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin-bottom: 6px;
}
.slash-menu-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  border-bottom: 1px solid var(--border-soft);
}
.slash-menu-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: 4px;
  font-size: 13px;
  font-weight: 700;
}
.slash-menu-count {
  margin-left: auto;
  font-weight: 400;
  color: var(--text-3);
}
.slash-menu-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}
.slash-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background .1s;
}
.slash-menu-item:hover,
.slash-menu-item.active {
  background: var(--accent-soft);
}
.slash-menu-icon {
  flex-shrink: 0;
  opacity: .5;
}
.slash-menu-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  flex-shrink: 0;
}
.slash-menu-desc {
  font-size: 12px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.slash-menu-footer {
  display: flex;
  gap: 12px;
  padding: 6px 12px;
  font-size: 11px;
  color: var(--text-3);
  border-top: 1px solid var(--border-soft);
  background: var(--surface-hover);
}
</style>
