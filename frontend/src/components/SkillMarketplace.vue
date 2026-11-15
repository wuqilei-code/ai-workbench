<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage, NModal } from 'naive-ui'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import { SKILL_CATEGORIES, skillCategoryInfo } from '@/types'
import type { MarketplaceSkillVo, UserSkill, FileNode, SkillFileVo } from '@/types'
import SkillEditor from '@/components/SkillEditor.vue'
import CodeEditor from '@/components/CodeEditor.vue'
import FilterChips from '@/components/ui/FilterChips.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ListPager from '@/components/ui/ListPager.vue'
import IconPicker from '@/components/ui/IconPicker.vue'
import { useThemeStore } from '@/stores/theme'

const { t } = useI18n()
const theme = useThemeStore()
/** 只读预览跟随主题:浅色下不要强制 oneDark(否则整块代码区是深色,和页面割裂) */
const isDark = computed(() => theme.mode === 'dark')
const props = defineProps<{ show?: boolean; page?: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'installed'): void
  (e: 'close'): void
}>()

/** 根节点绑定:page 模式渲染为整页(带面包屑),否则渲染为弹窗 */
const rootBind = computed(() =>
  props.page
    ? { class: 'manage-page' }
    : {
        show: props.show,
        preset: 'card',
    title: t('market.title'),
        style: 'width: 1080px; max-width: 96vw',
        bordered: false,
        'onUpdate:show': (v: boolean) => emit('update:show', v),
        onAfterEnter: onShow,
      },
)

const msg = useMessage()
const list = ref<MarketplaceSkillVo[]>([])
const loading = ref(false)
const keyword = ref('')
/** 顶部标签:市场(浏览全部) / 我的(我安装的 + 我创建的) */
const topTab = ref<'market' | 'mine'>('market')
/** 市场标签内的分类筛选 */
const activeCat = ref('all')

/** 分类筛选 chips:只展示有内容的分类(全 0 时只留"全部"),避免一排 0 显得空 */
const catChips = computed(() => {
  const all = { key: 'all', label: t('market.all'), emoji: '🧩', count: list.value.length }
  const rest = SKILL_CATEGORIES.map((c) => ({
    ...c,
    count: list.value.filter((s) => s.category === c.key).length,
  })).filter((c) => c.count > 0)
  return [all, ...rest]
})
/** 市场且至少有一个真实分类时才显示筛选条 */
const showCatFilter = computed(() => topTab.value === 'market' && catChips.value.length > 1)

/** 分类被过滤空后自动回到"全部" */
watch(catChips, (chips) => {
  if (activeCat.value !== 'all' && !chips.some((c) => c.key === activeCat.value)) {
    activeCat.value = 'all'
  }
})
/** 顶部 Tab 项(带「我的」计数) */
const topTabs = computed(() => [
  { key: 'market' as const, label: t('market.tabMarket') },
  { key: 'mine' as const, label: t('market.tabMine'), count: mineCount.value },
])
const catOptions = computed(() =>
  SKILL_CATEGORIES.map((c) => ({ label: `${c.emoji} ${c.label}`, value: c.key })),
)
/**
 * 「我的」列表 = 本地技能库(user_skill)全集,与「工作区技能」「选择技能」同源。
 *
 * 之前这里只过滤市场表里的 `mine || installed`,导致**自建但从未发布的私有技能**
 * (user_skill 有、skill_marketplace 没有)在这里完全看不见,三处计数对不上
 * (市场 N 个 / 工作区 M 个 / 选择技能 M 个)。现在以本地库为准,再按名字关联市场信息
 * (分类、图标、安装量、是否已发布),没发布的给「发布」入口。
 */
const mySkills = ref<UserSkill[]>([])
const marketByName = computed(() => {
  const map = new Map<string, MarketplaceSkillVo>()
  for (const s of list.value) map.set(s.name, s)
  return map
})
/** 我的技能条目:本地库为主,补上市场侧元数据 */
const myItems = computed(() =>
  mySkills.value.map((s) => {
    const m = marketByName.value.get(s.name)
    return {
      name: s.name,
      description: s.description || m?.description || '',
      // 分类/图标:市场有则用市场的,否则从 SKILL.md frontmatter 取,再兜底默认
      category: m?.category || 'other',
      icon: m?.icon || parseLocalIcon(s.content) || fallbackEmoji(s.name),
      installs: m?.installs ?? 0,
      /** 已在市场中(发布或安装) */
      inMarket: !!m,
      mine: !!m?.mine,
      installed: !!m?.installed,
      marketId: m?.id,
      source: s.source,
      visibility: s.visibility,
      files: s.files?.length ?? 0,
      updatedAt: s.updatedAt || s.createdAt || '',
    }
  }),
)
/** 从本地 SKILL.md frontmatter 读 icon(市场未收录时用) */
function parseLocalIcon(content?: string): string {
  if (!content) return ''
  const fm = content.match(/^---\s*\n([\s\S]*?)\n---/)
  return fm ? (fm[1].match(/^icon\s*:\s*(.+)$/m)?.[1]?.trim() ?? '') : ''
}
/** 名称哈希兜底图标(与 SkillsPanel 保持一致的观感) */
function fallbackEmoji(name: string): string {
  const pool = ['🤖', '⚙️', '📊', '📄', '✍️', '🗂️', '💻', '🧩', '📚', '🎨', '🔬', '🚀']
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0
  return pool[h % pool.length]
}
const mineCount = computed(() => myItems.value.length)

/** 统一卡片视图模型:市场 / 我的两种来源走同一套卡片渲染,避免两份重复模板 */
interface SkillCardVM {
  key: string
  kind: 'market' | 'mine'
  name: string
  description: string
  category: string
  icon: string
  installs: number
  files: number
  author: string
  updatedAt: string
  installed: boolean
  mine: boolean
  inMarket: boolean
  marketId?: number
  raw: any
}

const marketCards = computed<SkillCardVM[]>(() =>
  list.value.map((s) => ({
    key: 'm-' + s.id,
    kind: 'market' as const,
    name: s.name,
    description: s.description || '',
    category: s.category || 'other',
    icon: skillIcon(s),
    installs: s.installs || 0,
    files: s.files?.length ?? 0,
    author: s.authorName || t('market.official'),
    updatedAt: s.updatedAt || '',
    installed: !!s.installed,
    mine: !!s.mine,
    inMarket: true,
    marketId: s.id,
    raw: s,
  })),
)

