<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { mcpApi } from '@/api/mcp'
import { useMessage, NModal } from 'naive-ui'
import type { McpVo } from '@/types'
import SectionTabs from '@/components/ui/SectionTabs.vue'
import ListPager from '@/components/ui/ListPager.vue'

const { t } = useI18n()
const props = defineProps<{ show?: boolean; page?: boolean }>()
const emit = defineEmits<{ 'update:show': [v: boolean]; close: [] }>()

/** 根节点绑定:page 模式渲染为整页(带面包屑),否则渲染为弹窗 */
const rootBind = computed(() =>
  props.page
    ? { class: 'manage-page' }
    : {
        show: props.show,
        preset: 'card',
    title: t('mcp.title'),
        style: 'width: 780px; max-width: 96vw',
        'onUpdate:show': (v: boolean) => emit('update:show', v),
      },
)

const msg = useMessage()

const servers = ref<McpVo[]>([])
const loading = ref(false)
const rowBusy = ref<string | null>(null)

/** 工具库两类 Tab：MCP Server / 接口工具，分别独立维护 */
type TabType = 'remote' | 'manual'
const activeTab = ref<TabType>('remote')

const tabOptions: Array<{ label: string; value: TabType }> = [
  { label: 'MCP Server', value: 'remote' },
  { label: t('mcp.typeManual'), value: 'manual' },
]
/** 统一下划线 Tab 组件的 items(带各类型计数) */
const tabItems = computed(() =>
  tabOptions.map((o) => ({
    key: o.value,
    label: o.label,
    count: servers.value.filter((s) => serverType(s) === o.value).length,
  })),
)

function serverType(s: McpVo): TabType {
  return s.type === 'manual' ? 'manual' : 'remote'
}

const filteredServers = computed(() => servers.value.filter((s) => serverType(s) === activeTab.value))

const tabDesc: Record<TabType, string> = {
    remote: t('mcp.typeDescRemote'),
    manual: t('mcp.typeDescManual'),
}

// ---------- 分页 ----------
const page = ref(1)
const pageSize = 12
const pageCount = computed(() => Math.max(1, Math.ceil(filteredServers.value.length / pageSize)))
const pagedServers = computed(() => filteredServers.value.slice((page.value - 1) * pageSize, page.value * pageSize))
watch(activeTab, () => { page.value = 1 })
watch(() => filteredServers.value.length, (len) => { if (page.value > pageCount.value) page.value = pageCount.value })

const formView = ref(false)
const editingId = ref<number | null>(null)
type FormType = 'remote' | 'openapi' | 'manual'
const form = ref<{ name: string; type: FormType; url: string; headers: string; enabled: number }>({ name: '', type: 'remote', url: '', headers: '', enabled: 1 })
const formSaving = ref(false)
/** remote 传输协议(HTTP 默认;SSE 会在 URL 上带 transport=sse,agent-service 据此选 transport) */
const transport = ref<'http' | 'sse'>('http')
function deriveTransport(url: string): 'http' | 'sse' {
  const u = (url || '').toLowerCase()
  return (u.includes('/sse') || u.includes('transport=sse')) ? 'sse' : 'http'
}
function applyTransport(url: string): string {
  try {
    const u = new URL(url)
    if (transport.value === 'sse') u.searchParams.set('transport', 'sse')
    else u.searchParams.delete('transport')
    return u.toString()
  } catch {
    return url
  }
}

interface ParamRow { name: string; in: string; required: boolean; type: string; description: string }
interface BodyRow { name: string; type: string; required: boolean; description: string }
interface ManualRow {
  method: string
  name: string
  path: string
  description: string
  params: ParamRow[]
  body_fields: BodyRow[]
  open: boolean
  busy?: boolean
}
const manualRows = ref<ManualRow[]>([])
const methodOptions = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map((m) => ({ label: m, value: m }))
const paramInOptions = ['query', 'path', 'header', 'cookie'].map((v) => ({ label: v, value: v }))
const typeOptions = ['string', 'integer', 'number', 'boolean', 'array', 'object'].map((v) => ({ label: v, value: v }))

function newEndpoint(): ManualRow {
  return { method: 'GET', name: '', path: '/', description: '', params: [], body_fields: [], open: true }
}
function removeEndpoint(i: number) { manualRows.value.splice(i, 1) }
function addParam(row: ManualRow) { row.params.push({ name: '', in: 'query', required: false, type: 'string', description: '' }) }
function addBody(row: ManualRow) { row.body_fields.push({ name: '', type: 'string', required: false, description: '' }) }

// ---------- 接口编辑(独立子页:添加/编辑接口) ----------
const ifaceView = ref(false)
const ifaceEditIdx = ref(-1)
const ifaceDraft = ref<ManualRow>(newEndpoint())
function openAddEndpoint() {
  ifaceEditIdx.value = -1
  ifaceDraft.value = newEndpoint()
  ifaceView.value = true
}
function openEditEndpoint(i: number) {
  ifaceEditIdx.value = i
  ifaceDraft.value = JSON.parse(JSON.stringify(manualRows.value[i]))
  ifaceView.value = true
}
function cancelIface() { ifaceView.value = false }
function saveIfaceDraft() {
  const d = ifaceDraft.value
  if (!d.name.trim() || !d.name.trim().match(/^[A-Za-z0-9_]+$/)) { msg.warning(t('mcp.ifaceNameInvalid')); return }
  if (!d.path.trim()) { msg.warning(t('mcp.ifacePathEmpty')); return }
  if (ifaceEditIdx.value >= 0) manualRows.value[ifaceEditIdx.value] = d
  else manualRows.value.push(d)
  ifaceView.value = false
  msg.success(ifaceEditIdx.value >= 0 ? t('mcp.ifaceUpdated') : t('mcp.ifaceAdded'))
}

