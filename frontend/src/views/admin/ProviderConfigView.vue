<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { useMessage } from 'naive-ui'
import type { ProviderVo } from '@/types'

const router = useRouter()
const msg = useMessage()
const providers = ref<ProviderVo[]>([])
const loading = ref(false)
const applying = ref(false)

const showProvider = ref(false)
const newProvider = ref({ providerId: '', name: '', baseUrl: '', npm: '', apiKey: '', envVarName: '', enabled: 1 })
const showModelFor = ref<string | null>(null)
const newModel = ref({ providerId: '', modelId: '', name: '' })

// n-modal v-model:show 需可写引用;showModelFor 兼作"当前添加模型的 providerId",
// 用 computed 桥接为布尔可见性(null=关闭, 非null=打开;关闭时回写 null)
const modelModalShow = computed({
  get: () => showModelFor.value != null,
  set: (v: boolean) => { if (!v) showModelFor.value = null }
})

async function load() {
  loading.value = true
  try { providers.value = await adminApi.listProviders() }
  catch (e: any) { msg.error(e.message) }
  finally { loading.value = false }
}

async function addProvider() {
  try {
    await adminApi.createProvider({ ...newProvider.value })
    msg.success('已添加')
    showProvider.value = false
    newProvider.value = { providerId: '', name: '', baseUrl: '', npm: '', apiKey: '', envVarName: '', enabled: 1 }
    await load()
  } catch (e: any) { msg.error(e.message) }
}

async function addModel() {
  if (!showModelFor.value) return
  try {
    await adminApi.createModel({ providerId: showModelFor.value, modelId: newModel.value.modelId, name: newModel.value.name })
    msg.success('已添加模型')
    showModelFor.value = null
    newModel.value = { providerId: '', modelId: '', name: '' }
    await load()
  } catch (e: any) { msg.error(e.message) }
}

async function removeProvider(id: number) {
  if (!confirm('删除该 provider 及其所有模型？')) return
  try { await adminApi.deleteProvider(id); msg.success('已删除'); await load() }
  catch (e: any) { msg.error(e.message) }
}

async function removeModel(id: number) {
  try { await adminApi.deleteModel(id); msg.success('已删除'); await load() }
  catch (e: any) { msg.error(e.message) }
}

async function toggleEnabled(p: ProviderVo) {
  try {
    // 传 npm 保持原值(后端 null=不修改,这里显式传当前值)
    await adminApi.updateProvider(p.id, { providerId: p.providerId, name: p.name, baseUrl: p.baseUrl || '', npm: p.npm || '', enabled: p.enabled ? 0 : 1 })
    await load()
  } catch (e: any) { msg.error(e.message) }
}

async function apply() {
  applying.value = true
  try {
    await adminApi.apply()
    msg.success('配置已应用，opencode 已重启')
  } catch (e: any) { msg.error(e.message) }
  finally { applying.value = false }
}

