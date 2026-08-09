<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useMessage } from 'naive-ui'
import { chatApi } from '@/api/chat'
import type { TemplateVo } from '@/types'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'use', prompt: string): void
}>()

const msg = useMessage()
const templates = ref<TemplateVo[]>([])
const loading = ref(false)
const keyword = ref('')
const activeCategory = ref('全部')

const categories = computed(() => {
  const set = new Set<string>(['全部'])
  for (const t of templates.value) set.add(t.category)
  return Array.from(set)
})

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return templates.value.filter((t) => {
    if (activeCategory.value !== '全部' && t.category !== activeCategory.value) return false
    if (!q) return true
    return t.title.toLowerCase().includes(q) || t.content.toLowerCase().includes(q)
  })
})

async function load() {
  loading.value = true
  try {
    templates.value = await chatApi.listTemplates()
  } catch (e: any) {
    msg.error(e?.message || '加载模板失败')
  } finally {
    loading.value = false
  }
}

function onShow() {
  if (props.show) load()
}
function useTemplate(t: TemplateVo) {
  emit('use', t.content)
  emit('update:show', false)
}

// ---------- 新增/编辑 ----------
const editVisible = ref(false)
const editId = ref<number | null>(null)
const editTitle = ref('')
const editCategory = ref('办公')
const editContent = ref('')
const saving = ref(false)

function openCreate() {
  editId.value = null
  editTitle.value = ''
  editCategory.value = '办公'
  editContent.value = ''
  editVisible.value = true
}
function openEdit(t: TemplateVo) {
  editId.value = t.id
  editTitle.value = t.title
  editCategory.value = t.category
  editContent.value = t.content
  editVisible.value = true
}
async function save() {
  const title = editTitle.value.trim()
  const content = editContent.value.trim()
  if (!title || !content) {
    msg.warning('请填写标题和内容')
    return
  }
  saving.value = true
  try {
    if (editId.value == null) {
      await chatApi.createTemplate({ title, category: editCategory.value || '其他', content })
      msg.success('已创建模板')
    } else {
      await chatApi.updateTemplate(editId.value, { title, category: editCategory.value || '其他', content })
      msg.success('已保存')
    }
    editVisible.value = false
    await load()
  } catch (e: any) {
    msg.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(t: TemplateVo) {
  if (t.builtin) return
  try {
    await chatApi.deleteTemplate(t.id)
    msg.success('已删除')
    await load()
  } catch (e: any) {
    msg.error(e?.message || '删除失败')
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="提示词模板库"
    style="width: 720px; max-width: 92vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onShow"
  >
    <div class="tpl-head">
      <n-input v-model:value="keyword" size="small" clearable placeholder="搜索模板…" style="max-width: 220px">
        <template #prefix>🔍</template>
      </n-input>
      <div class="cat-tabs">
        <span
          v-for="c in categories"
          :key="c"
          class="cat-tab"
          :class="{ active: activeCategory === c }"
          @click="activeCategory = c"
        >{{ c }}</span>
      </div>
      <n-button size="small" type="primary" @click="openCreate">+ 新建模板</n-button>
    </div>

    <n-spin :show="loading" style="display: block; margin: 24px auto">
      <div v-if="!filtered.length" class="tpl-empty">暂无模板</div>
      <div v-else class="tpl-list">
        <div v-for="t in filtered" :key="t.id" class="tpl-item">
          <div class="tpl-main">
            <div class="tpl-row">
              <span class="tpl-title">{{ t.title }}</span>
              <n-tag size="tiny" :bordered="false" class="tpl-cat">{{ t.category }}</n-tag>
              <n-tag v-if="t.builtin" size="tiny" type="info" :bordered="false">内置</n-tag>
            </div>
            <div class="tpl-content">{{ t.content }}</div>
          </div>
          <div class="tpl-actions">
            <n-button size="tiny" type="primary" quaternary @click="useTemplate(t)">使用</n-button>
            <n-button v-if="!t.builtin" size="tiny" quaternary @click="openEdit(t)">编辑</n-button>
            <n-popconfirm v-if="!t.builtin" @positive-click="remove(t)">
              <template #trigger>
                <n-button size="tiny" quaternary type="error">删除</n-button>
              </template>
              确定删除该模板？
            </n-popconfirm>
          </div>
        </div>
      </div>
    </n-spin>

    <!-- 新增/编辑 -->
    <n-modal
      v-model:show="editVisible"
      preset="card"
      :title="editId == null ? '新建模板' : '编辑模板'"
      style="width: 560px; max-width: 92vw"
      :bordered="false"
    >
      <div class="edit-form">
        <n-input v-model:value="editTitle" maxlength="128" placeholder="模板标题,如「写周报」" />
        <n-select
          v-model:value="editCategory"
          :options="[
            { label: '办公', value: '办公' },
            { label: '开发', value: '开发' },
            { label: '其他', value: '其他' },
          ]"
          placeholder="分类"
        />
        <n-input
          v-model:value="editContent"
          type="textarea"
          :rows="6"
          maxlength="8000"
          show-count
          placeholder="模板内容(可含换行;发送时填入输入框供你补充)"
        />
      </div>
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button @click="editVisible = false">取消</n-button>
          <n-button type="primary" :loading="saving" @click="save">保存</n-button>
        </div>
      </template>
    </n-modal>
  </n-modal>
</template>

<style scoped>
.tpl-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.cat-tabs { display: flex; gap: 6px; flex: 1; min-width: 0; flex-wrap: wrap; }
.cat-tab {
  font-size: 12px; color: var(--text-3); padding: 3px 10px; border-radius: 12px; cursor: pointer;
  transition: background .15s, color .15s;
}
.cat-tab:hover { background: var(--surface-hover); color: var(--text-2); }
.cat-tab.active { background: var(--accent-soft); color: var(--accent-text); font-weight: 600; }
.tpl-empty { color: var(--text-3); font-size: 13px; padding: 32px 0; text-align: center; }
.tpl-list { display: flex; flex-direction: column; gap: 8px; max-height: 52vh; overflow-y: auto; }
.tpl-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px; border-radius: var(--radius-sm);
  background: var(--surface); border: 1px solid var(--border-soft);
}
.tpl-main { flex: 1; min-width: 0; }
.tpl-row { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tpl-title { font-size: 14px; font-weight: 600; color: var(--text); }
.tpl-cat { margin: 0; }
.tpl-content {
  font-size: 12px; color: var(--text-3); line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  white-space: pre-wrap;
}
.tpl-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }
.edit-form { display: flex; flex-direction: column; gap: 12px; }
</style>
