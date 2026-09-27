<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useThemeStore } from '@/stores/theme'
import { useMessage, useDialog } from 'naive-ui'
import CodeEditor from './CodeEditor.vue'
import TextPromptModal from './TextPromptModal.vue'
import type { SkillFileVo } from '@/types'

/**
 * 技能包文件编辑器:SKILL.md + 附加文件(scripts/data/reference)统一在一个
 * 文件树 + 多标签页里维护,支持新建/重命名/删除/上传(二进制 base64)/下载。
 * <p>受控组件:父级持有 modelValue {content,files},编辑即时 emit,保存由父级负责。
 */

interface Props {
  /** SKILL.md 正文 */
  content: string
  /** 附加文件(SKILL.md 之外的脚本/数据/参考;二进制以 encoding=base64 携带) */
  files: SkillFileVo[]
  /** 技能名(新建时用于生成 frontmatter 与默认脚本路径) */
  name: string
  description?: string
  /** 该技能是否只读(市场安装款) */
  readonly?: boolean
}

const { t: tr } = useI18n()
const props = withDefaults(defineProps<Props>(), {
  content: '',
  files: () => [],
  name: '',
  description: '',
  readonly: false,
})

const emit = defineEmits<{
  (e: 'update:content', v: string): void
  (e: 'update:files', v: SkillFileVo[]): void
}>()

const msg = useMessage()
const dialog = useDialog()
const theme = useThemeStore()
const isDark = computed(() => theme.mode === 'dark')

// ---------- 文件树 ----------
interface SkillNode {
  name: string
  /** 相对技能目录(目录以 / 结尾) */
  path: string
  dir: boolean
  kind: 'skillmd' | 'file'
  encoding?: string
  size?: number
  children?: SkillNode[]
}

function buildTree(files: SkillFileVo[]): SkillNode[] {
  const root: SkillNode[] = []
  const byPath = new Map<string, SkillNode>()
  for (const f of files) {
    if (!f || !f.path) continue
    const parts = f.path.replace(/\\/g, '/').split('/').filter(Boolean)
    if (parts.length === 0) continue
    for (let i = 0; i < parts.length; i++) {
      const seg = parts[i]
      const prefix = parts.slice(0, i + 1).join('/')
      const isFile = i === parts.length - 1
      const key = isFile ? prefix : prefix + '/'
      if (byPath.has(key)) continue
      const node: SkillNode = {
        name: seg,
        path: key,
        dir: !isFile,
        kind: 'file',
        children: isFile ? undefined : [],
      }
      byPath.set(key, node)
      if (i === 0) {
        root.push(node)
      } else {
        const parentKey = parts.slice(0, i).join('/') + '/'
        byPath.get(parentKey)?.children?.push(node)
      }
    }
    // 叶子保留文件元数据
    const leaf = byPath.get(parts.join('/'))
    if (leaf) {
      leaf.encoding = f.encoding
      leaf.size = estimateSize(f)
    }
  }
  const sort = (ns: SkillNode[]) => {
    ns.sort((a, b) => (a.dir !== b.dir ? (a.dir ? -1 : 1) : a.name.localeCompare(b.name, 'zh')))
    ns.forEach((n) => n.children && sort(n.children))
  }
  sort(root)
  return root
}

/** 二进制经 base64 存储,size 按解码后字节粗略估算 */
function estimateSize(f: SkillFileVo): number {
  if (f.encoding === 'base64' && f.content) {
    const pad = f.content.endsWith('==') ? 2 : f.content.endsWith('=') ? 1 : 0
    return Math.floor((f.content.length * 3) / 4) - pad
  }
  return f.content ? new TextEncoder().encode(f.content).length : 0
}

function collectFiles(root: SkillNode[]): SkillFileVo[] {
  const out: SkillFileVo[] = []
  const walk = (ns: SkillNode[], prefix = '') => {
    for (const n of ns) {
      if (n.dir) {
        walk(n.children || [], prefix + n.name + '/')
      } else {
        const vo = fileVoMap.value.get(n.path)
        if (vo) {
          if (n.path !== 'SKILL.md') out.push({ path: n.path, content: vo, encoding: n.encoding })
        }
      }
    }
  }
  walk(root)
  return out
}

