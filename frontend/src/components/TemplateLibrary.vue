<script setup lang="ts">
/**
 * 模板库(整页/弹窗两用):
 * - 列表:精选 / 收藏 / 我的 三个 Tab + 分类 chips + 搜索 + 卡片网格(文档型模板显示 live 预览缩略图)。
 * - 详情:整页展示(左预览 / 右信息:介绍、标签、关联技能、收藏、做同款)。
 * - 做同款:把模板(提示词或 HTML 样板)注入聊天输入框。
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage, NModal } from 'naive-ui'
import { chatApi } from '@/api/chat'
import TemplateThumb from '@/components/TemplateThumb.vue'
import SectionTabs from '@/components/ui/SectionTabs.vue'
import FilterChips from '@/components/ui/FilterChips.vue'
import EmptyState from '@/components/ui/EmptyState.vue'
import ListPager from '@/components/ui/ListPager.vue'
import IconPicker from '@/components/ui/IconPicker.vue'
import type { TemplateVo } from '@/types'


const props = defineProps<{ show?: boolean; page?: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'use', prompt: string): void
  (e: 'use-html', payload: { title: string; content: string }): void
  (e: 'close'): void
}>()

const msg = useMessage()
const { t: tr } = useI18n()
const templates = ref<TemplateVo[]>([])
const loading = ref(false)
const keyword = ref('')
const activeTab = ref<'featured' | 'fav' | 'mine' | 'market'>('featured')
const activeCategory = ref('全部')
const rowBusyId = ref<number | null>(null)

const rootBind = computed(() =>
  props.page
    ? { class: 'manage-page' }
    : {
        show: props.show,
        preset: 'card',
    title: tr('tpl2.title'),
        style: 'width: 1080px; max-width: 96vw',
        bordered: false,
        'onUpdate:show': (v: boolean) => emit('update:show', v),
        onAfterEnter: onShow,
      },
)

const isHtml = (t: TemplateVo) => (t.format || 'prompt') === 'html'

/** 顶部 Tab(统一下划线 Tab 组件的 items) */
const tabItems = computed(() => [
  { key: 'featured' as const, label: tr('templates.featured') },
  { key: 'fav' as const, label: tr('templates.fav') },
  { key: 'mine' as const, label: tr('templates.mine') },
  { key: 'market' as const, label: tr('templates.market') },
])

// ===== 模板市场 =====
const marketItems = ref<any[]>([])
const marketLoading = ref(false)
const marketBusyId = ref<number | null>(null)
async function loadMarket() {
  marketLoading.value = true
  try {
    marketItems.value = await chatApi.templateMarketList()
    pages.value.market = 1
  } catch (e: any) {
    msg.error(e?.message || tr('tpl.loadFailed'))
  } finally {
    marketLoading.value = false
  }
}
async function installFromMarket(m: any) {
  if (marketBusyId.value !== null) return
  marketBusyId.value = m.id
  try {
    await chatApi.templateInstall(m.id)
    m.installed = true
    m.installs = (m.installs || 0) + 1
    msg.success(tr('tpl.installedToMine'))
    await load()
  } catch (e: any) {
    msg.error(e?.message || tr('tpl.installFailed'))
  } finally {
    marketBusyId.value = null
  }
}
async function unpublishMarket(m: any) {
  if (marketBusyId.value !== null) return
  marketBusyId.value = m.id
  try {
    await chatApi.templateUnpublish(m.id)
    msg.success(tr('tpl.published'))
    await loadMarket()
  } catch (e: any) {
    msg.error(e?.message || tr('tpl.publishFailed'))
  } finally {
    marketBusyId.value = null
  }
}
/** 把我的模板分享到市场 */
async function shareToMarket(t: any) {
  if (marketBusyId.value !== null) return
  marketBusyId.value = t.id
  try {
    await chatApi.templatePublish(t.id)
    msg.success(tr('tpl.unpublished'))
  } catch (e: any) {
    msg.error(e?.message || tr('tpl.actionFailed'))
  } finally {
    marketBusyId.value = null
  }
}

