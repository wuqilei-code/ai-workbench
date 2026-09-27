<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { myApi, type MyStats } from '@/api/my'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'

const emit = defineEmits<{ (e: 'close'): void }>()
const props = defineProps<{ page?: boolean }>()

const msg = useMessage()
const { t } = useI18n()
const days = ref(14)
const loading = ref(false)
const data = ref<MyStats | null>(null)

/** Esc 关闭驾驶舱 */
function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}
onMounted(() => {
  load()
  window.addEventListener('keydown', onKey)
})
onUnmounted(() => window.removeEventListener('keydown', onKey))

async function load() {
  loading.value = true
  try {
    data.value = await myApi.stats(days.value)
  } catch (e: any) {
    msg.error(e.message || t('dashboard.loadFailed'))
  } finally {
    loading.value = false
  }
}

const ov = computed(() => data.value?.overview)

function fmtNum(n: number | undefined): string {
  return (n ?? 0).toLocaleString('en-US')
}
function fmtTokens(n: number | undefined): string {
  const v = n ?? 0
  if (v >= 1e9) return (v / 1e9).toFixed(2) + 'B'
  if (v >= 1e6) return (v / 1e6).toFixed(2) + 'M'
  if (v >= 1e3) return (v / 1e3).toFixed(1) + 'K'
  return String(v)
}
function fmtMoney(n: number | undefined): string {
  return '¥' + (n ?? 0).toFixed(2)
}

function pct(cur: number, prev: number): { text: string; up: boolean } {
  if (!prev) return { text: '—', up: true }
  const d = ((cur - prev) / prev) * 100
  return { text: (d > 0 ? '+' : '') + d.toFixed(1) + '%', up: d >= 0 }
}

function dayLabel(date: string): string {
  const d = new Date(date + 'T00:00:00')
  const today = new Date()
  const diff = Math.round((today.getTime() - d.getTime()) / 86400000)
  if (diff === 0) return t('dashboard.today')
  if (diff === 1) return t('dashboard.yesterday')
  return `${d.getMonth() + 1}/${d.getDate()}`
}

const maxDailyMessages = computed(() =>
  Math.max(1, ...(data.value?.daily.map((d) => d.messages) || [])),
)
const maxDailyTokens = computed(() =>
  Math.max(1, ...(data.value?.daily.map((d) => d.outputTokens) || [])),
)

const cacheHitRate = computed(() => {
  const r = ov.value?.cacheReadTokens ?? 0
  const w = ov.value?.cacheWriteTokens ?? 0
  if (!r && !w) return 0
  return Math.round((r / (r + w)) * 100)
})
const jobSuccessRate = computed(() => {
  const s = ov.value?.successRuns ?? 0
  const t = ov.value?.totalRuns ?? 0
  return t ? Math.round((s / t) * 100) : 0
})

const maxAgent = computed(() => Math.max(1, ...(data.value?.agents.map((a) => a.count) || [])))
</script>

