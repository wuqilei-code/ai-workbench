<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { fileApi } from '@/api/files'
import { useMessage, useDialog } from 'naive-ui'
import type { FileNode, FileHistoryEntry, FileDiffResult } from '@/types'

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
}>(), {
  root: '',
  skillsMode: false,
  changedPaths: () => [],
})

const msg = useMessage()
const dialog = useDialog()
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

// ---------- 版本历史 / diff / 回滚 ----------
const histVisible = ref(false)
const histPath = ref('')
const histLoading = ref(false)
const histEntries = ref<FileHistoryEntry[]>([])
/** 选中的两个 sha:index 0 为基线(from),index 1 为对比(to);只选一个时 to=null 对比工作区当前 */
const diffFrom = ref<string | null>(null)
const diffTo = ref<string | null>(null)
const diffResult = ref<FileDiffResult | null>(null)
const diffLoading = ref(false)
const restoring = ref(false)

/** 技能名校验正则(与 opencode 一致:小写字母数字 + 单连字符分隔,1-64 字符) */
const NAME_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/

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

async function load(path = '') {
  loading.value = true
  try {
    const all = await fileApi.list(abs(path))
    // 隐藏 git/opencode 系统内部文件:技能有专用「技能」标签访问,
    // .git/.gitignore 是 git plumbing,用户不应在文件树手动触碰,避免与用户文件交叉。
    nodes.value = all.filter((n) => n.name !== '.git' && n.name !== '.opencode' && n.name !== '.gitignore')
    currentPath.value = path
  } catch (e: any) { msg.error(e.message) }
  finally { loading.value = false }
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

async function createFile() {
  const name = prompt('文件名')
  if (!name) return
  const rel = currentPath.value ? `${currentPath.value}/${name}` : name
  try {
    await fileApi.write(abs(rel), '')
    msg.success('已创建')
    await load(currentPath.value)
  } catch (e: any) { msg.error(e.message) }
}

async function createDir() {
  const name = prompt('目录名')
  if (!name) return
  const rel = currentPath.value ? `${currentPath.value}/${name}` : name
  try {
    await fileApi.mkdir(abs(rel))
    msg.success('已创建目录')
    await load(currentPath.value)
  } catch (e: any) { msg.error(e.message) }
}

/** skillsMode 专用:新建技能 = 建目录 + 生成合规 SKILL.md,并进入该技能目录。
 *  之后的脚本/子目录扩展用通用的「新建文件/新建目录/上传」即可。 */
async function createSkill() {
  const name = prompt('技能名(小写字母/数字,单连字符分隔,如 note-summarizer)')
  if (!name) return
  if (!NAME_RE.test(name) || name.length > 64) {
    msg.error('技能名不合法:仅小写字母/数字,单连字符分隔,1-64 字符')
    return
  }
  // 重名检查(在 root 下)
  try {
    const existing = await fileApi.list(abs(''))
    if (existing.some((n) => n.name === name && n.directory)) {
      msg.error(`技能「${name}」已存在`)
      return
    }
  } catch { /* 读取失败时忽略,交给后续 mkdir/write 报错 */ }
  const description = prompt('技能描述(一句话说明做什么、何时使用)') || ''
  if (!description.trim()) {
    msg.error('描述不能为空')
    return
  }
  try {
    await fileApi.mkdir(abs(name))
    const md =
      `---\nname: ${name}\ndescription: ${description.trim().replace(/\n/g, ' ')}\n---\n\n` +
      `## What I do\n- \n\n## When to use me\n- \n`
    await fileApi.write(`${abs(name)}/SKILL.md`, md)
    msg.success(`已创建技能「${name}」`)
    clearEditor()
    await load(name)
  } catch (e: any) {
    msg.error(e?.message || '创建技能失败')
  }
}

async function downloadNode(node: FileNode) {
  if (node.directory) return
  try {
    await fileApi.download(node.path)
  } catch (e: any) { msg.error(e?.message || '下载失败') }
}

async function deleteNode(node: FileNode) {
  if (!confirm(`确认删除「${node.name}」?${node.directory ? '该目录及其内容将被删除。' : ''}`)) return
  try {
    await fileApi.delete(node.path)
    msg.success('已删除')
    if (selectedFile.value === node.path) {
      selectedFile.value = null
    }
    await load(currentPath.value)
  } catch (e: any) { msg.error(e.message) }
}

function triggerUpload() {
  uploadDir.value = abs(currentPath.value)
  fileInput.value?.click()
}

async function openHistory(node: FileNode) {
  if (node.directory) return
  histPath.value = node.path
  histVisible.value = true
  histEntries.value = []
  diffFrom.value = null
  diffTo.value = null
  diffResult.value = null
  histLoading.value = true
  try {
    histEntries.value = await fileApi.history(node.path)
  } catch (e: any) {
    msg.error(e.message)
  } finally {
    histLoading.value = false
  }
}

function fmtTime(ts: number): string {
  const d = new Date(ts)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function shortSha(sha: string): string {
  return sha.length > 7 ? sha.slice(0, 7) : sha
}

/** 选择基线:点某条历史 → 设为 from;再点另一条设为 to */
function pickVersion(sha: string) {
  if (diffFrom.value === null || diffFrom.value === sha) {
    diffFrom.value = sha
    diffTo.value = null
  } else if (diffTo.value === null) {
    diffTo.value = sha
  } else {
    // 已选了 to:重新从 from 开始
    diffFrom.value = sha
    diffTo.value = null
  }
  loadDiff()
}

async function loadDiff() {
  if (!diffFrom.value) {
    diffResult.value = null
    return
  }
  diffLoading.value = true
  try {
    diffResult.value = await fileApi.diff(histPath.value, diffFrom.value, diffTo.value || undefined)
  } catch (e: any) {
    msg.error(e.message)
  } finally {
    diffLoading.value = false
  }
}

function restoreVersion(sha: string) {
  dialog.warning({
    title: '回滚确认',
    content: `将「${histPath.value}」回滚到 ${shortSha(sha)} 这个版本的内容,并保存为一个新版本。确定继续?`,
    positiveText: '回滚',
    negativeText: '取消',
    onPositiveClick: async () => {
      restoring.value = true
      try {
        await fileApi.restore(histPath.value, sha)
        msg.success('已回滚')
        histVisible.value = false
        // 回滚后刷新文件列表(内容/大小变化)与历史
        await load(currentPath.value)
        selectedFile.value = null
      } catch (e: any) {
        msg.error(e.message)
      } finally {
        restoring.value = false
      }
    },
  })
}

function closeHistory() {
  histVisible.value = false
  histPath.value = ''
  histEntries.value = []
  diffFrom.value = null
  diffTo.value = null
  diffResult.value = null
}async function handleUpload(e: Event) {
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
    if (fail === 0) msg.success(`已上传 ${ok} 个文件`)
    else msg.warning(`上传完成:${ok} 成功,${fail} 失败`)
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
      msg.error(e?.message || '搜索失败')
      searchResults.value = []
    } finally {
      searching.value = false
    }
  }, 300)
}

