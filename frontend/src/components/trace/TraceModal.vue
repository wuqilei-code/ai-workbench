<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import TurnTraceList from './TurnTraceList.vue'

/** 操作日志弹窗:点回答左下角的「操作日志」按钮打开,不占用对话正文空间。 */
const props = defineProps<{
  show: boolean
  agentMessageId: string
  count: number
}>()
const emit = defineEmits<{ 'update:show': [v: boolean] }>()
const { t } = useI18n()
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="t('trace.logTitleWithCount', { n: count })"
    style="width: 1000px; max-width: 94vw"
    :bordered="false"
    @update:show="emit('update:show', $event)"
  >
    <div class="trace-modal-body">
      <TurnTraceList
        v-if="show && agentMessageId"
        :show="true"
        :header="false"
        :agent-message-id="agentMessageId"
        :count="count"
      />
    </div>
  </n-modal>
</template>

<style scoped>
.trace-modal-body {
  /* 固定高度:切换/展开调用记录时弹窗不再忽高忽低,内容在内部滚动 */
  height: 560px;
  max-height: 70vh;
  min-height: 380px;
  overflow-y: auto;
  padding-right: 2px;
}
/* 弹窗内不需要列表自带的卡片边框 */
.trace-modal-body :deep(.trace-sec) {
  margin-top: 0;
  border: 0;
  background: transparent;
}
</style>
