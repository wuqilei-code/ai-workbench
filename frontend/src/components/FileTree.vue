<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { fileApi } from '@/api/files'
import { useMessage, useDialog } from 'naive-ui'
import { useI18n } from 'vue-i18n'
import { useChatStore } from '@/stores/chat'
import TextPromptModal from './TextPromptModal.vue'
import type { FileNode } from '@/types'

/**
 * 工作区文件树浏览器。
 * - root:作用域根(相对工作区),默认 '' 即整个工作区;技能标签传 '.opencode/skills' 把浏览限定在技能目录。
 * - skillsMode:技能模式,显示「新建技能」按钮(建目录 + 生成合规 SKILL.md 并进入),并在空态/提示上引导。
 * currentPath 始终相对 root 记录;与后端交互时用 abs() 转成工作区相对路径。
 */
const props = withDefaults(defineProps<{
  root?: string
  skillsMode?: boolean
  /** 本会话内被 AI 改过的文件路径集合(工作区相对路径),用于高亮标记 */
  changedPaths?: string[]
  /** 自动刷新间隔(ms);>0 时轮询当前目录,文件变化后工作区自动更新。默认 5s */
  pollMs?: number
  /** 顶层目录名黑名单(如内置技能,不显示在用户技能区);仅过滤当前目录的直接子节点 */
  excludeNames?: string[]
}>(), {
  root: '',
  skillsMode: false,
  changedPaths: () => [],
  pollMs: 5000,
  excludeNames: () => [],
})

const emit = defineEmits<{ (e: 'askAI', path: string): void }>()

const msg = useMessage()
const { t } = useI18n()
const dialog = useDialog()
const chat = useChatStore()
const nodes = ref<FileNode[]>([])
const currentPath = ref('')
const selectedFile = ref<string | null>(null)
const loading = ref(false)
const previewVisible = ref(false)
const previewPath = ref<string | null>(null)
const previewSize = ref<number | undefined>(undefined)
const fileInput = ref<HTMLInputElement | null>(null)
const uploadDir = ref('')
const uploading = ref(false)
/** 自动刷新定时器句柄(pollMs>0 时启用) */
let pollTimer: ReturnType<typeof setInterval> | null = null

/** 技能名校验正则(与 opencode 一致:小写字母数字 + 单连字符分隔,1-64 字符) */
const NAME_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/

// ---------- 技能元信息(skillsMode):读每个技能目录 SKILL.md 的 frontmatter,展示图标 + 友好名称 ----------
/** 技能目录名 → { icon, title };title 为「Note Summarizer」式可读名,icon 取 frontmatter 或按名哈希兜底 */
const skillMetas = ref<Record<string, { icon: string; title: string }>>({})
const SKILL_FALLBACK_ICONS = ['🤖', '🧩', '📊', '📄', '✍️', '🗂️', '💻', '🔬', '🎨', '🚀', '🛠️', '📝']

/** kebab-case → Title Case(如 note-summarizer → Note Summarizer) */
function skillTitle(name: string): string {
  return name
    .split('-')
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ')
}

/** 从 SKILL.md frontmatter 提取 icon(可选项);无则按名字哈希在兜底池中取一个稳定 emoji */
function parseSkillFrontmatter(md: string): { icon?: string } {
  const m = md.match(/^---\s*\n([\s\S]*?)\n---/)
  if (m) {
    const icon = m[1].match(/^icon\s*:\s*(.+)$/m)?.[1]?.trim()
    if (icon) return { icon }
  }
  return {}
}

/** 按技能名哈希在兜底图标池中取稳定 emoji */
function fallbackIcon(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return SKILL_FALLBACK_ICONS[h % SKILL_FALLBACK_ICONS.length]
}

/** 刷新技能元信息:仅在最顶层(root 目录)读取每个技能目录的 SKILL.md,并缓存目录集合避免轮询重复请求 */
let skillMetaCacheKey = ''
async function loadSkillMetas() {
  const dirs = nodes.value.filter((n) => n.directory).map((n) => n.name).sort().join(',')
  if (dirs === skillMetaCacheKey) return
  skillMetaCacheKey = dirs
  const next: Record<string, { icon: string; title: string }> = {}
  const jobs = nodes.value
    .filter((n) => n.directory)
    .map(async (n) => {
      try {
        const md = await fileApi.content(abs(n.name) + '/SKILL.md')
        next[n.name] = { icon: parseSkillFrontmatter(md).icon || fallbackIcon(n.name), title: skillTitle(n.name) }
      } catch {
        next[n.name] = { icon: fallbackIcon(n.name), title: skillTitle(n.name) }
      }
    })
  await Promise.all(jobs)
  skillMetas.value = next
}