const mineCards = computed<SkillCardVM[]>(() =>
  myItems.value.map((s) => ({
    key: 'u-' + s.name,
    kind: 'mine' as const,
    name: s.name,
    description: s.description || '',
    category: s.category || 'other',
    icon: s.icon || fallbackEmoji(s.name),
    installs: s.installs || 0,
    files: s.files || 0,
    author: t('market.meAuthor'),
    updatedAt: (s as any).updatedAt || '',
    installed: s.installed,
    mine: s.mine,
    inMarket: s.inMarket,
    marketId: s.marketId,
    raw: s,
  })),
)

/** 排序:热门(安装量)/ 最新 / 名称 */
const sortKey = ref<'hot' | 'new' | 'name'>('hot')
const sortOptions = computed(() => [
  { label: t('market.sortHot'), value: 'hot' },
  { label: t('market.sortNew'), value: 'new' },
  { label: t('market.sortName'), value: 'name' },
])

const poolCards = computed(() => (topTab.value === 'mine' ? mineCards.value : marketCards.value))

const sortedCards = computed<SkillCardVM[]>(() => {
  const q = keyword.value.trim().toLowerCase()
  let arr = poolCards.value
  if (topTab.value === 'market' && activeCat.value !== 'all') {
    arr = arr.filter((s) => s.category === activeCat.value)
  }
  if (q) {
    arr = arr.filter(
      (s) => s.name.toLowerCase().includes(q) || (s.description || '').toLowerCase().includes(q),
    )
  }
  const copy = [...arr]
  if (sortKey.value === 'new') {
    copy.sort((a, b) => (b.updatedAt || '').localeCompare(a.updatedAt || '') || a.name.localeCompare(b.name))
  } else if (sortKey.value === 'name') {
    copy.sort((a, b) => a.name.localeCompare(b.name))
  } else {
    copy.sort((a, b) => b.installs - a.installs || a.name.localeCompare(b.name))
  }
  return copy
})

/** 空态区分:没有技能 / 搜索无结果 / 该分类为空 / 我的为空 */
const emptyState = computed(() => {
  if (topTab.value === 'mine') {
    return { icon: '🗂️', text: t('market.mineEmpty'), sub: t('market.mineEmptySub'), cta: 'publish' as const }
  }
  if (keyword.value.trim()) {
    return { icon: '🔍', text: t('market.emptySearch'), sub: t('market.emptySearchSub'), cta: 'clear' as const }
  }
  if (!list.value.length) {
    return { icon: '🧩', text: t('market.emptyNoSkills'), sub: t('market.emptyNoSkillsSub'), cta: 'upload' as const }
  }
  return { icon: '🧩', text: t('market.emptyCat'), sub: '', cta: 'all' as const }
})

function clearFilters() {
  keyword.value = ''
  keywordInput.value = ''
  activeCat.value = 'all'
  resetPage()
}

/** 分页(基于已过滤/排序的结果) */
const page = ref(1)
const pageSize = 18
const pagedCards = computed(() => sortedCards.value.slice((page.value - 1) * pageSize, page.value * pageSize))
function resetPage() {
  page.value = 1
}

/** 搜索防抖:输入停顿 250ms 再触发过滤 */
const keywordInput = ref('')
let keywordTimer: ReturnType<typeof setTimeout> | null = null
watch(keywordInput, (v) => {
  if (keywordTimer) clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => {
    keyword.value = v
    resetPage()
  }, 250)
})

async function load() {
  loading.value = true
  try {
    // 一次取两份:市场表用于「市场」Tab 与元数据关联,user_skill 用于「我的」Tab
    const [mkt, mine] = await Promise.all([
      chatApi.listMarketplaceSkills(),
      chatApi.listMineSkills(),
    ])
    list.value = mkt
    mySkills.value = mine as UserSkill[]
  } catch (e: any) {
    msg.error(e?.message || t('market.loadFailed'))
  } finally {
    loading.value = false
  }
}
function onShow() {
  load()
}

// ---------- 发布 ----------
const publishVisible = ref(false)
const pubName = ref('')
const pubDesc = ref('')
const pubCategory = ref('other')
const pubIcon = ref('🤖')
const pubContent = ref('')
const pubFiles = ref<SkillFileVo[]>([])
const pubSaving = ref(false)
const importSkills = ref<FileNode[]>([])
const importLoading = ref(false)

/** 导入自己工作区已存在的技能目录(递归读取全部文件,预填表单) */
async function loadImportable() {
  importLoading.value = true
  try {
    const nodes = await fileApi.list('.opencode/skills')
    importSkills.value = nodes.filter((n) => n.directory)
  } catch (e: any) {
    msg.error(e?.message || t('market.descLoadFailed'))
  } finally {
    importLoading.value = false
  }
}

/** 递归收集一个工作区目录下的文件(path 相对技能根),目录/二进制跳过。 */
async function collectFiles(dir: string): Promise<SkillFileVo[]> {
  const out: SkillFileVo[] = []
  const walk = async (rel: string) => {
    const nodes = await fileApi.list(rel)
    for (const n of nodes) {
      if (n.directory) {
        await walk(n.path)
      } else {
        try {
          const content = await fileApi.content(n.path)
          out.push({ path: n.path.replace(dir + '/', ''), content })
        } catch {
          /* 二进制/读取失败跳过 */
        }
      }
    }
  }
  await walk(dir)
  return out
}

async function importSkill(name: string) {
  importingSkill.value = true
  try {
    const root = `.opencode/skills/${name}`
    const md = await fileApi.content(`${root}/SKILL.md`)
    pubName.value = name
    pubContent.value = md
    // 从 frontmatter 提取 description / category / icon
    const m = md.match(/^---\s*\n([\s\S]*?)\n---/)
    if (m) {
      const pick = (k: string) => m[1].match(new RegExp(`^${k}\\s*:\\s*(.+)$`, 'm'))?.[1]?.trim()
      const desc = pick('description')
      if (desc) pubDesc.value = desc
      const cat = pick('category')
      if (cat && catOptions.value.some((o) => o.value === cat)) pubCategory.value = cat
      const icon = pick('icon')
      if (icon) pubIcon.value = icon
    }
    // 递归读取附加文件(SKILL.md 之外的全部;SKILL.md 已作为 content 单独提交,不能重复)
    pubFiles.value = (await collectFiles(root)).filter((f) => f.path !== 'SKILL.md')
    msg.success(t('market.trained', { name, n: pubFiles.value.length }))
  } catch (e: any) {
    msg.error(e?.message || t('market.trainFailed'))
  } finally {
    importingSkill.value = false
  }
}