/** 实际展示的节点:搜索模式用结果,否则用当前目录 */
const displayNodes = computed<FileNode[]>(() => searchResults.value ?? nodes.value)
const isSearchMode = computed(() => searchResults.value !== null)

/** 该节点是否属于本会话 AI 改动文件(高亮标记)。深度优先判完整路径是否命中。 */
function isAiChanged(node: FileNode): boolean {
  const paths = props.changedPaths
  if (!paths || paths.length === 0) return false
  return paths.includes(node.path) || paths.some((p) => p && p.startsWith(node.path + '/'))
}

onMounted(() => load(''))
</script>

<template>
  <div class="file-tree">
    <div class="actions">
      <n-button v-if="skillsMode" size="tiny" type="primary" @click="createSkill">新建技能</n-button>
      <n-button size="tiny" @click="up" :disabled="!currentPath">上级</n-button>
      <n-button size="tiny" @click="createFile">新建文件</n-button>
      <n-button size="tiny" @click="createDir">新建目录</n-button>
      <n-button size="tiny" type="info" :loading="uploading" @click="triggerUpload">上传</n-button>
      <input ref="fileInput" type="file" multiple class="hidden-input" @change="handleUpload" />
    </div>
    <n-input
      v-model:value="searchQuery"
      size="small"
      clearable
      placeholder="搜索文件名…"
      @input="onSearchInput"
      @clear="onSearchInput"
    >
      <template #prefix>🔍</template>
    </n-input>
    <div class="path">{{ isSearchMode ? `搜索结果 (${displayNodes.length})` : displayPath }}</div>
    <div v-if="skillsMode" class="hint">
      技能 = 目录 + <code>SKILL.md</code>;进入技能目录后可「新建目录/新建文件/上传」添加脚本等扩展,智能体经 bash 工具按相对路径执行。
    </div>
    <n-spin :show="loading || searching">
      <div v-for="n in displayNodes" :key="n.path" class="node">
        <div class="node-main" @click="open(n)">
          <span class="node-icon">{{ n.directory ? '📁' : '📄' }}</span>
          <span v-if="isAiChanged(n)" class="ai-mark" title="本轮 AI 改动">✦</span>
          <span class="node-name" :class="{ dir: n.directory }">{{ n.name }}</span>
          <span v-if="isSearchMode" class="node-sub" :title="relFromAbs(n.path)">{{ relFromAbs(n.path) }}</span>
          <span v-if="!n.directory && !isSearchMode" class="node-size">{{ fmtSize(n.size) }}</span>
        </div>
        <div class="node-ops">
          <n-button
            v-if="!n.directory"
            size="tiny"
            quaternary
            title="版本历史/回滚"
            @click.stop="openHistory(n)"
          >🕘</n-button>
          <n-button
            v-if="!n.directory"
            size="tiny"
            quaternary
            title="下载"
            @click.stop="downloadNode(n)"
          >📥</n-button>
          <n-button
            size="tiny"
            quaternary
            title="删除"
            @click.stop="deleteNode(n)"
          >🗑️</n-button>
        </div>
      </div>
      <n-empty
        v-if="!displayNodes.length && !loading && !searching"
        size="small"
        :description="isSearchMode ? '无匹配文件' : (skillsMode ? '尚无技能,点「新建技能」创建' : '空目录')"
      />
    </n-spin>

    <n-modal
      v-model:show="previewVisible"
      preset="card"
      :title="previewPath ? previewPath.split(/[\\/]/).pop() : '预览'"
      :style="{ width: '86vw', maxWidth: '1280px' }"
      :mask-closable="true"
      :bordered="false"
      class="fp-modal"
    >
      <FilePreview
        v-if="previewPath"
        :path="previewPath"
        :size="previewSize"
      />
    </n-modal>

    <n-modal
      v-model:show="histVisible"
      preset="card"
      :title="`历史 · ${histPath ? histPath.split(/[\\/]/).pop() : ''}`"
      :style="{ width: '86vw', maxWidth: '900px' }"
      :mask-closable="true"
      :bordered="false"
      class="fp-modal hist-modal"
      @update:show="(s) => !s && closeHistory()"
    >
      <div class="hist-body">
        <div class="hist-list">
          <div v-if="histLoading" class="hist-empty">加载中…</div>
          <div v-else-if="!histEntries.length" class="hist-empty">暂无历史版本(该文件尚未被快照)</div>
          <div
            v-for="h in histEntries"
            :key="h.sha"
            class="hist-item"
            :class="{
              isFrom: diffFrom === h.sha,
              isTo: diffTo === h.sha,
            }"
            @click="pickVersion(h.sha)"
          >
            <div class="hist-meta">
              <span class="hist-sha">{{ shortSha(h.sha) }}</span>
              <span class="hist-time">{{ fmtTime(h.timestamp) }}</span>
            </div>
            <div class="hist-msg">{{ h.message || '(无说明)' }}</div>
            <div class="hist-ops">
              <span class="hist-tag" v-if="diffFrom === h.sha">基线</span>
              <span class="hist-tag" v-else-if="diffTo === h.sha">对比</span>
              <n-button size="tiny" quaternary @click.stop="restoreVersion(h.sha)" :loading="restoring">回滚到此</n-button>
            </div>
          </div>
          <div class="hist-tip">点击历史条目选择基线,再点另一条对比;只选一条则对比工作区当前版本。</div>
        </div>
        <div class="hist-diff">
          <div class="hist-diff-head">
            <span v-if="diffFrom" class="hist-diff-title">
              {{ diffTo ? `${shortSha(diffFrom)} → ${shortSha(diffTo)}` : `${shortSha(diffFrom)} → 当前` }}
            </span>
            <span v-else class="hist-diff-title">选择基线后显示差异</span>
          </div>
          <n-spin class="hist-diff-spin" :show="diffLoading">
            <div v-if="diffResult" class="diff-view">
              <div v-if="!diffResult.textDiff" class="diff-note">二进制文件,无法按行显示差异</div>
              <div v-else-if="!diffResult.lines.length" class="diff-note">两版内容一致,无差异</div>
              <pre v-else class="diff-pre"><code>
                <span v-for="(l, i) in diffResult.lines" :key="i" class="diff-line" :class="'dl-' + l.kind">{{ l.content }}</span>
              </code></pre>
            </div>
          </n-spin>
        </div>
      </div>
    </n-modal>
  </div>