function parseManualConfig(config?: string | null): ManualRow[] {
  if (!config) return []
  try {
    const arr = JSON.parse(config)
    if (!Array.isArray(arr)) return []
    return arr.map((c: any) => ({
      method: (c.method || 'GET').toUpperCase(),
      name: c.name || '',
      path: c.path || '/',
      description: c.description || '',
      params: (c.params || []).map((p: any) => ({ name: p.name || '', in: p.in || 'query', required: !!p.required, type: p.type || 'string', description: p.description || '' })),
      body_fields: (c.body_fields || []).map((b: any) => ({ name: b.name || '', type: b.type || 'string', required: !!b.required, description: b.description || '' })),
      open: false,
    }))
  } catch { return [] }
}

function buildManualConfig(): { ok: boolean; err?: string; config?: string } {
  if (form.value.type !== 'manual') return { ok: true }
  if (!manualRows.value.length) return { ok: false, err: t('mcp.needOneIface') }
  for (let i = 0; i < manualRows.value.length; i++) {
    const r = manualRows.value[i]
    if (!r.name.trim() || !r.name.trim().match(/^[A-Za-z0-9_]+$/)) return { ok: false, err: t('mcp.ifaceNameInvalidAt', { i: i + 1 }) }
    if (!r.path.trim()) return { ok: false, err: t('mcp.ifacePathMissingAt', { i: i + 1 }) }
  }
  const config = manualRows.value.map((r) => {
    const item: any = { name: r.name.trim(), method: r.method, path: r.path.trim(), description: r.description.trim() }
    const params = r.params.filter((p) => p.name.trim()).map((p) => {
      const item: any = { name: p.name.trim(), in: p.in, required: p.required, type: p.type }
      if (p.description && p.description.trim()) item.description = p.description.trim()
      return item
    })
    const body_fields = r.body_fields.filter((b) => b.name.trim()).map((b) => {
      const item: any = { name: b.name.trim(), type: b.type, required: b.required }
      if (b.description && b.description.trim()) item.description = b.description.trim()
      return item
    })
    if (params.length) item.params = params
    if (body_fields.length) item.body_fields = body_fields
    return item
  })
  return { ok: true, config: JSON.stringify(config) }
}

// ---------- Headers 键值表(替代手写 JSON) ----------
const headerRows = ref<{ key: string; value: string }[]>([])
function parseHeaderRows(json?: string | null) {
  headerRows.value = []
  if (!json || !json.trim()) return
  try {
    const o = JSON.parse(json)
    if (o && typeof o === 'object' && !Array.isArray(o)) {
      headerRows.value = Object.entries(o).map(([k, v]) => ({ key: k, value: String(v) }))
    }
  } catch { /* ignore */ }
}
function addHeaderRow() { headerRows.value.push({ key: '', value: '' }) }
function removeHeaderRow(i: number) { headerRows.value.splice(i, 1) }
function buildHeadersJson(): string {
  const o: Record<string, string> = {}
  for (const r of headerRows.value) {
    const k = r.key.trim()
    if (k) o[k] = r.value
  }
  return Object.keys(o).length ? JSON.stringify(o) : ''
}

// ---------- AI 填充参数(按接口) ----------
async function aiFill(row: ManualRow) {
    if (!row.path.trim()) { msg.warning(t('mcp.pathNoPath')); return }
  if (row.busy) return
  row.busy = true
  try {
    const res: any = await mcpApi.manualAssistParams({
      method: row.method, path: row.path, name: row.name, description: row.description,
    })
    if (!res?.ok) throw new Error(res?.error || t('mcp.aiParseFailed'))
    const params = res.params || []
    const body_fields = res.body_fields || []
    if (!params.length && !body_fields.length) {
    msg.info(t('mcp.aiNoParams'))
      return
    }
    row.params = params.map((p: any) => ({
      name: p.name || '', in: p.in || 'query', required: !!p.required, type: p.type || 'string', description: p.description || '',
    }))
    row.body_fields = body_fields.map((b: any) => ({
      name: b.name || '', type: b.type || 'string', required: !!b.required, description: b.description || '',
    }))
    msg.success(t('mcp.aiFilled', { n: params.length, m: body_fields.length }))
  } catch (e: any) {
    msg.error(e?.message || t('mcp.aiFillFailed'))
  } finally {
    row.busy = false
  }
}

function methodColor(m: string): 'success' | 'warning' | 'error' | 'info' | 'default' {
  const s = (m || '').toUpperCase()
  if (s === 'GET') return 'success'
  if (s === 'POST') return 'warning'
  if (s === 'PUT' || s === 'PATCH') return 'info'
  if (s === 'DELETE') return 'error'
  return 'default'
}

function metaPreview(meta: string): string {
  if (!meta.trim()) return t('mcp.noParams')
  try {
    const obj = JSON.parse(meta)
    const p = Array.isArray(obj.params) ? obj.params.length : 0
    const b = Array.isArray(obj.body_fields) ? obj.body_fields.length : 0
  if (p && b) return t('mcp.pCount', { p }) + ' + ' + t('mcp.bCount', { b })
  if (p) return t('mcp.pCount', { p })
  if (b) return t('mcp.bCount', { b })
  return t('mcp.noParams')
} catch { return t('mcp.jsonParseFailed') }
}

// ---------- 列表 / 表单 ----------
async function loadServers() {
  servers.value = []
  loading.value = true
  try {
    servers.value = await mcpApi.list()
  } catch (e: any) {
    msg.error(e?.message || t('mcp.loadFailed'))
  } finally {
    loading.value = false
  }
}
watch(() => props.show, (v) => { if (v) loadServers() })
onMounted(() => { if (props.page) loadServers() })

