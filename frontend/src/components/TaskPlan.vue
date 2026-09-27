<script setup lang="ts">
/** 任务规划卡片:渲染 write_todos 产出的任务清单,随流式实时更新。
 *  - 默认展开,固定高度,步骤多时内部滚动;
 *  - 展开时右上角为向下箭头,点击收起为「仅一行」(进行中/首条),箭头变向上,再点展开;
 *  - 完成项:方框打勾 + 删除线;进行中:圆环实心点;待办:空心圆。
 *  数据来源:AssistantTurn 通过 extractPlan(turn) 取最后一次 write_todos 的整表。 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { PlanTodo } from '@/utils/turns'

const props = defineProps<{ todos: PlanTodo[] }>()
const { t } = useI18n()

const completedCount = computed(() => props.todos.filter((t) => t.status === 'completed').length)
/** 收起时展示的单条:优先进行中,否则第一条 */
const current = computed(() => props.todos.find((t) => t.status === 'in_progress') || props.todos[0])

/** 折叠状态:默认展开;用户手动切换后保持 */
const collapsed = ref(false)
function toggleCollapsed() {
  collapsed.value = !collapsed.value
}

/** 展示文本:进行中优先用 active_form(现在进行时描述) */
function label(t?: PlanTodo): string {
  if (!t) return ''
  return (t.status === 'in_progress' && t.active_form) || t.content
}
</script>

<template>
  <div v-if="todos.length" class="task-plan" :class="{ collapsed }">
    <div class="plan-head" role="button" tabindex="0" :aria-expanded="!collapsed" @click="toggleCollapsed" @keydown.enter.prevent="toggleCollapsed" @keydown.space.prevent="toggleCollapsed">
      <span class="plan-title">{{ t('plan.progress', { done: completedCount, total: todos.length }) }}</span>
      <svg class="plan-chevron" :class="{ up: collapsed }" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9"/></svg>
    </div>

    <!-- 收起:仅展示一条(进行中/首条) -->
    <div v-if="collapsed" class="plan-single">
      <span class="plan-check" :class="current?.status">
        <svg v-if="current?.status === 'completed'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="4"/><path d="M8 12l3 3 5-6"/></svg>
        <svg v-else-if="current?.status === 'in_progress'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3.5" fill="currentColor" stroke="none"/></svg>
        <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/></svg>
      </span>
      <span class="plan-text">{{ label(current) }}</span>
    </div>

    <!-- 展开:完整清单,固定高度,超出滚动 -->
    <div v-else class="plan-body">
      <ul class="plan-list">
        <li
          v-for="(t, i) in todos"
          :key="i"
          class="plan-item"
          :class="[t.status]"
        >
          <span class="plan-check">
            <!-- 完成:方框打勾 -->
            <svg v-if="t.status === 'completed'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="18" height="18" rx="4"/><path d="M8 12l3 3 5-6"/></svg>
            <!-- 进行中:圆环 + 实心点 -->
            <svg v-else-if="t.status === 'in_progress'" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3.5" fill="currentColor" stroke="none"/></svg>
            <!-- 待办:空心圆 -->
            <svg v-else width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="9"/></svg>
          </span>
          <span class="plan-text">{{ label(t) }}</span>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.task-plan {
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--bg-elev);
  overflow: hidden;
  max-width: 100%;
}
.plan-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  transition: background-color var(--motion-fast) var(--ease-standard);
}
.plan-head:hover { background: var(--fill-0); }
.plan-title { font-weight: 500; letter-spacing: 0.01em; }
.plan-chevron {
  color: var(--text-3);
  flex-shrink: 0;
  transition: transform var(--motion-fast) var(--ease-standard);
}
/* 展开(默认):向下箭头;收起:向上箭头 */
.plan-chevron.up { transform: rotate(180deg); }

/* 展开内容:固定高度,步骤多时内部滚动 */
.plan-body { padding: 0 14px 12px; max-height: 168px; overflow-y: auto; }
.plan-body::-webkit-scrollbar { width: 8px; }
.plan-body::-webkit-scrollbar-thumb { background: var(--border); border-radius: 4px; }
.plan-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 9px;
}
.plan-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--text-3);
}
.plan-item .plan-check {
  display: inline-flex;
  flex-shrink: 0;
  margin-top: 1px;
  color: var(--text-3);
}
.plan-text { min-width: 0; overflow-wrap: anywhere; }
.plan-item.completed .plan-text {
  text-decoration: line-through;
  text-decoration-color: var(--text-4);
}
.plan-item.in_progress { color: var(--text); }
.plan-item.in_progress .plan-check { color: var(--text-2); }

/* 收起时的单行 */
.plan-single {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 0 14px 12px;
  font-size: 13.5px;
  line-height: 1.5;
  color: var(--text);
}
.plan-single .plan-check { display: inline-flex; flex-shrink: 0; margin-top: 1px; color: var(--text-2); }
</style>
