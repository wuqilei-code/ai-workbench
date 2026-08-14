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
const savingProvider = ref(false)
const savingModel = ref(false)
/** 行级操作进行中(provider 删除/启停,model 删除):以字符串标识,如 'p-1' / 'm-3' */
const rowBusy = ref<string | null>(null)

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
  if (savingProvider.value) return
  savingProvider.value = true
  try {
    await adminApi.createProvider({ ...newProvider.value })
    msg.success('已添加')
    showProvider.value = false
    newProvider.value = { providerId: '', name: '', baseUrl: '', npm: '', apiKey: '', envVarName: '', enabled: 1 }
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally { savingProvider.value = false }
}

async function addModel() {
  if (!showModelFor.value || savingModel.value) return
  savingModel.value = true
  try {
    await adminApi.createModel({ providerId: showModelFor.value, modelId: newModel.value.modelId, name: newModel.value.name })
    msg.success('已添加模型')
    showModelFor.value = null
    newModel.value = { providerId: '', modelId: '', name: '' }
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally { savingModel.value = false }
}

async function removeProvider(id: number) {
  if (rowBusy.value !== null) return
  if (!confirm('删除该 provider 及其所有模型？')) return
  rowBusy.value = 'p-' + id
  try { await adminApi.deleteProvider(id); msg.success('已删除'); await load() }
  catch (e: any) { msg.error(e.message) }
  finally { rowBusy.value = null }
}

async function removeModel(id: number) {
  if (rowBusy.value !== null) return
  rowBusy.value = 'm-' + id
  try { await adminApi.deleteModel(id); msg.success('已删除'); await load() }
  catch (e: any) { msg.error(e.message) }
  finally { rowBusy.value = null }
}

async function toggleEnabled(p: ProviderVo) {
  if (rowBusy.value !== null) return
  rowBusy.value = 'p-' + p.id
  try {
    // 传 npm 保持原值(后端 null=不修改,这里显式传当前值)
    await adminApi.updateProvider(p.id, { providerId: p.providerId, name: p.name, baseUrl: p.baseUrl || '', npm: p.npm || '', enabled: p.enabled ? 0 : 1 })
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally { rowBusy.value = null }
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
          <n-switch :value="!!p.enabled" :disabled="rowBusy !== null" :loading="rowBusy === 'p-' + p.id" @update:value="toggleEnabled(p)" />
          <n-button size="small" quaternary :disabled="rowBusy !== null" @click="showModelFor = p.providerId; newModel = { providerId: p.providerId, modelId: '', name: '' }">+ 模型</n-button>
          <n-button size="small" quaternary type="error" :loading="rowBusy === 'p-' + p.id" :disabled="rowBusy !== null" @click="removeProvider(p.id)">删除</n-button>
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
              <td><n-button size="tiny" quaternary type="error" :loading="rowBusy === 'm-' + m.id" :disabled="rowBusy !== null" @click="removeModel(m.id)">删除</n-button></td>
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
        <n-button type="primary" :loading="savingProvider" :disabled="savingProvider" @click="addProvider">添加</n-button>
      </n-form>
    </n-modal>

    <!-- 新增 Model -->
    <n-modal v-model:show="modelModalShow" preset="card" title="添加模型" style="width: 420px">
      <n-form label-placement="top">
        <n-form-item label="Model ID"><n-input v-model:value="newModel.modelId" placeholder="gpt-4o-mini" /></n-form-item>
        <n-form-item label="显示名称"><n-input v-model:value="newModel.name" placeholder="GPT-4o mini" /></n-form-item>
        <n-button type="primary" :loading="savingModel" :disabled="savingModel" @click="addModel">添加</n-button>
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
.admin-head h2 { margin: 0; font-size: 16px; color: var(--text); }
.spacer { flex: 1; }
.provider-card { margin-bottom: 16px; }
.meta { display: flex; gap: 20px; font-size: 12px; color: var(--text-2); margin-bottom: 12px; flex-wrap: wrap; }

/* 卡片表面:略亮于页面背景,保持深色主题统一 */
.admin-view :deep(.n-card) {
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}
</style>