function openCreate() {
  form.value = { name: '', type: activeTab.value, url: '', headers: '', enabled: 1 }
  manualRows.value = []
  parseHeaderRows('')
  transport.value = 'http'
  editingId.value = null
  formView.value = true
}
function openEdit(s: McpVo) {
  editingId.value = s.id
  form.value = {
    name: s.name,
    type: (s.type === 'openapi' || s.type === 'manual' ? s.type : 'remote'),
    url: s.url || '',
    headers: s.headers || '',
    enabled: s.enabled ?? 1,
  }
  manualRows.value = form.value.type === 'manual' ? parseManualConfig(s.config) : []
  parseHeaderRows(s.headers)
  transport.value = deriveTransport(s.url || '')
  ifaceView.value = false
  formView.value = true
}
/** 关闭表单页,回到工具列表 */
function closeForm() {
  formView.value = false
  ifaceView.value = false
}

function isValidJsonObject(text: string): boolean {
  try {
    const v = JSON.parse(text)
    return !!v && typeof v === 'object' && !Array.isArray(v)
  } catch { return false }
}

async function saveServer() {
  const name = form.value.name.trim()
  const url = form.value.url.trim()
    if (!name) { msg.warning(t('mcp.nameEmpty')); return }
    if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(name)) { msg.warning(t('mcp.namePattern')); return }
    if (!/^https?:\/\/.+/.test(url)) { msg.warning(t('mcp.urlInvalid')); return }
  // Headers 由键值表生成合法 JSON(空表 → 空串)
  form.value.headers = buildHeadersJson()
  const finalType = form.value.type

  if (finalType === 'manual') {
    const manual = buildManualConfig()
    if (!manual.ok) { msg.warning(manual.err || t('mcp.manualInvalid')); return }
    const payload: any = { name, type: 'manual', url, headers: form.value.headers || null, config: manual.config, enabled: form.value.enabled, sortOrder: 0 }
    formSaving.value = true
    try {
      if (editingId.value != null) await mcpApi.update(editingId.value, payload)
      else await mcpApi.create(payload)
      msg.success(editingId.value != null ? t('mcp.updated') : t('mcp.created'))
      await afterSave(finalType)
    } catch (e: any) { msg.error(e?.message || t('mcp.createFailed')) } finally { formSaving.value = false }
    return
  }

  const payload: any = { name, type: finalType, url: applyTransport(url), headers: form.value.headers || null, enabled: form.value.enabled, sortOrder: 0 }
  formSaving.value = true
  try {
    if (editingId.value != null) await mcpApi.update(editingId.value, payload)
    else await mcpApi.create(payload)
      msg.success(editingId.value != null ? t('mcp.updated') : t('mcp.created'))
    await afterSave(finalType)
    } catch (e: any) { msg.error(e?.message || t('mcp.updateFailed')) } finally { formSaving.value = false }
}

/** 保存成功后:切到对应 Tab 并重新拉取列表(否则新建/修改不会立即出现在列表) */
async function afterSave(type: FormType) {
  formView.value = false
  activeTab.value = type === 'manual' ? 'manual' : 'remote'
  page.value = 1
  await loadServers()
}

async function deleteServer(s: McpVo) {
  rowBusy.value = String(s.id)
  try {
    await mcpApi.remove(s.id)
    servers.value = servers.value.filter((x) => x.id !== s.id)
      msg.success(t('mcp.deleted'))
    } catch (e: any) { msg.error(e?.message || t('mcp.deleteFailed')) } finally { rowBusy.value = null }
}

async function toggleServer(s: McpVo) {
  rowBusy.value = String(s.id)
  try {
    const newEnabled = s.enabled === 1 ? 0 : 1
    await mcpApi.update(s.id, { enabled: newEnabled })
    s.enabled = newEnabled
      msg.success(newEnabled ? t('mcp.enabled') : t('mcp.disabled'))
    } catch (e: any) { msg.error(e?.message || t('mcp.enableFailed')) } finally { rowBusy.value = null }
}

function headerPreview(headers?: string | null): string {
  if (!headers) return t('mcp.none')
  try {
    const obj = JSON.parse(headers)
  return Object.keys(obj).join(', ') || t('mcp.none')
  } catch { return headers }
}

function manualCount(s: McpVo): number {
  if (!s.config) return 0
  try {
    const arr = JSON.parse(s.config)
    return Array.isArray(arr) ? arr.length : 0
  } catch { return 0 }
}
</script>

