<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { adminApi } from '@/api/admin'
import { useMessage, useDialog } from 'naive-ui'
import type { AdminUserVo } from '@/types'

const router = useRouter()
const { t } = useI18n()
const msg = useMessage()
const dialog = useDialog()
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
  const target = u.status === 1 ? 0 : 1
  const action = target === 0 ? t('admin.user.disable') : t('admin.user.enable')
  if (target === 0) {
    dialog.warning({
      title: t('admin.user.disableTitle'),
      content: t('admin.user.disableContent', { name: u.username }),
      positiveText: t('admin.user.disable'),
      negativeText: t('common.cancel'),
      onPositiveClick: async () => {
        rowBusyId.value = u.id
        try {
          await adminApi.updateUserStatus(u.id, target)
          msg.success(t('admin.user.toggled', { action, name: u.username }))
          await load()
        } catch (e: any) {
          msg.error(e.message)
          await load()
        } finally {
          rowBusyId.value = null
        }
      },
    })
    return
  }
  rowBusyId.value = u.id
  try {
    await adminApi.updateUserStatus(u.id, target)
    msg.success(t('admin.user.toggled', { action, name: u.username }))
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
  const target = u.role === 'admin' ? 'user' : 'admin'
  const action = target === 'admin' ? t('admin.user.promoteTo') : t('admin.user.demoteTo')
  if (target === 'user') {
    dialog.warning({
      title: t('admin.user.roleTitle'),
      content: t('admin.user.roleContent', { name: u.username }),
      positiveText: t('admin.user.demote'),
      negativeText: t('common.cancel'),
      onPositiveClick: async () => {
        rowBusyId.value = u.id
        try {
          await adminApi.updateUserRole(u.id, target)
          msg.success(t('admin.user.roleChanged', { name: u.username, action }))
          await load()
        } catch (e: any) {
          msg.error(e.message)
          await load()
        } finally {
          rowBusyId.value = null
        }
      },
    })
    return
  }
  rowBusyId.value = u.id
  try {
    await adminApi.updateUserRole(u.id, target)
    msg.success(t('admin.user.roleChanged', { name: u.username, action }))
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
    msg.warning(t('admin.user.pwdTooShort'))
    return
  }
  resetting.value = true
  try {
    await adminApi.resetUserPassword(resetUser.value.id, resetPwd.value)
    msg.success(t('admin.user.resetDone', { name: resetUser.value.username }))
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
      <n-button @click="router.push('/')">{{ t('common.back') }}</n-button>
      <h2>{{ t('admin.user.title') }}</h2>
      <div class="spacer" />
      <n-input
        v-model:value="keyword"
        :placeholder="t('admin.user.searchPlaceholder')"
        clearable
        style="width: 220px"
        @keyup.enter="load"
      />
      <n-button type="primary" :loading="loading" @click="load">{{ t('admin.user.search') }}</n-button>
      <n-button @click="router.push('/admin/providers')">{{ t('admin.user.modelConfig') }}</n-button>
      <n-button @click="router.push('/admin/usage')">{{ t('admin.user.usage') }}</n-button>
    </div>

    <n-spin :show="loading">
      <n-card size="small" :bordered="false">
        <n-table :bordered="false" size="small">
          <thead>
            <tr>
              <th>{{ t('admin.user.id') }}</th>
              <th>{{ t('admin.user.username') }}</th>
              <th>{{ t('admin.user.email') }}</th>
              <th>{{ t('admin.user.role') }}</th>
              <th>{{ t('admin.user.status') }}</th>
              <th>{{ t('admin.user.uid') }}</th>
              <th>{{ t('admin.user.createdAt') }}</th>
              <th style="width: 200px">{{ t('admin.user.operator') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td>{{ u.id }}</td>
              <td>{{ u.username }}</td>
              <td>{{ u.email }}</td>
              <td>
                <n-tag :type="u.role === 'admin' ? 'warning' : 'default'" size="small">
                  {{ u.role === 'admin' ? t('admin.user.roleAdmin') : t('admin.user.roleUser') }}
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
                    {{ u.role === 'admin' ? t('admin.user.unsetAdmin') : t('admin.user.setAdmin') }}
                  </n-button>
                  <n-button size="tiny" quaternary type="error" :disabled="rowBusyId !== null" @click="openReset(u)">{{ t('admin.user.resetPwd') }}</n-button>
                </n-space>
              </td>
            </tr>
          </tbody>
        </n-table>
        <n-empty
          v-if="!users.length && !loading"
          :description="t('admin.user.empty')"
          style="margin-top: 30px"
        />
      </n-card>
    </n-spin>

    <n-modal
      v-model:show="resetVisible"
      preset="card"
      :title="t('admin.user.resetTitle')"
      style="width: 380px"
      :mask-closable="false"
      :bordered="false"
    >
      <div class="reset-tip">{{ t('admin.user.resetTip', { name: resetUser?.username }) }}</div>
      <n-input v-model:value="resetPwd" type="password" :placeholder="t('admin.user.resetPlaceholder')" show-password-on="click" />
      <template #footer>
        <div style="display: flex; justify-content: flex-end; gap: 8px">
          <n-button @click="resetVisible = false">{{ t('common.cancel') }}</n-button>
          <n-button type="primary" :loading="resetting" :disabled="resetting" @click="submitReset">{{ t('admin.user.resetConfirm') }}</n-button>
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