const categories = computed(() => {
  const set = new Set<string>(['全部'])
  for (const t of templates.value) if (t.category) set.add(t.category)
  return Array.from(set)
})
/** 分类筛选 chips */
const categoryChips = computed(() => categories.value.map((c) => ({ key: c, label: c })))

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return templates.value.filter((t) => {
    if (activeTab.value === 'featured' && !t.featured) return false
    if (activeTab.value === 'fav' && !t.favorited) return false
    if (activeTab.value === 'mine' && t.builtin) return false
    if (activeCategory.value !== '全部' && t.category !== activeCategory.value) return false
    if (!q) return true
    return (t.title || '').toLowerCase().includes(q) || (t.description || t.content || '').toLowerCase().includes(q)
  })
})

const pageSize = 12
/** 每个 Tab 独立页码(精选/收藏/我的/市场),切换互不影响 */
const pages = ref<Record<string, number>>({ featured: 1, fav: 1, mine: 1, market: 1 })
const curPage = computed(() => pages.value[activeTab.value] || 1)
const pagedTemplates = computed(() => {
  const start = (curPage.value - 1) * pageSize
  return filtered.value.slice(start, start + pageSize)
})
const pageCount = computed(() => Math.max(1, Math.ceil(filtered.value.length / pageSize)))

/** 市场 Tab 独立分页 */
const pagedMarketItems = computed(() => {
  const start = (pages.value.market - 1) * pageSize
  return marketItems.value.slice(start, start + pageSize)
})
const marketPageCount = computed(() => Math.max(1, Math.ceil(marketItems.value.length / pageSize)))

function resetPage() { pages.value[activeTab.value] = 1 }
function setPage(p: number) { pages.value[activeTab.value] = p }

// 数据变化导致总页数减少时,把当前页收敛到最后一页,避免停留在空白页
watch([pageCount, marketPageCount], () => {
  if (pages.value[activeTab.value] > pageCount.value) pages.value[activeTab.value] = pageCount.value
  if (pages.value.market > marketPageCount.value) pages.value.market = marketPageCount.value
})

/** 切换 Tab(由 SectionTabs 的 @change 驱动):重置分页,市场 Tab 额外加载市场数据 */
function onTabChange(k: string | number) {
  resetPage()
  if (k === 'market') loadMarket()
}

async function load() {
  loading.value = true
  try {
    templates.value = await chatApi.listTemplates()
  } catch (e: any) {
      msg.error(e?.message || tr('tpl.saveFailed'))
  } finally {
    loading.value = false
  }
}
function onShow() { load() }
onMounted(() => { if (props.page || props.show) load() })

// ---------- 详情 ----------
const detailView = ref(false)
const detail = ref<TemplateVo | null>(null)
function openDetail(t: TemplateVo) { detail.value = t; detailView.value = true }
function closeDetail() { detailView.value = false; detail.value = null }

function tagList(t: TemplateVo | null): string[] {
  if (!t || !t.tags) return []
  return t.tags.split(',').map((s) => s.trim()).filter(Boolean)
}

// ---------- 收藏 ----------
async function toggleFavorite(t: TemplateVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = t.id
  try {
    const res = await chatApi.setTemplateFavorite(t.id, !t.favorited)
    t.favorited = res.favorited
    if (detail.value && detail.value.id === t.id) detail.value.favorited = res.favorited
  msg.success(t.favorited ? tr('tpl.favorited') : tr('tpl.unfavorited'))
  } catch (e: any) {
    msg.error(e?.message || tr('tpl.actionFailed'))
  } finally {
    rowBusyId.value = null
  }
}

// ---------- 置顶 ----------
async function togglePin(t: TemplateVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = t.id
  try {
    const res = await chatApi.setTemplatePin(t.id, !t.pinned)
    t.pinned = res.pinned
    if (detail.value && detail.value.id === t.id) detail.value.pinned = res.pinned
  msg.success(t.pinned ? tr('tpl.pinned') : tr('tpl.unpinned'))
  } catch (e: any) {
    msg.error(e?.message || tr('tpl.actionFailed'))
  } finally {
    rowBusyId.value = null
  }
}

