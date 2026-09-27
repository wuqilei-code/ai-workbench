<script setup lang="ts">
/**
 * 通用图标(emoji)选择器。替代 SkillsPanel / SkillMarketplace / TemplateLibrary
 * 三处各自手写的 .icon-choice 网格(尺寸 28~30px 不一、hover 配色各异),
 * 并把被复制两份的 ICON_PRESETS 收敛到 utils/iconPresets.ts。
 */
import { ICON_PRESETS } from '@/utils/iconPresets'

const props = withDefaults(
  defineProps<{
    modelValue: string
    /** 单格尺寸(px) */
    size?: number
    /** 标题,默认走 i18n */
    title?: string
  }>(),
  { size: 30, title: '' },
)
const emit = defineEmits<{ (e: 'update:modelValue', v: string): void }>()

function pick(ic: string) {
  emit('update:modelValue', ic)
}
</script>

<template>
  <div class="icon-picker" role="group" :aria-label="props.title || undefined">
    <button
      v-for="ic in ICON_PRESETS"
      :key="ic"
      class="ip-cell"
      type="button"
      :style="{ width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.56) + 'px' }"
      :class="{ on: ic === modelValue }"
      :aria-label="ic"
      :aria-pressed="ic === modelValue"
      @click="pick(ic)"
    >{{ ic }}</button>
  </div>
</template>

<style scoped>
.icon-picker { display: flex; flex-wrap: wrap; gap: 6px; }
.ip-cell {
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid var(--border-soft); border-radius: 8px;
  background: var(--surface); cursor: pointer; padding: 0;
  line-height: 1; font-family: inherit;
  transition: border-color .12s, background .12s;
}
.ip-cell:hover { border-color: var(--accent-text); background: var(--surface-hover); }
.ip-cell.on { border-color: var(--accent); background: var(--accent-soft); }
.ip-cell:focus-visible { outline: 2px solid var(--accent-text); outline-offset: 1px; }
</style>
