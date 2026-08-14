<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { mcpApi } from '@/api/mcp'
import { useMessage } from 'naive-ui'
import type { McpVo } from '@/types'
import { useAuthStore } from '@/stores/auth'

const props = withDefaults(defineProps<{ scope?: 'admin' | 'user' }>(), { scope: 'admin' })

const router = useRouter()
const msg = useMessage()
const auth = useAuthStore()

/** admin=管理公共配置;user=管理我的配置(公共配置只读展示) */
const isAdminScope = props.scope === 'admin'
const mine = ref<McpVo[]>([])
const publicMcps = ref<McpVo[]>([])
const loading = ref(false)
const applying = ref(false)
/** 用户私有 MCP 变更后,后端正在重启该用户 agent 加载配置(阻塞等待),期间显示过渡动画 */
const configuring = ref(false)

const showModal = ref(false)
const editingId = ref<number | null>(null)
const form = ref<{
  name: string; url: string; headers: string; enabled: number
}>(emptyForm())

function emptyForm() {
  return { name: '', url: '', headers: '', enabled: 1 }
}

async function load() {
  loading.value = true
  try {
    if (isAdminScope) {
      mine.value = await adminApi.listMcps()
    } else {
      const [p, m] = await Promise.all([mcpApi.listPublic(), mcpApi.listMine()])
      publicMcps.value = p
      mine.value = m
    }
  }
  catch (e: any) { msg.error(e.message) }
  finally { loading.value = false }
}

function openCreate() {
  editingId.value = null
  form.value = emptyForm()
  showModal.value = true
}

function openEdit(m: McpVo) {
  editingId.value = m.id
  form.value = {
    name: m.name, url: m.url || '', headers: m.headers || '',
    enabled: m.enabled,
  }
  showModal.value = true
}

