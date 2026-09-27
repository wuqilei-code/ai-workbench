<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage, NModal } from 'naive-ui'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import { SKILL_CATEGORIES, skillCategoryInfo } from '@/types'
import type { MarketplaceSkillVo, UserSkill, FileNode, SkillFileVo } from '@/types'
import SkillEditor from '@/components/SkillEditor.vue'
import SectionTabs from '@/components/ui/SectionTabs.vue'
import FilterChips from '@/components/ui/FilterChips.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ListPager from '@/components/ui/ListPager.vue'
import IconPicker from '@/components/ui/IconPicker.vue'

const { t } = useI18n()
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

const catTabs = computed(() => [
  { key: 'all', label: t('market.all'), emoji: '🧩' },
  ...SKILL_CATEGORIES,
])
/** 分类筛选 chips(带各分类计数) */
const catChips = computed(() => catTabs.value.map((c) => ({ ...c, count: catCount(c.key) })))
/** 顶部 Tab 项(带「我的」计数) */
const topTabs = computed(() => [
  { key: 'market' as const, label: t('market.tabMarket') },
  { key: 'mine' as const, label: t('market.tabMine'), count: mineCount.value },
])
const catOptions = computed(() =>
  SKILL_CATEGORIES.map((c) => ({ label: `${c.emoji} ${c.label}`, value: c.key })),
)
/** 市场标签下各分类技能数 */
function catCount(key: string) {
  if (key === 'all') return list.value.length
  return list.value.filter((s) => s.category === key).length
}
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

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const pool = topTab.value === 'mine' ? myItems.value : list.value
  return pool.filter((s) => {
    if (topTab.value === 'market' && activeCat.value !== 'all' && s.category !== activeCat.value) {
      return false
    }
    if (!q) return true
    return s.name.toLowerCase().includes(q) || (s.description || '').toLowerCase().includes(q)
  })
})

/** 分页:两个 Tab 各自的数据源与切片分开,避免模板里出现联合类型 */
const page = ref(1)
const pageSize = 18
const pagedMine = computed(() => {
  const start = (page.value - 1) * pageSize
  return myItems.value.slice(start, start + pageSize)
})
const pagedMarket = computed(() => {
  const start = (page.value - 1) * pageSize
  return list.value.slice(start, start + pageSize)
})
function resetPage() {
  page.value = 1
}

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