// ---------- 编辑器态 ----------
interface Tab {
  path: string
  dirty: boolean
  isMarkdown?: boolean
  binary?: boolean
  size?: number
}

const fileTree = computed(() => buildTree(props.files))
const tabs = ref<Tab[]>([])
const activeTab = ref<string>('')
/** 当前打开文件的编辑缓冲(path -> latest content) */
const fileVoMap = ref(new Map<string, string>())

const running = ref<SkillNode | null>(null)

function isMarkdown(path: string): boolean {
  return /\.(md|markdown)$/i.test(path)
}

function isBinary(path: string, files: SkillFileVo[]): boolean {
  const f = files.find((x) => x.path === path)
  return !!f && f.encoding === 'base64'
}

function defaultTab(): Tab {
  return { path: 'SKILL.md', dirty: false, isMarkdown: true, binary: false }
}

function ensureSkillMdTab() {
  if (!tabs.value.some((t) => t.path === 'SKILL.md')) {
    tabs.value = [defaultTab(), ...tabs.value]
    fileVoMap.value.set('SKILL.md', props.content || '')
  } else {
    fileVoMap.value.set('SKILL.md', props.content || '')
  }
  if (!activeTab.value) activeTab.value = 'SKILL.md'
}

watch(() => props.content, (v) => {
  fileVoMap.value.set('SKILL.md', v || '')
  const t = tabs.value.find((x) => x.path === 'SKILL.md')
  if (t) t.dirty = false
}, { immediate: true })

watch(() => props.files, () => {
  // 外部刷新(保存后/切换技能)时,仅保留仍存在的打开文件
  const valid = new Set(props.files.map((f) => f.path).concat(['SKILL.md']))
  tabs.value = tabs.value.filter((t) => valid.has(t.path))
  if (activeTab.value && !tabs.value.some((t) => t.path === activeTab.value)) {
    activeTab.value = tabs.value[0]?.path ?? ''
  }
}, { immediate: true })

// ---------- 操作 ----------
async function openFile(node: SkillNode) {
  if (node.dir) return
  if (node.path === 'SKILL.md') {
    if (!tabs.value.some((t) => t.path === 'SKILL.md')) {
      tabs.value = [defaultTab(), ...tabs.value]
      fileVoMap.value.set('SKILL.md', props.content || '')
    }
    activeTab.value = 'SKILL.md'
    return
  }
  const vo = props.files.find((f) => f.path === node.path)
  const exist = tabs.value.find((t) => t.path === node.path)
  if (!exist) {
    tabs.value.push({
      path: node.path,
      dirty: false,
      isMarkdown: isMarkdown(node.path),
      binary: !!vo && vo.encoding === 'base64',
      size: node.size,
    })
    if (vo) fileVoMap.value.set(node.path, vo.content || '')
    else fileVoMap.value.set(node.path, '')
  }
  activeTab.value = node.path
}

function closeTab(path: string) {
  const t = tabs.value.find((x) => x.path === path)
  if (t && t.dirty) {
    dialog.warning({
      title: tr('skillEditor.unsavedTitle'),
      content: tr('skillEditor.unsavedContent', { path }),
      positiveText: tr('skillEditor.close'),
      negativeText: tr('skillEditor.cancel'),
      onPositiveClick: () => {
        tabs.value = tabs.value.filter((x) => x.path !== path)
        if (activeTab.value === path) activeTab.value = tabs.value[0]?.path ?? ''
      },
    })
    return
  }
  tabs.value = tabs.value.filter((x) => x.path !== path)
  if (activeTab.value === path) activeTab.value = tabs.value[0]?.path ?? ''
}

function updateBuffer(path: string, v: string) {
  fileVoMap.value.set(path, v)
  const t = tabs.value.find((x) => x.path === path)
  if (t) t.dirty = true
  if (path === 'SKILL.md') {
    emit('update:content', v)
  } else {
    const files = props.files.map((f) =>
      f.path === path ? { ...f, content: v, encoding: f.encoding } : f,
    )
    emit('update:files', files)
  }
}

function onEditorBlur() {
  // 失焦视为隐式保存:清理脏标记,保持前端状态一致
  if (activeTab.value) {
    const t = tabs.value.find((x) => x.path === activeTab.value)
    if (t) t.dirty = false
  }
}

