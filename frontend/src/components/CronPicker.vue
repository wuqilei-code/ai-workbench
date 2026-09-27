<script setup lang="ts">
/**
 * 定时 cron 可视化选择器:通过选项自动生成 cron 表达式(Spring 6 段格式)。
 * 最小调度粒度 = 小时:只提供「每N小时 / 每天 / 每周 / 每月」,不提供秒/分钟级选项。
 * v-model = cron 字符串(空 = 一次性/不定时)。
 */
import { computed, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import { useI18n } from 'vue-i18n'

const props = defineProps<{ value?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'update:value', v: string): void }>()

const msg = useMessage()
const { t } = useI18n()

type Mode = 'hourly' | 'daily' | 'weekly' | 'monthly' | 'none'
const mode = ref<Mode>('none')
const everyHours = ref(2)
const timeHour = ref(9)
const timeMin = ref(0)
const weekDays = ref<string[]>(['MON'])
const monthDay = ref(1)
const customCron = ref('')

const WEEK_OPTIONS = [
  { label: t('cron.mon'), value: 'MON' },
  { label: t('cron.tue'), value: 'TUE' },
  { label: t('cron.wed'), value: 'WED' },
  { label: t('cron.thu'), value: 'THU' },
  { label: t('cron.fri'), value: 'FRI' },
  { label: t('cron.sat'), value: 'SAT' },
  { label: t('cron.sun'), value: 'SUN' },
]
const DAYS_31 = Array.from({ length: 31 }, (_, i) => ({ label: t('cron.dayUnit', { n: i + 1 }), value: i + 1 }))
const HOURS = Array.from({ length: 24 }, (_, i) => ({ label: t('cron.hourLabel', { h: String(i).padStart(2, '0') }), value: i }))
const MINUTES = Array.from({ length: 60 }, (_, i) => ({ label: t('cron.minLabel', { m: String(i).padStart(2, '0') }), value: i }))

const hoursOptions = Array.from({ length: 24 }, (_, i) => ({ label: t('cron.everyNHours', { n: i + 1 }), value: i + 1 }))

/** 由当前选项生成 cron(Spring 6 段:秒 分 时 日 月 周)。 */
function buildCron(): string {
  const hh = String(timeHour.value).padStart(2, '0')
  const mm = String(timeMin.value).padStart(2, '0')
  switch (mode.value) {
    case 'hourly':
      return `0 0 */${everyHours.value} * * ?`
    case 'daily':
      return `0 ${mm} ${hh} * * ?`
    case 'weekly': {
      const days = weekDays.value.length ? weekDays.value.join(',') : 'MON'
      return `0 ${mm} ${hh} ? * ${days}`
    }
    case 'monthly':
      return `0 ${mm} ${hh} ${monthDay.value} * ?`
    case 'none':
    default:
      return ''
  }
}

/** 人类可读描述。 */
const summary = computed(() => {
  const hh = String(timeHour.value).padStart(2, '0')
  const mm = String(timeMin.value).padStart(2, '0')
  switch (mode.value) {
    case 'hourly': return t('cron.hourlyDesc', { n: everyHours.value })
    case 'daily': return t('cron.dailyDesc', { hh, mm })
    case 'weekly': {
    const names = weekDays.value.length ? weekDays.value.map((d) => WEEK_OPTIONS.find((w) => w.value === d)?.label ?? d).join('、') : t('cron.weeklyFallback')
    return t('cron.weeklyDesc', { names, hh, mm })
    }
    case 'monthly': return t('cron.monthlyDesc', { d: monthDay.value, hh, mm })
    case 'none': return t('cron.noneDesc')
    default: return ''
  }
})

watch(
  [mode, everyHours, timeHour, timeMin, weekDays, monthDay],
  () => {
    const c = buildCron()
    emit('update:value', c)
    customCron.value = c
  },
)