/** 技能模式下目录节点的展示信息(仅顶层技能目录有效) */
function skillDisplay(node: FileNode): { icon: string; title: string } | null {
  if (!props.skillsMode || !node.directory || currentPath.value) return null
  return skillMetas.value[node.name] ?? null
}

/** 相对 root 的路径 → 工作区相对路径(后端 API 入参) */
function abs(rel: string): string {
  const r = props.root
  if (!r) return rel || ''
  if (!rel) return r
  return `${r}/${rel}`
}
/** 工作区相对路径 → 相对 root 的路径(用于 currentPath) */
function relFromAbs(p: string): string {
  const r = props.root
  if (!r) return p
  if (p === r) return ''
  if (p.startsWith(r + '/')) return p.slice(r.length + 1)
  return p
}

const displayPath = computed(() => {
  // 技能模式隐藏 .opencode/skills 前缀,只显示技能内相对路径,更清爽
  if (props.skillsMode) return '/' + currentPath.value
  return '/' + [props.root, currentPath.value].filter(Boolean).join('/')
})

/** 清空文件选中态:导航到其他目录时调用,避免旧选中态残留 */
function clearEditor() {
  selectedFile.value = null
}

async function load(path = '', silent = false) {
  if (!silent) loading.value = true
  try {
    const all = await fileApi.list(abs(path))
    // 隐藏系统/内部文件:点开头的隐藏目录(.git/.opencode/.config/.local 等)、
    // tasks/ 是后台任务运行目录(由右侧抽屉专门管理)。jobs/ 保留(定时任务报告)。
    nodes.value = all.filter(
      (n) => !n.name.startsWith('.') && n.name !== 'tasks',
    )
    currentPath.value = path
    // 技能模式:读取技能目录的 SKILL.md 元信息(图标/友好名称),仅在根目录执行
    if (props.skillsMode && !path) await loadSkillMetas()
  } catch (e: any) { msg.error(e.message) }
  finally { if (!silent) loading.value = false }
}

async function open(node: FileNode) {
  if (node.directory) {
    clearEditor()
    await load(relFromAbs(node.path))
    // 从搜索结果进入目录后,退出搜索模式,恢复正常浏览
    if (searchResults.value !== null) {
      searchQuery.value = ''
      searchResults.value = null
    }
  } else {
    selectedFile.value = node.path
    previewPath.value = node.path
    previewSize.value = node.size
    previewVisible.value = true
  }
}

function up() {
  const parts = currentPath.value.split('/').filter(Boolean)
  parts.pop()
  clearEditor()
  load(parts.join('/'))
}

// ---------- 新建文件/目录:UI 输入弹窗 ----------
const fileNamePromptShow = ref(false)
const fileNamePrompt = ref<'file' | 'dir'>('file')

function openCreateFile() {
  fileNamePrompt.value = 'file'
  fileNamePromptShow.value = true
}
function openCreateDir() {
  fileNamePrompt.value = 'dir'
  fileNamePromptShow.value = true
}
function onFileNameConfirm(name: string) {
  fileNamePromptShow.value = false
  const trimmed = name.trim()
  if (!trimmed) return
  if (fileNamePrompt.value === 'dir') createDirWith(trimmed)
  else createFileWith(trimmed)
}

async function createFileWith(name: string) {
  const rel = currentPath.value ? `${currentPath.value}/${name}` : name
  try {
    await fileApi.write(abs(rel), '')
    msg.success(t('files.copied'))
    await load(currentPath.value)
  } catch (e: any) { msg.error(e.message) }
}

async function createDirWith(name: string) {
  const rel = currentPath.value ? `${currentPath.value}/${name}` : name
  try {
    await fileApi.mkdir(abs(rel))
    msg.success(t('files.dirCreated'))
    await load(currentPath.value)
  } catch (e: any) { msg.error(e.message) }
}