// ---------- UI 输入弹窗(替代 window.prompt) ----------
const promptShow = ref(false)
const promptCfg = ref({ title: '', placeholder: '', initial: '', allowEmpty: false, tip: '' })
let promptResolver: ((v: string | null) => void) | null = null

function askPrompt(cfg: { title: string; placeholder?: string; initial?: string; allowEmpty?: boolean; tip?: string }): Promise<string | null> {
  promptCfg.value = {
    title: cfg.title,
        placeholder: cfg.placeholder || tr('skillEditor.defaultPlaceholder'),
    initial: cfg.initial || '',
    allowEmpty: !!cfg.allowEmpty,
    tip: cfg.tip || '',
  }
  promptShow.value = true
  return new Promise((res) => { promptResolver = res })
}
function onPromptConfirm(v: string) {
  promptShow.value = false
  promptResolver?.(v)
  promptResolver = null
}
function onPromptCancel() {
  promptShow.value = false
  promptResolver?.(null)
  promptResolver = null
}

function cleanRelPath(p: string): string {
  return p.replace(/\\/g, '/').replace(/^\/+/, '').trim()
}

// 新建文件
async function createFile(defaultPath: string) {
  const p = await askPrompt({
    title: tr('skillEditor.newFileTitle'),
    placeholder: tr('skillEditor.filePlaceholder'),
    initial: defaultPath,
    tip: tr('skillEditor.fileTip'),
  })
  if (!p) return
  const clean = cleanRelPath(p)
  if (!clean) return
    if (clean.includes('..')) { msg.error(tr('skillEditor.dotdotForbidden')); return }
  if (props.files.some((f) => f.path === clean) || clean === 'SKILL.md') {
    msg.warning(tr('skillEditor.existsWarn', { path: clean }))
    return
  }
  const vo: SkillFileVo = { path: clean, content: '', encoding: '' }
  emit('update:files', [...props.files, vo])
  nextTick(() => {
    const root = buildTree(props.files)
    const found = findNode(root, clean)
    if (found) openFile(found)
    else msg.error(tr('skillEditor.createFailedRetry'))
  })
}

// 上传文件：文本直接读,二进制转 base64
const fileInput = ref<HTMLInputElement | null>(null)
let uploadDir = ''

async function triggerUpload(dirPath = '') {
  if (dirPath) {
    uploadDir = dirPath
  } else {
    // 顶层上传时弹 UI 输入框让用户填目标子目录,避免"文件上传了但没有归属目录"
    const target = await askPrompt({
    title: tr('skillEditor.uploadToDirTitle'),
    placeholder: tr('skillEditor.uploadToDirPlaceholder'),
      allowEmpty: true,
    tip: tr('skillEditor.uploadToDirTip'),
    })
    if (target === null) return // 用户取消
    uploadDir = target.replace(/^\/+|\/+$/g, '').trim()
  }
  fileInput.value?.click()
}

async function onUploadPick(ev: Event) {
  const el = ev.target as HTMLInputElement
  const file = el.files?.[0]
  el.value = ''
  if (!file) return
  try {
    const buf = new Uint8Array(await file.arrayBuffer())
    const isTextLike = !buf.some((b) => b === 0)
    let vo: SkillFileVo
    if (isTextLike) {
      const text = new TextDecoder('utf-8', { fatal: false }).decode(buf)
      vo = { path: joinPath(uploadDir, file.name), content: text, encoding: '' }
    } else {
      let bin = ''
      const chunk = 0x8000
      for (let i = 0; i < buf.length; i += chunk) {
        bin += String.fromCharCode(...buf.subarray(i, i + chunk))
      }
      vo = {
        path: joinPath(uploadDir, file.name),
        content: btoa(bin),
        encoding: 'base64',
      }
    }
    if (props.files.some((f) => f.path === vo.path)) {
    msg.warning(tr('skillEditor.existsWarn', { path: vo.path }))
      return
    }
    emit('update:files', [...props.files, vo])
    nextTick(() => {
      const root = buildTree(props.files)
      const found = findNode(root, vo.path)
      if (found) openFile(found)
    })
    msg.success(tr('skillEditor.uploadedTo', { path: vo.path }))
  } catch (e: unknown) {
    msg.error(e instanceof Error ? e.message : tr('skillEditor.uploadFailed'))
  }
}

