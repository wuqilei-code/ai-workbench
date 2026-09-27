<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage } from 'naive-ui'

const { t } = useI18n()
const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'generate', prompt: string): void
}>()

const msg = useMessage()

const errorText = ref('')
const lang = ref('auto')
const focus = ref(t('diagnose.focusRoot'))
const context = ref('')

const LANG_OPTIONS = [
  { label: t('diagnose.auto'), value: 'auto' },
  { label: 'JavaScript/TypeScript', value: 'JavaScript/TypeScript' },
  { label: 'Python', value: 'Python' },
  { label: 'Java', value: 'Java' },
  { label: 'Go', value: 'Go' },
  { label: t('diagnose.focusShell'), value: t('diagnose.focusShell') },
  { label: t('diagnose.focusSql'), value: t('diagnose.focusSql') },
  { label: t('diagnose.focusOther'), value: t('diagnose.focusOther') },
]
const FOCUS_OPTIONS = [
  { label: t('diagnose.focusRoot'), value: t('diagnose.focusRoot') },
  { label: t('diagnose.focusFix'), value: t('diagnose.focusFix') },
  { label: t('diagnose.focusLogic'), value: t('diagnose.focusLogic') },
  { label: t('diagnose.focusPerf'), value: t('diagnose.focusPerf') },
]

function onOpen() {
  errorText.value = ''
  context.value = ''
}

function buildPrompt(): string | null {
  const err = errorText.value.trim()
  if (!err) {
    msg.warning(t('diagnose.noContext'))
    return null
  }
  const lines: string[] = []
  const langText = lang.value === 'auto' ? '' : t('diagnose.langLine', { lang: lang.value })
  lines.push(t('diagnose.lineFocus', { focus: focus.value }))
  if (context.value.trim()) lines.push(t('diagnose.lineContext', { ctx: context.value.trim() }))
  lines.push(t('diagnose.lineInfo'))
  lines.push('```')
  lines.push(err)
  lines.push('```')
  lines.push(t('diagnose.lineRule'))
  return lines.join('\n')
}

function onSubmit() {
  const prompt = buildPrompt()
  if (!prompt) return
  emit('generate', prompt)
  emit('update:show', false)
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="t('diagnose.title')"
    style="width: 640px; max-width: 94vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onOpen"
  >
    <div class="er-field">
      <label class="er-label">{{ t('diagnose.infoLabel') }} <span class="er-req">*</span></label>
      <n-input
        v-model:value="errorText"
        type="textarea"
        :rows="6"
        size="small"
        placeholder="t('diagnose.infoPlaceholder')"
      />
    </div>
    <div class="er-row">
      <div class="er-field">
      <label class="er-label">{{ t('diagnose.langLabel') }}</label>
        <n-select v-model:value="lang" size="small" :options="LANG_OPTIONS" />
      </div>
      <div class="er-field">
      <label class="er-label">{{ t('diagnose.whatLabel') }}</label>
        <n-select v-model:value="focus" size="small" :options="FOCUS_OPTIONS" />
      </div>
    </div>
    <div class="er-field">
      <label class="er-label">{{ t('diagnose.actualLabel') }}</label>
      <n-input
        v-model:value="context"
        type="textarea"
        :rows="2"
        size="small"
        placeholder="t('diagnose.actualPlaceholder')"
      />
    </div>

    <template #footer>
      <div class="er-footer">
      <span class="er-hint">{{ t('diagnose.hint') }}</span>
        <div style="display: flex; gap: 8px">
          <n-button @click="emit('update:show', false)">取消</n-button>
      <n-button type="primary" @click="onSubmit">{{ t('diagnose.submit') }}</n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.er-field { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.er-label { font-size: 13px; color: var(--text-2); }
.er-req { color: var(--danger-text, #d03050); margin-left: 2px; }
.er-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.er-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.er-hint { font-size: 12px; color: var(--text-3); }
</style>