/** 根据技能名/描述生成 SKILL.md 骨架,预填内容区,减少手写 frontmatter 的负担。 */
function generateTemplate() {
  const name = (pubName.value || '').trim().toLowerCase().replace(/[^a-z0-9-]/g, '-')
  const desc = pubDesc.value.trim()
  if (!name) {
    msg.warning(t('market.needNameDesc'))
    return
  }
  if (!desc) {
    msg.warning(t('market.needNameDesc'))
    return
  }
  pubContent.value = `---
name: ${name}
description: ${desc}
license: MIT
compatibility: opencode
metadata:
  audience: all-users
---

# ${name}

    (在描述末尾加一句'什么时候用',例如:Use when ...)

    t('market.tplUsage')

- 

    t('market.tplSteps')

1. 

    t('market.tplNotes')

- 
`
    msg.success(t('market.tplGenerated'))
}

function openPublish() {
  pubName.value = ''
  pubDesc.value = ''
  pubCategory.value = 'other'
  pubIcon.value = '🤖'
  pubContent.value = ''
  pubFiles.value = []
  publishVisible.value = true
  loadImportable()
}

// ---------- 上传技能包(zip/.skill,内含根级 SKILL.md) ----------
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadInput = ref<HTMLInputElement | null>(null)
function openUpload() {
  uploadVisible.value = true
}
async function doUpload(file: File) {
  if (!file) return
  if (!/\.(zip|skill)$/i.test(file.name)) {
    msg.warning(t('market.onlyZip'))
    return
  }
  uploading.value = true
  try {
    await chatApi.uploadSkill(file)
    msg.success(t('market.uploaded'))
    uploadVisible.value = false
    await load()
  } catch (e: any) {
    msg.error(e?.message || t('market.uploadFailed'))
  } finally {
    uploading.value = false
  }
}
function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) doUpload(file)
  input.value = ''
}
function onDropFile(e: DragEvent) {
  const file = e.dataTransfer?.files?.[0]
  if (file) doUpload(file)
}

const validFiles = computed(() => pubFiles.value.filter((f) => f.path?.trim() && f.content != null))
const publishDisabled = computed(
  () => !pubName.value.trim() || !pubDesc.value.trim() || !pubContent.value.trim(),
)

async function submitPublish() {
  if (publishDisabled.value) {
      msg.warning(t('market.zipNeedSkillMd'))
    return
  }
  pubSaving.value = true
  try {
    await chatApi.publishSkill({
      name: pubName.value.trim(),
      description: pubDesc.value.trim(),
      category: pubCategory.value,
      icon: pubIcon.value.trim() || undefined,
      content: pubContent.value,
      files: validFiles.value.map((f) => ({ path: f.path.trim().replace(/\\/g, '/').replace(/^\/+/, ''), content: f.content, encoding: f.encoding })),
    })
    msg.success(t('market.inMarket'))
    publishVisible.value = false
    await load()
  } catch (e: any) {
    msg.error(e?.message || t('market.actionFailed'))
  } finally {
    pubSaving.value = false
  }
}

// ---------- 详情 ----------
const detailVisible = ref(false)
const detail = ref<MarketplaceSkillVo | null>(null)
const detailLoading = ref(false)
const installing = ref(false)
const activeFile = ref<string>('SKILL.md')
/** 从工作区导入技能进行中(遍历文件系统) */
const importingSkill = ref(false)
/** 列表行级操作进行中的技能 id(安装/下架) */
const rowBusyId = ref<number | null>(null)

async function openDetail(s: MarketplaceSkillVo) {
  detail.value = null
  detailVisible.value = true
  activeFile.value = 'SKILL.md'
  detailLoading.value = true
  try {
    detail.value = await chatApi.marketplaceSkillDetail(s.id)
  } catch (e: any) {
    msg.error(e?.message || t('market.actionFailed'))
  } finally {
    detailLoading.value = false
  }
}

/** 从「我的」卡片打开详情(只有已发布的才有市场 id) */
async function openDetailById(id: number) {
  detail.value = null
  detailVisible.value = true
  activeFile.value = 'SKILL.md'
  detailLoading.value = true
  try {
    detail.value = await chatApi.marketplaceSkillDetail(id)
  } catch (e: any) {
    msg.error(e?.message || t('market.actionFailed'))
  } finally {
    detailLoading.value = false
  }
}

/** 卡片点击:市场卡进详情;已发布的我的技能也进详情;未发布的直接打开发布表单 */
function onCardOpen(c: SkillCardVM) {
  if (c.kind === 'market') return void openDetail(c.raw as MarketplaceSkillVo)
  if (c.inMarket && c.marketId) return void openDetailById(c.marketId)
  return void publishFromMine(c.raw)
}

function onInstallCard(c: SkillCardVM) {
  void installDirect(c.raw as MarketplaceSkillVo)
}
function onRemoveCard(c: SkillCardVM) {
  if (c.marketId) return void removeOwnById(c.marketId)
  return void removeOwn(c.raw as MarketplaceSkillVo)
}
function onPublishCard(c: SkillCardVM) {
  void publishFromMine(c.raw)
}

/** 详情当前查看文件的内容(SKILL.md 或某个附加文件) */
const activeContent = computed(() => {
  if (!detail.value) return ''
  if (activeFile.value === 'SKILL.md') return detail.value.content || ''
  const f = detail.value.files?.find((x) => x.path === activeFile.value)
  return f ? f.content : ''
})

/** 详情文件树(SKILL.md + 附加文件按路径展开),拍平为带缩进的列表 */
interface FlatNode { name: string; path: string; depth: number; isDir: boolean }
const fileTree = computed<FlatNode[]>(() => {
  const d = detail.value
  if (!d) return []
  const paths = ['SKILL.md', ...((d.files || []).map((f) => f.path).filter(Boolean))]
  const root: any = { children: new Map<string, any>() }
  for (const p of paths) {
    const parts = String(p).split('/')
    let node = root
    for (let i = 0; i < parts.length; i++) {
      const seg = parts[i]
      if (!node.children.has(seg)) node.children.set(seg, { name: seg, children: new Map() })
      node = node.children.get(seg)
      if (i === parts.length - 1) node.path = p
    }
  }
  const out: FlatNode[] = []
  const walk = (n: any, depth: number) => {
    for (const child of n.children.values()) {
      const isDir = child.children.size > 0
      out.push({ name: child.name, path: child.path || child.name, depth, isDir })
      if (isDir) walk(child, depth + 1)
    }
  }
  walk(root, 0)
  return out
})
function getFileIcon(path: string): string {
  const ext = path.split('.').pop()?.toLowerCase()
  if (['py', 'js', 'ts', 'sh', 'java', 'go', 'rs'].includes(ext || '')) return '🐍'
  if (['csv', 'xlsx', 'xls', 'json', 'xml'].includes(ext || '')) return '🗃️'
  if (['md', 'txt'].includes(ext || '')) return '📄'
  if (['png', 'jpg', 'svg', 'webp'].includes(ext || '')) return '🖼️'
  return '📎'
}