/** 列表卡片显示技能结构摘要:如 "SKILL.md + 2 文件" */
function fileSummary(s: MarketplaceSkillVo): string {
  if (!s.files || !s.files.length) return t('market.onlySkillMd')
  return t('market.filesLabel', { n: s.files.length })
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

function fmtTime(ts: string): string {
  if (!ts) return ''
  return ts.slice(0, 16).replace('T', ' ')
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
  <component :is="page ? 'div' : NModal" v-bind="rootBind">
    <!-- 整页模式:面包屑 + 页头 -->
    <div v-if="page && !detailVisible" class="manage-crumb">
      <button class="crumb-link" @click="emit('close')">首页</button>
      <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ t('market.title') }}</span>
    </div>
    <div v-if="page && !detailVisible" class="manage-header">
    <div class="manage-title">{{ t('market.title') }}</div>
    <div class="manage-sub">{{ t('market.sub') }}</div>
    </div>

    <!-- 技能详情(整页 + 文件树) -->
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
          <div class="dt-head">
            <span class="dt-icon">{{ skillIcon(detail) }}</span>
            <div class="dt-head-info">
              <div class="dt-title">
                <span class="dt-name">{{ detail.name }}</span>
                <n-tag size="tiny" :bordered="false">{{ skillCategoryInfo(detail.category).emoji }} {{ skillCategoryInfo(detail.category).label }}</n-tag>
        <n-tag v-if="detail.installed" size="tiny" type="success" :bordered="false">{{ t('market.installed') }}</n-tag>
              </div>
              <div class="dt-sub">
      <n-tag size="tiny" :bordered="false">by {{ detail.authorName || t('market.official') }}</n-tag>
      <span class="dt-installs">{{ t('market.installs', { n: detail.installs }) }}</span>
              </div>
            </div>
            <div class="dt-actions">
      <n-button v-if="!detail.installed" size="small" type="primary" :loading="installing" @click="install">{{ t('market.installAndUse') }}</n-button>
      <n-button v-if="detail.mine" size="small" type="error" quaternary @click="removeOwn(detail)">{{ t('market.unpublish') }}</n-button>
            </div>
          </div>
          <div class="dt-desc">{{ detail.description }}</div>
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
              <div class="dt-view-head">{{ activeFile }}</div>
              <pre class="dt-content">{{ activeContent }}</pre>
            </div>
          </div>
        </template>
      </n-spin>
    </div>

    <template v-if="!detailVisible">
    <div class="mp-head">
      <n-input v-model:value="keyword" size="small" clearable :placeholder="t('market.searchPlaceholder')" style="max-width: 240px" @update:value="resetPage">
        <template #prefix>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </template>
      </n-input>
    <p class="mp-note">{{ t('market.note') }}</p>
      <n-button size="small" type="primary" @click="openUpload">{{ t('market.upload') }}</n-button>
    </div>

    <!-- 顶部标签:市场 / 我的(统一下划线 Tab 组件) -->
    <SectionTabs
      v-model:active="topTab"
      :items="topTabs"
      class="mp-toptabs"
      :aria-label="t('market.ariaTopTabs')"
      @change="resetPage()"
    />

    <!-- 市场:分类筛选(统一胶囊 chips 组件) -->
    <FilterChips
      v-if="topTab === 'market'"
      v-model:active="activeCat"
      :items="catChips"
      class="mp-cats"
      :aria-label="t('market.ariaCatFilter')"
      @change="resetPage()"
    />

    <n-spin :show="loading" class="mp-body">
    <EmptyState v-if="!filtered.length" :text="topTab === 'mine' ? t('market.mineEmpty') : t('market.empty')" :padding="48" />
      <div v-else class="mp-grid">
        <!-- 「我的」Tab:本地技能库条目(已发布的可下架,未发布的给发布入口) -->
        <div v-if="topTab === 'mine'" v-for="s in pagedMine" :key="s.name" class="mp-card">
          <div class="mp-card-icon" :title="skillCategoryInfo(s.category).label">{{ s.icon }}</div>
          <div class="mp-card-body">
            <div class="mp-card-title-row">
              <span class="mp-card-name">{{ s.name }}</span>
              <n-tag v-if="s.source === 'installed'" size="tiny" type="success" :bordered="false">{{ t('market.installed') }}</n-tag>
              <n-tag v-else size="tiny" :bordered="false">{{ t('market.ownTag') }}</n-tag>
              <n-tag v-if="s.visibility === 1" size="tiny" type="warning" :bordered="false">{{ t('market.mineTag') }}</n-tag>
            </div>
            <div class="mp-card-desc">{{ s.description }}</div>
            <div class="mp-card-meta">
              <span class="mp-cat">{{ skillCategoryInfo(s.category).emoji }} {{ skillCategoryInfo(s.category).label }}</span>
              <span class="mp-files">📎{{ t('market.filesLabel', { n: s.files }) }}</span>
              <span v-if="s.inMarket" class="mp-installs">↓ {{ s.installs }}</span>
              <span class="mp-author">{{ s.inMarket ? t('market.inMarket') : t('market.notInMarket') }}</span>
            </div>
          </div>
          <div class="mp-actions" @click.stop>
            <n-button v-if="!s.inMarket" size="small" type="primary" secondary @click="publishFromMine(s)">{{ t('market.publishNow') }}</n-button>
            <n-button v-else-if="s.mine" size="small" type="error" quaternary :loading="rowBusyId === s.marketId" :disabled="rowBusyId !== null" @click="removeOwnById(s.marketId!)">{{ t('market.unpublish') }}</n-button>
            <n-button v-else size="small" quaternary disabled>{{ t('market.installed') }}</n-button>
          </div>
        </div>
        <!-- 「市场」Tab:市场表条目 -->
        <div v-else v-for="s in pagedMarket" :key="s.id" class="mp-card" @click="openDetail(s)">
          <div class="mp-card-icon" :title="skillCategoryInfo(s.category).label">
            {{ skillIcon(s) }}
          </div>
          <div class="mp-card-body">
            <div class="mp-card-title-row">
              <span class="mp-card-name">{{ s.name }}</span>
        <n-tag v-if="s.mine" size="tiny" type="warning" :bordered="false">{{ t('market.mineTag') }}</n-tag>
        <n-tag v-if="s.installed" size="tiny" type="success" :bordered="false">{{ t('market.installed') }}</n-tag>
            </div>
            <div class="mp-card-desc">{{ s.description }}</div>
            <div class="mp-card-meta">
              <span class="mp-cat">{{ skillCategoryInfo(s.category).emoji }} {{ skillCategoryInfo(s.category).label }}</span>
              <span class="mp-files">📎{{ fileSummary(s) }}</span>
              <span class="mp-installs">↓ {{ s.installs }}</span>
        <span class="mp-author">by {{ s.authorName || t('market.official') }}</span>
            </div>
          </div>
          <div class="mp-actions" @click.stop>
        <n-button v-if="!s.installed" size="small" type="primary" secondary :loading="rowBusyId === s.id" :disabled="rowBusyId !== null" @click="installDirect(s)">{{ t('tpl.install') }}</n-button>
        <n-button v-if="s.mine" size="small" type="error" quaternary :loading="rowBusyId === s.id" :disabled="rowBusyId !== null" @click="removeOwn(s)">{{ t('market.unpublish') }}</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <div v-if="filtered.length > pageSize" class="mp-pager">
      <ListPager :page="page" :item-count="filtered.length" :page-size="pageSize" @update:page="page = $event" />
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
    title="t('market.importTitle')"
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
        <n-input v-model:value="pubName" maxlength="64" size="small" :placeholder="t('market.pubNamePlaceholder')" />
        <n-input v-model:value="pubDesc" maxlength="1024" size="small" :placeholder="t('market.pubDescPlaceholder')" />
        <div class="pub-row">
          <n-select
            v-model:value="pubCategory"
            size="small"
            :options="catOptions"
        placeholder="t('market.pickIcon')"
            style="flex: 1"
          />
        </div>
        <div class="icon-picker">
        <span class="icon-picker-label">{{ t('market.iconLabel') }}</span>
          <IconPicker v-model="pubIcon" :size="30" :title="t('market.iconLabel')" />
        </div>
        <div class="content-head">
      <span class="content-title">{{ t('market.contentTitle') }}</span>
      <n-button size="tiny" type="primary" quaternary :disabled="!pubName.trim() || !pubDesc.trim()" @click="generateTemplate">{{ t('market.genTemplate') }}</n-button>
      <span class="files-note-v2">{{ t('market.filesNote') }}</span>
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
.mp-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.mp-note {
  font-size: 12px; color: var(--text-3); flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* Tab 条 / 分类 chips / 空态 / 分页器样式已上收到 components/ui/*,此处只保留面板级间距 */
.mp-toptabs { margin-bottom: 14px; }
.mp-cats { margin-bottom: 14px; }

/* 内容区固定高度:数据未加载前也维持网格高度,避免弹窗先矮后高 */
.mp-body { display: block; min-height: 52vh; }

/* 卡片网格 */
.mp-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px; max-height: 52vh; overflow-y: auto; padding: 2px;
}
.mp-card {
  display: flex; gap: 12px; align-items: flex-start;
  padding: 12px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
  cursor: pointer; transition: border-color .15s, box-shadow .15s;
}
.mp-card:hover { border-color: var(--accent); box-shadow: 0 2px 10px rgba(0,0,0,.08); }
.mp-card-icon {
  width: 46px; height: 46px; flex-shrink: 0; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  font-size: 24px; background: var(--accent-soft); border: 1px solid var(--border-soft);
}
.mp-card-body { flex: 1; min-width: 0; }
.mp-card-title-row { display: flex; align-items: center; gap: 5px; margin-bottom: 4px; flex-wrap: wrap; }
.mp-card-name { font-size: 14px; font-weight: 600; color: var(--text); }
.mp-card-desc {
  font-size: 12px; color: var(--text-2); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  word-break: break-word;
  overflow-wrap: anywhere;
}
.mp-card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 11px; color: var(--text-3); flex-wrap: wrap; }
.mp-cat { color: var(--accent-text); }
.mp-installs { white-space: nowrap; }
.mp-author { margin-left: auto; }
.mp-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.mp-pager { flex-shrink: 0; margin-top: 14px; padding-bottom: 2px; }