<template>
  <component :is="page ? 'div' : NModal" v-bind="rootBind">
    <!-- 整页模式:面包屑 + 页头 -->
    <div v-if="page" class="manage-crumb">
      <button class="crumb-link" @click="emit('close')">首页</button>
      <span class="crumb-sep">/</span>
      <button v-if="formView" class="crumb-link" @click="closeForm">{{ t('mcp.title') }}</button>
      <span v-else class="crumb-cur">{{ t('mcp.title') }}</span>
      <template v-if="formView">
        <span class="crumb-sep">/</span>
      <button v-if="ifaceView" class="crumb-link" @click="cancelIface">{{ editingId != null ? t('mcp.editServer') : t('mcp.newServer') }}</button>
      <span v-else class="crumb-cur">{{ editingId != null ? t('mcp.editServer') : t('mcp.newServer') }}</span>
      </template>
      <template v-if="ifaceView">
        <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ ifaceEditIdx >= 0 ? t('mcp.editIface') : t('mcp.addIface') }}</span>
      </template>
    </div>
    <div v-if="page" class="manage-header">
      <div>
    <div class="manage-title">{{ ifaceView ? (ifaceEditIdx >= 0 ? t('mcp.editIface') : t('mcp.addIface')) : (formView ? (editingId != null ? t('mcp.editServer') : t('mcp.newServer')) : t('mcp.title')) }}</div>
    <div class="manage-sub" v-if="!formView">{{ t('mcp.manageSub') }}</div>
      </div>
      <n-button v-if="!formView" type="primary" @click="openCreate">{{ t('mcp.addBtn') }}</n-button>
    </div>
    <!-- ===== 列表页 ===== -->
    <template v-if="!formView">
      <!-- Tabs(统一下划线 Tab 组件) -->
      <SectionTabs v-model:active="activeTab" :items="tabItems" class="lib-tabs" :aria-label="t('mcp.ariaTabs')" />

      <!-- Banner -->
      <div class="lib-banner">
        <span class="banner-ico">ℹ️</span>
        <div>{{ tabDesc[activeTab] }}</div>
      </div>

      <!-- 已配置 -->
      <section class="lib-panel">
        <div class="lib-panel-head">
      <span class="sec-title">{{ t('mcp.secBasic') }}</span>
          <span class="count-chip">{{ filteredServers.length }}</span>
          <span class="spacer"></span>
        </div>
        <div class="lib-panel-body">
          <n-spin :show="loading">
      <div v-if="!pagedServers.length && !loading" class="tf-empty">{{ t('mcp.emptyList') }}</div>
            <div v-else class="tool-grid">
            <article v-for="s in pagedServers" :key="s.id" class="mcp-card">
              <div class="mcp-top">
                <div class="mcp-icon">{{ (s.name || '?').slice(0, 1).toUpperCase() }}</div>
                <div>
                  <div class="mcp-name">
                    {{ s.name }}
        <span class="badge" :class="s.enabled === 1 ? 'on' : 'off'">{{ s.enabled === 1 ? t('mcp.enabled') : t('mcp.disabled') }}</span>
                  </div>
        <div class="mcp-type">{{ s.type === 'manual' ? t('mcp.typeManualShort') : t('mcp.typeRemoteShort') }}</div>
                </div>
                <span class="spacer"></span>
                <div class="mcp-actions">
        <button class="btn-link edit" @click="openEdit(s)">{{ t('mcp.edit') }}</button>
        <button class="btn-link disable" :disabled="rowBusy === String(s.id)" @click="toggleServer(s)">{{ s.enabled === 1 ? t('mcp.disable') : t('mcp.enable') }}</button>
        <button class="btn-link danger" :disabled="rowBusy === String(s.id)" @click="deleteServer(s)">{{ t('mcp.delete') }}</button>
                </div>
              </div>
              <div class="mcp-detail">
                <div class="detail-item">
          <div class="detail-label">{{ s.type === 'manual' ? 'Base URL' : t('mcp.labelCommand') }}</div>
                  <div class="detail-val"><code class="url">{{ s.url || '—' }}</code></div>
                </div>
                <div class="detail-item">
          <div class="detail-label">{{ s.type === 'manual' ? t('mcp.labelIfaces') : t('mcp.labelTools') }}</div>
                  <div class="detail-val">
        <template v-if="s.type === 'manual'">{{ t('mcp.ifacesCount', { n: manualCount(s) }) }}</template>
                    <span v-else class="secret-tag"><span class="dot"></span>{{ headerPreview(s.headers) }}</span>
                  </div>
                </div>
              </div>
            </article>
            </div>
          </n-spin>
        </div>
        <div v-if="filteredServers.length > pageSize" class="lib-pager">
          <ListPager :page="page" :item-count="filteredServers.length" :page-size="pageSize" @update:page="page = $event" />
        </div>
      </section>
    </template>

    <!-- ===== 表单页(整页,无弹窗) ===== -->
    <div v-else class="tool-form-page">
      <!-- ---- 接口编辑子页(添加/编辑接口,独立维护) ---- -->
      <template v-if="ifaceView">
        <div class="tf-steps">
        <div class="tf-step done"><span class="dot">✓</span> {{ t('mcp.step1') }}</div>
          <div class="tf-step-line done"></div>
        <div class="tf-step active"><span class="dot">2</span> {{ t('mcp.step2Iface') }}</div>
          <div class="tf-step-line"></div>
        <div class="tf-step"><span class="dot">3</span> {{ t('mcp.step3') }}</div>
        </div>

        <section class="tf-card">
          <div class="tf-card-head">
            <span class="sec-icon">🧩</span>
      <div><div class="sec-title">{{ t('mcp.secIfaceInfo') }}</div><div class="sec-desc">{{ t('mcp.secIfaceInfoDesc') }}</div></div>
          </div>
          <div class="tf-card-body">
            <div class="tf-grid2">
        <div class="tf-field"><label>{{ t('mcp.labelMethod') }} <span class="req">*</span></label><n-select v-model:value="ifaceDraft.method" :options="methodOptions" /></div>
        <div class="tf-field"><label>{{ t('mcp.labelIfaceName') }} <span class="req">*</span><span class="hint">{{ t('mcp.hintNamePattern') }}</span></label><n-input v-model:value="ifaceDraft.name" placeholder="get_order" /></div>
        <div class="tf-field full"><label>{{ t('mcp.labelPath') }} <span class="req">*</span><span class="hint">{{ t('mcp.hintPath') }}</span></label><n-input v-model:value="ifaceDraft.path" placeholder="/orders/{id}" /></div>
        <div class="tf-field full"><label>{{ t('mcp.labelDesc') }} <span class="hint">{{ t('mcp.hintDesc') }}</span></label><n-input v-model:value="ifaceDraft.description" :placeholder="t('mcp.phDesc')" /></div>
            </div>
          </div>
        </section>

        <section class="tf-card">
          <div class="tf-card-head">
            <span class="sec-icon">🔧</span>
      <div><div class="sec-title">{{ t('mcp.secParams') }}</div><div class="sec-desc">{{ t('mcp.secParamsDesc') }}</div></div>
            <span class="spacer"></span>
        <n-button size="small" type="primary" ghost @click="addParam(ifaceDraft)">{{ t('mcp.addParam') }}</n-button>
          </div>
          <div class="tf-card-body">
        <div v-if="!ifaceDraft.params.length" class="tf-empty">{{ t('mcp.emptyParams') }}</div>
            <table v-else class="kv-table">
        <thead><tr><th style="width:150px">{{ t('mcp.thName') }}</th><th style="width:110px">{{ t('mcp.thIn') }}</th><th style="width:110px">{{ t('mcp.thType') }}</th><th style="width:60px">{{ t('mcp.thRequired') }}</th><th>{{ t('mcp.thDesc') }}</th><th class="ops">{{ t('mcp.thOps') }}</th></tr></thead>
              <tbody>
                <tr v-for="(p, pi) in ifaceDraft.params" :key="pi">
        <td><n-input v-model:value="p.name" size="small" :placeholder="t('mcp.phParamName')" /></td>
                  <td><n-select v-model:value="p.in" size="small" :options="paramInOptions" /></td>
                  <td><n-select v-model:value="p.type" size="small" :options="typeOptions" /></td>
                  <td><n-checkbox v-model:checked="p.required" size="small" /></td>
        <td><n-input v-model:value="p.description" size="small" :placeholder="t('mcp.phParamDesc')" /></td>
        <td class="ops"><n-button text type="error" size="small" @click="ifaceDraft.params.splice(pi, 1)">{{ t('mcp.delete') }}</n-button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section class="tf-card">
          <div class="tf-card-head">
            <span class="sec-icon">📦</span>
      <div><div class="sec-title">{{ t('mcp.secBody') }}</div><div class="sec-desc">{{ t('mcp.secBodyDesc') }}</div></div>
            <span class="spacer"></span>
        <n-button size="small" type="primary" ghost @click="addBody(ifaceDraft)">{{ t('mcp.addBody') }}</n-button>
          </div>
          <div class="tf-card-body">
        <div v-if="!ifaceDraft.body_fields.length" class="tf-empty">{{ t('mcp.emptyBody') }}</div>
            <table v-else class="kv-table">
        <thead><tr><th style="width:170px">{{ t('mcp.thField') }}</th><th style="width:110px">{{ t('mcp.thRequired') }}</th><th style="width:60px">{{ t('mcp.thReq') }}</th><th>{{ t('mcp.thDesc') }}</th><th class="ops">{{ t('mcp.thOps') }}</th></tr></thead>
              <tbody>
                <tr v-for="(b, bi) in ifaceDraft.body_fields" :key="bi">
        <td><n-input v-model:value="b.name" size="small" :placeholder="t('mcp.phFieldName')" /></td>
                  <td><n-select v-model:value="b.type" size="small" :options="typeOptions" /></td>
                  <td><n-checkbox v-model:checked="b.required" size="small" /></td>
        <td><n-input v-model:value="b.description" size="small" :placeholder="t('mcp.phFieldDesc')" /></td>
        <td class="ops"><n-button text type="error" size="small" @click="ifaceDraft.body_fields.splice(bi, 1)">{{ t('mcp.delete') }}</n-button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <div class="ai-tip">
          <span>✨</span>
      <div><strong>AI {{ t('mcp.aiFill') }}</strong> — {{ t('mcp.aiHint') }}</div>
          <span class="spacer"></span>
      <n-button size="small" type="primary" ghost :loading="ifaceDraft.busy" :disabled="ifaceDraft.busy" @click="aiFill(ifaceDraft)">{{ t('mcp.aiFill') }}</n-button>
        </div>

        <div class="tf-actionbar">
          <div class="tf-actionbar-inner">
      <div class="left">{{ t('mcp.draftSummary', { method: ifaceDraft.method, path: ifaceDraft.path || '/', p: ifaceDraft.params.length, b: ifaceDraft.body_fields.length }) }}</div>
            <div class="right">
      <n-button quaternary @click="cancelIface">{{ t('mcp.cancel') }}</n-button>
      <n-button type="primary" @click="saveIfaceDraft">{{ ifaceEditIdx >= 0 ? t('mcp.saveIface') : t('mcp.addIface') }}</n-button>
            </div>
          </div>
        </div>
      </template>

      <!-- ---- 连接表单子页 ---- -->
      <template v-else>
        <div class="tf-steps">
        <div class="tf-step done"><span class="dot">✓</span> {{ t('mcp.step1') }}</div>
          <div class="tf-step-line done"></div>
        <div class="tf-step active"><span class="dot">2</span> {{ form.type === 'manual' ? t('mcp.step2Iface') : t('mcp.step2Info') }}</div>
          <div class="tf-step-line"></div>
        <div class="tf-step"><span class="dot">3</span> {{ t('mcp.step3') }}</div>
        </div>

        <!-- 基础信息 -->
        <section class="tf-card">
          <div class="tf-card-head">
            <span class="sec-icon">🔗</span>
            <div>
        <div class="sec-title">{{ t('mcp.secBasicInfo') }}</div>
        <div class="sec-desc">{{ t('mcp.secBasicInfoDesc') }}</div>
            </div>
            <span class="spacer"></span>
          </div>
          <div class="tf-card-body">
            <div class="tf-grid2">
              <div class="tf-field">
          <label>{{ t('mcp.labelKey') }} <span class="req">*</span><span class="hint">{{ t('mcp.hintKebab') }}</span></label>
                <n-input v-model:value="form.name" placeholder="my-server" />
              </div>
              <div v-if="form.type === 'manual'" class="tf-field">
          <label>{{ t('mcp.labelType') }}</label>
          <n-input :value="t('mcp.typeManualHint')" readonly />
              </div>
              <div v-else class="tf-field">
          <label>{{ t('mcp.labelKey') }} <span class="req">*</span></label>
                <n-select v-model:value="transport" :options="[{ label: 'HTTP', value: 'http' }, { label: 'SSE', value: 'sse' }]" />
              </div>
              <div class="tf-field full">
          <label>{{ t('mcp.labelEndpoint') }} <span class="req">*</span><span class="hint">{{ t('mcp.hintEndpoint') }}</span></label>
                <n-input v-model:value="form.url" :placeholder="form.type === 'manual' ? 'https://api.example.com/v1' : 'https://mcp.example.com/mcp'" />
              </div>
            </div>
          </div>
        </section>

        <!-- 请求头 -->
        <section class="tf-card">
          <div class="tf-card-head">
            <span class="sec-icon">🛡️</span>
            <div>
        <div class="sec-title">{{ t('mcp.secHeaders') }}</div>
        <div class="sec-desc">{{ t('mcp.secHeadersDesc') }}</div>
            </div>
            <span class="spacer"></span>
        <n-button size="small" type="primary" ghost @click="addHeaderRow">{{ t('mcp.addHeader') }}</n-button>
          </div>
          <div class="tf-card-body">
        <div v-if="!headerRows.length" class="tf-empty">{{ t('mcp.emptyHeaders') }}</div>
            <table v-else class="kv-table">
        <thead><tr><th>{{ t('mcp.thHeaderName') }}</th><th>{{ t('mcp.thValue') }}</th><th class="ops">{{ t('mcp.thOps') }}</th></tr></thead>
              <tbody>
                <tr v-for="(h, i) in headerRows" :key="i">
                  <td><n-input v-model:value="h.key" size="small" placeholder="Authorization" /></td>
                  <td><n-input v-model:value="h.value" size="small" placeholder="Bearer xxx" /></td>
        <td class="ops"><n-button text type="error" size="small" @click="removeHeaderRow(i)">{{ t('mcp.delete') }}</n-button></td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <!-- 接口清单(已添加接口的列表展示) -->
        <section v-if="form.type === 'manual'" class="tf-card">
          <div class="tf-card-head">
            <span class="sec-icon">🗂️</span>
            <div>
        <div class="sec-title">{{ t('mcp.secIfaces') }}</div>
        <div class="sec-desc">{{ t('mcp.secIfacesDesc') }}</div>
            </div>
            <span class="spacer"></span>
        <n-button size="small" type="primary" @click="openAddEndpoint">{{ t('mcp.addEndpoint') }}</n-button>
          </div>
          <div class="tf-card-body">
        <div v-if="!manualRows.length" class="tf-empty">{{ t('mcp.emptyEndpoints') }}</div>
            <div v-else class="endpoint-list">
              <div v-for="(row, i) in manualRows" :key="i" class="endpoint-row">
                <span class="method" :class="(row.method || 'GET').toLowerCase()">{{ row.method }}</span>
                <span class="e-path">{{ row.path || '/' }}</span>
        <span class="e-name">{{ row.name || t('mcp.unnamed') }}</span>
                <span v-if="row.description" class="e-desc">{{ row.description }}</span>
        <span class="e-meta">{{ t('mcp.eMeta', { p: row.params.length, b: row.body_fields.length }) }}</span>
                <span class="spacer"></span>
        <n-button size="tiny" quaternary @click="openEditEndpoint(i)">{{ t('mcp.edit') }}</n-button>
        <n-button size="tiny" quaternary type="error" @click="removeEndpoint(i)">{{ t('mcp.delete') }}</n-button>
              </div>
            </div>
          </div>
        </section>

        <!-- 启用 -->
        <section class="tf-card">
          <div class="tf-card-body toggle-row">
            <div class="toggle-info">
      <div class="t-title">{{ t('mcp.advanced') }}</div>
      <div class="t-sub">{{ t('mcp.advancedSub') }}</div>
            </div>
            <n-switch v-model:value="form.enabled" :checked-value="1" :unchecked-value="0" />
          </div>
        </section>

        <!-- 底部操作栏 -->
        <div class="tf-actionbar">
          <div class="tf-actionbar-inner">
            <div class="left">
      <span v-if="form.type === 'manual'">{{ t('mcp.manualSummary', { n: manualRows.length }) }}</span>
      <span v-else>{{ t('mcp.remoteSummary') }}</span>
            </div>
            <div class="right">
      <n-button quaternary @click="closeForm">{{ t('mcp.cancel') }}</n-button>
      <n-button type="primary" :loading="formSaving" :disabled="formSaving" @click="saveServer">{{ editingId != null ? t('mcp.saveChanges') : t('mcp.createAndConfig') }}</n-button>
            </div>
          </div>
        </div>
      </template>
    </div>

    <template v-if="!page" #footer><div /></template>
  </component>