async function install() {
  if (!detail.value) return
  installing.value = true
  try {
    await chatApi.installSkill(detail.value.id)
    msg.success(t('market.installedAndOpen', { name: detail.value.name }))
    detailVisible.value = false
    emit('installed')
    await load()
  } catch (e: any) {
    msg.error(e?.message || t('tpl.installFailed'))
  } finally {
    installing.value = false
  }
}

/** 列表内直接安装(不再弹详情) */
async function installDirect(s: MarketplaceSkillVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = s.id
  try {
    await chatApi.installSkill(s.id)
    msg.success(t('market.installedAndOpen', { name: s.name }))
    emit('installed')
    await load()
  } catch (e: any) {
    msg.error(e?.message || t('tpl.installFailed'))
  } finally {
    rowBusyId.value = null
  }
}

async function removeOwn(s: MarketplaceSkillVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = s.id
  try {
    await chatApi.deleteMarketplaceSkill(s.id)
    msg.success(t('market.unpublish'))
    await load()
  } catch (e: any) {
    msg.error(e?.message || t('market.deleteFailed'))
  } finally {
    rowBusyId.value = null
  }
}

/** 按 id 下架(「我的」Tab 用:条目来自本地库,市场 id 单独取) */
async function removeOwnById(marketId: number) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = marketId
  try {
    await chatApi.deleteMarketplaceSkill(marketId)
    msg.success(t('market.unpublish'))
    await load()
  } catch (e: any) {
    msg.error(e?.message || t('market.deleteFailed'))
  } finally {
    rowBusyId.value = null
  }
}

/** 「我的」Tab:把本地技能预填进发布表单(未发布过的) */
async function publishFromMine(s: { name: string; description: string; icon: string; category: string }) {
  pubName.value = s.name
  pubDesc.value = s.description || ''
  pubIcon.value = s.icon || '🤖'
  pubCategory.value = s.category || 'other'
  pubContent.value = ''
  pubFiles.value = []
  publishVisible.value = true
  try {
    // 从本地技能库取 SKILL.md 与附加文件(虚拟根是 skills/,不是 .opencode/skills)
    const md = await fileApi.content(`skills/${s.name}/SKILL.md`)
    if (md) {
      pubContent.value = md
      pubFiles.value = (await collectFiles(`skills/${s.name}`)).filter((f) => f.path !== 'SKILL.md')
    }
  } catch {
    msg.warning(t('market.mineLoadFailed', { name: s.name }))
  }
}

/** 相对时间:今天 / 昨天 / N 天前 / 日期(用于卡片作者与更新时间) */
function fmtRel(ts: string): string {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ts.slice(0, 10)
  const diff = Date.now() - d.getTime()
  const day = 86400000
  if (diff < day && new Date().getDate() === d.getDate()) return t('market.relToday')
  if (diff < 2 * day) return t('market.relYesterday')
  if (diff < 30 * day) return t('market.relDays', { n: Math.floor(diff / day) })
  return ts.slice(0, 10)
}

/** 详情代码区语言(按当前文件名后缀) */
const detailLang = computed(() => {
  const ext = (activeFile.value || '').split('.').pop()?.toLowerCase() || ''
  const map: Record<string, string> = {
    md: 'markdown', py: 'python', js: 'javascript', ts: 'typescript', json: 'json',
    sh: 'bash', yml: 'yaml', yaml: 'yaml', html: 'html', css: 'css', sql: 'sql',
    java: 'java', go: 'go', rs: 'rust', txt: '',
  }
  return map[ext] || ''
})

async function copyDetail() {
  try {
    await navigator.clipboard.writeText(activeContent.value || '')
    msg.success(t('market.copied'))
  } catch (e: any) {
    msg.error(e?.message || t('market.copyFailed'))
  }
}

function skillIcon(s: { icon?: string; category: string }): string {
  if (s.icon && s.icon.trim()) return s.icon.trim()
  return skillCategoryInfo(s.category).emoji
}

onMounted(() => {
  if (props.page || props.show) load()
})
</script>