async function save() {
  const payload = {
    name: form.value.name.trim(),
    type: 'remote',
    url: form.value.url.trim(),
    headers: form.value.headers.trim() || null,
    enabled: form.value.enabled,
  }
  if (!payload.name) { msg.error('名称必填'); return }
  if (!payload.url) { msg.error('URL 必填'); return }
  try {
    configuring.value = true
    if (editingId.value != null) {
      if (isAdminScope) await adminApi.updateMcp(editingId.value, payload)
      else await mcpApi.updateMine(editingId.value, payload)
      msg.success('已更新')
    } else {
      if (isAdminScope) await adminApi.createMcp(payload)
      else await mcpApi.createMine(payload)
      msg.success('已添加')
    }
    showModal.value = false
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally {
    setTimeout(() => { configuring.value = false }, 600)
  }
}

async function remove(id: number) {
  if (!confirm('删除该 MCP Server？')) return
  try {
    configuring.value = true
    if (isAdminScope) await adminApi.deleteMcp(id)
    else await mcpApi.deleteMine(id)
    msg.success('已删除')
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally {
    setTimeout(() => { configuring.value = false }, 600)
  }
}

async function toggleEnabled(m: McpVo) {
  const payload = {
    name: m.name, type: 'remote', url: m.url || '',
    headers: m.headers || null, enabled: m.enabled ? 0 : 1,
  }
  try {
    configuring.value = true
    if (isAdminScope) await adminApi.updateMcp(m.id, payload)
    else await mcpApi.updateMine(m.id, payload)
    await load()
  } catch (e: any) { msg.error(e.message) }
  finally {
    setTimeout(() => { configuring.value = false }, 600)
  }
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
      <h2>{{ isAdminScope ? 'MCP Server 管理（公共）' : '我的 MCP Server' }}</h2>
      <div class="spacer" />
      <n-button type="primary" @click="openCreate">+ 添加 MCP Server</n-button>
      <n-button v-if="isAdminScope" type="warning" :loading="applying" @click="apply">应用并重启 opencode</n-button>
    </div>

    <n-alert type="info" :show-icon="true" style="margin-bottom: 16px">
      <div>接入外部 MCP Server（仅支持远程），agent 即可在会话中调用其工具:</div>
      <ul style="margin: 4px 0; padding-left: 18px">
        <li><code>remote</code> — HTTP/SSE URL 直连，如 <code>https://mcp.example.com/mcp</code>。</li>
        <li><code>headers</code> 为 JSON 对象，可携带鉴权头，如 <code>{ "Authorization": "Bearer xxx" }</code>。</li>
      </ul>
    </n-alert>

    <n-spin :show="loading">
      <template v-if="!isAdminScope">
        <h3 style="color: var(--text); margin-bottom: 8px">公共配置（管理员维护，只读）</h3>
        <n-card v-for="m in publicMcps" :key="m.id" class="mcp-card">
          <template #header>
            <div class="mcp-title">
              <span>{{ m.name }}</span>
              <n-tag type="info" size="tiny">remote</n-tag>
              <n-tag :type="m.enabled ? 'success' : 'default'" size="tiny">{{ m.enabled ? '启用' : '禁用' }}</n-tag>
            </div>
          </template>
          <div class="meta">URL: <code>{{ m.url }}</code></div>
          <div v-if="m.headers" class="meta">Headers: <code>{{ m.headers }}</code></div>
        </n-card>
        <h3 style="color: var(--text); margin: 20px 0 8px">我的配置（仅我生效）</h3>
        <n-empty v-if="!mine.length && !loading" description="尚未添加自己的 MCP Server" />
      </template>
      <template v-else>
        <n-empty v-if="!mine.length && !loading" description="尚未接入任何 MCP Server" />
      </template>

      <n-card v-for="m in mine" :key="m.id" class="mcp-card">
        <template #header>
          <div class="mcp-title">
            <span>{{ m.name }}</span>
            <n-tag type="info" size="tiny">remote</n-tag>
            <n-tag :type="m.enabled ? 'success' : 'default'" size="tiny">{{ m.enabled ? '启用' : '禁用' }}</n-tag>
          </div>
        </template>
        <template #header-extra>
          <n-switch :value="!!m.enabled" @update:value="toggleEnabled(m)" />
          <n-button size="small" quaternary @click="openEdit(m)">编辑</n-button>
          <n-button size="small" quaternary type="error" @click="remove(m.id)">删除</n-button>
        </template>
        <div class="meta">URL: <code>{{ m.url }}</code></div>
        <div v-if="m.headers" class="meta">Headers: <code>{{ m.headers }}</code></div>
      </n-card>
    </n-spin>

    <n-modal v-model:show="showModal" preset="card" :title="editingId != null ? '编辑 MCP Server' : '添加 MCP Server'" style="width: 560px">
      <n-form label-placement="top">
        <n-form-item label="名称（标识，小写 kebab-case，如 mysql / context7）">
          <n-input v-model:value="form.name" placeholder="mysql" />
        </n-form-item>
        <n-form-item label="URL（remote，HTTP/SSE 端点）">
          <n-input v-model:value="form.url" placeholder="https://mcp.example.com/mcp" />
        </n-form-item>
        <n-form-item label="Headers（JSON，可选）">
          <n-input v-model:value="form.headers" type="textarea" :rows="3"
            placeholder='{ "Authorization": "Bearer xxx" }' />
        </n-form-item>
        <n-form-item label="启用">
          <n-switch v-model:value="form.enabled" :checked-value="1" :unchecked-value="0" />
        </n-form-item>
        <div class="modal-actions">
          <n-button type="primary" @click="save">保存</n-button>
          <n-button @click="showModal = false">取消</n-button>
        </div>
      </n-form>
    </n-modal>

    <!-- 智能体加载配置中过渡动画:私有 MCP 变更后,后端重启该用户 agent 期间显示 -->
    <transition name="mcp-fade">
      <div v-if="configuring" class="mcp-overlay">
        <div class="mcp-overlay-card">
          <n-spin size="large" />
          <p class="mcp-overlay-text">智能体加载配置中,请稍候…</p>
        </div>
      </div>
    </transition>
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
.mcp-card { margin-bottom: 16px; }
.mcp-title { display: flex; align-items: center; gap: 8px; font-size: 15px; color: var(--text); }
.meta { font-size: 12px; color: var(--text-2); margin-bottom: 6px; word-break: break-all; }
.modal-actions { display: flex; gap: 12px; justify-content: flex-end; }
.admin-view :deep(.n-card) {
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}
.mcp-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(3px);
}
.mcp-overlay-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 28px 44px;
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
}
.mcp-overlay-text {
  margin: 0;
  font-size: 14px;
  color: var(--text);
}
.mcp-fade-enter-active,
.mcp-fade-leave-active {
  transition: opacity 0.3s ease;
}
.mcp-fade-enter-from,
.mcp-fade-leave-to {
  opacity: 0;
}
</style>