</template>

<style scoped>
/* Tab 条与计数徽章样式已上收到 components/ui/SectionTabs.vue(下表 18px 内边距为本面板特例) */
.lib-tabs :deep(.sec-tab) { padding: 10px 18px; font-size: 14px; }
.mcp-desc { font-size: 13px; color: var(--text-2); line-height: 1.7; background: var(--surface-hover); border: 1px solid var(--border-soft); border-radius: 10px; padding: 10px 14px; margin: 4px 0 16px; }
/* 固定窗口高度:列表区滚动,分页固定底部 */
.lib-body { height: 460px; overflow-y: auto; }
.lib-pager { flex-shrink: 0; }
.mng-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.mng-title { font-size: 14px; font-weight: 700; color: var(--text); }
.mcp-list { display: flex; flex-direction: column; gap: 12px; }
.mcp-card { border: 1px solid var(--border-soft); border-radius: 12px; }
.meta { display: flex; gap: 10px; font-size: 13px; color: var(--text-2); margin-bottom: 6px; align-items: baseline; }
.meta .label { flex-shrink: 0; color: var(--text-3); width: 60px; }
.meta .url { font-size: 13px; word-break: break-all; }
.meta .headers { word-break: break-all; }
.form-foot { display: flex; justify-content: flex-end; gap: 8px; }

