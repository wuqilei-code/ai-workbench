<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
import { SKILL_CATEGORIES, skillCategoryInfo } from '@/types'
import type { MarketplaceSkillVo, FileNode } from '@/types'

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

// ---------- 发布 ----------
const ICON_PRESETS = ['🤖', '⚙️', '📊', '📄', '✍️', '🗂️', '💻', '🧩', '📚', '🎨', '🔬', '🚀', '🛠️', '📝', '🗣️', '🧠']
const publishVisible = ref(false)
const pubName = ref('')
const pubDesc = ref('')
const pubCategory = ref('other')
const pubIcon = ref('🤖')
const pubContent = ref('')
const pubSaving = ref(false)
const importSkills = ref<FileNode[]>([])
const importLoading = ref(false)

/** 导入自己工作区已存在的技能(读取 .opencode/skills/<name>/SKILL.md 预填) */
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
async function importSkill(name: string) {
  try {
    const md = await fileApi.content(`.opencode/skills/${name}/SKILL.md`)
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
    msg.success(`已导入技能「${name}」`)
  } catch (e: any) {
    msg.error(e?.message || '导入失败')
  }
}

function openPublish() {
  pubName.value = ''
  pubDesc.value = ''
  pubCategory.value = 'other'
  pubIcon.value = '🤖'
  pubContent.value = ''
  publishVisible.value = true
  loadImportable()
}

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

async function openDetail(s: MarketplaceSkillVo) {
  detail.value = null
  detailVisible.value = true
  detailLoading.value = true
  try {
    detail.value = await chatApi.marketplaceSkillDetail(s.id)
  } catch (e: any) {
    msg.error(e?.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
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
  try {
    await chatApi.installSkill(s.id)
    msg.success(`技能「${s.name}」已安装到工作区`)
    emit('installed')
    await load()
  } catch (e: any) {
    msg.error(e?.message || '安装失败')
  }
}

async function removeOwn(s: MarketplaceSkillVo) {
  try {
    await chatApi.deleteMarketplaceSkill(s.id)
    msg.success('已下架')
    await load()
  } catch (e: any) {
    msg.error(e?.message || '删除失败')
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
    style="width: 980px; max-width: 96vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onShow"
  >
    <div class="mp-head">
      <n-input v-model:value="keyword" size="small" clearable placeholder="搜索技能名或描述…" style="max-width: 240px">
        <template #prefix>🔍</template>
      </n-input>
      <span class="mp-note">把好用的技能分享给团队,也能一键安装别人的技能到工作区</span>
      <n-button size="small" type="primary" @click="openPublish">+ 发布技能</n-button>
    </div>

    <div class="mp-tabs">
      <button
        v-for="c in catTabs"
        :key="c.key"
        class="mp-tab"
        :class="{ active: activeCat === c.key }"
        @click="activeCat = c.key"
      >
        <span class="mp-tab-emoji">{{ c.emoji }}</span>{{ c.label }}
        <span class="mp-tab-count">{{ catCount(c.key) }}</span>
      </button>
    </div>

    <n-spin :show="loading" style="display: block; min-height: 120px">
      <div v-if="!filtered.length" class="mp-empty">暂无匹配的技能,发布第一个吧</div>
      <div v-else class="mp-grid">
        <div v-for="s in filtered" :key="s.id" class="mp-card" @click="openDetail(s)">
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
              <span class="mp-installs">↓ {{ s.installs }}</span>
              <span class="mp-author">by {{ s.authorName || '匿名' }}</span>
            </div>
          </div>
          <div class="mp-actions" @click.stop>
            <n-button v-if="!s.installed" size="small" type="primary" secondary @click="installDirect(s)">安装</n-button>
            <n-button v-if="s.mine" size="small" type="error" quaternary @click="removeOwn(s)">下架</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <!-- 发布弹窗 -->
    <n-modal
      v-model:show="publishVisible"
      preset="card"
      title="发布技能到广场"
      style="width: 680px; max-width: 94vw"
      :bordered="false"
    >
      <div v-if="importSkills.length || importLoading" class="mp-import">
        <span class="mp-import-label">从工作区导入:</span>
        <span
          v-for="s in importSkills"
          :key="s.path"
          class="mp-import-chip"
          @click="importSkill(s.name)"
        >{{ s.name }}</span>
        <span v-if="importLoading" class="mp-import-loading">加载中…</span>
      </div>
      <div class="pub-form">
        <n-input v-model:value="pubName" maxlength="64" size="small" placeholder="技能名(小写字母/数字,连字符分隔,如 data-analyzer)" />
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
        <n-input
          v-model:value="pubContent"
          type="textarea"
          :rows="8"
          maxlength="60000"
          show-count
          size="small"
          placeholder="SKILL.md 全文(需包含 ---frontmatter--- 的 name 与 description)"
        />
      </div>
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
      style="width: 720px; max-width: 94vw"
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
          <pre class="dt-content">{{ detail.content }}</pre>
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

/* 卡片网格 */
.mp-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px; max-height: 56vh; overflow-y: auto; padding: 2px;
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
.mp-card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 11px; color: var(--text-3); }
.mp-cat { color: var(--accent-text); }
.mp-author { margin-left: auto; }
.mp-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex-shrink: 0; }

.mp-import { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 12px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--accent-soft); }
.mp-import-label { font-size: 12px; color: var(--accent-text); }
.mp-import-chip { font-size: 12px; color: var(--text-2); background: var(--surface); border: 1px solid var(--border-soft); border-radius: 10px; padding: 2px 8px; cursor: pointer; }
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
.dt-content {
  background: var(--bg-elev); border: 1px solid var(--border-soft); border-radius: var(--radius-sm);
  padding: 12px; font-size: 12px; line-height: 1.6; max-height: 40vh; overflow: auto;
  white-space: pre-wrap; word-break: break-word; color: var(--text-2);
}
</style>
