<script setup lang="ts">
import { ref, watch } from 'vue'
import { mcpApi } from '@/api/mcp'
import { useMessage } from 'naive-ui'
import type { McpVo } from '@/types'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ 'update:show': [v: boolean] }>()

const msg = useMessage()

const servers = ref<McpVo[]>([])
const loading = ref(false)
const rowBusy = ref<string | null>(null)

const showModal = ref(false)
const editingId = ref<number | null>(null)
type FormType = 'remote' | 'openapi' | 'manual'
const form = ref<{ name: string; type: FormType; url: string; headers: string; enabled: number }>({ name: '', type: 'remote', url: '', headers: '', enabled: 1 })
const formSaving = ref(false)

interface ManualRow {
  method: string
  name: string
  path: string
  description: string
  meta: string
}
const manualRows = ref<ManualRow[]>([])
const methodOptions = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'].map((m) => ({ label: m, value: m }))

const typeOptions: Array<{ label: string; value: FormType }> = [
  { label: 'MCP Server（远程 HTTP/SSE）', value: 'remote' },
  { label: 'OpenAPI 接口组（文档批量转）', value: 'openapi' },
  { label: '手动接口组（无 Swagger）', value: 'manual' },
]

function typeLabel(t?: string | null): string {
  if (t === 'openapi') return 'OpenAPI 接口'
  if (t === 'manual') return '手动接口'
  return 'MCP Server'
}

// ---------- 接口编辑弹窗 ----------
const ifaceModal = ref(false)
const ifaceEditIdx = ref(-1) // -1 = 新增, >=0 = 编辑
const ifaceForm = ref<ManualRow>({ method: 'GET', name: '', path: '/', description: '', meta: '' })
const ifaceBusy = ref(false)

function openAddIface() {
  ifaceEditIdx.value = -1
  ifaceForm.value = { method: 'GET', name: '', path: '/', description: '', meta: '' }
  ifaceModal.value = true
}
function openEditIface(i: number) {
  const r = manualRows.value[i]
  if (!r) return
  ifaceEditIdx.value = i
  ifaceForm.value = { ...r }
  ifaceModal.value = true
}
function saveIface() {
  const f = ifaceForm.value
  if (!f.name.trim() || !f.name.trim().match(/^[A-Za-z0-9_]+$/)) {
    msg.warning('接口名称需为字母/数字/下划线')
    return
  }
  if (!f.path.trim()) { msg.warning('请填写路径'); return }
  if (ifaceEditIdx.value >= 0) {
    manualRows.value[ifaceEditIdx.value] = { ...f }
  } else {
    manualRows.value.push({ ...f })
  }
  ifaceModal.value = false
  msg.success(ifaceEditIdx.value >= 0 ? '接口已更新' : '接口已添加')
}
function removeIface(i: number) {
  manualRows.value.splice(i, 1)
}

// ---------- AI 填充参数 ----------
async function runAssistParams() {
  const f = ifaceForm.value
  if (!f.path.trim()) { msg.warning('请先填写路径，AI 需要根据路径推断参数'); return }
  if (ifaceBusy.value) return
  ifaceBusy.value = true
  try {
    const res: any = await mcpApi.manualAssistParams({
      method: f.method, path: f.path, name: f.name, description: f.description,
    })
    if (!res?.ok) throw new Error(res?.error || 'AI 参数填充失败')
    const params = res.params || []
    const body_fields = res.body_fields || []
    if (!params.length && !body_fields.length) {
      msg.info('AI 未推断出额外参数')
      return
    }
    const meta: any = {}
    if (params.length) meta.params = params
    if (body_fields.length) meta.body_fields = body_fields
    ifaceForm.value.meta = JSON.stringify(meta, null, 2)
    msg.success(`AI 已填充 ${params.length} 个参数` + (body_fields.length ? ` + ${body_fields.length} 个请求体字段` : ''))
  } catch (e: any) {
    msg.error(e?.message || 'AI 参数填充失败')
  } finally {
    ifaceBusy.value = false
  }
}

// ---------- 配置读写 ----------
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
      meta: JSON.stringify({ params: c.params || [], body_fields: c.body_fields || [] }) || '',
    }))
  } catch { return [] }
}