</template>

<style scoped>
.file-tree { display: flex; flex-direction: column; height: 100%; }
.actions { display: flex; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.hidden-input { display: none; }
.path {
  font-size: 12px; color: #888; margin-bottom: 8px;
  font-family: ui-monospace, monospace; word-break: break-all;
}
.hint {
  font-size: 12px; color: #888; line-height: 1.5; margin-bottom: 8px;
  background: #1f1f23; border: 1px solid #2a2a2e; border-radius: 6px; padding: 6px 8px;
}
.hint code { color: #7ab8ff; font-family: ui-monospace, monospace; }
.node {
  display: flex; align-items: center; justify-content: space-between;
  padding: 4px; border-radius: 4px; gap: 4px;
}
.node:hover { background: #26262a; }
.node-main {
  display: flex; align-items: center; gap: 6px;
  cursor: pointer; flex: 1; min-width: 0; padding: 2px 4px;
}
.node-icon { flex-shrink: 0; }
.node-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-mark { color: #d4a72c; font-size: 11px; flex-shrink: 0; }
.node-name.dir { font-weight: 600; }
.node-size { color: #666; font-size: 11px; flex-shrink: 0; margin-left: auto; }
.node-sub { color: #5a8fbf; font-size: 11px; font-family: ui-monospace, monospace; flex-shrink: 0; margin-left: auto; max-width: 50%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-ops { display: flex; gap: 2px; flex-shrink: 0; }

.hist-body { display: flex; flex-direction: column; gap: 12px; height: 100%; min-height: 0; }
.hist-list { overflow-y: auto; flex: 0 0 auto; max-height: 38%; border-bottom: 1px solid #2a2a2e; padding-bottom: 10px; }
.hist-diff { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; }
.hist-diff-head { margin-bottom: 8px; flex: 0 0 auto; }
.hist-diff-title { font-size: 12px; color: #7ab8ff; font-family: ui-monospace, monospace; }
.hist-diff-spin { flex: 1; min-height: 0; display: flex; }
.hist-diff-spin :deep(.n-spin-content) { flex: 1; min-height: 0; display: flex; }
.hist-item {
  padding: 8px 10px; border: 1px solid #2a2a2e; border-radius: 6px;
  margin-bottom: 6px; cursor: pointer; background: #1f1f23;
}
.hist-item:hover { border-color: #3a3a40; }
.hist-item.isFrom { border-color: #63e2b7; background: #102e26; }
.hist-item.isTo { border-color: #7ab8ff; background: #10273f; }
.hist-meta { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.hist-sha { color: #7ab8ff; font-family: ui-monospace, monospace; font-size: 12px; }
.hist-time { color: #888; font-size: 11px; }
.hist-msg {
  margin: 4px 0; color: #ddd; font-size: 12px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.hist-ops { display: flex; align-items: center; justify-content: flex-end; gap: 6px; }
.hist-tag { font-size: 11px; padding: 1px 6px; border-radius: 3px; }
.hist-item.isFrom .hist-tag { background: #1f6f4a; color: #c8ffe0; }
.hist-item.isTo .hist-tag { background: #1f4e7a; color: #cfe9ff; }
.hist-tip { margin-top: 8px; font-size: 11px; color: #777; line-height: 1.5; }
.hist-empty { color: #777; font-size: 12px; padding: 12px 4px; }

.diff-view { flex: 1; min-height: 0; overflow: auto; }
.diff-pre { margin: 0; font-size: 12px; line-height: 1.55; font-family: ui-monospace, monospace; white-space: pre; overflow-x: auto; }
.diff-pre code { display: block; padding: 8px 0; }
.diff-line { display: block; padding: 0 8px; }
.dl-add { background: #1f4a2e; color: #b6e7c5; }
.dl-del { background: #4a2020; color: #e7b6b6; }
.dl-hunk { background: #24304a; color: #9db8e0; }
.dl-meta { color: #666; }
.dl-ctx { color: #ccc; }
.diff-note { color: #888; font-size: 12px; padding: 12px 4px; }

@media (max-width: 720px) {
  .hist-list { max-height: 35%; }
}

</style>

<style>
/* n-modal teleport 到 body,需用全局样式(非 scoped)控制内容区高度 */
.fp-modal .n-card__content {
  height: calc(86vh - 56px);
  overflow: hidden;
  padding: 0;
}
.hist-modal .n-card__content { padding: 16px; }
.hist-modal .hist-body { height: calc(86vh - 100px); }
</style>