/* 接口卡片列表 */
.manual-editor { width: 100%; }
.me-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.me-title { font-size: 13px; font-weight: 600; color: var(--text-2); }
.me-empty { font-size: 13px; color: var(--text-4); text-align: center; padding: 20px 0; border: 1px dashed var(--border-soft); border-radius: 10px; }
.me-card { border: 1px solid var(--border-soft); border-radius: 10px; padding: 12px 14px; margin-bottom: 10px; background: var(--bg-elev); transition: border-color .15s; }
.me-card:hover { border-color: var(--accent); }
.me-card-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.me-card-path { font-family: var(--font-mono, monospace); font-size: 13px; color: var(--text); background: var(--surface-hover); border-radius: 4px; padding: 1px 6px; }
.me-card-name { font-size: 13px; font-weight: 600; color: var(--text-2); }
.me-card-meta { font-size: 12px; color: var(--text-4); margin-left: auto; }
.me-card-desc { font-size: 12px; color: var(--text-3); margin-top: 6px; line-height: 1.5; }
.me-card-actions { display: flex; gap: 4px; margin-top: 8px; }

/* 接口编辑弹窗 */
.iface-row { display: flex; gap: 12px; }
.iface-meta { font-family: var(--font-mono, monospace); font-size: 12px; }
.iface-footer { display: flex; align-items: center; justify-content: space-between; }
.iface-footer-right { display: flex; gap: 8px; }

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
.manage-header { margin-bottom: 18px; display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; }
.manage-title { font-size: 20px; font-weight: 700; color: var(--text); margin-bottom: 4px; }
.manage-sub { font-size: 13px; color: var(--text-3); }