// ---------- skillsMode 新建技能:表单弹窗(名 + 描述 + 图标) ----------
const skFormShow = ref(false)
const skForm = ref({ name: '', description: '', icon: '' })

function openSkillForm() {
  skForm.value = { name: '', description: '', icon: '' }
  skFormShow.value = true
}

/** 新建技能 = 建目录 + 生成合规 SKILL.md,并进入该技能目录。
 *  之后的脚本/子目录扩展用通用的「新建文件/新建目录/上传」即可。 */
async function submitSkillForm() {
  const name = skForm.value.name.trim()
  const description = skForm.value.description.trim()
  const icon = skForm.value.icon.trim()
  if (!NAME_RE.test(name) || name.length > 64) {
    msg.error(t('files.nameInvalid'))
    return
  }
  // 重名检查(在 root 下)
  try {
    const existing = await fileApi.list(abs(''))
    if (existing.some((n) => n.name === name && n.directory)) {
      msg.error(t('files.copyDirFailed', { name }))
      return
    }
  } catch { /* 读取失败时忽略,交给后续 mkdir/write 报错 */ }
  if (!description) {
    msg.error(t('files.nameEmpty'))
    return
  }
  if (description.length > 1024) {
    msg.error(t('files.nameTooLong'))
    return
  }
  skFormShow.value = false
  try {
    await fileApi.mkdir(abs(name))
    const fmIcon = icon ? `\nicon: ${icon}` : ''
    const md =
      `---\nname: ${name}\ndescription: ${description.replace(/\n/g, ' ')}${fmIcon}\n---\n\n` +
      `## What I do\n- \n\n## When to use me\n- \n`
    await fileApi.write(`${abs(name)}/SKILL.md`, md)
    msg.success(t('files.skillCreated', { name }))
    skillMetaCacheKey = '' // 强制下次加载重读元信息
    clearEditor()
    await load(name)
  } catch (e: any) {
    msg.error(e?.message || t('files.createFailed'))
  }
}

async function downloadNode(node: FileNode) {
  if (node.directory) return
  try {
    await fileApi.download(node.path)
  } catch (e: any) { msg.error(e?.message || t('files.uploadFailed')) }
}

async function deleteNode(node: FileNode) {
  dialog.warning({
    title: t('files.deleteTitle'),
    content: t('files.deleteContent', { name: node.name, tip: node.directory ? t('files.deleteDirTip') : '' }),
    positiveText: t('files.deleteBtn'),
    negativeText: t('files.cancel'),
    onPositiveClick: async () => {
      try {
        await fileApi.delete(node.path)
        msg.success(t('files.deleted'))
        if (selectedFile.value === node.path) {
          selectedFile.value = null
        }
        await load(currentPath.value)
      } catch (e: any) { msg.error(e.message) }
    },
  })
}

// ---------- 右键菜单:下载 / 问 AI / 复制路径 ----------
const ctxVisible = ref(false)
const ctxX = ref(0)
const ctxY = ref(0)
const ctxNode = ref<FileNode | null>(null)

function onContextMenu(node: FileNode, e: MouseEvent) {
  e.preventDefault()
  ctxNode.value = node
  ctxX.value = e.clientX
  ctxY.value = e.clientY
  ctxVisible.value = true
}

function closeContextMenu() {
  ctxVisible.value = false
  ctxNode.value = null
}

async function copyPath(node: FileNode) {
  try {
    await navigator.clipboard.writeText(node.path)
    msg.success(t('files.pathCopied'))
  } catch (e: any) {
    msg.error(e?.message || t('files.copied'))
  }
}

/** 右键「问 AI」:把文件路径交给上层填入输入框,用户补充问题后发送 */
function askAIMenu(node: FileNode) {
  // 经 store 中转(绕开组件事件名匹配),ChatInput 监听后挂附件并填提示词
  chat.attachFile(node.path)
  emit('askAI', node.path)
  msg.success(t('files.addedForAnalysis', { path: node.path }))
}

function triggerUpload() {
  uploadDir.value = abs(currentPath.value)
  fileInput.value?.click()
}

