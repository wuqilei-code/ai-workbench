<script setup lang="ts">
import { ref } from 'vue'
import { useMessage } from 'naive-ui'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'generate', prompt: string): void
}>()

const msg = useMessage()

const errorText = ref('')
const lang = ref('auto')
const focus = ref('分析原因')
const context = ref('')

const LANG_OPTIONS = [
  { label: '自动识别语言', value: 'auto' },
  { label: 'JavaScript/TypeScript', value: 'JavaScript/TypeScript' },
  { label: 'Python', value: 'Python' },
  { label: 'Java', value: 'Java' },
  { label: 'Go', value: 'Go' },
  { label: 'Shell/命令行', value: 'Shell/命令行' },
  { label: 'SQL/数据库', value: 'SQL/数据库' },
  { label: '其他', value: '其他' },
]
const FOCUS_OPTIONS = [
  { label: '分析报错原因', value: '分析报错原因' },
  { label: '给出修复方案', value: '给出修复方案' },
  { label: '解释报错含义(面向新手)', value: '解释报错含义(面向新手)' },
  { label: '优化/改写这段代码', value: '优化/改写这段代码' },
]

function onOpen() {
  errorText.value = ''
  context.value = ''
}

function buildPrompt(): string | null {
  const err = errorText.value.trim()
  if (!err) {
    msg.warning('请先粘贴报错信息或日志')
    return null
  }
  const lines: string[] = []
  const langText = lang.value === 'auto' ? '' : `(涉及语言/环境:${lang.value})`
  lines.push(`请帮我${focus.value}。${langText}`)
  if (context.value.trim()) lines.push(`补充上下文:${context.value.trim()}`)
  lines.push('报错信息/日志如下:')
  lines.push('```')
  lines.push(err)
  lines.push('```')
  lines.push('要求:直接给出结论,用要点说明原因,给出可操作的修复建议;若信息不足,列出还需要的排查信息。')
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
    title="解读报错"
    style="width: 640px; max-width: 94vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onOpen"
  >
    <div class="er-field">
      <label class="er-label">报错信息 / 日志 <span class="er-req">*</span></label>
      <n-input
        v-model:value="errorText"
        type="textarea"
        :rows="6"
        size="small"
        placeholder="把报错内容或终端日志粘贴到这里…"
      />
    </div>
    <div class="er-row">
      <div class="er-field">
        <label class="er-label">语言 / 环境</label>
        <n-select v-model:value="lang" size="small" :options="LANG_OPTIONS" />
      </div>
      <div class="er-field">
        <label class="er-label">想让它做什么</label>
        <n-select v-model:value="focus" size="small" :options="FOCUS_OPTIONS" />
      </div>
    </div>
    <div class="er-field">
      <label class="er-label">补充上下文(可选)</label>
      <n-input
        v-model:value="context"
        type="textarea"
        :rows="2"
        size="small"
        placeholder="例如:在什么操作/命令后出现、期望的行为、相关代码片段…"
      />
    </div>

    <template #footer>
      <div class="er-footer">
        <span class="er-hint">生成后填入输入框,确认后发送</span>
        <div style="display: flex; gap: 8px">
          <n-button @click="emit('update:show', false)">取消</n-button>
          <n-button type="primary" @click="onSubmit">生成提问</n-button>
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
