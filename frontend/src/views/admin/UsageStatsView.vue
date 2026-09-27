<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { adminApi } from '@/api/admin'
import { useMessage } from 'naive-ui'
import type { UsageStats } from '@/types'

const router = useRouter()
const { t } = useI18n()
const msg = useMessage()
const days = ref(14)
const loading = ref(false)
const data = ref<UsageStats | null>(null)

const maxDailyMessages = computed(() =>
  Math.max(1, ...(data.value?.daily.map((d) => d.messages) || [])),
)

async function load() {
  loading.value = true
  try {
    data.value = await adminApi.usageStats(days.value)
  } catch (e: any) {
    msg.error(e.message)
  } finally {
    loading.value = false
  }
}

function dayLabel(date: string): string {
  const d = new Date(date + 'T00:00:00')
  const today = new Date()
  const diff = Math.round((today.getTime() - d.getTime()) / 86400000)
  if (diff === 0) return t('admin.usage.today')
  if (diff === 1) return t('admin.usage.yesterday')
  return `${d.getMonth() + 1}/${d.getDate()}`
}

onMounted(load)
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <n-button @click="router.push('/')">{{ t('common.back') }}</n-button>
      <h2>{{ t('admin.usage.title') }}</h2>
      <div class="spacer" />
      <n-select
        v-model:value="days"
        :options="[7, 14, 30, 90].map((d) => ({ label: t('admin.usage.lastDays', { d }), value: d }))"
        style="width: 120px"
        @update:value="load"
      />
      <n-button type="primary" :loading="loading" @click="load">{{ t('common.refresh') }}</n-button>
      <n-button @click="router.push('/admin/providers')">{{ t('admin.user.modelConfig') }}</n-button>
    </div>

    <n-spin :show="loading">
      <template v-if="data">
        <!-- 概览 -->
        <div class="overview">
          <n-card size="small" :title="t('admin.usage.totalUsers')"><div class="big">{{ data.overview.totalUsers }}</div></n-card>
          <n-card size="small" :title="t('admin.usage.totalSessions')"><div class="big">{{ data.overview.totalSessions }}</div></n-card>
          <n-card size="small" :title="t('admin.usage.totalMessages')"><div class="big">{{ data.overview.totalMessages }}</div></n-card>
          <n-card size="small" :title="t('admin.usage.toolCalls')"><div class="big">{{ data.overview.totalToolCalls }}</div></n-card>
          <n-card size="small" :title="t('admin.usage.todayMessages')"><div class="big">{{ data.overview.todayMessages }}</div></n-card>
          <n-card size="small" :title="t('admin.usage.activeUsers7d')"><div class="big">{{ data.overview.activeUsers7d }}</div></n-card>
        </div>

        <!-- 每日趋势 -->
        <n-card size="small" :title="t('admin.usage.dailyTitle')" class="block">
          <div v-if="data.daily.length" class="bars">
            <div
              v-for="d in data.daily"
              :key="d.date"
              class="bar-col"
              :title="t('admin.usage.dailyTip', { date: d.date, msgs: d.messages, users: d.users })"
            >
              <div class="bar-val">{{ d.messages }}</div>
              <div class="bar-track">
                <div
                  class="bar-fill"
                  :style="{ height: Math.round((d.messages / maxDailyMessages) * 100) + '%' }"
                />
              </div>
              <div class="bar-label">{{ dayLabel(d.date) }}</div>
            </div>
          </div>
          <n-empty v-else :description="t('admin.usage.dailyEmpty')" size="small" />
        </n-card>

        <!-- Top 用户 -->
        <n-card size="small" :title="t('admin.usage.topTitle')" class="block">
          <n-table size="small" :bordered="false" v-if="data.topUsers.length">
            <thead>
              <tr><th>{{ t('admin.usage.username') }}</th><th>{{ t('admin.usage.sessions') }}</th><th>{{ t('admin.usage.messages') }}</th></tr>
            </thead>
            <tbody>
              <tr v-for="u in data.topUsers" :key="u.userId">
                <td>{{ u.username }}</td>
                <td>{{ u.sessions }}</td>
                <td>{{ u.messages }}</td>
              </tr>
            </tbody>
          </n-table>
          <n-empty v-else :description="t('admin.usage.topEmpty')" size="small" />
        </n-card>
      </template>
      <n-empty v-else-if="!loading" :description="t('admin.usage.noData')" style="margin-top: 60px" />
    </n-spin>
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

.overview { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 16px; }
@media (max-width: 900px) { .overview { grid-template-columns: repeat(3, 1fr); } }
.overview .big { font-size: 28px; font-weight: 700; text-align: center; padding: 4px 0 8px; }

.block { margin-bottom: 16px; }

/* 卡片表面:略亮于页面背景,保持深色主题统一 */
.admin-view :deep(.n-card) {
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}

.bars { display: flex; align-items: flex-end; gap: 8px; height: 200px; padding: 8px 4px 0; }
.bar-col {
  flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; min-width: 0;
  height: 100%;
}
.bar-val { font-size: 11px; color: var(--text-2); line-height: 1; }
.bar-track {
  flex: 1; width: 100%; max-width: 36px;
  display: flex; align-items: flex-end;
  background: var(--bg); border-radius: 6px; overflow: hidden;
  position: relative; min-height: 0;
}
.bar-fill {
  width: 100%;
  background: linear-gradient(180deg, var(--accent), #8b7bfd);
  border-radius: 6px 6px 0 0; transition: height .3s ease;
}
.bar-label { font-size: 10px; color: var(--text-3); white-space: nowrap; line-height: 1; }
</style>