<template>
  <component :is="props.page ? 'div' : NModal" v-bind="rootBind">
    <!-- 整页模式:面包屑 + 页头 -->
    <div v-if="props.page && !detailVisible" class="manage-crumb">
      <button class="crumb-link" @click="emit('close')">首页</button>
      <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ t('market.title') }}</span>
    </div>

    <!-- 技能详情(整页 + 文件树 + 只读代码编辑区) -->
    <div v-if="detailVisible" class="dt-page">
      <div class="manage-crumb">
        <button class="crumb-link" @click="emit('close')">首页</button>
        <span class="crumb-sep">/</span>
        <button class="crumb-link" @click="detailVisible = false">{{ t('market.title') }}</button>
        <span class="crumb-sep">/</span>
        <span class="crumb-cur">{{ detail?.name || t('market.unknown') }}</span>
      </div>
      <n-spin :show="detailLoading">
        <template v-if="detail">
          <div class="dt-cover">
            <div class="dt-icon" :class="'cat-' + (detail.category || 'other')">{{ skillIcon(detail) }}</div>
            <div class="dt-head-info">
              <div class="dt-title">
                <span class="dt-name">{{ detail.name }}</span>
                <span class="mp-badge">{{ skillCategoryInfo(detail.category).emoji }} {{ skillCategoryInfo(detail.category).label }}</span>
                <span v-if="detail.installed" class="mp-badge ok">{{ t('market.installed') }}</span>
              </div>
              <div class="dt-desc">{{ detail.description }}</div>
              <div class="dt-meta">
                <span>by {{ detail.authorName || t('market.official') }}</span>
                <span class="dt-dot">·</span>
                <span>{{ t('market.installs', { n: detail.installs }) }}</span>
                <template v-if="detail.updatedAt">
                  <span class="dt-dot">·</span>
                  <span>{{ t('market.updatedAt', { t: fmtRel(detail.updatedAt) }) }}</span>
                </template>
              </div>
            </div>
            <div class="dt-actions">
              <n-button v-if="!detail.installed" size="small" type="primary" :loading="installing" @click="install">{{ t('market.installAndUse') }}</n-button>
              <n-button v-if="detail.mine" size="small" type="error" quaternary @click="removeOwn(detail)">{{ t('market.unpublish') }}</n-button>
            </div>
          </div>
          <div class="dt-body">
            <div class="dt-tree">
              <div class="dt-tree-title">{{ t('market.fileTree') }}</div>
              <div
                v-for="n in fileTree"
                :key="n.path"
                class="dt-tree-item"
                :class="{ active: activeFile === n.path && !n.isDir, dir: n.isDir }"
                :style="{ paddingLeft: (10 + n.depth * 14) + 'px' }"
                @click="!n.isDir && (activeFile = n.path)"
              >
                <span class="dt-tree-ico">{{ n.isDir ? '📁' : getFileIcon(n.path) }}</span>
                <span class="dt-tree-name">{{ n.name }}</span>
              </div>
            </div>
            <div class="dt-view">
              <div class="dt-view-head">
                <span class="dt-file">{{ activeFile }}</span>
                <button type="button" class="dt-copy" :title="t('market.copyContent')" @click="copyDetail">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
                  {{ t('market.copyContent') }}
                </button>
              </div>
              <CodeEditor
                :model-value="activeContent"
                :language="detailLang"
                :filename="activeFile"
                :read-only="true"
                :is-dark="isDark"
                class="dt-editor"
              />
            </div>
          </div>
        </template>
      </n-spin>
    </div>

    <template v-if="!detailVisible">
      <!-- 页头(整页模式):标题 + 主操作 -->
      <div v-if="props.page" class="mp-hero">
        <div class="mp-hero-text">
          <div class="manage-title">{{ t('market.title') }}</div>
          <div class="manage-sub">{{ t('market.sub') }}</div>
        </div>
        <div class="mp-hero-actions">
          <n-button size="small" @click="openPublish">{{ t('market.publishSkill') }}</n-button>
          <n-button size="small" type="primary" @click="openUpload">{{ t('market.upload') }}</n-button>
        </div>
      </div>

      <!-- 工具行:搜索 / 排序 / 结果数(弹窗模式补上传入口) -->
      <div class="mp-toolbar">
        <n-input
          v-model:value="keywordInput"
          class="mp-search"
          size="small"
          clearable
          :placeholder="t('market.searchPlaceholder')"
        >
          <template #prefix>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          </template>
        </n-input>
        <n-select
          v-model:value="sortKey"
          class="mp-sort"
          size="small"
          :options="sortOptions"
          :aria-label="t('market.sortLabel')"
        />
        <span class="mp-count">{{ t('market.resultCount', { n: sortedCards.length }) }}</span>
        <n-button v-if="!props.page" size="small" type="primary" class="mp-toolbar-upload" @click="openUpload">{{ t('market.upload') }}</n-button>
      </div>

      <!-- 市场 / 我的:分段控件(替代下划线 tab,避免与分类筛选抢层级) -->
      <div class="mp-seg" role="tablist" :aria-label="t('market.ariaTopTabs')">
        <button
          v-for="tab in topTabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="mp-seg-btn"
          :class="{ active: topTab === tab.key }"
          :aria-selected="topTab === tab.key"
          @click="topTab = tab.key; resetPage()"
        >
          {{ tab.label }}<span v-if="tab.count" class="mp-seg-count">{{ tab.count }}</span>
        </button>
      </div>

      <!-- 分类筛选:只显示有内容的分类 -->
      <FilterChips
        v-if="showCatFilter"
        v-model:active="activeCat"
        :items="catChips"
        class="mp-cats"
        :aria-label="t('market.ariaCatFilter')"
        @change="resetPage()"
      />

      <div class="mp-body" :class="{ 'mp-body--modal': !props.page }">
        <!-- 加载骨架 -->
        <div v-if="loading" class="mp-grid">
          <div v-for="i in 6" :key="i" class="mp-card mp-skeleton">
            <div class="mp-card-main">
              <div class="sk-icon" />
              <div class="sk-lines">
                <div class="sk-line w60" />
                <div class="sk-line w90" />
                <div class="sk-line w40" />
              </div>
            </div>
            <div class="sk-foot"><div class="sk-line w30" /></div>
          </div>
        </div>

        <!-- 空态:区分 无技能 / 搜索无结果 / 分类为空 / 我的为空 -->
        <EmptyState
          v-else-if="!sortedCards.length"
          :icon="emptyState.icon"
          :text="emptyState.text"
          :sub="emptyState.sub"
          :padding="56"
        >
          <n-button v-if="emptyState.cta === 'upload'" size="small" type="primary" @click="openUpload">{{ t('market.upload') }}</n-button>
          <n-button v-else-if="emptyState.cta === 'publish'" size="small" type="primary" @click="openPublish">{{ t('market.publishNow') }}</n-button>
          <n-button v-else size="small" @click="clearFilters">{{ emptyState.cta === 'clear' ? t('market.clearFilter') : t('market.all') }}</n-button>
        </EmptyState>

        <div v-else class="mp-grid">
          <div
            v-for="c in pagedCards"
            :key="c.key"
            class="mp-card"
            :class="'cat-' + c.category"
            tabindex="0"
            role="button"
            @click="onCardOpen(c)"
            @keydown.enter.prevent="onCardOpen(c)"
            @keydown.space.prevent="onCardOpen(c)"
          >
            <div class="mp-card-main">
              <div class="mp-card-icon" :title="skillCategoryInfo(c.category).label">{{ c.icon }}</div>
              <div class="mp-card-body">
                <div class="mp-card-title-row">
                  <span class="mp-card-name">{{ c.name }}</span>
                  <span v-if="c.installed" class="mp-badge ok">
                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"/></svg>
                    {{ t('market.installed') }}
                  </span>
                  <span v-if="c.mine" class="mp-badge mine">{{ t('market.mineTag') }}</span>
                  <span v-if="c.kind === 'mine' && !c.inMarket" class="mp-badge muted">{{ t('market.notInMarket') }}</span>
                </div>
                <div class="mp-card-desc">{{ c.description || t('market.noDesc') }}</div>
              </div>
            </div>

            <div class="mp-card-foot">
              <div class="mp-card-meta">
                <span class="mp-pill mp-pill-cat">{{ skillCategoryInfo(c.category).label }}</span>
                <span v-if="c.installs" class="mp-pill" :title="t('market.installs', { n: c.installs })">
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v12"/><polyline points="7 11 12 16 17 11"/><path d="M4 21h16"/></svg>
                  {{ c.installs }}
                </span>
                <span v-if="c.files" class="mp-pill" :title="t('market.filesShort', { n: c.files })">
                  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                  {{ c.files }}
                </span>
              </div>
              <span class="mp-card-byline">
                {{ c.author }}<template v-if="c.updatedAt"> · {{ fmtRel(c.updatedAt) }}</template>
              </span>
              <div class="mp-card-action" @click.stop>
                <n-button
                  v-if="c.kind === 'market' && !c.installed"
                  size="tiny"
                  type="primary"
                  :loading="rowBusyId === c.marketId"
                  :disabled="rowBusyId !== null"
                  @click="onInstallCard(c)"
                >{{ t('tpl.install') }}</n-button>
                <n-button
                  v-else-if="c.kind === 'mine' && !c.inMarket"
                  size="tiny"
                  type="primary"
                  secondary
                  @click="onPublishCard(c)"
                >{{ t('market.publishNow') }}</n-button>
                <n-button
                  v-else-if="c.mine"
                  size="tiny"
                  type="error"
                  quaternary
                  :loading="rowBusyId === c.marketId"
                  :disabled="rowBusyId !== null"
                  @click="onRemoveCard(c)"
                >{{ t('market.unpublish') }}</n-button>
              </div>
            </div>
          </div>
        </div>

        <div v-if="sortedCards.length > pageSize" class="mp-pager">
          <ListPager :page="page" :item-count="sortedCards.length" :page-size="pageSize" @update:page="page = $event" />
        </div>
      </div>
    </template>

    <!-- 上传技能弹窗 -->
    <n-modal v-model:show="uploadVisible" preset="card" :title="t('market.uploadTitle')" style="width: 560px; max-width: 96vw" :bordered="false">
      <div
        class="up-drop"
        :class="{ busy: uploading }"
        @click="!uploading && uploadInput?.click()"
        @dragover.prevent
        @drop.prevent="onDropFile"
      >
        <div class="up-spinner" v-if="uploading"></div>
        <svg v-else width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
        <div class="up-drop-title">{{ uploading ? t('market.uploading') : t('market.dropHere') }}</div>
      </div>
      <input ref="uploadInput" type="file" accept=".zip,.skill" style="display:none" @change="onPickFile" />
      <ul class="up-tips">
          <li>{{ t('market.zipTip') }}</li>
          <li>{{ t('market.yamlTip') }}</li>
      </ul>
      <template #footer>
        <div style="display:flex;justify-content:flex-end;gap:8px">
        <n-button quaternary :disabled="uploading" @click="uploadVisible = false">{{ t('files.cancel') }}</n-button>
        <n-button type="primary" :loading="uploading" @click="uploadInput?.click()">{{ t('market.chooseFile') }}</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 发布弹窗 -->
    <n-modal
      v-model:show="publishVisible"
      preset="card"
    :title="t('market.publishSkill')"
      style="width: 880px; max-width: 96vw"
      :bordered="false"
    >
      <div v-if="importSkills.length || importLoading" class="mp-import">
      <span class="mp-import-label">{{ t('market.importLabel') }}</span>
        <span
          v-for="s in importSkills"
          :key="s.path"
          class="mp-import-chip"
          :class="{ busy: importingSkill }"
          @click="importSkill(s.name)"
        >{{ s.name }}</span>
      <span v-if="importLoading" class="mp-import-loading">{{ t('market.importing') }}</span>
      <span v-else-if="importingSkill" class="mp-import-loading">{{ t('market.importingSkill') }}</span>
      </div>
      <div class="pub-form">
        <!-- 分组 1:基本信息 -->
        <div class="pub-group">
          <div class="pub-group-head">
            <span class="pub-group-title">{{ t('market.groupBasic') }}</span>
            <span class="pub-group-hint">{{ t('market.groupBasicHint') }}</span>
          </div>
          <n-input v-model:value="pubName" maxlength="64" size="small" :placeholder="t('market.pubNamePlaceholder')" />
          <n-input v-model:value="pubDesc" maxlength="1024" size="small" :placeholder="t('market.pubDescPlaceholder')" />
          <div class="pub-row">
            <n-select
              v-model:value="pubCategory"
              size="small"
              :options="catOptions"
              :placeholder="t('market.pickIcon')"
              style="width: 220px"
            />
          </div>
          <div class="icon-picker">
            <span class="icon-picker-label">{{ t('market.iconLabel') }}</span>
            <IconPicker v-model="pubIcon" :size="30" :title="t('market.iconLabel')" />
          </div>
        </div>

        <!-- 分组 2:技能内容 -->
        <div class="pub-group">
          <div class="pub-group-head">
            <span class="pub-group-title">{{ t('market.contentTitle') }}</span>
            <span class="pub-group-hint">{{ t('market.filesNote') }}</span>
            <n-button size="tiny" type="primary" quaternary :disabled="!pubName.trim() || !pubDesc.trim()" @click="generateTemplate">{{ t('market.genTemplate') }}</n-button>
          </div>
          <SkillEditor
            :content="pubContent"
            :files="pubFiles"
            :name="pubName.trim() || 'unnamed-skill'"
            :description="pubDesc"
            :readonly="false"
            @update:content="pubContent = $event"
            @update:files="pubFiles = $event"
          />
        </div>
      </div>

      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
        <n-button @click="publishVisible = false">{{ t('files.cancel') }}</n-button>
        <n-button type="primary" :loading="pubSaving" :disabled="publishDisabled" @click="submitPublish">{{ t('market.publish') }}</n-button>
        </div>
      </template>
    </n-modal>

  </component>