// ---------- 做同款 ----------
function useTemplate(t: TemplateVo) {
  if (isHtml(t)) {
    // HTML 文档模板:把 HTML 引用到对话(附件)+ 回填提示词,由父组件处理
    emit('use-html', { title: t.title, content: t.content || '' })
  } else {
    emit('use', t.content || '')
  }
  emit('update:show', false)
  emit('close')
}

// ---------- 上传 HTML 模板(用户上传 .html 文件即可) ----------
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadInput = ref<HTMLInputElement | null>(null)
const uploadTitle = ref('')
const uploadCategory = ref('内容创作')

function openUpload() {
  uploadTitle.value = ''
  uploadCategory.value = '内容创作'
  uploadVisible.value = true
}
async function uploadFile(file: File) {
  if (!file) return
    if (!/\.(html?|htm)$/i.test(file.name)) { msg.warning(tr('tpl.onlyHtml')); return }
  uploading.value = true
  try {
    const content = await file.text()
    const title = (uploadTitle.value.trim() || file.name.replace(/\.(html?|htm)$/i, '')).slice(0, 128)
    if (!title) { msg.warning(tr('tpl.needTitle')); return }
    await chatApi.createTemplate({ title, category: uploadCategory.value || '其他', description: '', content, icon: '📄', format: 'html' })
    msg.success(tr('tpl.uploaded'))
    uploadVisible.value = false
    await load()
  } catch (err: any) {
    msg.error(err?.message || tr('tpl.uploadFailed'))
  } finally {
    uploading.value = false
  }
}
function onPickFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file) uploadFile(file)
}
function onDropFile(e: DragEvent) {
  const file = e.dataTransfer?.files?.[0]
  if (file) uploadFile(file)
}

// ---------- 新增/编辑(仅提示词模板) ----------
const editVisible = ref(false)
const saving = ref(false)
const editId = ref<number | null>(null)
const editTitle = ref('')
const editCategory = ref('办公')
const editDescription = ref('')
const editFormat = ref<'prompt' | 'html'>('prompt')
const editContent = ref('')
const editIcon = ref('📋')
const ICON_PRESETS = ['📋', '📝', '📊', '📈', '📉', '💻', '🐛', '🛠️', '🧪', '🔍', '📚', '✍️', '📧', '🗓️', '💡', '🚀', '🎯', '🧩', '🗂️', '📄', '🤖', '🧠', '📎', '🗺️']

function openCreate() {
  editId.value = null; editTitle.value = ''; editCategory.value = '办公'; editDescription.value = ''
  editFormat.value = 'prompt'; editContent.value = ''; editIcon.value = '📋'
  editVisible.value = true
}
function openEdit(t: TemplateVo) {
  editId.value = t.id; editTitle.value = t.title; editCategory.value = t.category
  editDescription.value = t.description || ''; editFormat.value = (t.format === 'html' ? 'html' : 'prompt')
  editContent.value = t.content; editIcon.value = t.icon || '📋'
  editVisible.value = true
}
async function save() {
  const title = editTitle.value.trim()
  const content = editContent.value.trim()
    if (!title || !content) { msg.warning(tr('tpl.needTitleContent')); return }
  saving.value = true
  try {
    const data = { title, category: editCategory.value || '其他', description: editDescription.value.trim(), content, icon: editIcon.value, format: editFormat.value }
    if (editId.value == null) { await chatApi.createTemplate(data); msg.success(tr('tpl.created')) }
    else { await chatApi.updateTemplate(editId.value, data); msg.success(tr('tpl.saved')) }
    editVisible.value = false
    await load()
    } catch (e: any) { msg.error(e?.message || tr('tpl.saveFailed')) } finally { saving.value = false }
}

async function remove(t: TemplateVo) {
  if (t.builtin || rowBusyId.value !== null) return
  rowBusyId.value = t.id
  try {
    await chatApi.deleteTemplate(t.id)
    msg.success(tr('tpl.deleted'))
    if (detail.value?.id === t.id) closeDetail()
    await load()
    } catch (e: any) { msg.error(e?.message || tr('tpl.deleteFailed')) } finally { rowBusyId.value = null }
}
</script>

