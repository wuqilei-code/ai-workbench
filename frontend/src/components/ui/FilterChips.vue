<script setup lang="ts">
/**
 * 分类筛选 chips(胶囊式)。替代 SkillMarketplace 的 .mp-tab 与 TemplateLibrary 的 .tpl-cat
 * 两套手写实现:前者有 emoji + 计数、后者只有文字且 active 底色更实,这里用 variant 收敛。
 */
export interface FilterChipItem<V = string> {
  key: V
  label: string
  emoji?: string
  count?: number
}

const props = withDefaults(
  defineProps<{
    items: FilterChipItem<any>[]
    active: any
    /** soft=淡色底(默认);solid=实色底(选中更醒目) */
    variant?: 'soft' | 'solid'
    ariaLabel?: string
  }>(),
  { variant: 'soft', ariaLabel: '' },
)
const emit = defineEmits<{ (e: 'update:active', v: any): void; (e: 'change', v: any): void }>()

function select(key: any) {
  if (key === props.active) return
  emit('update:active', key)
  emit('change', key)
}
</script>

<template>
  <div class="filter-chips" :class="variant" role="group" :aria-label="ariaLabel || undefined">
    <button
      v-for="c in items"
      :key="c.key"
      class="filter-chip"
      type="button"
      :class="{ active: c.key === active }"
      :aria-pressed="c.key === active"
      @click="select(c.key)"
    >
      <span v-if="c.emoji" class="fc-emoji">{{ c.emoji }}</span>
      <span class="fc-label">{{ c.label }}</span>
      <span v-if="c.count != null" class="fc-count">{{ c.count }}</span>
    </button>
  </div>
</template>

<style scoped>
.filter-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.filter-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 12px; border-radius: 16px;
  border: 1px solid var(--border-soft);
  background: var(--surface); color: var(--text-2);
  font-size: 12px; font-family: inherit; cursor: pointer;
  transition: background .15s, border-color .15s, color .15s;
}
.filter-chip:hover { border-color: var(--accent-text); color: var(--text); }
.filter-chip.active { background: var(--accent-soft); border-color: var(--accent-text); color: var(--accent-text); font-weight: 600; }
.filter-chips.solid .filter-chip.active { background: var(--accent); border-color: var(--accent); color: #fff; }
.filter-chip:focus-visible { outline: 2px solid var(--accent-text); outline-offset: 1px; }
.fc-emoji { font-size: 14px; }
.fc-count { font-size: 11px; opacity: .7; }
</style>