function fmtTime(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 创建日期:今天显示 时:分,昨天显示「昨天」,今年显示 月-日,更早显示 年-月-日 */
function fmtDate(ts: number): string {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  const sameDay = d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth() && d.getDate() === now.getDate()
  if (sameDay) return `${p(d.getHours())}:${p(d.getMinutes())}`
  const yest = new Date(now)
  yest.setDate(now.getDate() - 1)
  const isYesterday = d.getFullYear() === yest.getFullYear() && d.getMonth() === yest.getMonth() && d.getDate() === yest.getDate()
  if (isYesterday) return t('files.yesterday')
  if (d.getFullYear() === now.getFullYear()) return `${p(d.getMonth() + 1)}-${p(d.getDate())}`
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

async function handleUpload(e: Event) {
  const input = e.target as HTMLInputElement
  const files = input.files
  if (!files || !files.length) return
  uploading.value = true
  try {
    let ok = 0
    let fail = 0
    for (const f of Array.from(files)) {
      try {
        await fileApi.upload(uploadDir.value, f)
        ok++
      } catch {
        fail++
      }
    }
    if (fail === 0) msg.success(t('files.uploaded', { ok }))
    else msg.warning(t('files.uploadPartial', { ok, fail }))
    await load(currentPath.value)
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function fmtSize(n: number): string {
  if (!n) return '-'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

// ---------- 搜索 ----------
const searchQuery = ref('')
const searching = ref(false)
/** null=正常目录浏览;数组=搜索结果(可能为空) */
const searchResults = ref<FileNode[] | null>(null)
let searchTimer: ReturnType<typeof setTimeout> | null = null

function onSearchInput() {
  const q = searchQuery.value.trim()
  if (searchTimer) { clearTimeout(searchTimer); searchTimer = null }
  if (!q) {
    searchResults.value = null
    searching.value = false
    return
  }
  searching.value = true
  searchTimer = setTimeout(async () => {
    try {
      searchResults.value = await fileApi.search(props.root, q)
    } catch (e: any) {
      msg.error(e?.message || t('fileTree2.searchFailed'))
      searchResults.value = []
    } finally {
      searching.value = false
    }
  }, 300)
}

/** 实际展示的节点:搜索模式用结果,否则用当前目录(目录优先,目录按名,文件按创建时间倒序);过滤 excludeNames 黑名单顶层项 */
const sortedNodes = computed<FileNode[]>(() => {
  const list = [...nodes.value]
  list.sort((a, b) => {
    if (a.directory !== b.directory) return a.directory ? -1 : 1
    if (a.directory) return a.name.localeCompare(b.name)
    return (b.created || 0) - (a.created || 0)
  })
  return list
})
const displayNodes = computed<FileNode[]>(() => {
  const list = searchResults.value ?? sortedNodes.value
  const exclude = props.excludeNames
  if (!exclude || exclude.length === 0) return list
  return list.filter((n) => !exclude.includes(n.name))
})
const isSearchMode = computed(() => searchResults.value !== null)

/** 工具栏「+」下拉:skillsMode 追加新建技能 */
const createOptions = computed(() => {
  const opts = [{ key: 'file', label: t('files.newFile') }, { key: 'dir', label: t('files.newDir') }]
  return props.skillsMode ? [{ key: 'skill', label: t('files.newSkill') }, { type: 'divider', key: 'd1' }, ...opts] : opts
})
function onCreateSelect(key: string | number) {
  if (key === 'skill') openSkillForm()
  else if (key === 'dir') openCreateDir()
  else openCreateFile()
}

/** 当前目录内可预览文件(非目录),供 ←/→ 翻页;当前选中文件在首或尾时禁用对应方向 */
const previewList = computed(() => displayNodes.value.filter((n) => !n.directory))
function previewIndex(): number {
  return previewList.value.findIndex((n) => n.path === previewPath.value)
}
function stepPreview(dir: -1 | 1) {
  const list = previewList.value
  if (list.length < 2) return
  const i = previewIndex()
  const j = i + dir
  if (j < 0 || j >= list.length) return
  const n = list[j]
  selectedFile.value = n.path
  previewPath.value = n.path
  previewSize.value = n.size
}

/** 按文件扩展名返回预览类型图标 */
function getFileIcon(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    png: '🖼', jpg: '🖼', jpeg: '🖼', gif: '🖼', webp: '🖼', svg: '🖼', bmp: '🖼',
    pdf: '📄', html: '🌐', htm: '🌐',
    md: '📝', markdown: '📝',
    docx: '📘', doc: '📘', xlsx: '📊', xls: '📊', csv: '📊',
    pptx: '📙', ppt: '📙',
    js: '💻', ts: '💻', vue: '💻', py: '💻', java: '💻', go: '💻', rs: '💻',
    json: '📋', yml: '📋', yaml: '📋', toml: '📋',
    mp3: '🎵', wav: '🎵', ogg: '🎵', mp4: '🎬', webm: '🎬', mov: '🎬',
    txt: '📃', log: '📃', env: '📃', conf: '📃',
  }
  return map[ext] || '📄'
}

/** 预览弹窗快捷键:Esc 关闭、←/→ 切换同目录上下一个文件 */
/** 预览弹窗快捷键:Esc 关闭、←/→ 切换同目录上下一个文件 */
function onPreviewKeydown(e: KeyboardEvent) {
  if (!previewVisible.value) return
  const t = e.target as HTMLElement
  const inInput = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)
  if (inInput && e.key === 'Escape') {
    ;(t as HTMLElement).blur()
    return
  }
  if (e.key === 'Escape') {
    e.preventDefault()
    previewVisible.value = false
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault()
    stepPreview(-1)
  } else if (e.key === 'ArrowRight') {
    e.preventDefault()
    stepPreview(1)
  }
}

/** 该节点是否属于本会话 AI 改动文件(高亮标记)。深度优先判完整路径是否命中。 */
function isAiChanged(node: FileNode): boolean {
  const paths = props.changedPaths
  if (!paths || paths.length === 0) return false
  return paths.includes(node.path) || paths.some((p) => p && p.startsWith(node.path + '/'))
}

onMounted(() => {
  load('')
  // 点击其它区域时关闭右键菜单(菜单右键自身已在节点上 preventDefault,不在此监听)
  window.addEventListener('click', closeContextMenu)
  // 预览弹窗快捷键(Esc / ←→ 翻页)
  window.addEventListener('keydown', onPreviewKeydown)
  // 自动刷新:文件变化后工作区自动更新。轮询静默刷新当前目录,保留用户导航位置与选中态;
  // 搜索/上传/预览进行中跳过,避免干扰交互。
  if (props.pollMs > 0) {
    pollTimer = setInterval(() => {
      if (searchQuery.value || searchResults.value !== null || uploading.value || previewVisible.value) return
      load(currentPath.value, true)
    }, props.pollMs)
  }
})
onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  window.removeEventListener('click', closeContextMenu)
  window.removeEventListener('keydown', onPreviewKeydown)
})
</script>

<template>
  <div class="file-tree">
    <div class="toolbar">
      <button class="tb-btn" :disabled="!currentPath" :title="t('files.upDir')" @click="up">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"/></svg>
      </button>
      <div class="crumb" :title="isSearchMode ? t('files.searchCrumb', { q: searchQuery }) : displayPath">
        <template v-if="isSearchMode">{{ t('files.searchResult', { q: searchQuery, n: displayNodes.length }) }}</template>
        <template v-else>{{ displayPath }}</template>
      </div>
      <n-dropdown trigger="click" :options="createOptions" @select="onCreateSelect">
        <button class="tb-btn" :title="t('files.newEntry')">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v14M5 12h14"/></svg>
        </button>
      </n-dropdown>
      <button class="tb-btn" :class="{ busy: uploading }" :title="t('files.uploadFiles')" @click="triggerUpload">
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
      </button>
      <input ref="fileInput" type="file" multiple class="hidden-input" @change="handleUpload" />
    </div>
    <n-input
      v-model:value="searchQuery"
      size="small"
      clearable
      :placeholder="t('files.searchPlaceholder')"
      @input="onSearchInput"
      @clear="onSearchInput"
    >
      <template #prefix>
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      </template>
    </n-input>
    <div v-if="skillsMode" class="hint">
      {{ t('files.skillHint') }}
    </div>
    <n-spin :show="loading || searching">
      <div v-for="n in displayNodes" :key="n.path" class="node" :class="{ 'ai-changed': isAiChanged(n), selected: selectedFile === n.path }">
        <div class="node-main" @click="open(n)" @contextmenu="onContextMenu(n, $event)">
          <span class="node-icon">
            <template v-if="skillDisplay(n)">{{ skillDisplay(n)!.icon }}</template>
            <svg v-else-if="n.directory" class="dir-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z"/></svg>
            <svg v-else class="file-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/></svg>
          </span>
<span v-if="isAiChanged(n)" class="ai-mark" :title="t('files.aiChanged')">?</span>
          <span class="node-name" :class="{ dir: n.directory }" :title="skillDisplay(n) ? `${skillDisplay(n)!.title} (${n.name})` : n.name">{{ skillDisplay(n) ? skillDisplay(n)!.title : n.name }}</span>
          <span v-if="skillDisplay(n)" class="skill-sub" :title="n.name">/{{ n.name }}</span>
          <span v-if="isSearchMode" class="node-sub" :title="relFromAbs(n.path)">{{ relFromAbs(n.path) }}</span>
          <span v-if="!n.directory && !isSearchMode" class="node-size">{{ fmtSize(n.size) }}</span>
        </div>
        <div class="node-date" v-if="!isSearchMode" :title="t('files.createdAt', { t: fmtTime(n.created) })">{{ fmtDate(n.created) }}</div>
        <div class="node-ops">
          <button
            v-if="!n.directory"
            class="op-btn"
            :title="t('fileTree2.download')"
            @click.stop="downloadNode(n)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
          <button
            class="op-btn op-btn-danger"
            :title="t('files.deleteBtn')"
            @click.stop="deleteNode(n)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
          </button>
        </div>
      </div>
      <n-empty
        v-if="!displayNodes.length && !loading && !searching"
        size="small"
        :description="isSearchMode ? t('files.noMatch') : (skillsMode ? t('files.noFiles') : t('files.dirEmpty'))"
      />
    </n-spin>

    <!-- 右键菜单:下载 / 问 AI / 复制路径 -->
    <Teleport to="body">
      <div
        v-if="ctxVisible && ctxNode"
        class="ctx-menu"
        :style="{ left: ctxX + 'px', top: ctxY + 'px' }"
        @click.stop
      >
        <div v-if="!ctxNode.directory" class="ctx-item" @click="downloadNode(ctxNode); closeContextMenu()">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          <span>{{ t('files.download') }}</span>
        </div>
        <div class="ctx-item" @click="askAIMenu(ctxNode); closeContextMenu()">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>
          <span>{{ t('files.askAi') }}</span>
        </div>
        <div class="ctx-item" @click="copyPath(ctxNode); closeContextMenu()">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          <span>{{ t('files.copyPath') }}</span>
        </div>
      </div>
    </Teleport>

    <n-modal
      v-model:show="previewVisible"
      preset="card"
      :style="{ width: '86vw', maxWidth: '1280px' }"
      :mask-closable="true"
      :bordered="false"
      class="fp-modal"
    >
      <template #header>
        <div class="fp-modal-title">
          <span class="fp-modal-icon">{{ previewPath ? getFileIcon(previewPath) : '📄' }}</span>
<span>{{ previewPath ? previewPath.split(/[\\/]/).pop() : t('files.preview') }}</span>
        </div>
      </template>
      <FilePreview
        v-if="previewPath"
        :path="previewPath"
        :size="previewSize"
        hide-file-info
      />
      <button
        v-if="previewList.length > 1 && previewIndex() > 0"
        class="fp-nav fp-nav--left"
        :title="t('files.prevFile')"
        @click="stepPreview(-1)"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <button
        v-if="previewList.length > 1 && previewIndex() < previewList.length - 1"
        class="fp-nav fp-nav--right"
        :title="t('files.nextFile')"
        @click="stepPreview(1)"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
      </button>
      <div class="fp-hint">{{ t('files.fpHint') }}</div>
    </n-modal>

    <!-- 新建文件/目录 输入弹窗 -->
    <TextPromptModal
      :show="fileNamePromptShow"
      :title="fileNamePrompt === 'dir' ? t('files.newDir') : t('files.newFile')"
:placeholder="fileNamePrompt === 'dir' ? t('files.dirPlaceholder') : t('files.filePlaceholder')"
      @update:show="fileNamePromptShow = false"
      @confirm="onFileNameConfirm"
      @cancel="fileNamePromptShow = false"
    />

    <!-- skillsMode 新建技能:表单弹窗(名 + 描述 + 图标) -->
    <n-modal
      v-model:show="skFormShow"
      preset="card"
      :title="t('files.newSkill')"
      style="width: 460px; max-width: 92vw"
      :mask-closable="true"
      :close-on-esc="true"
      :bordered="false"
    >
      <div class="skf-body">
        <div class="skf-row">
          <span class="skf-label">{{ t('files.skillName') }}</span>
          <n-input v-model:value="skForm.name" :placeholder="t('files.skillNamePlaceholder')" size="small" />
        </div>
        <div class="skf-row">
          <span class="skf-label">{{ t('files.skillDesc') }}</span>
          <n-input v-model:value="skForm.description" type="textarea" :rows="2" :placeholder="t('files.skillDescPlaceholder')" size="small" />
        </div>
        <div class="skf-row">
          <span class="skf-label">{{ t('files.skillIcon') }}</span>
          <n-input v-model:value="skForm.icon" :placeholder="t('files.skillIconPlaceholder')" size="small" />
        </div>
        <p class="skf-hint">{{ t('files.skillHint') }}</p>
      </div>
      <template #footer>
        <div class="skf-footer">
          <n-button size="small" quaternary @click="skFormShow = false">{{ t('files.cancel') }}</n-button>
          <n-button size="small" type="primary" :disabled="!skForm.name.trim() || !skForm.description.trim()" @click="submitSkillForm">{{ t('files.create') }}</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.file-tree { display: flex; flex-direction: column; height: 100%; }

/* 工具行:上级 + 路径 + 新建下拉 + 上传 */
.toolbar {
  display: flex; align-items: center; gap: 6px;
  margin-bottom: 8px; padding: 3px;
  background: var(--surface-hover); border: 1px solid var(--border-soft);
  border-radius: 10px;
}
.tb-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 26px; height: 26px; padding: 0; flex-shrink: 0;
  background: transparent; border: none; border-radius: 7px;
  color: var(--text-2); cursor: pointer;
  transition: background .12s, color .12s;
}
.tb-btn:hover:not(:disabled) { background: var(--accent-soft); color: var(--accent-text); }
.tb-btn:disabled { opacity: .35; cursor: default; }
.tb-btn.busy { animation: tb-spin 1s linear infinite; }
@keyframes tb-spin { to { transform: rotate(360deg); } }
.crumb {
  flex: 1; min-width: 0;
  font-size: 12px; color: var(--text-2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  padding: 0 2px;
}
.hidden-input { display: none; }
.hint {
  font-size: 12px; color: var(--text-3); line-height: 1.5; margin: 8px 0;
  background: var(--bg-elev); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 6px 8px;
}
.hint code { color: var(--accent-text); font-family: ui-monospace, monospace; }

/* 文件行 */
.node {
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 10px; border-radius: 9px; gap: 6px;
  position: relative;
  font-size: 12.5px; color: var(--text-2);
  transition: background-color .12s, color .12s;
}
.node:hover { background: var(--fill-0); color: var(--text); }
.node.selected .node-main { background: var(--accent-soft); border-radius: 6px; }
.node.selected .node-main .node-name { color: var(--accent-text); font-weight: 600; }
/* AI 改动:行左侧高亮竖条 */
.node.ai-changed::before {
  content: ''; position: absolute; left: -1px; top: 7px; bottom: 7px;
  width: 2px; border-radius: 2px; background: var(--warn);
}
.node-main {
  display: flex; align-items: center; gap: 9px;
  cursor: pointer; flex: 1; min-width: 0; padding: 2px 4px;
  border-radius: 7px;
}
.node-icon { flex-shrink: 0; font-size: 14px; display: inline-flex; align-items: center; justify-content: center; }
.node-icon .dir-icon { color: var(--accent-text); }
.node-icon .file-icon { color: var(--text-3); }
.node-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-mark { color: var(--warn); font-size: 11px; flex-shrink: 0; }
.node-name.dir { font-weight: 500; }
.node-size { color: var(--text-3); font-size: 11px; flex-shrink: 0; margin-left: auto; }
.node-date { color: var(--text-3); font-size: 11px; flex-shrink: 0; white-space: nowrap; min-width: 34px; text-align: right; }
.node-ops {
  display: flex; gap: 2px; flex-shrink: 0;
  opacity: 0; transition: opacity .12s;
}
.node:hover .node-ops, .node-ops:focus-within { opacity: 1; }
.op-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; padding: 0;
  background: transparent; border: none; border-radius: 6px;
  color: var(--text-3); cursor: pointer;
  transition: background .12s, color .12s;
}
.op-btn:hover { background: var(--accent-soft); color: var(--accent-text); }
.op-btn-danger:hover { background: rgba(228, 122, 134, 0.14); color: var(--err); }
.node-sub { color: var(--accent-text); font-size: 11px; font-family: ui-monospace, monospace; flex-shrink: 0; margin-left: auto; max-width: 50%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.skill-sub { color: var(--text-3); font-size: 11px; font-family: ui-monospace, monospace; flex-shrink: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 触屏:操作按钮常显(hover 不生效) */
@media (hover: none) {
  .node-ops { opacity: 1; }
}

/* 新建技能表单弹窗 */
.skf-body { display: flex; flex-direction: column; gap: 10px; }
.skf-row { display: flex; flex-direction: column; gap: 3px; }
.skf-label { font-size: 12px; color: var(--text-2); font-weight: 500; }
.skf-hint { margin: 0; font-size: 12px; color: var(--text-3); line-height: 1.5; }
.skf-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>

<style>
/* 右键菜单(Teleport 到 body,需全局样式) */
.ctx-menu {
  position: fixed; z-index: 3000; min-width: 150px;
  background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-sm); box-shadow: var(--shadow);
  padding: 4px; user-select: none;
}
.ctx-item {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px; font-size: 13px; color: var(--text-2);
  border-radius: 6px; cursor: pointer;
}
.ctx-item:hover { background: var(--accent-soft); color: var(--accent-text); }
.ctx-item svg { flex-shrink: 0; }

/* n-modal teleport 到 body,需用全局样式(非 scoped)控制内容区高度 */
.fp-modal .n-card {
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.45), 0 0 0 1px var(--border-soft, rgba(255,255,255,0.06));
}
.fp-modal .n-card__header {
  padding: 14px 20px;
  border-bottom: 1px solid var(--border-soft, rgba(255,255,255,0.06));
}
.fp-modal .n-card__title {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}
.fp-modal-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fp-modal-icon {
  font-size: 18px;
  width: 30px; height: 30px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 8px;
  background: var(--accent-soft, rgba(255,255,255,0.1));
  flex-shrink: 0;
}
.fp-modal .n-card__content {
  height: calc(86vh - 56px);
  overflow: hidden;
  padding: 0;
  position: relative;
}
/* 毛玻璃遮罩 */
.fp-modal .n-modal-mask {
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
}
/* 全屏模式 */

.fp-hint {
  position: absolute;
  bottom: 10px;
  right: 14px;
  font-size: 11px;
  color: var(--text-3, #888);
  background: var(--surface, rgba(32,36,43,0.85));
  backdrop-filter: blur(6px);
  padding: 4px 10px;
  border-radius: 6px;
  border: 1px solid var(--border-soft, rgba(255,255,255,0.06));
  pointer-events: none;
  z-index: 10;
}
.fp-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 10;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 50%;
  background: var(--surface, rgba(32,36,43,0.8));
  backdrop-filter: blur(6px);
  color: var(--text-2, #aeb3bb);
  cursor: pointer;
  transition: background 0.15s, color 0.15s, transform 0.15s;
  box-shadow: 0 2px 8px rgba(0,0,0,0.3);
}
.fp-nav:hover {
  background: var(--accent, #e8eaed);
  color: var(--accent-contrast, #17181c);
  transform: translateY(-50%) scale(1.1);
}
.fp-nav--left { left: 10px; }
.fp-nav--right { right: 10px; }
</style>