<template>
  <component :is="page ? 'div' : NModal" v-bind="rootBind">
    <!-- ===== 详情整页 ===== -->
    <div v-if="detailView && detail" class="tpl-detail-page">
      <div class="manage-crumb">
        <button class="crumb-link" @click="emit('close')">首页</button>
        <span class="crumb-sep">/</span>
      <button class="crumb-link" @click="closeDetail">{{ tr('tpl.title') }}</button>
        <span class="crumb-sep">/</span>
        <span class="crumb-cur">{{ detail.title }}</span>
      </div>
      <div class="tpl-detail">
        <div class="tpl-preview">
          <iframe
            v-if="isHtml(detail) && detail.content"
            :srcdoc="detail.content"
            class="tpl-preview-frame"
            sandbox=""
          ></iframe>
          <pre v-else class="tpl-preview-text">{{ detail.content }}</pre>
        </div>
        <aside class="tpl-info">
          <div class="tpl-info-actions">
            <button class="tpl-star" :class="{ on: detail.favorited }" :disabled="rowBusyId !== null" @click="toggleFavorite(detail)">
        {{ detail.favorited ? tr('tpl.favorited') : tr('tpl.favorite') }}
            </button>
          </div>
          <h2 class="tpl-info-title">
            {{ detail.title }}
        <span v-if="detail.source === 'installed'" class="tpl-info-badge">{{ tr('tpl.installedReadonly') }}</span>
          </h2>
          <div class="tpl-info-block">
        <div class="tpl-info-label">{{ tr('tpl.templateName') }}</div>
        <p class="tpl-info-text">{{ detail.description || tr('tpl.noDesc') }}</p>
          </div>
          <div v-if="detail.skill" class="tpl-info-block">
        <div class="tpl-info-label">{{ tr('tpl.category') }}</div>
            <div class="tpl-skill-tag">🧩 {{ detail.skill }}</div>
          </div>
          <div v-if="tagList(detail).length" class="tpl-info-block">
        <div class="tpl-info-label">{{ tr('tpl.signature') }}</div>
            <div class="tpl-tags">
              <span class="tpl-tag" :class="{ fmt: tag === 'HTML' }" v-for="tag in tagList(detail)" :key="tag">{{ tag }}</span>
            </div>
          </div>
          <div class="tpl-info-foot">
            <div class="tpl-info-secondary">
      <n-button size="small" quaternary @click="closeDetail">{{ tr('common.close') }}</n-button>
      <n-button v-if="!detail.builtin" size="small" quaternary :loading="rowBusyId === detail.id" :disabled="rowBusyId !== null" @click="togglePin(detail)">{{ detail.pinned ? tr('tpl.unpinned') : tr('tpl.pin') }}</n-button>
      <n-button v-if="!detail.builtin && detail.source !== 'installed'" size="small" quaternary @click="openEdit(detail)">{{ tr('files.edit') }}</n-button>
      <n-button v-if="!detail.builtin && detail.source !== 'installed'" size="small" quaternary :loading="marketBusyId === detail.id" :disabled="marketBusyId !== null" @click="shareToMarket(detail)">{{ tr('tpl.shareToMarket') }}</n-button>
      <n-button v-if="!detail.builtin" size="small" quaternary type="error" :disabled="rowBusyId !== null" @click="remove(detail)">{{ tr('files.deleteBtn') }}</n-button>
            </div>
      <n-button type="primary" block @click="useTemplate(detail)">{{ tr('tpl.useIt') }}</n-button>
          </div>
        </aside>
      </div>
    </div>

    <!-- ===== 列表 ===== -->
    <template v-else>
      <div v-if="page" class="manage-crumb">
        <button class="crumb-link" @click="emit('close')">首页</button>
        <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ tr('tpl.title') }}</span>
      </div>
      <div v-if="page" class="manage-header">
        <div>
    <div class="manage-title">{{ tr('tpl.title') }}</div>
    <div class="manage-sub">{{ tr('tpl.subtitle') }}</div>
        </div>
      <n-button type="primary" @click="openUpload">{{ tr('tpl.uploadTpl') }}</n-button>
      </div>

      <!-- Tabs + 搜索 -->
      <div class="tpl-topbar">
        <SectionTabs
          v-model:active="activeTab"
          :items="tabItems"
          :aria-label="tr('templates.ariaTabs')"
          @change="onTabChange"
        />
        <n-input v-if="activeTab !== 'market'" v-model:value="keyword" size="small" clearable :placeholder="tr('templates.search')" style="max-width: 240px" @update:value="resetPage">
          <template #prefix>
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          </template>
        </n-input>
      </div>

      <!-- 市场 Tab:安装/已安装/下架 -->
      <div v-if="activeTab === 'market'" class="tpl-market">
        <n-spin :show="marketLoading">
          <div v-if="!marketItems.length && !marketLoading" class="tpl-empty">{{ tr('templates.marketEmpty') }}</div>          <div v-else class="tpl-grid">
            <article v-for="m in pagedMarketItems" :key="m.id" class="tpl-card">
              <div class="tpl-card-thumb">
                <TemplateThumb v-if="(m.format || 'prompt') === 'html'" :html="m.content" :height="200" />
                <div v-else class="tpl-card-prompt" style="height: 200px">
                  <div class="tpl-card-ico">{{ m.icon || '📋' }}</div>
                  <div class="tpl-card-excerpt">{{ m.content }}</div>
                </div>
              </div>
              <div class="tpl-card-foot">
                <div class="tpl-card-title" :title="m.title">{{ m.title }}</div>
              </div>
              <div class="tpl-card-meta">
          <span class="tpl-fmt">{{ (m.format || 'prompt') === 'html' ? 'HTML' : tr('tpl.promptFmt') }}</span>
          <span class="tpl-author">by {{ m.authorName || tr('tpl.official') }}</span>
                <span class="tpl-installs">↓ {{ m.installs }}</span>
              </div>
              <div class="tpl-card-actions">
          <n-button v-if="!m.installed" size="tiny" type="primary" :loading="marketBusyId === m.id" :disabled="marketBusyId !== null" @click="installFromMarket(m)">{{ tr('tpl.install') }}</n-button>
          <n-button v-else size="tiny" quaternary disabled>{{ tr('tpl.installed') }}</n-button>
                <span class="spacer"></span>
          <n-button v-if="m.mine" size="tiny" quaternary type="error" :loading="marketBusyId === m.id" :disabled="marketBusyId !== null" @click="unpublishMarket(m)">{{ tr('tpl.unpublish') }}</n-button>
              </div>
            </article>
          </div>
        </n-spin>
        <div v-if="marketPageCount > 1" class="tpl-pager">
          <ListPager :page="pages.market" :page-count="marketPageCount" @update:page="pages.market = $event" />
        </div>
      </div>

      <template v-else>
      <FilterChips
        class="tpl-cats"
        :items="categoryChips"
        :active="activeCategory"
        variant="solid"
        :aria-label="tr('templates.ariaCats')"
        @update:active="activeCategory = $event"
        @change="resetPage()"
      />

      <n-spin :show="loading">
        <EmptyState v-if="!filtered.length && !loading" :text="tr('templates.empty')" :padding="40" />
        <div v-else class="tpl-grid">
          <article v-for="t in pagedTemplates" :key="t.id" class="tpl-card" @click="openDetail(t)">
            <div class="tpl-card-thumb">
              <TemplateThumb v-if="isHtml(t)" :html="t.content" :height="230" />
              <div v-else class="tpl-card-prompt">
                <div class="tpl-card-ico">{{ t.icon || '📋' }}</div>
                <div class="tpl-card-excerpt">{{ t.content }}</div>
              </div>
            </div>
            <div class="tpl-card-foot">
              <div class="tpl-card-title" :title="t.title">{{ t.title }}</div>
              <button class="tpl-card-star" :class="{ on: t.favorited }" :disabled="rowBusyId !== null" @click.stop="toggleFavorite(t)">
                {{ t.favorited ? '★' : '☆' }}
              </button>
            </div>
            <div class="tpl-card-meta">
          <span class="tpl-fmt">{{ isHtml(t) ? 'HTML' : tr('tpl.promptFmt') }}</span>
          <span v-if="t.source === 'installed'" class="tpl-src">{{ tr('tpl.installedFromMarket') }}</span>
          <span v-if="t.pinned" class="tpl-pin">{{ tr('tpl.pin') }}</span>
          <span v-if="t.featured" class="tpl-feat">{{ tr('templates.featured') }}</span>
            </div>
            <div v-if="activeTab === 'mine'" class="tpl-card-actions">
        <n-button size="tiny" quaternary :loading="rowBusyId === t.id" :disabled="rowBusyId !== null" @click.stop="togglePin(t)">{{ t.pinned ? tr('tpl.unpinned') : tr('tpl.pin') }}</n-button>
        <n-button v-if="t.source !== 'installed'" size="tiny" quaternary :loading="marketBusyId === t.id" :disabled="marketBusyId !== null" @click.stop="shareToMarket(t)">{{ tr('tpl.shareToMarket') }}</n-button>
            </div>
          </article>
        </div>
      </n-spin>

      <div v-if="pageCount > 1" class="tpl-pager">
        <ListPager :page="curPage" :page-count="pageCount" @update:page="setPage" />
      </div>
      </template>
    </template>

    <!-- 上传 HTML 模板 -->
    <n-modal v-model:show="uploadVisible" preset="card" :title="tr('tpl.uploadTitle')" style="width: 560px; max-width: 94vw" :bordered="false">
      <div class="edit-form">
        <n-input v-model:value="uploadTitle" maxlength="128" :placeholder="tr('tpl.titlePlaceholder')" />
        <n-select v-model:value="uploadCategory" :options="[{ label: '内容创作', value: '内容创作' }, { label: '数据分析', value: '数据分析' }, { label: '办公', value: '办公' }, { label: '开发', value: '开发' }, { label: '其他', value: '其他' }]" placeholder="分类" />
        <div class="up-drop" :class="{ busy: uploading }" @click="!uploading && uploadInput?.click()" @dragover.prevent @drop.prevent="onDropFile">
          <div class="up-spinner" v-if="uploading"></div>
          <svg v-else width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
        <div class="up-drop-title">{{ uploading ? tr('tpl.uploading') : tr('tpl.dropHtml') }}</div>
        </div>
        <input ref="uploadInput" type="file" accept=".html,.htm" style="display:none" @change="onPickFile" />
        <ul class="up-tips">
          <li>{{ tr('tpl.supportHtml') }}</li>
          <li>{{ tr('tpl.uploadTip') }}</li>
        </ul>
      </div>
    </n-modal>

    <!-- 新建/编辑模板 -->
    <n-modal v-model:show="editVisible" preset="card" :title="editId == null ? tr('tpl.newTpl') : tr('tpl.editTpl')" style="width: 680px; max-width: 94vw" :bordered="false">
      <div class="edit-form">
        <div class="ef-row">
        <n-input v-model:value="editTitle" maxlength="128" :placeholder="tr('tpl.titlePlaceholder2')" style="flex:1" />
        <n-select v-model:value="editFormat" style="width: 150px" :options="[{ label: tr('tpl.promptTpl'), value: 'prompt' }, { label: tr('tpl.htmlTpl'), value: 'html' }]" />
        </div>
        <n-input v-model:value="editDescription" maxlength="512" :placeholder="tr('tpl.descPlaceholder')" />
        <n-select v-model:value="editCategory" :options="[{ label: '办公', value: '办公' }, { label: '开发', value: '开发' }, { label: '数据分析', value: '数据分析' }, { label: '内容创作', value: '内容创作' }, { label: '其他', value: '其他' }]" placeholder="分类" />
        <div v-if="editFormat === 'prompt'" class="icon-picker">
        <span class="icon-label">{{ tr('tpl.icon') }}:</span>
          <IconPicker v-model="editIcon" :size="28" :title="tr('tpl.icon')" />
        </div>
        <n-input
          v-model:value="editContent"
          type="textarea"
          :rows="editFormat === 'html' ? 14 : 6"
          :maxlength="editFormat === 'html' ? 60000 : 8000"
          show-count
        :placeholder="editFormat === 'html' ? tr('tpl.htmlContentPlaceholder') : tr('tpl.promptContentPlaceholder')"
        />
      </div>
      <template #footer>
        <div style="display:flex;justify-content:flex-end;gap:8px">
      <n-button @click="editVisible = false">{{ tr('common.cancel') }}</n-button>
      <n-button type="primary" :loading="saving" @click="save">{{ tr('files.create') }}</n-button>
        </div>
      </template>
    </n-modal>
  </component>