function joinPath(dir: string, name: string): string {
  return dir ? dir.replace(/\/?$/, '/') + name : name
}

function findNode(root: SkillNode[], target: string): SkillNode | null {
  for (const n of root) {
    if (n.path === target) return n
    if (n.dir) {
      const r = findNode(n.children || [], target)
      if (r) return r
    }
  }
  return null
}

// 下载
function downloadFile(node: SkillNode) {
  const vo = props.files.find((f) => f.path === node.path)
  if (!vo) return
  let blob: Blob
  if (vo.encoding === 'base64') {
    const bin = atob(vo.content)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    blob = new Blob([bytes])
  } else {
    blob = new Blob([vo.content || ''], { type: 'text/plain;charset=utf-8' })
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = node.name
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

// 重命名：就地改 path(同时迁移打开的开缓冲)
async function renameFile(node: SkillNode, oldPath: string) {
  const next = await askPrompt({
    title: tr('skillEditor.renameMoveTitle'),
    placeholder: tr('skillEditor.renamePlaceholder'),
    initial: oldPath,
    tip: tr('skillEditor.renameTip'),
  })
  if (!next) return
  const clean = cleanRelPath(next)
  if (!clean) return
  if (clean === oldPath) return
    if (clean.includes('..')) { msg.error(tr('skillEditor.dotdotForbidden')); return }
  if (props.files.some((f) => f.path === clean) || clean === 'SKILL.md') {
    msg.warning(tr('skillEditor.existsWarn', { path: clean }))
    return
  }
  const vo = props.files.find((f) => f.path === oldPath)
  if (!vo) return
  const migrated = props.files.map((f) =>
    f.path === oldPath ? { ...f, path: clean } : f,
  )
  emit('update:files', migrated)
  // 迁移标签页
  const t = tabs.value.find((x) => x.path === oldPath)
  if (t) {
    const v = fileVoMap.value.get(oldPath) || ''
    fileVoMap.value.delete(oldPath)
    fileVoMap.value.set(clean, v)
    t.path = clean
    if (activeTab.value === oldPath) activeTab.value = clean
  }
    msg.success(tr('skillEditor.renamed', { path: clean }))
}

// 删除(弹框确认)
function removeNode(node: SkillNode) {
  const label = node.path === 'SKILL.md' ? 'SKILL.md' : node.path
  dialog.warning({
    title: node.dir ? tr('skillEditor.delDirTitle') : tr('skillEditor.delFileTitle'),
    content: node.dir ? tr('skillEditor.delDirContent', { name: label }) : tr('skillEditor.delFileContent', { name: label }),
    positiveText: tr('skillEditor.delete'),
    negativeText: tr('skillEditor.cancel'),
    onPositiveClick: () => {
      if (node.dir) {
        const prefix = node.path
        const remaining = props.files.filter((f) => !f.path.startsWith(prefix))
        emit('update:files', remaining)
        tabs.value = tabs.value.filter((t) => !t.path.startsWith(prefix))
        if (activeTab.value && !tabs.value.some((t) => t.path === activeTab.value)) {
          activeTab.value = tabs.value[0]?.path ?? ''
        }
      } else if (node.path === 'SKILL.md') {
        emit('update:content', '')
      } else {
        emit('update:files', props.files.filter((f) => f.path !== node.path))
        tabs.value = tabs.value.filter((t) => t.path !== node.path)
        if (activeTab.value === node.path) activeTab.value = tabs.value[0]?.path ?? ''
      }
    msg.success(tr('skillEditor.deleted'))
    },
  })
}

// 二进制文件编辑器占位
function isActiveBinary(): boolean {
  const t = tabs.value.find((x) => x.path === activeTab.value)
  return !!t && !!t.binary
}

function activeIsReadonly(): boolean {
  return props.readonly
}

function activeIsSkillmd(): boolean {
  return activeTab.value === 'SKILL.md'
}

function activeFilename(): string {
  return activeTab.value.split('/').pop() || ''
}

function activeLang(): string {
  const t = tabs.value.find((x) => x.path === activeTab.value)
  return t?.isMarkdown ? 'markdown' : ''
}

function activeBuffer(): string {
  return fileVoMap.value.get(activeTab.value) || ''
}

// SKILL.md 初始化:新建时若无 frontmatter 生成带 name/description 的头
watch(() => props.name, () => {
  ensureSkillMdTab()
}, { immediate: true })

function seedFrontmatterIfEmpty() {
  const cur = fileVoMap.value.get('SKILL.md') || ''
  if (!props.readonly && props.name && !cur.trim().startsWith('---')) {
  const desc = props.description?.trim() || tr('skillEditor.noDescFallback')
    const seed = `---\nname: ${props.name}\ndescription: ${desc} \n---\n\n# ${props.name}\n\n> 技能功能说明(何时使用、如何使用、注意事项)。\n`
    fileVoMap.value.set('SKILL.md', seed)
    emit('update:content', seed)
  }
}

// 组件挂载后打开 SKILL.md 标签
watch(() => true, () => {
  nextTick(() => {
    ensureSkillMdTab()
    seedFrontmatterIfEmpty()
    activeTab.value = 'SKILL.md'
  })
}, { immediate: true })

const expandedDirs = ref<Set<string>>(new Set())

function toggleDir(node: SkillNode) {
  const next = new Set(expandedDirs.value)
  if (next.has(node.path)) next.delete(node.path)
  else next.add(node.path)
  expandedDirs.value = next
}

function isDirExpanded(node: SkillNode): boolean {
  return expandedDirs.value.has(node.path)
}

function fileIcon(name: string): string {
  const ext = (name.split('.').pop() || '').toLowerCase()
  const map: Record<string, string> = {
    md: '📄', py: '🐍', js: '🟨', ts: '🔷', json: '🧾', yml: '⚙️', yaml: '⚙️',
    css: '🎨', html: '🌐', sh: '🖥️', txt: '📝', csv: '📊', xml: '📰',
    png: '🖼️', jpg: '🖼️', jpeg: '🖼️', gif: '🖼️', svg: '🖼️', ico: '🖼️', webp: '🖼️',
    wav: '🎵', mp3: '🎵', diff: '🔀', sql: '🗄️',
  }
  return map[ext] || '📎'
}
</script>

<template>
  <div class="skill-editor">
    <!-- 左:文件树 -->
    <div class="se-tree-pane">
      <div class="se-tree-head">
        <span class="se-tree-title">{{ name }}</span>
        <span v-if="readonly" class="se-ro-tag">{{ tr('skillEditor.roTag') }}</span>
      </div>
      <div class="se-tree-actions" v-if="!readonly">
        <button class="se-act" :title="tr('skillEditor.newFile')" @click="createFile('scripts/tool.py')">{{ tr('skillEditor.newFile') }}</button>
        <button class="se-act" :title="tr('skillEditor.uploadFile')" @click="triggerUpload()">{{ tr('skillEditor.uploadFile') }}</button>
      </div>
      <div class="se-tree-body" v-if="fileTree.length || content">
        <div class="se-node se-node-root">
          <span class="se-node-img">📄</span>
          <span class="se-node-name" :class="{ active: activeTab === 'SKILL.md' }" @click="openFile({ path: 'SKILL.md', name: 'SKILL.md', dir: false, kind: 'skillmd' })">SKILL.md</span>
          <span v-if="!readonly" class="se-node-x" @click.stop="removeNode({ path: 'SKILL.md', name: 'SKILL.md', dir: false, kind: 'skillmd' })">🗑</span>
        </div>
        <template v-for="node in fileTree" :key="node.path">
          <div v-if="node.dir" class="se-node">
            <span class="se-caret" @click="toggleDir(node)">{{ isDirExpanded(node) ? '▾' : '▸' }}</span>
            <span class="se-node-img">🗂️</span>
            <span class="se-node-name" @click="toggleDir(node)">{{ node.name }}/</span>
            <span v-if="!readonly" class="se-node-x" @click.stop="removeNode(node)">🗑</span>
          </div>
          <template v-if="node.dir && isDirExpanded(node)">
            <div v-for="ch in node.children || []" :key="ch.path" class="se-node se-node-ind">
              <span class="se-caret">{{ ch.dir ? (isDirExpanded(ch) ? '▾' : '▸') : '' }}</span>
              <span class="se-node-img">{{ ch.dir ? '🗂️' : fileIcon(ch.name) }}</span>
              <span class="se-node-name" :class="{ active: activeTab === ch.path }" @click="ch.dir ? toggleDir(ch) : openFile(ch)">{{ ch.name }}</span>
              <span v-if="ch.encoding === 'base64'" class="se-binary-tag">bin</span>
              <span v-if="!readonly" class="se-act-x" @click.stop="renameFile(ch, ch.path)">✎</span>
              <span v-if="!readonly" class="se-node-x" @click.stop="removeNode(ch)">🗑</span>
            </div>
            <div v-if="!readonly" class="se-node se-node-ind se-add-dir">
            <button class="se-mini" @click="triggerUpload(node.path)">{{ tr('skillEditor.uploadHere') }}</button>
            </div>
          </template>
        </template>
        <div v-if="!readonly && !fileTree.length" class="se-tree-empty">
        <p style="margin:0 0 8px">{{ tr('skillEditor.emptyFolder') }}</p>
        <button class="se-upload-big" @click="triggerUpload()">{{ tr('skillEditor.uploadFile') }}</button>
        <p style="margin:8px 0 0;font-size:11px;opacity:.6">{{ tr('market.zipTip') }}</p>
        </div>
      </div>
      <div v-else class="se-tree-body se-tree-empty">{{ tr('skillEditor.emptyFolder') }}</div>
    </div>

    <!-- 右:标签页 + 编辑器 -->
    <div class="se-edit-pane">
      <div class="se-tabs">
        <div v-for="t in tabs" :key="t.path" class="se-tab" :class="{ active: activeTab === t.path }" @click="activeTab = t.path">
        <span class="se-tab-dot" v-if="t.dirty && !readonly" :title="tr('skillEditor.dirty')">●</span>
          <span class="se-tab-ico">{{ t.isMarkdown ? '📄' : '📎' }}</span>
          <span class="se-tab-name">{{ t.path }}</span>
          <span v-if="!readonly" class="se-tab-x" @click.stop="closeTab(t.path)">×</span>
        </div>
      </div>
      <div class="se-edit-body">
        <!-- 二进制文件:只读信息 + 下载/替换 -->
        <div v-if="isActiveBinary() && activeTab !== 'SKILL.md'" class="se-binary-view">
          <div class="se-binary-ico">🖼️</div>
          <p class="se-binary-name">{{ activeFilename() }}</p>
      <p class="se-binary-hint">{{ tr('skillEditor.binaryHint') }}</p>
          <div class="se-binary-actions">
        <button class="se-mini" @click="downloadFile(findNode(fileTree, activeTab)!)">{{ tr('skillEditor.download') }}</button>
        <button v-if="!readonly" class="se-mini" @click="triggerUpload(activeTab.includes('/') ? activeTab.replace(/\/[^/]+$/, '') : '')">{{ tr('skillEditor.replace') }}</button>
          </div>
        </div>
        <!-- 文本编辑器(只读技能同样走 CodeEditor,read-only 渲染内容) -->
        <CodeEditor
          v-else
          :model-value="activeBuffer()"
          :filename="activeFilename()"
          :language="activeTab === 'SKILL.md' ? 'markdown' : activeLang()"
          :is-dark="isDark"
          :read-only="activeIsReadonly() || isActiveBinary()"
          @update:model-value="(v) => updateBuffer(activeTab, v)"
          @blur="onEditorBlur"
        />
      </div>
    </div>

    <input ref="fileInput" type="file" class="se-file-input" @change="onUploadPick" />

    <TextPromptModal
      :show="promptShow"
      :title="promptCfg.title"
      :placeholder="promptCfg.placeholder"
      :initial="promptCfg.initial"
      :allow-empty="promptCfg.allowEmpty"
      :tip="promptCfg.tip"
      @update:show="onPromptCancel"
      @confirm="onPromptConfirm"
      @cancel="onPromptCancel"
    />
  </div>
</template>

<style scoped>
.skill-editor {
  display: flex;
  height: min(62vh, 560px);
  border: 1px solid var(--border-2, rgba(128,128,128,.25));
  border-radius: 8px;
  overflow: hidden;
}
.se-tree-pane {
  width: 240px;
  flex-shrink: 0;
  border-right: 1px solid var(--border-2, rgba(128,128,128,.25));
  display: flex;
  flex-direction: column;
  background: var(--bg-section, rgba(128,128,128,.06));
}
.se-tree-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--border-2, rgba(128,128,128,.25));
}
.se-tree-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.se-ro-tag {
  font-size: 11px; color: #fff; background: #8a5a00; border-radius: 4px; padding: 0 6px;
}
.se-tree-actions {
  display: flex; gap: 6px; padding: 6px 10px;
  border-bottom: 1px solid var(--border-2, rgba(128,128,128,.25));
}
.se-act {
  border: none; cursor: pointer; font-size: 12px; color: var(--primary, #4a9eff);
  background: transparent; padding: 2px 4px; border-radius: 4px;
}
.se-act:hover { background: rgba(128,128,128,.15); }
.se-tree-body { flex: 1; overflow: auto; padding: 6px 4px; }
.se-node {
  display: flex; align-items: center; gap: 2px; padding: 2px 6px;
  font-size: 12.5px; border-radius: 4px; cursor: default; white-space: nowrap;
}
.se-node:hover { background: rgba(128,128,128,.12); }
.se-node-ind { padding-left: 22px; }
.se-caret { width: 12px; text-align: center; color: var(--text-3, #999); cursor: pointer; }
.se-node-img { font-size: 13px; }
.se-node-name { flex: 1; overflow: hidden; text-overflow: ellipsis; cursor: pointer; color: var(--text-2, #ccc); }
.se-node-name.active { color: var(--primary, #4a9eff); font-weight: 600; }
.se-node-x { opacity: .55; cursor: pointer; font-size: 11px; padding: 0 2px; }
.se-node-x:hover { opacity: 1; }
.se-act-x { opacity: .45; cursor: pointer; font-size: 11px; padding: 0 2px; }
.se-act-x:hover { opacity: 1; }
.se-binary-tag {
  font-size: 9px; color: #fff; background: #b26a00; border-radius: 3px; padding: 0 4px; opacity: .85;
}
.se-tree-empty { font-size: 12px; color: var(--text-3, #999); padding: 10px; line-height: 1.6; }
.se-upload-big {
  display: block; width: 100%; padding: 8px; border: 1px dashed var(--primary, #4a9eff); border-radius: 6px;
  background: transparent; color: var(--primary, #4a9eff); font-size: 13px; cursor: pointer; text-align: center;
}
.se-upload-big:hover { background: rgba(74,158,255,.08); }
.se-add-dir { padding: 2px 22px; }
.se-mini {
  border: 1px solid var(--border-2, rgba(128,128,128,.35)); border-radius: 4px;
  background: transparent; color: var(--text-2, #ccc); font-size: 12px; padding: 3px 10px; cursor: pointer;
}
.se-mini:hover { border-color: var(--primary, #4a9eff); color: var(--primary, #4a9eff); }
.se-edit-pane { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.se-tabs {
  display: flex; gap: 2px; padding: 4px 6px 0;
  border-bottom: 1px solid var(--border-2, rgba(128,128,128,.25));
  overflow-x: auto; flex-shrink: 0; background: var(--bg-section, rgba(128,128,128,.04));
}
.se-tab {
  display: flex; align-items: center; gap: 4px; padding: 4px 8px; font-size: 12px;
  border: 1px solid transparent; border-bottom: none; border-radius: 6px 6px 0 0;
  cursor: pointer; color: var(--text-3, #999); white-space: nowrap; max-width: 220px;
}
.se-tab.active {
  background: var(--chat-bg, #1e1e24); border-color: var(--border-2, rgba(128,128,128,.25));
  color: var(--text-1, #eee);
}
.se-tab-name { overflow: hidden; text-overflow: ellipsis; }
.se-tab-dot { color: #ffa940; font-size: 9px; }
.se-tab-x { opacity: .6; cursor: pointer; font-size: 14px; line-height: 1; }
.se-tab-x:hover { opacity: 1; }
.se-edit-body { flex: 1; overflow: hidden; }
.se-binary-view {
  height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
  color: var(--text-3, #999);
}
.se-binary-ico { font-size: 42px; }
.se-binary-name { font-size: 14px; font-weight: 600; color: var(--text-1, #eee); }
.se-binary-hint { font-size: 12px; }
.se-binary-actions { display: flex; gap: 10px; }
.se-file-input { display: none; }
</style>