onMounted(load)
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <n-button @click="router.push('/')">← 返回</n-button>
      <h2>模型配置</h2>
      <div class="spacer" />
      <n-button type="primary" @click="showProvider = true">+ 添加 Provider</n-button>
      <n-button type="warning" :loading="applying" @click="apply">应用并重启 opencode</n-button>
    </div>

    <n-spin :show="loading">
      <n-card v-for="p in providers" :key="p.id" class="provider-card" :title="`${p.name} (${p.providerId})`">
        <template #header-extra>
          <n-switch :value="!!p.enabled" @update:value="toggleEnabled(p)" />
          <n-button size="small" quaternary @click="showModelFor = p.providerId; newModel = { providerId: p.providerId, modelId: '', name: '' }">+ 模型</n-button>
          <n-button size="small" quaternary type="error" @click="removeProvider(p.id)">删除</n-button>
        </template>
        <div class="meta">
          <span>Base URL: {{ p.baseUrl || '—' }}</span>
          <span>NPM: {{ p.npm || '—' }}</span>
          <span>Env: {{ p.envVarName }}</span>
          <span>API Key: {{ p.apiKeyMasked || '—' }}</span>
        </div>
        <n-table size="small" :bordered="false" v-if="p.models?.length">
          <thead><tr><th>Model ID</th><th>名称</th><th>状态</th><th></th></tr></thead>
          <tbody>
            <tr v-for="m in p.models" :key="m.id">
              <td><code>{{ m.modelId }}</code></td>
              <td>{{ m.name }}</td>
              <td><n-tag :type="m.enabled ? 'success' : 'default'" size="tiny">{{ m.enabled ? '启用' : '禁用' }}</n-tag></td>
              <td><n-button size="tiny" quaternary type="error" @click="removeModel(m.id)">删除</n-button></td>
            </tr>
          </tbody>
        </n-table>
        <n-empty v-else size="small" description="暂无模型" />
      </n-card>
      <n-empty v-if="!providers.length && !loading" description="尚未配置任何 Provider" />
    </n-spin>

    <!-- 新增 Provider -->
    <n-modal v-model:show="showProvider" preset="card" title="添加 Provider" style="width: 480px">
      <n-form label-placement="top">
        <n-form-item label="Provider ID（逻辑标识，如 openai / ollama）"><n-input v-model:value="newProvider.providerId" /></n-form-item>
        <n-form-item label="名称"><n-input v-model:value="newProvider.name" /></n-form-item>
        <n-form-item label="Base URL（OpenAI 兼容端点，内置 provider 可留空）"><n-input v-model:value="newProvider.baseUrl" placeholder="http://127.0.0.1:11434/v1" /></n-form-item>
        <n-form-item label="NPM 包名（自定义 provider 的 AI SDK 包，如 @ai-sdk/openai-compatible；内置 provider 可留空）"><n-input v-model:value="newProvider.npm" placeholder="@ai-sdk/openai-compatible" /></n-form-item>
        <n-form-item label="API Key"><n-input v-model:value="newProvider.apiKey" type="password" /></n-form-item>
        <n-form-item label="Env 变量名（留空自动生成 <PROVIDER>_API_KEY）"><n-input v-model:value="newProvider.envVarName" placeholder="OPENAI_API_KEY" /></n-form-item>
        <n-button type="primary" @click="addProvider">添加</n-button>
      </n-form>
    </n-modal>

    <!-- 新增 Model -->
    <n-modal v-model:show="modelModalShow" preset="card" title="添加模型" style="width: 420px">
      <n-form label-placement="top">
        <n-form-item label="Model ID"><n-input v-model:value="newModel.modelId" placeholder="gpt-4o-mini" /></n-form-item>
        <n-form-item label="显示名称"><n-input v-model:value="newModel.name" placeholder="GPT-4o mini" /></n-form-item>
        <n-button type="primary" @click="addModel">添加</n-button>
      </n-form>
    </n-modal>
  </div>
</template>

<style scoped>
.admin-view {
  min-height: 100vh; background: var(--bg);
  max-width: 900px; margin: 0 auto; padding: 20px;
  box-sizing: border-box;
}
.admin-head {
  display: flex; align-items: center; gap: 12px; margin-bottom: 20px;
  padding: 14px 16px; background: var(--bg-elev);
  border: 1px solid var(--border-soft); border-radius: var(--radius);
}
.admin-head h2 { margin: 0; font-size: 16px; }
.spacer { flex: 1; }
.provider-card { margin-bottom: 16px; }
.meta { display: flex; gap: 20px; font-size: 12px; color: var(--text-2); margin-bottom: 12px; flex-wrap: wrap; }

/* 卡片改白色底:与深色页面背景形成高对比;卡片内文字/表格配套适配为深色 */
.admin-view :deep(.n-card) {
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: var(--radius);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.18);
}
.admin-view :deep(.n-card__header) {
  --n-title-text-color: #1f2329;
}
.admin-view :deep(.n-card__header),
.admin-view :deep(.n-card__title),
.admin-view :deep(.n-card__content) {
  color: #1f2329;
}
.admin-view :deep(.n-card .meta) {
  color: #4a505a;
}
.admin-view :deep(.n-table) {
  color: #1f2329;
}
.admin-view :deep(.n-table .n-data-table-th),
.admin-view :deep(.n-table .n-data-table-td),
.admin-view :deep(.n-table thead th),
.admin-view :deep(.n-table tbody td) {
  color: #2a2e35;
  background: #ffffff;
}
.admin-view :deep(.n-table thead th) {
  background: #f5f6f8;
}
.admin-view :deep(.n-table tbody tr:hover) {
  background: #f3f5f9;
}
.admin-view :deep(.n-switch__track) {
  background-color: rgba(0, 0, 0, 0.35);
}
.admin-view :deep(.n-card .n-button) {
  --n-text-color: #2a2e35;
  --n-text-color-hover: #1f2329;
  --n-text-color-pressed: #1f2329;
  --n-text-color-focus: #2a2e35;
}
.admin-view :deep(.n-card .n-button--error-type) {
  --n-text-color: #c0392b;
  --n-text-color-hover: #a93226;
  --n-text-color-pressed: #a93226;
}
/* 白卡片内的空态与代码文字改深色 */
.admin-view :deep(.n-card .n-empty__description) {
  color: #5a616b;
}
.admin-view :deep(.n-card code) {
  color: #1f2329;
}
</style>
