<script setup lang="ts">
/** 任务规划卡片:渲染 write_todos 产出的任务清单,随流式实时更新。
 *  进行中项高亮 + spinner,完成项打勾;头部显示整体进度条。
 *  数据来源:AssistantTurn 通过 extractPlan(turn) 取最后一次 write_todos 的整表。 */
import { computed } from 'vue'
import type { PlanTodo } from '@/utils/turns'

const props = defineProps<{ todos: PlanTodo[] }>()

const completedCount = computed(() => props.todos.filter((t) => t.status === 'completed').length)
const pct = computed(() =>
  props.todos.length ? Math.round((completedCount.value / props.todos.length) * 100) : 0,
)

/** 展示文本:进行中优先用 active_form(现在进行时描述) */
function label(t: PlanTodo): string {
  return (t.status === 'in_progress' && t.active_form) || t.content
}
</script>

<template>
  <div v-if="todos.length" class="task-plan">
    <div class="plan-head">
      <span class="plan-title">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/></svg>
        任务规划
      </span>
      <span class="plan-progress-text">{{ completedCount }}/{{ todos.length }}</span>
    </div>
    <div class="plan-bar">
      <div class="plan-bar-fill" :style="{ width: pct + '%' }" />
    </div>
    <ul class="plan-list">
      <li
        v-for="(t, i) in todos"
        :key="i"
        class="plan-item"
        :class="[t.status]"
      >
        <span class="plan-check">
          <!-- 完成:绿勾 -->
          <svg v-if="t.status === 'completed'" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
          <!-- 进行中:spinner 环 -->
          <span v-else-if="t.status === 'in_progress'" class="plan-ring" />
          <!-- 待办:空心圆 -->
          <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9" /></svg>
        </span>
        <span class="plan-text">{{ label(t) }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.task-plan {
  margin: 6px 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-elev);
  overflow: hidden;
}
.plan-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 12px 4px;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
}
.plan-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--accent);
}
.plan-progress-text {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.plan-bar {
  margin: 0 12px 8px;
  height: 3px;
  border-radius: 2px;
  background: var(--border);
  overflow: hidden;
}
.plan-bar-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--ok);
  transition: width .4s ease;
}
.plan-list {
  margin: 0;
  padding: 2px 12px 8px;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.plan-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--text-3);
}
.plan-item .plan-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 3px;
  color: var(--text-3);
}
.plan-item.completed { color: var(--text-3); }
.plan-item.completed .plan-check { color: var(--ok); }
.plan-item.completed .plan-text { text-decoration: line-through; text-decoration-color: var(--text-3); }
.plan-item.in_progress { color: var(--text); font-weight: 500; }
.plan-item.in_progress .plan-check { color: var(--accent); }
.plan-text { min-width: 0; overflow-wrap: anywhere; }

/* 进行中 spinner 环(与思考头部同款节奏) */
.plan-ring {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  border: 2px solid var(--accent-soft, rgba(88, 166, 255, .25));
  border-top-color: var(--accent);
  animation: plan-spin .8s linear infinite;
}
@keyframes plan-spin { to { transform: rotate(360deg); } }
</style>
