<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'

/**
 * 通用单输入弹窗:替代浏览器 prompt 的 UI 版本。
 * show 为受控;confirm 携带输入值;取消/关闭(遮罩/×)走 cancel。
 */
const props = withDefaults(defineProps<{
  show?: boolean
  title?: string
  placeholder?: string
  initial?: string
  /** 允许空值提交(如"上传到子目录,留空=根目录") */
  allowEmpty?: boolean
  tip?: string
}>(), {
  show: false,
  title: '请输入',
  placeholder: '请输入',
  initial: '',
  allowEmpty: false,
  tip: '',
})

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'confirm', value: string): void
  (e: 'cancel'): void
}>()

const value = ref(props.initial)
const inputRef = ref<{ focus: () => void } | null>(null)

watch(() => props.show, (s) => {
  if (s) {
    value.value = props.initial || ''
    nextTick(() => inputRef.value?.focus())
  }
})

function submit() {
  if (!props.allowEmpty && !value.value.trim()) return
  emit('confirm', value.value)
}
function cancel() {
  emit('cancel')
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    style="width: 420px; max-width: 92vw"
    :mask-closable="true"
    :close-on-esc="true"
    :bordered="false"
    @update:show="(v: boolean) => { if (!v) emit('cancel') }"
  >
    <div class="tpm-body">
      <n-input
        ref="inputRef"
        v-model:value="value"
        :placeholder="placeholder"
        size="medium"
        @keydown.enter.prevent="submit"
        @keydown.esc.prevent="cancel"
      />
      <p v-if="tip" class="tpm-tip">{{ tip }}</p>
    </div>
    <template #footer>
      <div class="tpm-footer">
        <n-button size="small" quaternary @click="cancel">取消</n-button>
        <n-button size="small" type="primary" :disabled="!allowEmpty && !value.trim()" @click="submit">确定</n-button>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.tpm-body { padding: 4px 0; }
.tpm-tip { margin: 8px 0 0; font-size: 12px; color: var(--text-3); line-height: 1.5; }
.tpm-footer { display: flex; justify-content: flex-end; gap: 8px; }
</style>