<template>
  <div :class="page ? 'dash-page' : 'dash-mask'" @click.self="!page && emit('close')">
    <div v-if="page" class="manage-crumb">
      <button class="crumb-link" @click="emit('close')">首页</button>
      <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ t('dashboard.crumb') }}</span>
    </div>
    <div class="dash">
      <div class="dash-head">
      <div>
        <h2>{{ t('dashboard.crumb') }}</h2>
        <p class="sub">{{ t('dashboard.sub') }}</p>
      </div>
      <div class="head-ops">
        <n-select
          v-model:value="days"
          :options="[7, 14, 30, 90].map((d) => ({ label: t('dashboard.lastDays', { d }), value: d }))"
          style="width: 116px"
          @update:value="load"
        />
        <n-button type="primary" :loading="loading" @click="load">{{ t('dashboard.refresh') }}</n-button>
        <n-button quaternary title="关闭驾驶舱" @click="emit('close')" v-if="!page">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </n-button>
      </div>
    </div>

    <n-spin :show="loading">
      <template v-if="data">
        <!-- 核心指标 -->
        <div class="cards">
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statSessions') }}</span>
            <span class="stat-value">{{ fmtNum(ov?.totalSessions) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statMessages') }}</span>
            <span class="stat-value">{{ fmtNum(ov?.totalMessages) }}</span>
            <span class="stat-sub">{{ t('dashboard.statMsgSub', { u: fmtNum(ov?.userMessages), a: fmtNum(ov?.assistantMessages) }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statOutputToken') }}</span>
            <span class="stat-value">{{ fmtTokens(ov?.outputTokens) }}</span>
            <span class="stat-sub">{{ t('dashboard.statTokenSub', { v: fmtTokens(ov?.inputTokens) }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statToolCalls') }}</span>
            <span class="stat-value">{{ fmtNum(ov?.toolCalls) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statFileChanges') }}</span>
            <span class="stat-value">{{ fmtNum(ov?.filesChanged) }}</span>
          </div>
          <div class="stat accent">
            <span class="stat-label">{{ t('dashboard.statCost') }}</span>
            <span class="stat-value">{{ fmtMoney(ov?.totalEstimatedCost) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statSuccessRate') }}</span>
            <span class="stat-value">{{ jobSuccessRate }}%</span>
            <span class="stat-sub">{{ t('dashboard.statRunsSub', { ok: fmtNum(ov?.successRuns), total: fmtNum(ov?.totalRuns) }) }}</span>
          </div>
          <div class="stat">
            <span class="stat-label">{{ t('dashboard.statUnread') }}</span>
            <span class="stat-value">{{ fmtNum(ov?.unreadNotifications) }}</span>
          </div>
        </div>

        <!-- 环比 -->
        <div class="wow-row">
          <div class="wow-item">
            <span class="wow-label">{{ t('dashboard.wowMessages') }}</span>
            <span class="wow-value">{{ fmtNum(ov?.weekMessages) }}</span>
            <span class="wow-pct" :class="{ up: pct(ov?.weekMessages || 0, ov?.prevWeekMessages || 0).up, down: !pct(ov?.weekMessages || 0, ov?.prevWeekMessages || 0).up }">
              {{ pct(ov?.weekMessages || 0, ov?.prevWeekMessages || 0).text }}
            </span>
            <span class="wow-base">{{ t('dashboard.wowPrevMessages', { v: fmtNum(ov?.prevWeekMessages) }) }}</span>
          </div>
          <div class="wow-item">
            <span class="wow-label">{{ t('dashboard.wowOutputToken') }}</span>
            <span class="wow-value">{{ fmtTokens(ov?.weekOutputTokens) }}</span>
            <span class="wow-pct" :class="{ up: pct(ov?.weekOutputTokens || 0, ov?.prevWeekOutputTokens || 0).up, down: !pct(ov?.weekOutputTokens || 0, ov?.prevWeekOutputTokens || 0).up }">
              {{ pct(ov?.weekOutputTokens || 0, ov?.prevWeekOutputTokens || 0).text }}
            </span>
            <span class="wow-base">{{ t('dashboard.wowPrevToken', { v: fmtTokens(ov?.prevWeekOutputTokens) }) }}</span>
          </div>
          <div class="wow-item">
            <span class="wow-label">{{ t('dashboard.wowCache') }}</span>
            <span class="wow-value">{{ cacheHitRate }}%</span>
            <span class="wow-base">{{ t('dashboard.wowCacheSub', { r: fmtTokens(ov?.cacheReadTokens), w: fmtTokens(ov?.cacheWriteTokens) }) }}</span>
          </div>
          <div class="wow-item">
            <span class="wow-label">{{ t('dashboard.wowStreak') }}</span>
            <span class="wow-value">{{ t('dashboard.streakDays', { d: ov?.currentStreak }) }}</span>
            <span class="wow-base">{{ t('dashboard.streakTotal', { d: ov?.activeDays }) }}</span>
          </div>
        </div>

        <!-- 每日趋势 -->
        <div class="grid-2">
          <n-card size="small" :title="t('dashboard.dailyMessages')" class="block">
            <div v-if="data.daily.length" class="bars">
              <div v-for="d in data.daily" :key="d.date" class="bar-col" :title="t('dashboard.barTitle', { date: d.date, n: d.messages })">
                <div class="bar-val">{{ d.messages }}</div>
                <div class="bar-track">
                  <div class="bar-fill" :style="{ height: Math.round((d.messages / maxDailyMessages) * 100) + '%' }" />
                </div>
                <div class="bar-label">{{ dayLabel(d.date) }}</div>
              </div>
            </div>
            <n-empty v-else :description="t('dashboard.emptyMessages')" size="small" />
          </n-card>

          <n-card size="small" :title="t('dashboard.dailyToken')" class="block">
            <div v-if="data.daily.length" class="bars">
              <div v-for="d in data.daily" :key="d.date" class="bar-col" :title="`${d.date} · ${fmtTokens(d.outputTokens)} tokens`">
                <div class="bar-val">{{ fmtTokens(d.outputTokens) }}</div>
                <div class="bar-track">
                  <div class="bar-fill green" :style="{ height: Math.round((d.outputTokens / maxDailyTokens) * 100) + '%' }" />
                </div>
                <div class="bar-label">{{ dayLabel(d.date) }}</div>
              </div>
            </div>
            <n-empty v-else :description="t('dashboard.emptyToken')" size="small" />
          </n-card>
        </div>

        <!-- 模型维度 -->
        <n-card size="small" :title="t('dashboard.modelTable')" class="block">
          <n-table size="small" :bordered="false" v-if="data.models.length">
            <thead>
              <tr><th>{{ t('dashboard.thModel') }}</th><th>{{ t('dashboard.thSessions') }}</th><th>{{ t('dashboard.thInput') }}</th><th>{{ t('dashboard.thOutput') }}</th><th>{{ t('dashboard.thCost') }}</th></tr>
            </thead>
            <tbody>
              <tr v-for="m in data.models" :key="m.model">
                <td class="mono">{{ m.model }}</td>
                <td>{{ fmtNum(m.sessions) }}</td>
                <td>{{ fmtTokens(m.inputTokens) }}</td>
                <td>{{ fmtTokens(m.outputTokens) }}</td>
                <td>{{ fmtMoney(m.estimatedCost) }}</td>
              </tr>
            </tbody>
          </n-table>
          <n-empty v-else :description="t('dashboard.emptyModel')" size="small" />
        </n-card>

        <div class="grid-2">
          <!-- agent 分布 -->
          <n-card size="small" :title="t('dashboard.agentDist')" class="block">
            <div v-if="data.agents.length" class="dist-list">
              <div v-for="a in data.agents" :key="a.name" class="dist-row">
                <span class="dist-name">{{ a.name }}</span>
                <div class="dist-track">
                  <div class="dist-fill" :style="{ width: Math.round((a.count / maxAgent) * 100) + '%' }" />
                </div>
                <span class="dist-count">{{ a.count }}</span>
              </div>
            </div>
            <n-empty v-else :description="t('dashboard.emptyAgent')" size="small" />
          </n-card>

          <!-- 资产 -->
          <n-card size="small" :title="t('dashboard.assets')" class="block">
            <div class="asset-grid">
              <div class="asset"><span class="asset-num">{{ ov?.installedSkills }}</span><span class="asset-label">{{ t('dashboard.assetSkills') }}</span></div>
              <div class="asset"><span class="asset-num">{{ ov?.publishedSkills }}</span><span class="asset-label">{{ t('dashboard.assetPublished') }}</span></div>
              <div class="asset"><span class="asset-num">{{ ov?.privateMcps }}</span><span class="asset-label">{{ t('dashboard.assetMcp') }}</span></div>
              <div class="asset"><span class="asset-num">{{ ov?.myTemplates }}</span><span class="asset-label">{{ t('dashboard.assetTemplates') }}</span></div>
              <div class="asset"><span class="asset-num">{{ ov?.favorites }}</span><span class="asset-label">{{ t('dashboard.assetFav') }}</span></div>
              <div class="asset"><span class="asset-num">{{ ov?.compactionCount }}</span><span class="asset-label">{{ t('dashboard.assetCompact') }}</span></div>
            </div>
          </n-card>
        </div>
      </template>
      <n-empty v-else-if="!loading" :description="t('dashboard.emptyAll')" style="margin-top: 60px" />
    </n-spin>
      </div>
  </div>
</template>

<style scoped>
/* 模态遮罩:点击背景关闭;内容居中,不再遮挡会话区 */
.dash-mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
}
.dash {
  width: min(1100px, 100%);
  max-height: 100%;
  overflow-y: auto;
  background: var(--chat-bg, var(--bg));
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  box-shadow: var(--shadow);
  padding: 16px 24px 40px;
  box-sizing: border-box;
}
.dash-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.dash-head h2 { margin: 0; font-size: 20px; color: var(--text); }
.dash-head .sub { margin: 4px 0 0; font-size: 13px; color: var(--text-3); }
.head-ops { display: flex; align-items: center; gap: 8px; }

.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 1100px) { .cards { grid-template-columns: repeat(2, 1fr); } }
.stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}
.stat-label { font-size: 12px; color: var(--text-3); }
.stat-value { font-size: 26px; font-weight: 700; color: var(--text); line-height: 1.1; }
.stat-sub { font-size: 11px; color: var(--text-3); }
.stat.accent .stat-value { color: var(--accent); }

.wow-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 1100px) { .wow-row { grid-template-columns: repeat(2, 1fr); } }
.wow-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}
.wow-label { font-size: 12px; color: var(--text-3); }
.wow-value { font-size: 20px; font-weight: 700; color: var(--text); }
.wow-pct { font-size: 13px; font-weight: 600; }
.wow-pct.up { color: #10B981; }
.wow-pct.down { color: #EF4444; }
.wow-base { font-size: 11px; color: var(--text-3); }

.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
@media (max-width: 1100px) { .grid-2 { grid-template-columns: 1fr; } }
.block { margin-bottom: 16px; }

.dash :deep(.n-card) {
  background: var(--bg-elev);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
}

.bars { display: flex; align-items: flex-end; gap: 8px; height: 190px; padding: 8px 4px 0; }
.bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 4px; min-width: 0; height: 100%; }
.bar-val { font-size: 10px; color: var(--text-3); line-height: 1; white-space: nowrap; }
.bar-track {
  flex: 1; width: 100%; max-width: 40px;
  display: flex; align-items: flex-end;
  background: var(--bg); border-radius: 6px; overflow: hidden;
  min-height: 0;
}
.bar-fill {
  width: 100%;
  background: linear-gradient(180deg, var(--accent), #8b7bfd);
  border-radius: 6px 6px 0 0; transition: height .3s ease;
}
.bar-fill.green { background: linear-gradient(180deg, #34d399, #10b981); }
.bar-label { font-size: 10px; color: var(--text-3); white-space: nowrap; line-height: 1; }

.mono { font-family: ui-monospace, monospace; }

.dist-list { display: flex; flex-direction: column; gap: 10px; }
.dist-row { display: flex; align-items: center; gap: 10px; }
.dist-name { width: 120px; flex-shrink: 0; font-size: 13px; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dist-track { flex: 1; height: 8px; background: var(--bg); border-radius: 4px; overflow: hidden; }
.dist-fill { height: 100%; background: var(--accent); border-radius: 4px; transition: width .3s ease; }
.dist-count { width: 40px; flex-shrink: 0; text-align: right; font-size: 12px; color: var(--text-3); }

.asset-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.asset { display: flex; flex-direction: column; align-items: center; gap: 4px; padding: 12px; background: var(--bg); border-radius: var(--radius-sm); }
.asset-num { font-size: 20px; font-weight: 700; color: var(--text); }
.asset-label { font-size: 11px; color: var(--text-3); }

/* 整页模式:面包屑 + 内容区滚动 */
.dash-page { flex: 1; min-width: 0; display: flex; flex-direction: column; height: 100%; min-height: 0; overflow-y: auto; padding: 18px 24px 28px; background: var(--bg); box-sizing: border-box; }
.manage-crumb { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-3); margin-bottom: 12px; }
.crumb-link { background: none; border: none; padding: 0; color: var(--text-3); cursor: pointer; font-size: 12px; }
.crumb-link:hover { color: var(--accent-text); }
.crumb-sep { color: var(--text-4); }
.crumb-cur { color: var(--text-2); }
/* 整页模式下 .dash 去掉浮层盒子样式,铺满内容区 */
.dash-page .dash {
  width: 100%; max-width: none; max-height: none; overflow: visible;
  background: transparent; border: none; border-radius: 0; box-shadow: none; padding: 0;
}
.dash-page .cards { grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); }
.dash-page .grid-2 { grid-template-columns: 1fr 1fr; }
@media (max-width: 900px) { .dash-page .grid-2 { grid-template-columns: 1fr; } }
</style>
