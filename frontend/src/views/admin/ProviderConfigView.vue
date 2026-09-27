<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { adminApi } from '@/api/admin'
import { useMessage, useDialog } from 'naive-ui'
import type { ProviderVo } from '@/types'

const router = useRouter()
const { t } = useI18n()
const msg = useMessage()
const dialog = useDialog()
const providers = ref<ProviderVo[]>([])
const loading = ref(false)
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
    msg.success(t('admin.provider.added'))
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
    msg.success(t('admin.provider.modelAdded'))
    showModelFor.value = null
    newModel.value = { providerId: '', modelId: '', name: '' }
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally { savingModel.value = false }
}

async function removeProvider(id: number) {
  if (rowBusy.value !== null) return
  dialog.warning({
    title: t('admin.provider.deleteTitle'),
    content: t('admin.provider.deleteContent'),
    positiveText: t('common.delete'),
    negativeText: t('common.cancel'),
    onPositiveClick: async () => {
      rowBusy.value = 'p-' + id
      try { await adminApi.deleteProvider(id); msg.success(t('admin.provider.deleted')); await load() }
      catch (e: any) { msg.error(e.message) }
      finally { rowBusy.value = null }
    },
  })
}

async function removeModel(id: number) {
  if (rowBusy.value !== null) return
  rowBusy.value = 'm-' + id
  try { await adminApi.deleteModel(id); msg.success(t('admin.provider.deleted')); await load() }
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

onMounted(load)
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <n-button @click="router.push('/')">{{ t('common.back') }}</n-button>
      <h2>{{ t('admin.provider.title') }}</h2>
      <div class="spacer" />
      <n-button type="primary" @click="showProvider = true">{{ t('admin.provider.addProvider') }}</n-button>
    </div>

    <n-spin :show="loading">
      <n-card v-for="p in providers" :key="p.id" class="provider-card" :title="`${p.name} (${p.providerId})`">
        <template #header-extra>
          <n-switch :value="!!p.enabled" :disabled="rowBusy !== null" :loading="rowBusy === 'p-' + p.id" @update:value="toggleEnabled(p)" />
          <n-button size="small" quaternary :disabled="rowBusy !== null" @click="showModelFor = p.providerId; newModel = { providerId: p.providerId, modelId: '', name: '' }">{{ t('admin.provider.addModel') }}</n-button>
          <n-button size="small" quaternary type="error" :loading="rowBusy === 'p-' + p.id" :disabled="rowBusy !== null" @click="removeProvider(p.id)">{{ t('common.delete') }}</n-button>
        </template>
        <div class="meta">
          <span>Base URL: {{ p.baseUrl || '—' }}</span>
          <span>NPM: {{ p.npm || '—' }}</span>
          <span>Env: {{ p.envVarName }}</span>
          <span>API Key: {{ p.apiKeyMasked || '—' }}</span>
        </div>
        <n-table size="small" :bordered="false" v-if="p.models?.length">
          <thead><tr><th>{{ t('admin.provider.modelId') }}</th><th>{{ t('admin.provider.name') }}</th><th>{{ t('admin.provider.statusHeader') }}</th><th></th></tr></thead>
          <tbody>
            <tr v-for="m in p.models" :key="m.id">
              <td><code>{{ m.modelId }}</code></td>
              <td>{{ m.name }}</td>
              <td><n-tag :type="m.enabled ? 'success' : 'default'" size="tiny">{{ m.enabled ? t('admin.provider.enabled') : t('admin.provider.disabled') }}</n-tag></td>
              <td><n-button size="tiny" quaternary type="error" :loading="rowBusy === 'm-' + m.id" :disabled="rowBusy !== null" @click="removeModel(m.id)">{{ t('common.delete') }}</n-button></td>
            </tr>
          </tbody>
        </n-table>
        <n-empty v-else size="small" :description="t('admin.provider.emptyModels')" />
      </n-card>
      <n-empty v-if="!providers.length && !loading" :description="t('admin.provider.emptyProviders')" />
    </n-spin>

    <!-- 新增 Provider -->
    <n-modal v-model:show="showProvider" preset="card" :title="t('admin.provider.addProviderTitle')" style="width: 480px">
      <n-form label-placement="top">
        <n-form-item :label="t('admin.provider.providerIdLabel')"><n-input v-model:value="newProvider.providerId" /></n-form-item>
        <n-form-item :label="t('admin.provider.nameLabel')"><n-input v-model:value="newProvider.name" /></n-form-item>
        <n-form-item :label="t('admin.provider.baseUrlLabel')"><n-input v-model:value="newProvider.baseUrl" placeholder="http://127.0.0.1:11434/v1" /></n-form-item>
        <n-form-item :label="t('admin.provider.npmLabel')"><n-input v-model:value="newProvider.npm" placeholder="@ai-sdk/openai-compatible" /></n-form-item>
        <n-form-item :label="t('admin.provider.apiKeyLabel')"><n-input v-model:value="newProvider.apiKey" type="password" /></n-form-item>
        <n-form-item :label="t('admin.provider.envLabel')"><n-input v-model:value="newProvider.envVarName" placeholder="OPENAI_API_KEY" /></n-form-item>
        <n-button type="primary" :loading="savingProvider" :disabled="savingProvider" @click="addProvider">{{ t('admin.provider.add') }}</n-button>
      </n-form>
    </n-modal>

    <!-- 新增 Model -->
    <n-modal v-model:show="modelModalShow" preset="card" :title="t('admin.provider.addModelTitle')" style="width: 420px">
      <n-form label-placement="top">
        <n-form-item :label="t('admin.provider.modelId')"><n-input v-model:value="newModel.modelId" placeholder="gpt-4o-mini" /></n-form-item>
        <n-form-item :label="t('admin.provider.modelNameLabel')"><n-input v-model:value="newModel.name" placeholder="GPT-4o mini" /></n-form-item>
        <n-button type="primary" :loading="savingModel" :disabled="savingModel" @click="addModel">{{ t('admin.provider.add') }}</n-button>
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