</template>

<style scoped>
/* ---- 页头:标题 + 主操作 ---- */
.mp-hero { display: flex; align-items: flex-start; gap: 16px; margin-bottom: 16px; }
.mp-hero-text { flex: 1; min-width: 0; }
.mp-hero-actions { display: flex; gap: 8px; flex-shrink: 0; padding-top: 2px; }

/* ---- 工具行:搜索 / 排序 / 结果数 ---- */
.mp-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.mp-search { flex: 1; min-width: 200px; max-width: 420px; }
.mp-sort { width: 120px; flex-shrink: 0; }
.mp-count { font-size: 12px; color: var(--text-3); margin-left: auto; }
.mp-toolbar-upload { flex-shrink: 0; }

/* ---- 市场 / 我的:分段控件 ---- */
.mp-seg {
  display: inline-flex; align-self: flex-start; gap: 2px; padding: 3px; margin-bottom: 12px;
  border-radius: 10px; background: var(--fill-0, rgba(127, 127, 127, 0.08));
  border: 1px solid var(--border-soft);
}
.mp-seg-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 14px; border: 0; border-radius: 8px;
  background: transparent; color: var(--text-3);
  font-size: 13px; font-family: inherit; cursor: pointer;
  transition: background .15s, color .15s;
}
.mp-seg-btn:hover { color: var(--text-2); }
.mp-seg-btn.active { background: var(--surface); color: var(--text); box-shadow: 0 1px 3px rgba(0, 0, 0, .08); }
.mp-seg-btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 1px; }
.mp-seg-count { font-size: 11px; color: var(--text-3); background: var(--fill-0); border-radius: 8px; padding: 0 6px; }

