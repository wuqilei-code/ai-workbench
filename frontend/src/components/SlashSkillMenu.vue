<script setup lang="ts">
/** 输入框「/」技能快捷菜单:选择内置技能插入到输入框。
 *  键盘导航状态由父组件持有(selectedIdx),本组件负责渲染与选中项滚动到可视区。 */
import { ref, watch } from 'vue'
import type { SkillInfo } from '@/types'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const props = defineProps<{ open: boolean; skills: SkillInfo[]; selectedIdx: number }>()
const emit = defineEmits<{
  (e: 'select', skill: SkillInfo): void
  (e: 'hover', idx: number): void
}>()

const itemEls = ref<HTMLElement[]>([])
function setItem(el: any, idx: number) {
  if (el) itemEls.value[idx] = el as HTMLElement
}

/** 选中项变化时滚动到可视区(上下键导航) */
watch(() => props.selectedIdx, (i) => {
  itemEls.value[i]?.scrollIntoView({ block: 'nearest' })
})
</script>

<template>
  <div v-if="open && skills.length" class="slash-menu">
    <div class="slash-menu-header">
      <span class="slash-menu-icon">/</span>
      <span>{{ t('slashMenu2.title') }}</span>
      <span class="slash-menu-count">{{ skills.length }}</span>
    </div>
    <div class="slash-menu-list">
      <div
        v-for="(skill, idx) in skills"
        :key="skill.name"
        :ref="(el) => setItem(el, idx)"
        class="slash-menu-item"
        :class="{ active: idx === selectedIdx }"
        @mouseenter="emit('hover', idx)"
        @mousedown.prevent
        @click.stop="emit('select', skill)"
      >
        <svg class="slash-menu-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
        <span class="slash-menu-name">{{ skill.name }}</span>
        <span class="slash-menu-desc">{{ skill.description }}</span>
      </div>
    </div>
    <div class="slash-menu-footer">
      <span>{{ t('slashMenu2.nav') }}</span>
      <span>{{ t('slashMenu2.enter') }}</span>
      <span>{{ t('slashMenu2.esc') }}</span>
    </div>
  </div>
</template>

<style scoped>
.slash-menu {
  position: absolute;
  bottom: 100%;
  left: 20px;
  right: 20px;
  max-height: 360px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius);
  box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.12);
  z-index: 100;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin-bottom: 6px;
}
.slash-menu-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  border-bottom: 1px solid var(--border-soft);
}
.slash-menu-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  background: var(--accent-soft);
  color: var(--accent);
  border-radius: 4px;
  font-size: 13px;
  font-weight: 700;
}
.slash-menu-count {
  margin-left: auto;
  font-weight: 400;
  color: var(--text-3);
}
.slash-menu-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}
.slash-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: background .1s;
}
.slash-menu-item:hover,
.slash-menu-item.active {
  background: var(--accent-soft);
}
.slash-menu-item .slash-menu-icon {
  flex-shrink: 0;
  opacity: .5;
}
.slash-menu-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1);
  flex-shrink: 0;
}
.slash-menu-desc {
  font-size: 12px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.slash-menu-footer {
  display: flex;
  gap: 12px;
  padding: 6px 12px;
  font-size: 11px;
  color: var(--text-3);
  border-top: 1px solid var(--border-soft);
  background: var(--surface-hover);
}
</style>