function buildManualConfig(): { ok: boolean; err?: string; config?: string } {
  if (form.value.type !== 'manual') return { ok: true }
  if (!manualRows.value.length) return { ok: false, err: '请至少添加一个接口' }
  for (let i = 0; i < manualRows.value.length; i++) {
    const r = manualRows.value[i]
    if (!r.name.trim() || !r.name.trim().match(/^[A-Za-z0-9_]+$/)) return { ok: false, err: `第 ${i + 1} 个接口的名称非法（字母/数字/下划线）` }
    if (!r.path.trim()) return { ok: false, err: `第 ${i + 1} 个接口缺少路径` }
    let meta: any = {}
    if (r.meta.trim()) {
      try { meta = JSON.parse(r.meta) } catch { return { ok: false, err: `第 ${i + 1} 个接口的「参数/请求体」需为合法 JSON` } }
    }
    if (meta && typeof meta !== 'object') return { ok: false, err: `第 ${i + 1} 个接口的「参数/请求体」需为 JSON 对象` }
  }
  const config = manualRows.value.map((r) => {
    let meta: any = {}
    try { meta = r.meta.trim() ? JSON.parse(r.meta) : {} } catch { /* handled above */ }
    const item: any = { name: r.name.trim(), method: r.method, path: r.path.trim(), description: r.description.trim() }
    if (Array.isArray(meta.params) && meta.params.length) item.params = meta.params
    if (Array.isArray(meta.body_fields) && meta.body_fields.length) item.body_fields = meta.body_fields
    return item
  })
  return { ok: true, config: JSON.stringify(config) }
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
  if (!meta.trim()) return '无参数'
  try {
    const obj = JSON.parse(meta)
    const p = Array.isArray(obj.params) ? obj.params.length : 0
    const b = Array.isArray(obj.body_fields) ? obj.body_fields.length : 0
    if (p && b) return `${p} 参数 + ${b} 体字段`
    if (p) return `${p} 参数`
    if (b) return `${b} 体字段`
    return '无参数'
  } catch { return 'JSON 语法错误' }
}

// ---------- 列表 / 表单 ----------
watch(() => props.show, async (v) => {
  if (!v) return
  servers.value = []
  loading.value = true
  try {
    servers.value = await mcpApi.list()
  } catch (e: any) {
    msg.error(e?.message || '加载 MCP 配置失败')
  } finally {
    loading.value = false
  }
})

function openCreate() {
  form.value = { name: '', type: 'remote', url: '', headers: '', enabled: 1 }
  manualRows.value = []
  editingId.value = null
  showModal.value = true
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
  showModal.value = true
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
  if (!name) { msg.warning('请填写名称'); return }
  if (!/^[a-z0-9][a-z0-9-]{0,63}$/.test(name)) { msg.warning('名称需为小写字母/数字/连字符，如 my-server'); return }
  if (!/^https?:\/\/.+/.test(url)) { msg.warning('URL 需以 http:// 或 https:// 开头'); return }
  if (form.value.headers.trim() && !isValidJsonObject(form.value.headers)) { msg.warning('Headers 需为 JSON 对象'); return }

  if (form.value.type === 'manual') {
    const manual = buildManualConfig()
    if (!manual.ok) { msg.warning(manual.err); return }
    const payload: any = { name, type: 'manual', url, headers: form.value.headers || null, config: manual.config, enabled: form.value.enabled, sortOrder: 0 }
    formSaving.value = true
    try {
      if (editingId.value != null) await mcpApi.update(editingId.value, payload)
      else await mcpApi.create(payload)
      msg.success(editingId.value != null ? '已更新' : '已添加')
      showModal.value = false
    } catch (e: any) { msg.error(e?.message || '保存失败') } finally { formSaving.value = false }
    return
  }

  const payload: any = { name, type: form.value.type, url, headers: form.value.headers || null, enabled: form.value.enabled, sortOrder: 0 }
  formSaving.value = true
  try {
    if (editingId.value != null) await mcpApi.update(editingId.value, payload)
    else await mcpApi.create(payload)
    msg.success(editingId.value != null ? '已更新' : '已添加')
    showModal.value = false
  } catch (e: any) { msg.error(e?.message || '保存失败') } finally { formSaving.value = false }
}

async function deleteServer(s: McpVo) {
  rowBusy.value = String(s.id)
  try {
    await mcpApi.remove(s.id)
    servers.value = servers.value.filter((x) => x.id !== s.id)
    msg.success('已删除')
  } catch (e: any) { msg.error(e?.message || '删除失败') } finally { rowBusy.value = null }
}

async function toggleServer(s: McpVo) {
  rowBusy.value = String(s.id)
  try {
    const newEnabled = s.enabled === 1 ? 0 : 1
    await mcpApi.update(s.id, { enabled: newEnabled })
    s.enabled = newEnabled
    msg.success(newEnabled ? '已启用' : '已停用')
  } catch (e: any) { msg.error(e?.message || '切换失败') } finally { rowBusy.value = null }
}

