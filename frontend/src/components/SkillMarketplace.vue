<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import { SKILL_CATEGORIES, skillCategoryInfo } from '@/types'
import type { MarketplaceSkillVo, FileNode, SkillFileVo } from '@/types'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'installed'): void
}>()

const msg = useMessage()
const list = ref<MarketplaceSkillVo[]>([])
const loading = ref(false)
const keyword = ref('')
const activeCat = ref('all')

const catTabs = computed(() => [
  { key: 'all', label: '全部', emoji: '🌟' },
  ...SKILL_CATEGORIES,
])
const catOptions = computed(() =>
  SKILL_CATEGORIES.map((c) => ({ label: `${c.emoji} ${c.label}`, value: c.key })),
)
/** 选中分类下的技能数 */
function catCount(key: string) {
  if (key === 'all') return list.value.length
  return list.value.filter((s) => s.category === key).length
}

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return list.value.filter((s) => {
    if (activeCat.value !== 'all' && s.category !== activeCat.value) return false
    if (!q) return true
    return s.name.toLowerCase().includes(q) || s.description.toLowerCase().includes(q)
  })
})

/** 分页 */
const page = ref(1)
const pageSize = 9
const pagedSkills = computed(() => {
  const start = (page.value - 1) * pageSize
  return filtered.value.slice(start, start + pageSize)
})
function resetPage() {
  page.value = 1
}

async function load() {
  loading.value = true
  try {
    list.value = await chatApi.listMarketplaceSkills()
  } catch (e: any) {
    msg.error(e?.message || '加载技能广场失败')
  } finally {
    loading.value = false
  }
}
function onShow() {
  if (props.show) load()
}

/** 列表卡片显示技能结构摘要:如 "SKILL.md + 2 文件" */
function fileSummary(s: MarketplaceSkillVo): string {
  if (!s.files || !s.files.length) return '仅 SKILL.md'
  return `SKILL.md + ${s.files.length} 附加文件`
}

// ---------- 发布 ----------
const ICON_PRESETS = ['🤖', '⚙️', '📊', '📄', '✍️', '🗂️', '💻', '🧩', '📚', '🎨', '🔬', '🚀', '🛠️', '📝', '🗣️', '🧠']
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
    msg.error(e?.message || '读取工作区技能失败')
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
    // 递归读取附加文件(SKILL.md 之外的全部)
    pubFiles.value = await collectFiles(root)
    msg.success(`已导入技能「${name}」,共 ${pubFiles.value.length} 个附加文件`)
  } catch (e: any) {
    msg.error(e?.message || '导入失败')
  } finally {
    importingSkill.value = false
  }
}

function addFile() {
  pubFiles.value.push({ path: '', content: '' })
}
function removeFile(i: number) {
  pubFiles.value.splice(i, 1)
}

