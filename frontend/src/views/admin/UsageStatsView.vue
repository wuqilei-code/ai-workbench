<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '@/api/admin'
import { useMessage } from 'naive-ui'
import type { UsageStats } from '@/types'

const router = useRouter()
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
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  return `${d.getMonth() + 1}/${d.getDate()}`
}

onMounted(load)
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <n-button @click="router.push('/')">← 返回</n-button>
      <h2>用量统计</h2>
      <div class="spacer" />
      <n-select
        v-model:value="days"
        :options="[7, 14, 30, 90].map((d) => ({ label: `近 ${d} 天`, value: d }))"
        style="width: 120px"
        @update:value="load"
      />
      <n-button type="primary" :loading="loading" @click="load">刷新</n-button>
      <n-button @click="router.push('/admin/providers')">模型配置</n-button>
    </div>

    <n-spin :show="loading">
      <template v-if="data">
        <!-- 概览 -->
        <div class="overview">
          <n-card size="small" title="注册用户"><div class="big">{{ data.overview.totalUsers }}</div></n-card>
          <n-card size="small" title="会话总数"><div class="big">{{ data.overview.totalSessions }}</div></n-card>
          <n-card size="small" title="消息总数"><div class="big">{{ data.overview.totalMessages }}</div></n-card>
          <n-card size="small" title="工具调用"><div class="big">{{ data.overview.totalToolCalls }}</div></n-card>
          <n-card size="small" title="今日消息"><div class="big">{{ data.overview.todayMessages }}</div></n-card>
          <n-card size="small" title="近 7 天活跃用户"><div class="big">{{ data.overview.activeUsers7d }}</div></n-card>
        </div>

        <!-- 每日趋势 -->
        <n-card size="small" title="每日消息量趋势" class="block">
          <div v-if="data.daily.length" class="bars">
            <div
              v-for="d in data.daily"
              :key="d.date"
              class="bar-col"
              :title="`${d.date} · ${d.messages} 条消息 · ${d.users} 个用户`"
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
          <n-empty v-else description="该时间范围内暂无消息" size="small" />
        </n-card>

        <!-- Top 用户 -->
        <n-card size="small" title="活跃用户 Top 10(近 7 天)" class="block">
          <n-table size="small" :bordered="false" v-if="data.topUsers.length">
            <thead>
              <tr><th>用户名</th><th>会话数</th><th>消息数</th></tr>
            </thead>
            <tbody>
              <tr v-for="u in data.topUsers" :key="u.userId">
                <td>{{ u.username }}</td>
                <td>{{ u.sessions }}</td>
                <td>{{ u.messages }}</td>
              </tr>
            </tbody>
          </n-table>
          <n-empty v-else description="近 7 天暂无活跃用户" size="small" />
        </n-card>
      </template>
      <n-empty v-else-if="!loading" description="暂无统计数据" style="margin-top: 60px" />
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
.admin-head h2 { margin: 0; font-size: 16px; }
.spacer { flex: 1; }

.overview { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 16px; }
@media (max-width: 900px) { .overview { grid-template-columns: repeat(3, 1fr); } }
.overview .big { font-size: 28px; font-weight: 700; text-align: center; padding: 4px 0 8px; }

.block { margin-bottom: 16px; }

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