/* 整页模式:内容区滚动,分页贴底 */
.manage-page { overflow: hidden; }
.manage-page .lib-spin { flex: 1; min-height: 0; overflow-y: auto; }
.manage-page .lib-pager { flex-shrink: 0; }

/* ===== 列表页(banner / 卡片);Tab 条见上方 SectionTabs 覆盖 ===== */
.lib-tabs { margin-bottom: 18px; }
.lib-banner {
  display: flex; align-items: flex-start; gap: 10px;
  background: var(--accent-soft); border: 1px solid var(--border-soft);
  border-radius: var(--radius-sm); padding: 11px 14px;
  font-size: 13px; color: var(--text-2); margin-bottom: 18px; flex-shrink: 0;
}
.lib-banner .banner-ico { flex-shrink: 0; }

.lib-panel { background: var(--surface); border: 1px solid var(--border-soft); border-radius: var(--radius); flex-shrink: 0; }
.lib-panel-head { display: flex; align-items: center; gap: 8px; padding: 12px 18px; border-bottom: 1px solid var(--border-soft); }
.lib-panel-head .sec-title { font-size: 15px; font-weight: 600; color: var(--text); }
.count-chip { font-size: 11px; font-weight: 600; background: var(--fill-1); color: var(--text-2); padding: 0 7px; border-radius: 999px; line-height: 1.7; }
.lib-panel-body { padding: 16px 18px; }
.tool-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 14px; }

