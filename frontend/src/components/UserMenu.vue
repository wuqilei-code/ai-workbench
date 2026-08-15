<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { useMessage } from 'naive-ui'

const router = useRouter()
const chat = useChatStore()
const auth = useAuthStore()
const theme = useThemeStore()
const msg = useMessage()

/** 头像悬浮下拉:导入 / 主题 / 管理(管理员) / 退出登录 */
const userMenuOptions = computed(() => {
  const opts: any[] = [
    { label: '导入会话', key: 'import' },
    { label: theme.mode === 'dark' ? '切换浅色主题' : '切换深色主题', key: 'theme' },
  ]
  if (auth.isAdmin) {
    opts.push({
      label: '管理',
      key: 'admin',
      children: [
        { label: '用户管理', key: '/admin/users' },
        { label: '模型管理', key: '/admin/providers' },
        { label: 'MCP 服务', key: '/admin/mcps' },
        { label: '用量统计', key: '/admin/usage' },
      ],
    })
  }
  opts.push({ type: 'divider', key: 'd1' })
  opts.push({ label: '退出登录', key: 'logout' })
  return opts
})

function onUserMenuSelect(key: string) {
  if (key === 'logout') { auth.logout(); return }
  if (key === 'import') { importFileRef.value?.click(); return }
  if (key === 'theme') { theme.toggle(); return }
  if (key.startsWith('/admin/')) router.push(key)
}

/** 导入会话的 file input */
const importFileRef = ref<HTMLInputElement | null>(null)
const importing = ref(false)

async function importSessionFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  importing.value = true
  try {
    const text = await file.text()
    const s = await chat.importSession(text)
    msg.success(`已导入会话「${s.title}」`)
    router.push(`/session/${s.id}`)
  } catch (err: any) {
    msg.error(err?.message || '导入失败,请确认是导出的 JSON 文件')
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="user-menu">
    <n-dropdown :options="userMenuOptions" trigger="hover" placement="bottom-end" @select="onUserMenuSelect">
      <div class="user-chip" :title="auth.user?.username">
        <span class="avatar">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
        </span>
        <span class="name">{{ auth.user?.username }}</span>
        <n-tag v-if="auth.isAdmin" size="tiny" type="warning" :bordered="false">admin</n-tag>
        <svg class="chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
      </div>
    </n-dropdown>

    <input ref="importFileRef" type="file" accept=".json,application/json" class="hidden-import" @change="importSessionFile" />
  </div>
</template>

<style scoped>
.user-menu {
  display: flex;
  align-items: center;
  gap: 4px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: 8px;
  cursor: pointer;
  max-width: 220px;
  transition: background .15s;
}
.user-chip:hover { background: var(--surface-hover); }
.avatar {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent);
}
.name {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--text); font-size: 13px; font-weight: 500;
}
.chevron {
  flex-shrink: 0;
  color: var(--text-3);
  transition: transform .15s;
}
.user-chip:hover .chevron { transform: rotate(180deg); }
.hidden-import { display: none; }
</style>