</template>

<style scoped>
/* ===== 整页外壳 ===== */
.manage-page { flex: 1; min-width: 0; display: flex; flex-direction: column; height: 100%; min-height: 0; overflow: hidden; padding: 18px 24px 24px; background: var(--bg); box-sizing: border-box; }
.manage-crumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); margin-bottom: 12px; flex-shrink: 0; }
.crumb-link { background: none; border: none; padding: 0; color: var(--text-3); cursor: pointer; font-size: 12px; }
.crumb-link:hover { color: var(--accent-text); }
.crumb-sep { color: var(--text-4); }
.crumb-cur { color: var(--text-2); }
.manage-header { margin-bottom: 16px; display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-shrink: 0; }
.manage-title { font-size: 20px; font-weight: 700; color: var(--text); margin-bottom: 4px; }
.manage-sub { font-size: 13px; color: var(--text-3); }

/* ===== Tabs / 搜索 / 分类 ===== */
.tpl-topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-shrink: 0; }
/* Tab 条 / 分类 chips / 空态 / 分页器 / 图标格子样式已上收到 components/ui/* */
.tpl-cats { margin: 14px 0 18px; flex-shrink: 0; }
.tpl-empty { color: var(--text-3); font-size: 13px; padding: 40px 0; text-align: center; }