.mp-cats { margin-bottom: 14px; }

/* ---- 内容区与网格 ---- */
.mp-body { display: block; }
.mp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 14px; padding: 2px; }
/* 弹窗模式:网格内部滚动,避免弹窗随内容无限变高 */
.mp-body--modal .mp-grid { max-height: 56vh; overflow-y: auto; }

/* ---- 卡片 ---- */
/* 分类色板:图标底色、分类胶囊、hover 光晕共用 --cat */
.cat-dev { --cat: #5b8cff; }
.cat-doc { --cat: #8b7bff; }
.cat-data { --cat: #2fb98a; }
.cat-writing { --cat: #e0a33c; }
.cat-office { --cat: #c56be0; }
.cat-other { --cat: #98a2b3; }

.mp-card {
  --cat: var(--accent);
  position: relative; display: flex; flex-direction: column; gap: 12px;
  padding: 16px 18px 14px; border-radius: 16px;
  background:
    radial-gradient(120% 90% at 0% 0%, color-mix(in srgb, var(--cat) 8%, transparent), transparent 62%),
    var(--surface);
  border: 1px solid var(--border-soft);
  cursor: pointer; outline: none;
  transition: border-color .18s, box-shadow .18s, transform .18s;
}
.mp-card:hover, .mp-card:focus-visible {
  border-color: color-mix(in srgb, var(--cat) 42%, var(--border-soft));
  box-shadow:
    0 12px 26px -16px color-mix(in srgb, var(--cat) 65%, transparent),
    0 2px 6px rgba(0, 0, 0, .04);
  transform: translateY(-2px);
}
/* 顶部分类色细条:低饱和渐变,轻量区分类型 */
.mp-card::before {
  content: ''; position: absolute; inset: 0 0 auto 0; height: 2px;
  border-radius: 16px 16px 0 0;
  background: linear-gradient(90deg, var(--cat), color-mix(in srgb, var(--cat) 10%, transparent));
  opacity: .55; transition: opacity .18s;
}
.mp-card:hover::before, .mp-card:focus-visible::before { opacity: 1; }
.mp-card:focus-visible {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--cat) 40%, transparent),
    0 12px 26px -16px color-mix(in srgb, var(--cat) 60%, transparent);
}

.mp-card-main { display: flex; gap: 12px; align-items: flex-start; min-width: 0; }
.mp-card-icon {
  width: 52px; height: 52px; flex-shrink: 0; border-radius: 15px;
  display: flex; align-items: center; justify-content: center; font-size: 25px;
  background: linear-gradient(135deg,
    color-mix(in srgb, var(--cat) 26%, transparent),
    color-mix(in srgb, var(--cat) 8%, transparent));
  border: 1px solid color-mix(in srgb, var(--cat) 28%, transparent);
}
.mp-card-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.mp-card-title-row { display: flex; align-items: center; gap: 6px; min-width: 0; }
.mp-card-name {
  font-size: 15px; font-weight: 600; color: var(--text); letter-spacing: .1px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0;
}
.mp-card-desc {
  font-size: 12.5px; color: var(--text-3); line-height: 1.6; min-height: 2.6em;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  word-break: break-word; overflow-wrap: anywhere;
}
.mp-card-foot {
  display: flex; align-items: center; gap: 8px; min-width: 0;
  padding-top: 10px; border-top: 1px dashed var(--border-soft);
}
.mp-card-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; min-width: 0; }
.mp-pill {
  display: inline-flex; align-items: center; gap: 3px;
  height: 20px; padding: 0 7px; border-radius: 6px;
  font-size: 11px; line-height: 1; color: var(--text-3);
  background: var(--fill-0, rgba(127, 127, 127, .08));
  white-space: nowrap;
}
.mp-pill svg { opacity: .65; }
.mp-pill-cat {
  color: color-mix(in srgb, var(--cat) 78%, var(--text));
  background: color-mix(in srgb, var(--cat) 13%, transparent);
  font-weight: 500;
}
.mp-card-byline {
  margin-left: auto; font-size: 11px; color: var(--text-4);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0;
}
.mp-card-action { flex-shrink: 0; display: flex; align-items: center; }

/* 状态徽标 */
.mp-badge {
  display: inline-flex; align-items: center; gap: 3px; flex-shrink: 0;
  font-size: 10.5px; line-height: 1.7; padding: 0 6px; border-radius: 6px;
  color: var(--text-3); background: var(--fill-0);
}
.mp-badge.ok { color: var(--ok); background: color-mix(in srgb, var(--ok) 12%, transparent); }
.mp-badge.mine { color: var(--warn); background: color-mix(in srgb, var(--warn) 14%, transparent); }
.mp-badge.muted { color: var(--text-4); }

.mp-pager { margin-top: 16px; }

/* ---- 加载骨架 ---- */
.mp-skeleton { cursor: default; background: var(--surface); }
.mp-skeleton:hover { border-color: var(--border-soft); box-shadow: none; transform: none; }
.sk-icon { width: 48px; height: 48px; border-radius: 14px; background: var(--fill-0); flex-shrink: 0; }
.sk-lines { flex: 1; display: flex; flex-direction: column; gap: 8px; padding-top: 4px; }
.sk-line { height: 10px; border-radius: 5px; background: var(--fill-0); }
.sk-line.w30 { width: 30%; }
.sk-line.w40 { width: 40%; }
.sk-line.w60 { width: 60%; }
.sk-line.w90 { width: 90%; }
.sk-foot { padding-top: 10px; border-top: 1px dashed var(--border-soft); }
@keyframes sk-pulse { 0%, 100% { opacity: .55; } 50% { opacity: 1; } }
.mp-skeleton .sk-icon, .mp-skeleton .sk-line { animation: sk-pulse 1.4s ease-in-out infinite; }

/* 发布表单 */
.mp-import { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 12px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--accent-soft); }
.mp-import-label { font-size: 12px; color: var(--accent-text); }
.mp-import-chip { font-size: 12px; color: var(--text-2); background: var(--surface); border: 1px solid var(--border-soft); border-radius: 10px; padding: 2px 8px; cursor: pointer; }
.mp-import-chip.busy { opacity: .5; pointer-events: none; cursor: default; }
.mp-import-chip:hover { border-color: var(--accent-text); color: var(--accent-text); }
.mp-import-loading { font-size: 12px; color: var(--text-3); }
.pub-form { display: flex; flex-direction: column; gap: 18px; }
.pub-group { display: flex; flex-direction: column; gap: 10px; }
.pub-group-head { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.pub-group-title { font-size: 13px; font-weight: 600; color: var(--text); }
.pub-group-hint { font-size: 11px; color: var(--text-3); flex: 1; min-width: 0; }
.pub-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.icon-picker { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.icon-picker-label { font-size: 12px; color: var(--text-2); }
/* 图标格子样式已上收到 components/ui/IconPicker.vue */

/* 详情:封面区 + 左右分栏 */
.dt-cover {
  display: flex; align-items: flex-start; gap: 16px; padding: 18px;
  margin-bottom: 14px; border-radius: 14px;
  background: var(--surface); border: 1px solid var(--border-soft);
}
.dt-icon {
  width: 64px; height: 64px; flex-shrink: 0; border-radius: 16px;
  display: flex; align-items: center; justify-content: center; font-size: 30px;
  background: var(--accent-soft); border: 1px solid var(--border-soft);
}
.dt-head-info { flex: 1; min-width: 0; }
.dt-title { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.dt-name { font-size: 19px; font-weight: 700; color: var(--text); }
.dt-desc { font-size: 13px; color: var(--text-2); line-height: 1.6; margin-bottom: 8px; word-break: break-word; overflow-wrap: anywhere; }
.dt-meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12px; color: var(--text-3); }
.dt-dot { color: var(--text-4); }
.dt-actions { display: flex; gap: 8px; flex-shrink: 0; margin-left: auto; }
.dt-body { display: flex; gap: 14px; flex: 1; min-height: 0; }
.dt-tree {
  width: 260px; flex-shrink: 0; border: 1px solid var(--border-soft); border-radius: 10px;
  background: var(--surface); padding: 8px 0; overflow-y: auto;
}
.dt-view {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  border: 1px solid var(--border-soft); border-radius: 10px;
  background: var(--bg-elev); overflow: hidden;
}
.dt-view-head {
  display: flex; align-items: center; gap: 8px;
  padding: 8px 12px; font-size: 12px; color: var(--text-3);
  border-bottom: 1px solid var(--border-soft);
  font-family: var(--font-mono, ui-monospace, monospace);
}
.dt-file { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dt-copy {
  display: inline-flex; align-items: center; gap: 4px; flex-shrink: 0;
  padding: 2px 8px; border: 1px solid var(--border-soft); border-radius: 6px;
  background: transparent; color: var(--text-3); font-size: 11px; font-family: inherit; cursor: pointer;
}
.dt-copy:hover { color: var(--accent-text); border-color: var(--accent); }
.dt-editor { flex: 1; min-height: 0; }
.dt-editor :deep(.cm-editor) { height: 100%; }
.dt-editor :deep(.cm-scroller) { overflow: auto; }

/* ===== 整页模式(manage-page) ===== */
.manage-page {
  flex: 1; min-width: 0;
  display: flex; flex-direction: column; height: 100%; min-height: 0; overflow-y: auto;
  padding: 18px 24px 32px; background: var(--bg); box-sizing: border-box;
}
.manage-crumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); margin-bottom: 12px; }
.crumb-link { background: none; border: none; padding: 0; color: var(--text-3); cursor: pointer; font-size: 12px; }
.crumb-link:hover { color: var(--accent-text); }
.crumb-sep { color: var(--text-4); }
.crumb-cur { color: var(--text-2); }
.manage-header { margin-bottom: 18px; }
.manage-title { font-size: 20px; font-weight: 700; color: var(--text); margin-bottom: 4px; }
.manage-sub { font-size: 13px; color: var(--text-3); }

/* 整页模式:内容区滚动,分页贴底(避免分页悬在页面中部) */
.manage-page { overflow: hidden; }
.manage-page .mp-body { flex: 1; min-height: 0; overflow-y: auto; }
.manage-page .mp-pager { flex-shrink: 0; margin-top: 14px; padding-bottom: 2px; }

/* 上传技能弹窗 */
.up-drop {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px;
  height: 200px; border: 1.5px dashed var(--border); border-radius: var(--radius);
  background: var(--bg-elev); color: var(--text-3); cursor: pointer;
  transition: border-color .15s, background .15s, color .15s;
}
.up-drop:hover { border-color: var(--accent); background: var(--fill-0); color: var(--accent-text); }
.up-drop.busy { cursor: default; }
.up-drop-title { font-size: 13px; }
.up-spinner {
  width: 22px; height: 22px; border: 2px solid var(--border); border-top-color: var(--accent);
  border-radius: 50%; animation: up-spin .8s linear infinite;
}
@keyframes up-spin { to { transform: rotate(360deg); } }
.up-tips { margin: 12px 0 0; padding-left: 18px; color: var(--text-3); font-size: 12px; line-height: 1.9; }
.up-tips code { background: var(--fill-0); padding: 1px 5px; border-radius: 4px; font-size: 11px; }

/* ===== 技能详情(整页 + 文件树) ===== */
.dt-page { display: flex; flex-direction: column; flex: 1; min-height: 0; }
.dt-page .dt-head { align-items: flex-start; }
.dt-actions { display: flex; gap: 8px; flex-shrink: 0; margin-left: auto; }
.dt-body { display: flex; gap: 14px; flex: 1; min-height: 0; margin-top: 12px; }
.dt-tree {
  width: 260px; flex-shrink: 0; border: 1px solid var(--border-soft); border-radius: var(--radius-sm);
  background: var(--surface); padding: 8px 0; overflow-y: auto;
}
.dt-tree-title { font-size: 12px; color: var(--text-3); padding: 0 12px 8px; }
.dt-tree-item {
  display: flex; align-items: center; gap: 6px; padding: 5px 12px;
  font-size: 13px; color: var(--text-2); cursor: pointer; white-space: nowrap;
}
.dt-tree-item:hover { background: var(--fill-0); color: var(--text); }
.dt-tree-item.active { background: var(--accent-soft); color: var(--accent-text); }
.dt-tree-item.dir { color: var(--text-3); cursor: default; }
.dt-tree-item.dir:hover { background: transparent; color: var(--text-3); }
.dt-tree-ico { flex-shrink: 0; }
.dt-tree-name { overflow: hidden; text-overflow: ellipsis; }
.dt-view {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  border: 1px solid var(--border-soft); border-radius: var(--radius-sm);
  background: var(--bg-elev); overflow: hidden;
}
.dt-view-head {
  padding: 8px 12px; font-size: 12px; color: var(--text-3);
  border-bottom: 1px solid var(--border-soft); font-family: var(--font-mono, monospace);
}
.dt-view .dt-content { flex: 1; margin: 0; border-radius: 0; max-height: none; }
</style>