/* 发布表单 */
.mp-import { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 12px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--accent-soft); }
.mp-import-label { font-size: 12px; color: var(--accent-text); }
.mp-import-chip { font-size: 12px; color: var(--text-2); background: var(--surface); border: 1px solid var(--border-soft); border-radius: 10px; padding: 2px 8px; cursor: pointer; }
.mp-import-chip.busy { opacity: .5; pointer-events: none; cursor: default; }
.mp-import-chip:hover { border-color: var(--accent-text); color: var(--accent-text); }
.mp-import-loading { font-size: 12px; color: var(--text-3); }
.pub-form { display: flex; flex-direction: column; gap: 10px; }
.pub-row { display: flex; gap: 10px; }
.icon-picker { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.icon-picker-label { font-size: 12px; color: var(--text-2); }
/* 图标格子样式已上收到 components/ui/IconPicker.vue */

/* 附加文件编辑 */
.content-head { display: flex; align-items: center; gap: 8px; margin: 14px 0 8px; flex-wrap: wrap; }
.content-title { font-size: 13px; font-weight: 600; color: var(--text); }
.files-note-v2 { font-size: 11px; color: var(--text-3); flex: 1; min-width: 0; }

/* 详情 */
.dt-head { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; }
.dt-icon {
  width: 60px; height: 60px; flex-shrink: 0; border-radius: 14px;
  display: flex; align-items: center; justify-content: center; font-size: 32px;
  background: var(--accent-soft); border: 1px solid var(--border-soft);
}
.dt-head-info { flex: 1; min-width: 0; }
.dt-title { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.dt-name { font-size: 18px; font-weight: 700; color: var(--text); }
.dt-sub { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.dt-installs { font-size: 12px; color: var(--text-3); }
.dt-desc { font-size: 13px; color: var(--text-2); margin-bottom: 10px; line-height: 1.6; word-break: break-word; overflow-wrap: anywhere; }
.dt-structure { border: 1px solid var(--border-soft); border-radius: var(--radius-sm); background: var(--surface); }
.dt-tabs { padding: 0 10px; }
.dt-content {
  background: var(--bg-elev); border-radius: 0 0 var(--radius-sm) var(--radius-sm);
  padding: 12px; font-size: 12px; line-height: 1.6; max-height: 38vh; overflow: auto;
  white-space: pre-wrap; word-break: break-word; color: var(--text-2); margin: 0;
}

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
