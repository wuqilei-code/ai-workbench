<script setup lang="ts">
import { computed } from 'vue'
import { useChatStore } from '@/stores/chat'

const chat = useChatStore()

const options = computed(() => {
  const opts: { label: string; value: string; providerId: string; modelId: string }[] = []
  for (const p of chat.availableModels) {
    for (const m of p.models) {
      opts.push({
        label: `${p.name} / ${m.name}`,
        value: `${p.providerId}::${m.modelId}`,
        providerId: p.providerId,
        modelId: m.modelId,
      })
    }
  }
  return opts
})

const value = computed({
  get: () => {
    if (chat.selectedProviderId && chat.selectedModelId)
      return `${chat.selectedProviderId}::${chat.selectedModelId}`
    return null
  },
  set: (v: string | null) => {
    if (!v) return
    const [pid, mid] = v.split('::')
    chat.selectModel(pid, mid)
  },
})
</script>

<template>
  <n-select
    v-model:value="value"
    :options="options"
    placeholder="选择模型"
    size="small"
    style="width: 240px"
    :disabled="!options.length"
  />
  <n-text v-if="!options.length" depth="3" style="margin-left: 8px; font-size: 12px">
    暂无可用模型，请前往「模型管理」配置
  </n-text>
</template>
