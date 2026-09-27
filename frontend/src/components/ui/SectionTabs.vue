<script setup lang="ts">
import { nextTick, ref } from 'vue'

/**
 * 统一的下划线式分区 Tab 条(替代各组件里各自手写的 .lib-tab / .skill-tab)。
 *
 * 相比原先散落 5 处的实现,这里额外补了:
 * - 键盘可达:←/→ 切换、Home/End 跳首尾,roving tabindex + role="tablist"/"tab";
 * - 计数徽章样式统一(原先 .lib-tab-n / .tab-count / .cnt 三套);
 * - 切换即触发 @change,各调用方不再各自 `activeTab = x; resetPage()`。
 */
export interface SectionTabItem<V = string> {
  key: V
  label: string
  /** 右侧计数徽章;0/未传不渲染 */
  count?: number
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    items: SectionTabItem<any>[]
    /** v-model:active */
    active: any
    /** 无障碍名称 */
    ariaLabel?: string
  }>(),
  { ariaLabel: '' },
)
const emit = defineEmits<{ (e: 'update:active', v: any): void; (e: 'change', v: any): void }>()

const rootRef = ref<HTMLElement | null>(null)

function select(item: SectionTabItem, idx: number) {
  if (item.disabled || item.key === props.active) return
  emit('update:active', item.key)
  emit('change', item.key)
  // 焦点跟随选中项,保证连续键盘操作
  nextTick(() => {
    const el = rootRef.value?.querySelectorAll<HTMLElement>('[role="tab"]')[idx]
    el?.focus()
  })
}

/** roving tabindex:只有选中项可 Tab 进入,方向键在 tablist 内移动 */
function onKeydown(e: KeyboardEvent) {
  const list = props.items
  if (!list.length) return
  const cur = Math.max(
    0,
    list.findIndex((i) => i.key === props.active),
  )
  let next = -1
  if (e.key === 'ArrowRight' || e.key === 'ArrowDown') next = (cur + 1) % list.length
  else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') next = (cur - 1 + list.length) % list.length
  else if (e.key === 'Home') next = 0
  else if (e.key === 'End') next = list.length - 1
  if (next < 0) return
  e.preventDefault()
  const target = list[next]
  if (target.disabled) return
  emit('update:active', target.key)
  emit('change', target.key)
  nextTick(() => {
    const el = rootRef.value?.querySelectorAll<HTMLElement>('[role="tab"]')[next]
    el?.focus()
  })
}
</script>

<template>
  <div
    ref="rootRef"
    class="sec-tabs"
    role="tablist"
    :aria-label="ariaLabel || undefined"
    @keydown="onKeydown"
  >
    <button
      v-for="(it, idx) in items"
      :key="it.key"
      class="sec-tab"
      type="button"
      role="tab"
      :aria-selected="it.key === active"
      :tabindex="it.key === active ? 0 : -1"
      :disabled="it.disabled"
      :class="{ active: it.key === active }"
      @click="select(it, idx)"
    >
      <span class="sec-tab-label">{{ it.label }}</span>
      <span v-if="it.count != null && it.count > 0" class="sec-tab-count">{{ it.count }}</span>
    </button>
  </div>
</template>

<style scoped>
.sec-tabs { display: flex; gap: 4px; border-bottom: 1px solid var(--border-soft); }
.sec-tab {
  display: inline-flex; align-items: center; gap: 7px;
  background: none; border: none; border-bottom: 2px solid transparent;
  padding: 8px 4px; margin-right: 14px;
  font-size: 15px; font-weight: 500; color: var(--text-2);
  cursor: pointer; font-family: inherit;
  transition: color .15s, border-color .15s;
}
.sec-tab:hover:not(:disabled) { color: var(--text); }
.sec-tab.active { color: var(--accent-text); border-bottom-color: var(--accent); }
.sec-tab:disabled { opacity: .5; cursor: not-allowed; }
.sec-tab:focus-visible { outline: 2px solid var(--accent-text); outline-offset: -2px; border-radius: 4px 4px 0 0; }
.sec-tab-count {
  font-size: 11px; font-weight: 600;
  background: var(--fill-1); color: var(--text-2);
  padding: 0 7px; border-radius: 999px; line-height: 1.7;
}
.sec-tab.active .sec-tab-count { background: var(--accent); color: #fff; }
/* 紧凑档:用于面板内嵌(技能面板等) */
.sec-tabs.compact .sec-tab { font-size: 14px; padding: 8px 6px; }
</style>