/** 解析已有 cron,回填控件(新/编辑时表单带值)。 */
function parseInto(cron?: string | null) {
  customCron.value = cron ?? ''
  if (!cron) {
    mode.value = 'none'
    return
  }
  const p = cron.trim().split(/\s+/)
  if (p.length !== 6) {
    mode.value = 'none'
    return
  }
  const [, minute, hour, dom, , dow] = p
  const mm = /^\d+$/.test(minute) ? Number(minute) : 0
  const hh = /^\d+$/.test(hour) ? Number(hour) : 0
  if (minute === '0' && /^\*\/\d+$/.test(hour)) {
    mode.value = 'hourly'
    everyHours.value = Number(hour.replace('*/', ''))
    return
  }
  if (dow !== '?' && dom === '*') {
    mode.value = 'weekly'
    timeHour.value = Math.min(hh, 23)
    timeMin.value = Math.min(mm, 59)
    weekDays.value = dow.split(',').filter((d) => WEEK_OPTIONS.some((w) => w.value === d))
    if (!weekDays.value.length) weekDays.value = ['MON']
    return
  }
  if (dow === '?' && /^\d+$/.test(dom)) {
    mode.value = 'monthly'
    timeHour.value = Math.min(hh, 23)
    timeMin.value = Math.min(mm, 59)
    monthDay.value = Math.min(Number(dom), 31)
    return
  }
  if (dow === '?' && dom === '*') {
    mode.value = 'daily'
    timeHour.value = Math.min(hh, 23)
    timeMin.value = Math.min(mm, 59)
    return
  }
  mode.value = 'none'
}

watch(
  () => props.value,
  (v) => {
    if (v !== buildCron()) parseInto(v)
  },
  { immediate: true },
)
</script>

<template>
  <div class="cron-picker">
    <n-radio-group v-model:value="mode" size="small">
        <n-radio-button value="none">{{ t('cron.modeNone') }}</n-radio-button>
        <n-radio-button value="hourly">{{ t('cron.modeHourly') }}</n-radio-button>
        <n-radio-button value="daily">{{ t('cron.modeDaily') }}</n-radio-button>
        <n-radio-button value="weekly">{{ t('cron.modeWeekly') }}</n-radio-button>
        <n-radio-button value="monthly">{{ t('cron.modeMonthly') }}</n-radio-button>
    </n-radio-group>

    <div v-if="mode === 'hourly'" class="pick-row">
      <n-select v-model:value="everyHours" :options="hoursOptions" size="small" class="pick-select" />
        <span class="pick-note">{{ t('cron.note') }}</span>
    </div>

    <div v-if="mode === 'daily'" class="pick-row">
        <span class="pick-label">{{ t('cron.labelDay') }}</span>
      <n-select v-model:value="timeHour" :options="HOURS" size="small" class="pick-time" />
      <n-select v-model:value="timeMin" :options="MINUTES" size="small" class="pick-time" />
    </div>

    <div v-if="mode === 'weekly'" class="pick-row">
      <n-checkbox-group v-model:value="weekDays">
        <n-checkbox v-for="w in WEEK_OPTIONS" :key="w.value" :value="w.value" :label="w.label" size="small" />
      </n-checkbox-group>
        <span class="pick-label">{{ t('cron.labelAt') }}</span>
      <n-select v-model:value="timeHour" :options="HOURS" size="small" class="pick-time" />
      <n-select v-model:value="timeMin" :options="MINUTES" size="small" class="pick-time" />
    </div>

    <div v-if="mode === 'monthly'" class="pick-row">
        <span class="pick-label">{{ t('cron.labelDay') }}</span>
      <n-select v-model:value="monthDay" :options="DAYS_31" size="small" class="pick-day" />
        <span class="pick-label">{{ t('cron.labelAt') }}</span>
      <n-select v-model:value="timeHour" :options="HOURS" size="small" class="pick-time" />
      <n-select v-model:value="timeMin" :options="MINUTES" size="small" class="pick-time" />
    </div>

    <div class="cron-output">
      <span class="cron-summary">{{ summary }}</span>
      <code class="cron-code" :title="customCron">{{ customCron || '—' }}</code>
    </div>
  </div>
</template>

<style scoped>
.cron-picker { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.pick-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pick-label { font-size: 12px; color: var(--text-2); }
.pick-note { font-size: 11px; color: var(--text-3); }
.pick-select { width: 150px; }
.pick-time { width: 86px; }
.pick-day { width: 86px; }
.cron-output {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 4px 8px; border: 1px dashed var(--border); border-radius: var(--radius-sm);
  background: var(--surface);
}
.cron-summary { font-size: 12px; color: var(--text-2); }
.cron-code { font-size: 12px; font-family: Consolas, Menlo, monospace; color: var(--accent); }
</style>