.mcp-card {
  background: var(--surface); border: 1px solid var(--border-soft); border-radius: var(--radius);
  margin-bottom: 0; transition: border-color .15s, box-shadow .15s;
  display: flex; flex-direction: column;
}
.mcp-card:hover { border-color: var(--border); box-shadow: var(--shadow); }
.mcp-top { display: flex; align-items: center; gap: 14px; padding: 16px 20px; border-bottom: 1px solid var(--border-soft); }
.mcp-icon {
  width: 42px; height: 42px; border-radius: 10px; flex: none;
  background: linear-gradient(135deg, #2563eb, #7c3aed); color: #fff; font-size: 18px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.mcp-name { font-size: 16px; font-weight: 600; color: var(--text); display: flex; align-items: center; gap: 10px; }
.badge { font-size: 12px; font-weight: 500; padding: 2px 9px; border-radius: 999px; }
.badge.on { background: rgba(88, 196, 155, .16); color: var(--ok); }
.badge.off { background: var(--fill-1); color: var(--text-2); }
.mcp-type { font-size: 12.5px; color: var(--text-3); margin-top: 2px; }
.mcp-actions { display: flex; gap: 2px; }
.btn-link {
  background: none; border: none; cursor: pointer; color: var(--text-2);
  font-size: 13px; font-weight: 500; padding: 4px 8px; border-radius: 6px; font-family: inherit;
}
.btn-link:hover { background: var(--fill-0); color: var(--text); }
.btn-link.edit { color: var(--accent-text); }
.btn-link.edit:hover { background: var(--accent-soft); }
.btn-link.disable { color: var(--warn); }
.btn-link.danger { color: var(--err); }
.btn-link.danger:hover { background: rgba(228, 122, 134, .12); }
.btn-link:disabled { opacity: .5; cursor: default; }

.mcp-detail { display: grid; grid-template-columns: 1fr; gap: 0; }
.detail-item { padding: 12px 20px; border-bottom: 1px solid var(--border-soft); min-width: 0; }
.detail-item:last-child { border-bottom: none; }
.detail-label { font-size: 12px; color: var(--text-3); margin-bottom: 6px; }
.detail-val { font-size: 13.5px; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.detail-val code.url { font-family: var(--font-mono, monospace); font-size: 13px; color: var(--text-2); }
.secret-tag {
  display: inline-flex; align-items: center; gap: 6px;
  background: var(--bg-elev); border: 1px solid var(--border-soft);
  border-radius: 6px; padding: 3px 8px; font-size: 12.5px; color: var(--text-2);
}
.secret-tag .dot { width: 6px; height: 6px; border-radius: 50%; background: var(--ok); }

/* ===== 表单页(整页,无弹窗) ===== */
.tool-form-page {
  display: flex; flex-direction: column; width: 100%; max-width: 960px; margin: 0 auto;
  flex: 1; min-height: 0; overflow-y: auto;
}
/* 步骤条 */
.tf-steps {
  display: flex; align-items: center; gap: 8px; flex-shrink: 0;
  background: var(--surface); border: 1px solid var(--border-soft);
  border-radius: var(--radius); padding: 12px 18px; margin-bottom: 16px;
}
.tf-step { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: 13px; font-weight: 500; }
.tf-step .dot {
  width: 22px; height: 22px; border-radius: 50%; background: var(--fill-1); color: var(--text-3);
  display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600;
}
.tf-step.active { color: var(--text); }
.tf-step.active .dot { background: var(--accent); color: #fff; }
.tf-step.done { color: var(--accent-text); }
.tf-step.done .dot { background: var(--ok); color: #fff; }
.tf-step-line { flex: 1; height: 2px; background: var(--border-soft); border-radius: 2px; max-width: 80px; }
.tf-step-line.done { background: var(--accent); }

/* 卡片 */
.tf-card {
  background: var(--surface); border: 1px solid var(--border-soft);
  border-radius: var(--radius); margin-bottom: 16px; overflow: hidden; flex-shrink: 0;
}
.tf-card-head { display: flex; align-items: center; gap: 12px; padding: 14px 18px; border-bottom: 1px solid var(--border-soft); }
.sec-icon {
  width: 34px; height: 34px; border-radius: 9px; background: var(--accent-soft); color: var(--accent-text);
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.sec-title { font-size: 15px; font-weight: 600; color: var(--text); }
.sec-desc { font-size: 12.5px; color: var(--text-3); }
.spacer { flex: 1; }
.tf-card-body { padding: 16px 18px; }
.tf-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 18px; }
.tf-field { display: flex; flex-direction: column; gap: 6px; }
.tf-field.full { grid-column: 1 / -1; }
.tf-field label { font-size: 13px; font-weight: 500; color: var(--text); display: flex; align-items: center; gap: 6px; }
.tf-field label .req { color: var(--err); }
.tf-field label .hint { font-weight: 400; color: var(--text-3); font-size: 12px; }
.tf-empty {
  border: 1.5px dashed var(--border); border-radius: var(--radius-sm);
  padding: 14px; background: var(--bg-elev); color: var(--text-3);
  font-size: 13px; text-align: center;
}

/* 键值/参数表格 */
.kv-table { width: 100%; border-collapse: collapse; }
.kv-table th, .kv-table td { text-align: left; padding: 8px 10px; font-size: 13px; vertical-align: middle; }
.kv-table thead th {
  background: var(--fill-0); color: var(--text-2); font-weight: 500; font-size: 12.5px;
  border-bottom: 1px solid var(--border-soft);
}
.kv-table tbody td { border-bottom: 1px solid var(--border-soft); }
.kv-table tbody tr:last-child td { border-bottom: none; }
.kv-table .ops { width: 70px; text-align: right; }

/* 接口清单(已添加接口列表) */
.endpoint-list { display: flex; flex-direction: column; }
.endpoint-row { display: flex; align-items: center; gap: 12px; padding: 10px 4px; border-bottom: 1px solid var(--border-soft); }
.endpoint-row:last-child { border-bottom: none; }
.method { font-size: 12px; font-weight: 700; padding: 3px 9px; border-radius: 6px; letter-spacing: .3px; flex: none; }
.method.get { background: rgba(88, 196, 155, .16); color: var(--ok); }
.method.post { background: var(--accent-soft); color: var(--accent-text); }
.method.put, .method.patch { background: rgba(232, 178, 107, .16); color: var(--warn); }
.method.delete { background: rgba(228, 122, 134, .16); color: var(--err); }
.e-path { font-family: var(--font-mono, monospace); font-size: 13px; color: var(--text); }
.e-name { font-size: 13px; color: var(--text-3); }
.e-desc { font-size: 12.5px; color: var(--text-3); max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.e-meta { font-size: 12px; color: var(--text-4); white-space: nowrap; }
.ai-tip {
  display: flex; align-items: center; gap: 10px; flex-shrink: 0;
  background: rgba(232, 178, 107, .1); border: 1px solid rgba(232, 178, 107, .3);
  border-radius: var(--radius-sm); padding: 10px 14px; font-size: 13px; color: var(--text-2); margin-top: 14px;
}
.ai-tip strong { color: var(--text); }

/* 启用 */
.toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.toggle-info .t-title { font-size: 14px; font-weight: 600; color: var(--text); }
.toggle-info .t-sub { font-size: 12.5px; color: var(--text-3); }

/* 底部操作栏(随内容区吸底) */
.tf-actionbar {
  position: sticky; bottom: 0; z-index: 5; flex-shrink: 0;
  background: var(--surface); border-top: 1px solid var(--border-soft);
  padding: 12px 2px; margin-top: 4px;
}
.tf-actionbar-inner { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.tf-actionbar .left { color: var(--text-3); font-size: 13px; }
.tf-actionbar .right { display: flex; gap: 10px; }

@media (max-width: 720px) {
  .tf-grid2 { grid-template-columns: 1fr; }
}
</style>