/* ===== 卡片网格 ===== */
.tpl-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 18px; }
.tpl-card { border: 1px solid var(--border-soft); border-radius: 12px; background: var(--surface); padding: 12px; cursor: pointer; transition: border-color .15s, box-shadow .15s, transform .15s; }
.tpl-card:hover { border-color: var(--border); box-shadow: var(--shadow); transform: translateY(-2px); }
.tpl-card-thumb { margin-bottom: 10px; }
.tpl-card-prompt { height: 230px; border: 1px solid var(--border-soft); border-radius: 10px; background: var(--bg-elev); padding: 14px; overflow: hidden; display: flex; flex-direction: column; gap: 8px; }
.tpl-card-ico { font-size: 22px; }
.tpl-card-excerpt { font-size: 12px; color: var(--text-3); line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 8; -webkit-box-orient: vertical; overflow: hidden; }
.tpl-card-foot { display: flex; align-items: center; gap: 8px; }
.tpl-card-title { flex: 1; min-width: 0; font-size: 14px; font-weight: 600; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tpl-card-star { background: none; border: none; cursor: pointer; font-size: 16px; color: var(--text-4); padding: 2px 4px; }
.tpl-card-star.on { color: #f5b301; }
.tpl-card-meta { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.tpl-fmt { font-size: 11px; font-weight: 600; color: var(--accent-text); background: var(--accent-soft); padding: 2px 8px; border-radius: 6px; }
.tpl-feat { font-size: 11px; color: var(--warn); background: rgba(232,178,107,.14); padding: 2px 8px; border-radius: 6px; }
.tpl-pin { font-size: 11px; color: var(--accent-text); background: var(--accent-soft); padding: 2px 8px; border-radius: 6px; }
.tpl-src { font-size: 11px; color: var(--text-3); background: var(--fill-0); padding: 2px 8px; border-radius: 6px; }
.tpl-pager { flex-shrink: 0; }
.tpl-author { font-size: 11px; color: var(--text-3); }
.tpl-installs { font-size: 11px; color: var(--text-3); }
.tpl-card-actions { display: flex; align-items: center; gap: 6px; margin-top: 8px; }
.tpl-card-actions .spacer { flex: 1; }
.tpl-market { display: block; }

/* ===== 详情整页 ===== */
.tpl-detail-page { flex: 1; min-width: 0; display: flex; flex-direction: column; height: 100%; min-height: 0; padding: 18px 24px 24px; background: var(--bg); box-sizing: border-box; }
.tpl-detail { flex: 1; min-height: 0; display: grid; grid-template-columns: 1fr 340px; gap: 20px; }
.tpl-preview { border: 1px solid var(--border-soft); border-radius: 12px; background: #fff; overflow: hidden; }
.tpl-preview-frame { width: 100%; height: 100%; border: 0; background: #fff; }
.tpl-preview-text { margin: 0; padding: 20px; font-size: 13px; line-height: 1.7; color: var(--text-2); white-space: pre-wrap; overflow: auto; height: 100%; }
.tpl-info { display: flex; flex-direction: column; gap: 16px; overflow-y: auto; }
.tpl-info-actions { display: flex; justify-content: flex-end; }
.tpl-star { background: none; border: 1px solid var(--border); border-radius: 8px; padding: 5px 12px; font-size: 13px; color: var(--text-2); cursor: pointer; }
.tpl-star.on { color: #f5b301; border-color: #f5b301; }
.tpl-info-title { font-size: 20px; font-weight: 700; color: var(--text); margin: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.tpl-info-badge { font-size: 11px; font-weight: 500; color: var(--text-3); background: var(--fill-0); border: 1px solid var(--border-soft); padding: 2px 8px; border-radius: 6px; }
.tpl-info-block { display: flex; flex-direction: column; gap: 8px; }
.tpl-info-label { font-size: 12px; color: var(--text-3); }
.tpl-info-text { font-size: 13px; color: var(--text-2); line-height: 1.7; margin: 0; }
.tpl-skill-tag { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--accent-text); background: var(--accent-soft); border-radius: 8px; padding: 5px 12px; width: fit-content; }
.tpl-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.tpl-tag { font-size: 12px; color: var(--text-2); border: 1px solid var(--border); border-radius: 8px; padding: 4px 10px; }
.tpl-tag.fmt { color: var(--accent-text); border-color: var(--accent); background: var(--accent-soft); }
.tpl-info-foot { margin-top: auto; display: flex; flex-direction: column; gap: 10px; }
.tpl-info-secondary { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 4px; }
.tpl-info-secondary :deep(.n-button) { flex: 0 0 auto; }
.edit-form { display: flex; flex-direction: column; gap: 12px; }
.ef-row { display: flex; gap: 10px; align-items: center; }
.up-drop { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; height: 180px; border: 1.5px dashed var(--border); border-radius: var(--radius); background: var(--bg-elev); color: var(--text-3); cursor: pointer; transition: border-color .15s, background .15s, color .15s; }
.up-drop:hover { border-color: var(--accent); background: var(--fill-0); color: var(--accent-text); }
.up-drop.busy { cursor: default; }
.up-drop-title { font-size: 13px; }
.up-spinner { width: 22px; height: 22px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: up-spin .8s linear infinite; }
@keyframes up-spin { to { transform: rotate(360deg); } }
.up-tips { margin: 10px 0 0; padding-left: 18px; color: var(--text-3); font-size: 12px; line-height: 1.9; }
.up-tips code { background: var(--fill-0); padding: 1px 5px; border-radius: 4px; font-size: 11px; }
.icon-picker { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.icon-label { font-size: 12px; color: var(--text-3); margin-right: 4px; }
/* 图标格子样式已上收到 components/ui/IconPicker.vue */

@media (max-width: 900px) {
  .tpl-detail { grid-template-columns: 1fr; }
  .tpl-detail .tpl-preview { min-height: 320px; }
}
</style>
