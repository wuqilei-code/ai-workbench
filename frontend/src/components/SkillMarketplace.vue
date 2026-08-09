<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/chat'
import { fileApi } from '@/api/files'
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

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter(
    (s) => s.name.toLowerCase().includes(q) || s.description.toLowerCase().includes(q),
  )
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
const publishVisible = ref(false)
const pubName = ref('')
const pubDesc = ref('')
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
    // 尝试从 frontmatter 提取 description
    const m = md.match(/^---\s*\n([\s\S]*?)\n---/)
    if (m) {
      const desc = m[1].match(/description\s*:\s*(.+)/)
      if (desc) pubDesc.value = desc[1].trim()
    }
    msg.success(`已导入技能「${name}」`)
  } catch (e: any) {
    msg.error(e?.message || '导入失败')
  }
}

function openPublish() {
  pubName.value = ''
  pubDesc.value = ''
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
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="技能广场"
    style="width: 760px; max-width: 94vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onShow"
  >
    <div class="mp-head">
      <n-input v-model:value="keyword" size="small" clearable placeholder="搜索技能…" style="max-width: 220px">
        <template #prefix>🔍</template>
      </n-input>
      <span class="mp-note">把好用的技能分享给团队,也能装下别人的技能</span>
      <n-button size="small" type="primary" @click="openPublish">+ 发布技能</n-button>
    </div>

    <n-spin :show="loading" style="display: block; margin: 24px auto">
      <div v-if="!filtered.length" class="mp-empty">广场暂无技能,发布第一个吧</div>
      <div v-else class="mp-list">
        <div v-for="s in filtered" :key="s.id" class="mp-item" @click="openDetail(s)">
          <div class="mp-main">
            <div class="mp-row">
              <span class="mp-name">{{ s.name }}</span>
              <n-tag v-if="s.mine" size="tiny" type="warning" :bordered="false">我的</n-tag>
              <n-tag v-if="s.installed" size="tiny" type="success" :bordered="false">已安装</n-tag>
              <span class="mp-installs">↓ {{ s.installs }}</span>
            </div>
            <div class="mp-desc">{{ s.description }}</div>
            <div class="mp-meta">by {{ s.authorName || '匿名' }} · {{ fmtTime(s.updatedAt) }}</div>
          </div>
          <div class="mp-actions" @click.stop>
            <n-button v-if="!s.installed" size="tiny" type="primary" secondary @click="installDirect(s)">安装</n-button>
            <n-button v-if="s.mine" size="tiny" type="error" quaternary @click="removeOwn(s)">下架</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <!-- 发布弹窗 -->
    <n-modal
      v-model:show="publishVisible"
      preset="card"
      title="发布技能到广场"
      style="width: 640px; max-width: 94vw"
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
        <n-input
          v-model:value="pubContent"
          type="textarea"
          :rows="10"
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
      style="width: 680px; max-width: 94vw"
      :bordered="false"
    >
      <n-spin :show="detailLoading">
        <template v-if="detail">
          <div class="dt-head">
            <n-tag size="tiny" :bordered="false">by {{ detail.authorName || '匿名' }}</n-tag>
            <n-tag v-if="detail.installed" size="tiny" type="success" :bordered="false">已安装</n-tag>
            <span class="dt-installs">↓ {{ detail.installs }}</span>
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
.mp-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.mp-note { font-size: 12px; color: var(--text-3); flex: 1; min-width: 0; }
.mp-empty { color: var(--text-3); font-size: 13px; padding: 32px 0; text-align: center; }
.mp-list { display: flex; flex-direction: column; gap: 8px; max-height: 52vh; overflow-y: auto; }
.mp-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
  cursor: pointer; transition: border-color .15s;
}
.mp-item:hover { border-color: var(--accent); }
.mp-main { flex: 1; min-width: 0; }
.mp-row { display: flex; align-items: center; gap: 6px; margin-bottom: 3px; }
.mp-name { font-size: 14px; font-weight: 600; color: var(--text); }
.mp-installs { font-size: 12px; color: var(--text-3); margin-left: auto; }
.mp-desc { font-size: 12px; color: var(--text-2); line-height: 1.5; }
.mp-meta { font-size: 11px; color: var(--text-3); margin-top: 3px; }
.mp-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.mp-import { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 12px; padding: 8px 10px; border-radius: var(--radius-sm); background: var(--accent-soft); }
.mp-import-label { font-size: 12px; color: var(--accent-text); }
.mp-import-chip { font-size: 12px; color: var(--text-2); background: var(--surface); border: 1px solid var(--border-soft); border-radius: 10px; padding: 2px 8px; cursor: pointer; }
.mp-import-chip:hover { border-color: var(--accent-text); color: var(--accent-text); }
.mp-import-loading { font-size: 12px; color: var(--text-3); }
.pub-form { display: flex; flex-direction: column; gap: 10px; }
.dt-head { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }
.dt-installs { font-size: 12px; color: var(--text-3); margin-left: auto; }
.dt-desc { font-size: 13px; color: var(--text-2); margin-bottom: 10px; }
.dt-content {
  background: var(--bg-elev); border: 1px solid var(--border-soft); border-radius: var(--radius-sm);
  padding: 12px; font-size: 12px; line-height: 1.6; max-height: 40vh; overflow: auto;
  white-space: pre-wrap; word-break: break-word; color: var(--text-2);
}
</style>