/** 根据技能名/描述生成 SKILL.md 骨架,预填内容区,减少手写 frontmatter 的负担。 */
function generateTemplate() {
  const name = (pubName.value || '').trim().toLowerCase().replace(/[^a-z0-9-]/g, '-')
  const desc = pubDesc.value.trim()
  if (!name) {
    msg.warning('请先填写技能名称')
    return
  }
  if (!desc) {
    msg.warning('请先填写技能描述')
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

(在此描述技能的目标:用户何时会用到它、它解决什么问题。Use when ...)

## 使用场景

- 

## 执行步骤

1. 

## 注意事项

- 
`
  msg.success('已生成 SKILL.md 模板,请完善正文')
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

const validFiles = computed(() => pubFiles.value.filter((f) => f.path?.trim() && f.content != null))
const publishDisabled = computed(
  () => !pubName.value.trim() || !pubDesc.value.trim() || !pubContent.value.trim(),
)

async function submitPublish() {
  if (publishDisabled.value) {
    msg.warning('请填写技能名、描述和 SKILL.md 内容')
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
      files: validFiles.value.map((f) => ({ path: f.path.trim().replace(/\\/g, '/').replace(/^\/+/, ''), content: f.content })),
    })
    msg.success('已发布到技能广场')
    publishVisible.value = false
    await load()
  } catch (e: any) {
    msg.error(e?.message || '发布失败')
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
    msg.error(e?.message || '加载详情失败')
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
    msg.success(`技能「${detail.value.name}」已安装到工作区,可在「技能」页查看`)
    detailVisible.value = false
    emit('installed')
    await load()
  } catch (e: any) {
    msg.error(e?.message || '安装失败')
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
    msg.success(`技能「${s.name}」已安装到工作区`)
    emit('installed')
    await load()
  } catch (e: any) {
    msg.error(e?.message || '安装失败')
  } finally {
    rowBusyId.value = null
  }
}

async function removeOwn(s: MarketplaceSkillVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = s.id
  try {
    await chatApi.deleteMarketplaceSkill(s.id)
    msg.success('已下架')
    await load()
  } catch (e: any) {
    msg.error(e?.message || '删除失败')
  } finally {
    rowBusyId.value = null
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
  if (props.show) load()
})
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="技能广场"
    style="width: 1080px; max-width: 96vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onShow"
  >
    <div class="mp-head">
      <n-input v-model:value="keyword" size="small" clearable placeholder="搜索技能名或描述…" style="max-width: 240px" @update:value="resetPage">
        <template #prefix>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </template>
      </n-input>
      <span class="mp-note">技能 = SKILL.md(指令)+ 可选脚本/数据/参考文件;发布后他人一键安装到工作区</span>
      <n-button size="small" type="primary" @click="openPublish">+ 发布技能</n-button>
    </div>

    <div class="mp-tabs">
      <button
        v-for="c in catTabs"
        :key="c.key"
        class="mp-tab"
        :class="{ active: activeCat === c.key }"
        @click="activeCat = c.key; resetPage()"
      >
        <span class="mp-tab-emoji">{{ c.emoji }}</span>{{ c.label }}
        <span class="mp-tab-count">{{ catCount(c.key) }}</span>
      </button>
    </div>

    <n-spin :show="loading" class="mp-body">
      <div v-if="!filtered.length" class="mp-empty">暂无匹配的技能,发布第一个吧</div>
      <div v-else class="mp-grid">
        <div v-for="s in pagedSkills" :key="s.id" class="mp-card" @click="openDetail(s)">
          <div class="mp-card-icon" :title="skillCategoryInfo(s.category).label">
            {{ skillIcon(s) }}
          </div>
          <div class="mp-card-body">
            <div class="mp-card-title-row">
              <span class="mp-card-name">{{ s.name }}</span>
              <n-tag v-if="s.mine" size="tiny" type="warning" :bordered="false">我的</n-tag>
              <n-tag v-if="s.installed" size="tiny" type="success" :bordered="false">已安装</n-tag>
            </div>
            <div class="mp-card-desc">{{ s.description }}</div>
            <div class="mp-card-meta">
              <span class="mp-cat">{{ skillCategoryInfo(s.category).emoji }} {{ skillCategoryInfo(s.category).label }}</span>
              <span class="mp-files">📎{{ fileSummary(s) }}</span>
              <span class="mp-installs">↓ {{ s.installs }}</span>
              <span class="mp-author">by {{ s.authorName || '匿名' }}</span>
            </div>
          </div>
          <div class="mp-actions" @click.stop>
            <n-button v-if="!s.installed" size="small" type="primary" secondary :loading="rowBusyId === s.id" :disabled="rowBusyId !== null" @click="installDirect(s)">安装</n-button>
            <n-button v-if="s.mine" size="small" type="error" quaternary :loading="rowBusyId === s.id" :disabled="rowBusyId !== null" @click="removeOwn(s)">下架</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <div v-if="filtered.length > pageSize" class="mp-pager">
      <n-pagination v-model:page="page" :page-size="pageSize" :item-count="filtered.length" size="small" />
    </div>

    <!-- 发布弹窗 -->
    <n-modal
      v-model:show="publishVisible"
      preset="card"
      title="发布技能到广场"
      style="width: 760px; max-width: 96vw"
      :bordered="false"
    >
      <div v-if="importSkills.length || importLoading" class="mp-import">
        <span class="mp-import-label">从工作区导入(含脚本/参考文件):</span>
        <span
          v-for="s in importSkills"
          :key="s.path"
          class="mp-import-chip"
          :class="{ busy: importingSkill }"
          @click="importSkill(s.name)"
        >{{ s.name }}</span>
        <span v-if="importLoading" class="mp-import-loading">加载中…</span>
        <span v-else-if="importingSkill" class="mp-import-loading">导入中…</span>
      </div>
      <div class="pub-form">
        <n-input v-model:value="pubName" maxlength="64" size="small" placeholder="技能名(小写字母/数字,连字符分隔,如 data-cleaner)" />
        <n-input v-model:value="pubDesc" maxlength="1024" size="small" placeholder="一句话描述(他人据此判断用途)" />
        <div class="pub-row">
          <n-select
            v-model:value="pubCategory"
            size="small"
            :options="catOptions"
            placeholder="选择分类"
            style="flex: 1"
          />
        </div>
        <div class="icon-picker">
          <span class="icon-picker-label">图标:</span>
          <span
            v-for="e in ICON_PRESETS"
            :key="e"
            class="icon-choice"
            :class="{ active: pubIcon === e }"
            @click="pubIcon = e"
          >{{ e }}</span>
        </div>
        <div class="content-head">
          <span class="content-title">SKILL.md 内容</span>
          <n-button size="tiny" type="primary" quaternary :disabled="!pubName.trim() || !pubDesc.trim()" @click="generateTemplate">✨ 生成模板</n-button>
        </div>
        <n-input
          v-model:value="pubContent"
          type="textarea"
          :rows="6"
          maxlength="60000"
          show-count
          size="small"
          placeholder="SKILL.md 全文(需包含 ---frontmatter--- 的 name 与 description)"
        />
      </div>

      <div class="files-head">
        <span class="files-title">附加文件(可选)</span>
        <span class="files-note">脚本/模板/参考数据等。安装了别人的技能把整目录写到你工作区。</span>
        <n-button size="tiny" quaternary type="primary" @click="addFile">+ 添加文件</n-button>
      </div>
      <div v-if="pubFiles.length" class="files-list">
        <div v-for="(f, i) in pubFiles" :key="i" class="file-row">
          <n-input v-model:value="f.path" size="small" placeholder="相对路径,如 scripts/clean.py" style="flex: 0 0 260px" />
          <n-button size="tiny" type="error" quaternary @click="removeFile(i)">删除</n-button>
          <n-input v-model:value="f.content" type="textarea" :rows="4" size="small" placeholder="文件内容" />
        </div>
      </div>
      <div v-if="!pubFiles.length" class="files-empty">未添加附加文件(该技能将只有 SKILL.md)。</div>

      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button @click="publishVisible = false">取消</n-button>
          <n-button type="primary" :loading="pubSaving" :disabled="publishDisabled" @click="submitPublish">发布</n-button>
        </div>
      </template>
    </n-modal>

    <!-- 详情弹窗 -->
    <n-modal
      v-model:show="detailVisible"
      preset="card"
      :title="detail?.name ? `技能 · ${detail.name}` : '技能详情'"
      style="width: 860px; max-width: 96vw"
      :bordered="false"
    >
      <n-spin :show="detailLoading">
        <template v-if="detail">
          <div class="dt-head">
            <span class="dt-icon">{{ skillIcon(detail) }}</span>
            <div class="dt-head-info">
              <div class="dt-title">
                <span class="dt-name">{{ detail.name }}</span>
                <n-tag size="tiny" :bordered="false">{{ skillCategoryInfo(detail.category).emoji }} {{ skillCategoryInfo(detail.category).label }}</n-tag>
              </div>
              <div class="dt-sub">
                <n-tag size="tiny" :bordered="false">by {{ detail.authorName || '匿名' }}</n-tag>
                <n-tag v-if="detail.installed" size="tiny" type="success" :bordered="false">已安装</n-tag>
                <span class="dt-installs">↓ {{ detail.installs }} 次安装</span>
              </div>
            </div>
          </div>
          <div class="dt-desc">{{ detail.description }}</div>

          <div class="dt-structure">
            <n-tabs
              v-model:value="activeFile"
              size="small"
              type="line"
              class="dt-tabs"
            >
              <n-tab-pane name="SKILL.md" tab="SKILL.md">
                <pre class="dt-content">{{ activeContent }}</pre>
              </n-tab-pane>
              <n-tab-pane
                v-for="f in (detail.files || [])"
                :key="f.path"
                :name="f.path"
                :tab="getFileIcon(f.path) + ' ' + f.path"
              >
                <pre class="dt-content">{{ f.content }}</pre>
              </n-tab-pane>
            </n-tabs>
          </div>
        </template>
      </n-spin>

      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button @click="detailVisible = false">关闭</n-button>
          <n-button v-if="detail && !detail.installed" type="primary" :loading="installing" @click="install">安装到工作区</n-button>
        </div>
      </template>
    </n-modal>
  </n-modal>
</template>

<style scoped>
.mp-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.mp-note { font-size: 12px; color: var(--text-3); flex: 1; min-width: 0; }

/* 分类标签 */
.mp-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 14px; }
.mp-tab {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 12px; border-radius: 16px; border: 1px solid var(--border-soft);
  background: var(--surface); color: var(--text-2); font-size: 12px; cursor: pointer;
  transition: all .15s;
}
.mp-tab:hover { border-color: var(--accent-text); color: var(--text); }
.mp-tab.active { background: var(--accent-soft); border-color: var(--accent-text); color: var(--accent-text); font-weight: 600; }
.mp-tab-emoji { font-size: 14px; }
.mp-tab-count { font-size: 11px; opacity: .7; }

.mp-empty { color: var(--text-3); font-size: 13px; padding: 48px 0; text-align: center; }

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
}
.mp-card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 11px; color: var(--text-3); flex-wrap: wrap; }
.mp-cat { color: var(--accent-text); }
.mp-installs { white-space: nowrap; }
.mp-author { margin-left: auto; }
.mp-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }
.mp-pager { display: flex; justify-content: center; padding: 10px 0 2px; }

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
.icon-choice {
  width: 30px; height: 30px; display: inline-flex; align-items: center; justify-content: center;
  font-size: 16px; border-radius: 8px; border: 1px solid var(--border-soft); cursor: pointer;
  background: var(--surface); transition: all .15s;
}
.icon-choice:hover { border-color: var(--accent-text); }
.icon-choice.active { background: var(--accent-soft); border-color: var(--accent-text); }

/* 附加文件编辑 */
.files-head { display: flex; align-items: center; gap: 8px; margin: 14px 0 8px; }
.content-head { display: flex; align-items: center; gap: 8px; margin: 14px 0 8px; }
.content-title { font-size: 13px; font-weight: 600; color: var(--text); }
.files-title { font-size: 13px; font-weight: 600; color: var(--text); }
.files-note { font-size: 11px; color: var(--text-3); flex: 1; min-width: 0; }
.files-list { display: flex; flex-direction: column; gap: 8px; max-height: 34vh; overflow-y: auto; padding-right: 2px; }
.file-row {
  display: flex; align-items: flex-start; gap: 8px; flex-wrap: wrap;
  padding: 8px; border: 1px solid var(--border-soft); border-radius: var(--radius-sm); background: var(--surface);
}
.file-row .n-input { flex: 1 1 100%; }
.file-row > .n-input { flex: 0 0 260px; }
.file-row .file-row-path { flex: 0 0 260px; }
.files-empty { font-size: 12px; color: var(--text-3); padding: 6px 0; }

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
.dt-desc { font-size: 13px; color: var(--text-2); margin-bottom: 10px; line-height: 1.6; }
.dt-structure { border: 1px solid var(--border-soft); border-radius: var(--radius-sm); background: var(--surface); }
.dt-tabs { padding: 0 10px; }
.dt-content {
  background: var(--bg-elev); border-radius: 0 0 var(--radius-sm) var(--radius-sm);
  padding: 12px; font-size: 12px; line-height: 1.6; max-height: 38vh; overflow: auto;
  white-space: pre-wrap; word-break: break-word; color: var(--text-2); margin: 0;
}
</style>