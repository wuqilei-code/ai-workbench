<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { useMessage } from 'naive-ui'
import type { AdminUserVo } from '@/types'

const router = useRouter()
const msg = useMessage()
const loading = ref(false)
const users = ref<AdminUserVo[]>([])
const keyword = ref('')
/** 行级操作进行中的用户 id(启停/角色) */
const rowBusyId = ref<number | null>(null)
/** 重置密码提交中 */
const resetting = ref(false)

const resetVisible = ref(false)
const resetUser = ref<AdminUserVo | null>(null)
const resetPwd = ref('')

async function load() {
  loading.value = true
  try {
    users.value = await adminApi.listUsers(keyword.value || undefined)
  } catch (e: any) {
    msg.error(e.message)
  } finally {
    loading.value = false
  }
}

async function toggleStatus(u: AdminUserVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = u.id
  const target = u.status === 1 ? 0 : 1
  const action = target === 0 ? '禁用' : '启用'
  if (target === 0 && !confirm(`确定禁用用户「${u.username}」？禁用后其 agent 实例将被停止，无法登录。`)) {
    rowBusyId.value = null
    return
  }
  try {
    await adminApi.updateUserStatus(u.id, target)
    msg.success(`已${action}「${u.username}」`)
    await load()
  } catch (e: any) {
    msg.error(e.message)
    await load()
  } finally {
    rowBusyId.value = null
  }
}

async function toggleRole(u: AdminUserVo) {
  if (rowBusyId.value !== null) return
  rowBusyId.value = u.id
  const target = u.role === 'admin' ? 'user' : 'admin'
  const action = target === 'admin' ? '设为管理员' : '降为普通用户'
  if (target === 'user' && !confirm(`确定将「${u.username}」降为普通用户？`)) {
    rowBusyId.value = null
    return
  }
  try {
    await adminApi.updateUserRole(u.id, target)
    msg.success(`已将「${u.username}」${action}`)
    await load()
  } catch (e: any) {
    msg.error(e.message)
    await load()
  } finally {
    rowBusyId.value = null
  }
}

function openReset(u: AdminUserVo) {
  resetUser.value = u
  resetPwd.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!resetUser.value) return
  if (!resetPwd.value || resetPwd.value.length < 6) {
    msg.warning('新密码至少 6 位')
    return
  }
  resetting.value = true
  try {
    await adminApi.resetUserPassword(resetUser.value.id, resetPwd.value)
    msg.success(`已重置「${resetUser.value.username}」的密码`)
    resetVisible.value = false
  } catch (e: any) {
    msg.error(e.message)
  } finally {
    resetting.value = false
  }
}

function fmtTime(t?: string): string {
  if (!t) return '—'
  const d = new Date(t)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(load)
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <n-button @click="router.push('/')">← 返回</n-button>
      <h2>用户管理</h2>
      <div class="spacer" />
      <n-input
        v-model:value="keyword"
        placeholder="搜索用户名 / 邮箱"
        clearable
        style="width: 220px"
        @keyup.enter="load"
      />
      <n-button type="primary" :loading="loading" @click="load">搜索</n-button>
      <n-button @click="router.push('/admin/providers')">模型配置</n-button>
      <n-button @click="router.push('/admin/usage')">用量统计</n-button>
    </div>

    <n-spin :show="loading">
      <n-card size="small" :bordered="false">
        <n-table :bordered="false" size="small">
          <thead>
            <tr>
              <th>ID</th>
              <th>用户名</th>
              <th>邮箱</th>
              <th>角色</th>
              <th>状态</th>
              <th>UID</th>
              <th>注册时间</th>
              <th style="width: 200px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>{{ u.id }}</td>
              <td>{{ u.username }}</td>
              <td>{{ u.email }}</td>
              <td>
                <n-tag :type="u.role === 'admin' ? 'warning' : 'default'" size="small">
                  {{ u.role === 'admin' ? '管理员' : '普通用户' }}
                </n-tag>
              </td>
              <td>
                <n-switch
                  :value="u.status === 1"
                  :disabled="u.role === 'admin' || rowBusyId !== null"
                  :loading="rowBusyId === u.id"
                  @update:value="toggleStatus(u)"
                />
              </td>
              <td>{{ u.uid ?? '—' }}</td>
              <td>{{ fmtTime(u.createdAt) }}</td>
              <td>
                <n-space size="small">
                  <n-button size="tiny" quaternary :loading="rowBusyId === u.id" :disabled="rowBusyId !== null" @click="toggleRole(u)">
                    {{ u.role === 'admin' ? '取消管理' : '设为管理' }}
                  </n-button>
                  <n-button size="tiny" quaternary type="error" :disabled="rowBusyId !== null" @click="openReset(u)">重置密码</n-button>
                </n-space>
              </td>
            </tr>
          </tbody>
        </n-table>
        <n-empty
          v-if="!users.length && !loading"
          description="暂无用户"
          style="margin-top: 30px"
        />
      </n-card>
    </n-spin>

    <n-modal
      v-model:show="resetVisible"
      preset="card"
      title="重置密码"
      style="width: 380px"
      :mask-closable="false"
      :bordered="false"
    >
      <div class="reset-tip">为用户「{{ resetUser?.username }}」设置新密码</div>
      <n-input v-model:value="resetPwd" type="password" placeholder="输入新密码（至少 6 位）" show-password-on="click" />
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button @click="resetVisible = false">取消</n-button>
          <n-button type="primary" :loading="resetting" :disabled="resetting" @click="submitReset">确认重置</n-button>
        </div>
      </template>
    </n-modal>
  </div>
</template>

<style scoped>
.admin-view {
  min-height: 100vh; background: var(--bg);
  max-width: 980px; margin: 0 auto; padding: 20px;
  box-sizing: border-box;
}
.admin-head {
  display: flex; align-items: center; gap: 12px; margin-bottom: 20px;
  padding: 14px 16px; background: var(--bg-elev);
  border: 1px solid var(--border-soft); border-radius: var(--radius);
}
.admin-head h2 { margin: 0; font-size: 16px; color: var(--text); }
.spacer { flex: 1; }
.reset-tip { margin-bottom: 12px; font-size: 13px; color: var(--text-2); }

/* 卡片表面:略亮于页面背景,保持深色主题统一 */
.admin-view :deep(.n-card) {
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}
</style>
