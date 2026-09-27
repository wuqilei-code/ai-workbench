<script setup lang="ts">
/**
 * 列表/网格空态占位。统一原先各处手写的 .mp-empty / .tpl-empty / .fav-empty 等
 * 「灰字 + 上下留白」写法,并把「图标 + 主文案 + 副文案 + 操作按钮」收敛成一个组件。
 */
withDefaults(
  defineProps<{
    /** 主文案(必填) */
    text: string
    /** 副文案/引导 */
    sub?: string
    /** 顶部图标/emoji */
    icon?: string
    /** 上下留白(px) */
    padding?: number
  }>(),
  { sub: '', icon: '', padding: 40 },
)
</script>

<template>
  <div class="empty-state" :style="{ paddingTop: padding + 'px', paddingBottom: padding + 'px' }">
    <div v-if="icon" class="es-icon">{{ icon }}</div>
    <div class="es-text">{{ text }}</div>
    <div v-if="sub" class="es-sub">{{ sub }}</div>
    <div v-if="$slots.default" class="es-actions"><slot /></div>
  </div>
</template>

<style scoped>
.empty-state { text-align: center; color: var(--text-3); font-size: 13px; }
.es-icon { font-size: 30px; line-height: 1; margin-bottom: 10px; opacity: .75; }
.es-text { color: var(--text-2); }
.es-sub { margin-top: 6px; font-size: 12.5px; color: var(--text-3); line-height: 1.6; }
.es-actions { margin-top: 14px; display: flex; justify-content: center; gap: 8px; }
</style>