function headerPreview(headers?: string | null): string {
  if (!headers) return '无'
  try {
    const obj = JSON.parse(headers)
    return Object.keys(obj).join(', ') || '空'
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
  <n-modal :show="show" @update:show="emit('update:show', $event)" preset="card" title="MCP 连接管理" style="width: 780px; max-width: 96vw">
    <div class="mcp-desc">
      管理你个人的 MCP Server / OpenAPI 接口组 / 手动接口组。开启后，对应工具会出现在会话顶部「MCP」按钮中，按会话勾选即可使用。
    </div>

    <div class="mng-head">
      <span class="mng-title">已配置（{{ servers.length }}）</span>
      <n-button type="primary" size="small" dashed @click="openCreate">+ 添加</n-button>
    </div>

    <n-spin :show="loading">
      <div v-if="!servers.length && !loading" style="text-align: center; color: var(--text-4); padding: 20px 0; font-size: 13px;">暂无配置</div>
      <div class="mcp-list">
        <div class="mcp-card" v-for="s in servers" :key="s.id" style="padding: 14px 16px;">
          <div class="meta" style="margin-bottom: 8px; align-items: center;">
            <span style="font-weight: 700; font-size: 15px;">{{ s.name }}</span>
            <n-tag size="small" :bordered="false" :type="s.type === 'openapi' ? 'info' : s.type === 'manual' ? 'warning' : 'success'" style="margin-left: 6px;">{{ typeLabel(s.type) }}</n-tag>
            <n-tag size="small" :bordered="false" :type="s.enabled === 1 ? 'success' : 'default'" style="margin-left: 4px;">{{ s.enabled === 1 ? '已启用' : '已停用' }}</n-tag>
          </div>
          <div class="meta"><span class="label">URL</span><code class="url">{{ s.url || '—' }}</code></div>
          <div class="meta"><span class="label">Headers</span><span class="headers">{{ headerPreview(s.headers) }}</span></div>
          <div v-if="s.type === 'manual'" class="meta"><span class="label">接口</span><span class="headers">{{ manualCount(s) }} 个</span></div>
          <div style="display: flex; gap: 6px; margin-top: 8px;">
            <n-button size="tiny" quaternary @click="openEdit(s)">编辑</n-button>
            <n-button size="tiny" quaternary :type="s.enabled === 1 ? 'warning' : 'success'" :loading="rowBusy === String(s.id)" @click="toggleServer(s)">{{ s.enabled === 1 ? '停用' : '启用' }}</n-button>
            <n-button size="tiny" quaternary type="error" :loading="rowBusy === String(s.id)" @click="deleteServer(s)">删除</n-button>
          </div>
        </div>
      </div>
    </n-spin>

    <template #footer><div /></template>
  </n-modal>

  <!-- 主表单弹窗 -->
  <n-modal v-model:show="showModal" preset="card" :title="editingId != null ? '编辑连接' : '添加连接'" :style="form.type === 'manual' ? 'width: 820px; max-width: 96vw' : 'width: 600px; max-width: 94vw'" @update:show="showModal = $event">
    <n-form label-placement="top">
      <n-form-item label="类型">
        <n-select v-model:value="form.type" :options="typeOptions" />
      </n-form-item>
      <n-form-item label="名称（小写 kebab-case，如 my-server/fetch）"><n-input v-model:value="form.name" placeholder="my-server" /></n-form-item>
      <n-form-item v-if="form.type !== 'manual'" :label="form.type === 'openapi' ? 'OpenAPI/Swagger 文档 URL' : 'MCP 端点 URL（HTTP/SSE）'">
        <n-input v-model:value="form.url" :placeholder="form.type === 'openapi' ? 'https://api.example.com/openapi.json' : 'https://mcp.example.com/mcp'" />
      </n-form-item>
      <n-form-item v-else label="接口组 Base URL（协议+主机+前缀）">
        <n-input v-model:value="form.url" placeholder="https://api.example.com/v1" />
      </n-form-item>
      <n-form-item label="Headers（公共鉴权，可选，JSON）"><n-input v-model:value="form.headers" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder='{"Authorization":"Bearer xxx"}' /></n-form-item>

      <template v-if="form.type === 'manual'">
        <n-form-item label="接口定义">
          <div class="manual-editor">
            <div class="me-head">
              <span class="me-title">接口清单（每个接口变成一个 agent 工具，工具名 {{ form.name || '平台名' }}_接口名）</span>
              <n-button size="small" type="primary" dashed @click="openAddIface">+ 添加接口</n-button>
            </div>
            <div v-if="!manualRows.length" class="me-empty">尚未添加接口</div>
            <div v-for="(row, i) in manualRows" :key="i" class="me-card">
              <div class="me-card-top">
                <n-tag size="small" :bordered="false" :type="methodColor(row.method)" style="font-family: var(--font-mono, monospace); font-weight: 700;">{{ row.method }}</n-tag>
                <code class="me-card-path">{{ row.path }}</code>
                <span class="me-card-name">{{ row.name }}</span>
                <span class="me-card-meta">{{ metaPreview(row.meta) }}</span>
              </div>
              <div v-if="row.description" class="me-card-desc">{{ row.description }}</div>
              <div class="me-card-actions">
                <n-button size="tiny" quaternary @click="openEditIface(i)">编辑</n-button>
                <n-button size="tiny" quaternary type="error" @click="removeIface(i)">删除</n-button>
              </div>
            </div>
          </div>
        </n-form-item>
      </template>

      <n-form-item label="启用"><n-switch v-model:value="form.enabled" :checked-value="1" :unchecked-value="0" /></n-form-item>
      <n-alert v-if="form.type === 'openapi'" type="info" :bordered="false" style="margin-bottom: 12px">
        保存后，该 Swagger 文档里的每个接口都会变成一个可被 agent 调用的工具（工具名为「{{ form.name }}_接口名」）。
      </n-alert>
      <n-alert v-if="form.type === 'manual'" type="info" :bordered="false" style="margin-bottom: 12px">
        路径支持 <code>{变量}</code> 模板，如 /orders/{id}；点「+ 添加接口」进入编辑弹窗，可使用 AI 自动填充参数。该组的公共 Headers 会在调用时自动带上。
      </n-alert>
      <div class="form-foot">
        <n-button quaternary @click="showModal = false">取消</n-button>
        <n-button type="primary" :loading="formSaving" :disabled="formSaving" @click="saveServer">保存</n-button>
      </div>
    </n-form>
  </n-modal>

  <!-- 接口编辑弹窗 -->
  <n-modal v-model:show="ifaceModal" preset="card" :title="ifaceEditIdx >= 0 ? '编辑接口 #' + (ifaceEditIdx + 1) : '添加接口'" style="width: 640px; max-width: 94vw" :closable="!ifaceBusy">
    <n-form label-placement="top">
      <div class="iface-row">
        <n-form-item label="方法" style="flex: 0 0 140px;">
          <n-select :options="methodOptions" v-model:value="ifaceForm.method" />
        </n-form-item>
        <n-form-item label="路径（支持 {变量} 模板）" style="flex: 1;">
          <n-input v-model:value="ifaceForm.path" placeholder="/orders/{id}" />
        </n-form-item>
      </div>
      <n-form-item label="接口名（英文小写下划线，如 get_order）">
        <n-input v-model:value="ifaceForm.name" placeholder="get_order" />
      </n-form-item>
      <n-form-item label="接口说明（可选，agent 据此判断何时调用）">
        <n-input v-model:value="ifaceForm.description" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="按 id 查询订单详情" />
      </n-form-item>
      <n-form-item label="参数 / 请求体 JSON（可选，或点击下方 AI 按钮自动生成）">
        <n-input v-model:value="ifaceForm.meta" type="textarea" :autosize="{ minRows: 4, maxRows: 10 }" class="iface-meta"
          placeholder='{"params":[{"name":"id","in":"path","required":true,"type":"integer"}],"body_fields":[{"name":"name","type":"string","required":true}]}' />
      </n-form-item>
    </n-form>
    <template #footer>
      <div class="iface-footer">
        <n-button :loading="ifaceBusy" :disabled="ifaceBusy" @click="runAssistParams">
          <template #icon>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2v4M12 18v4M2 12h4M18 12h4M4.9 4.9l2.8 2.8M16.3 16.3l2.8 2.8M4.9 19.1l2.8-2.8M16.3 7.7l2.8-2.8"/><circle cx="12" cy="12" r="3"/></svg>
          </template>
          AI 填充参数
        </n-button>
        <div class="iface-footer-right">
          <n-button quaternary :disabled="ifaceBusy" @click="ifaceModal = false">取消</n-button>
          <n-button type="primary" :disabled="ifaceBusy" @click="saveIface">确定</n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.mcp-desc { font-size: 13px; color: var(--text-2); line-height: 1.7; background: var(--surface-hover); border: 1px solid var(--border-soft); border-radius: 10px; padding: 10px 14px; margin-bottom: 16px; }
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
.me-card:hover { border-color: var(--primary-color, #7c3aed); }
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
</style>
