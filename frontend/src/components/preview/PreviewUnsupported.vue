<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { fmtSize } from './ext'

defineProps<{ path: string; size?: number; reason?: string }>()
defineEmits<{ download: [] }>()
</script>

<template>
  <div class="unsupported">
    <div class="icon">📄</div>
    <div class="filename">{{ path.split(/[\\/]/).pop() }}</div>
    <div v-if="reason" class="reason">{{ reason }}</div>
    <div v-else class="reason">t('preview.unsupported')</div>
    <div v-if="size" class="size">{{ fmtSize(size) }}</div>
    <n-button size="small" type="primary" @click="$emit('download')">下载文件</n-button>
  </div>
</template>

<style scoped>
.unsupported {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  height: 100%; gap: 8px; color: #888;
}
.icon { font-size: 48px; }
.filename { font-size: 14px; color: #ccc; word-break: break-all; text-align: center; }
.reason { font-size: 13px; }
.size { font-size: 12px; color: #666; }
</style>
