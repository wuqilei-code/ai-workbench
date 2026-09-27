<script setup lang="ts">
/**
 * 全局错误边界:捕获子树渲染/生命周期异常,展示兜底 UI 而非整页白屏。
 * 用于包裹 <router-view/>;单个组件崩溃不影响导航外壳。
 */
import { ref, onErrorCaptured } from 'vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
withDefaults(defineProps<{ label?: string }>(), { label: '' })

const errored = ref(false)
const detail = ref('')

onErrorCaptured((err) => {
  errored.value = true
  detail.value = err instanceof Error ? err.message : String(err)
  console.error('[ErrorBoundary] captured:', err)
  // 返回 false:阻止错误继续向上冒泡(避免根组件卸载导致白屏)
  return false
})

function retry() {
  errored.value = false
  detail.value = ''
}

function reload() {
  window.location.reload()
}
</script>

<template>
  <div v-if="errored" class="eb-wrap">
    <div class="eb-card">
      <div class="eb-icon">⚠️</div>
      <div class="eb-title">{{ label || t('errBoundary2.title') }}</div>
      <div v-if="detail" class="eb-detail">{{ detail }}</div>
      <div class="eb-actions">
        <button class="eb-btn" @click="retry">{{ t('errBoundary2.retry') }}</button>
        <button class="eb-btn primary" @click="reload">{{ t('errBoundary2.reload') }}</button>
      </div>
    </div>
  </div>
  <slot v-else />
</template>

<style scoped>
.eb-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  min-height: 240px;
  padding: 24px;
  box-sizing: border-box;
}
.eb-card {
  max-width: 460px;
  width: 100%;
  text-align: center;
  padding: 28px 24px;
  border-radius: 12px;
  background: var(--surface);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow);
}
.eb-icon { font-size: 30px; margin-bottom: 8px; }
.eb-title { font-size: 15px; font-weight: 600; color: var(--text); }
.eb-detail {
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--text-3);
  font-family: var(--font-mono, ui-monospace, monospace);
  word-break: break-word;
  max-height: 120px;
  overflow: auto;
}
.eb-actions { margin-top: 18px; display: flex; gap: 10px; justify-content: center; }
.eb-btn {
  padding: 7px 16px;
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-2);
  font-family: inherit;
  transition: background .15s, color .15s, border-color .15s;
}
.eb-btn:hover { background: var(--surface-hover); color: var(--text); }
.eb-btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}
.eb-btn.primary:hover { background: var(--accent); color: var(--accent-contrast); opacity: .9; }
</style>
