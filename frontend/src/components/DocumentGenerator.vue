<script setup lang="ts">
import { ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMessage } from 'naive-ui'

const { t: tr } = useI18n()
const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'generate', prompt: string): void
}>()

const msg = useMessage()

/** 文档类型定义:周报 / 会议纪要 / 邮件 / 分析报告 */
interface DocField {
  key: string
  label: string
  type: 'input' | 'textarea' | 'select'
  placeholder?: string
  options?: { label: string; value: string }[]
}
interface DocType {
  key: string
  label: string
  desc: string
  fields: DocField[]
  required: string[]
}

const DOC_TYPES: DocType[] = [
  {
    key: 'weekly',
    label: tr('docgen.workType'),
    desc: tr('docgen.workDesc'),
    fields: [
    { key: 'work', label: tr('docgen.workSummary'), type: 'textarea', placeholder: tr('docgen.phWork') },
    { key: 'result', label: tr('docgen.workResult'), type: 'textarea', placeholder: tr('docgen.phResult') },
    { key: 'issue', label: tr('docgen.workIssue'), type: 'textarea', placeholder: tr('docgen.phIssue') },
    { key: 'plan', label: tr('docgen.workPlan'), type: 'textarea', placeholder: tr('docgen.phPlan') },
    { key: 'tone', label: tr('docgen.toneLabel'), type: 'select', options: [{ label: tr('docgen.toneBrief'), value: tr('docgen.toneBrief') }, { label: tr('docgen.toneDetail'), value: tr('docgen.toneDetail') }, { label: tr('docgen.toneFormal'), value: tr('docgen.toneFormal') }] },
    ],
    required: ['work'],
  },
  {
    key: 'minutes',
    label: tr('docgen.meetingTitle'),
    desc: tr('docgen.meetingDesc'),
    fields: [
    { key: 'topic', label: tr('docgen.mTopic'), type: 'input', placeholder: tr('docgen.phMTopic') },
    { key: 'time', label: tr('docgen.mTime'), type: 'input', placeholder: tr('docgen.phMTime') },
    { key: 'points', label: tr('docgen.mPoints'), type: 'textarea', placeholder: tr('docgen.phMPoints') },
    { key: 'action', label: tr('docgen.mAction'), type: 'textarea', placeholder: tr('docgen.phMAction') },
    ],
    required: ['topic'],
  },
  {
    key: 'email',
    label: tr('docgen.emailTitle'),
    desc: tr('docgen.emailDesc'),
    fields: [
    { key: 'to', label: tr('docgen.eTo'), type: 'input', placeholder: tr('docgen.phETo') },
    { key: 'subject', label: tr('docgen.eSubject'), type: 'input', placeholder: tr('docgen.phESubject') },
    { key: 'purpose', label: tr('docgen.ePurpose'), type: 'select', options: [{ label: tr('docgen.purposeNotify'), value: tr('docgen.purposeNotify') }, { label: tr('docgen.purposeWeekly'), value: tr('docgen.purposeWeekly') }, { label: tr('docgen.purposeInquire'), value: tr('docgen.purposeInquire') }, { label: tr('docgen.purposeApology'), value: tr('docgen.purposeApology') }, { label: tr('docgen.purposeThanks'), value: tr('docgen.purposeThanks') }] },
    { key: 'points', label: tr('docgen.ePoints'), type: 'textarea', placeholder: tr('docgen.phEPoints') },
    { key: 'tone', label: tr('docgen.eTone'), type: 'select', options: [{ label: tr('docgen.toneFormal'), value: tr('docgen.toneFormal') }, { label: tr('docgen.toneFriendly'), value: tr('docgen.toneFriendly') }, { label: tr('docgen.toneEfficient'), value: tr('docgen.toneEfficient') }] },
    ],
    required: ['points'],
  },
  {
    key: 'report',
    label: tr('docgen.reportTitle'),
    desc: tr('docgen.reportDesc'),
    fields: [
    { key: 'topic', label: tr('docgen.rTopic'), type: 'input', placeholder: tr('docgen.phRTopic') },
    { key: 'data', label: tr('docgen.rData'), type: 'textarea', placeholder: tr('docgen.phRData') },
    { key: 'dims', label: tr('docgen.rDims'), type: 'textarea', placeholder: tr('docgen.phRDims') },
    { key: 'bias', label: tr('docgen.rBias'), type: 'input', placeholder: tr('docgen.phRBias') },
    ],
    required: ['topic'],
  },
]

const activeType = ref<DocType>(DOC_TYPES[0])
const form = ref<Record<string, string>>({})

function switchType(t: DocType) {
  activeType.value = t
  form.value = {}
  for (const f of t.fields) {
    const d = f.options?.[0]?.value
    if (d) form.value[f.key] = d
  }
}
function onOpen() {
  switchType(DOC_TYPES[0])
}
switchType(DOC_TYPES[0])

const filledCount = computed(() => Object.values(form.value).filter((v) => v && v.trim()).length)

/** 按类型把表单字段拼成结构化的生成提示词 */
function buildPrompt(): string | null {
  const t = activeType.value
  const emptyRequired = t.required.filter((k) => !(form.value[k] || '').trim())
  if (emptyRequired.length) {
    const label = t.fields.find((f) => f.key === emptyRequired[0])?.label || emptyRequired[0]
    msg.warning(tr('docgen.needField', { label }))
    return null
  }
  const get = (k: string) => (form.value[k] || '').trim()

  const lines: string[] = []
    lines.push(tr('docgen.filledField', { label: t.label }))

  const body: string[] = []
  const tone = get('tone')
    if (tone) body.push(tr('docgen.toneLine', { tone }))

  switch (t.key) {
    case 'weekly':
      if (get('work')) body.push(`本周工作内容:${get('work')}`)
      if (get('result')) body.push(`本周成果/进展:${get('result')}`)
      if (get('issue')) body.push(`遇到的问题:${get('issue')}`)
      if (get('plan')) body.push(`下周计划:${get('plan')}`)
    body.push(tr('docgen.ruleWork'))
      break
    case 'minutes':
      if (get('topic')) body.push(`会议主题:${get('topic')}`)
      if (get('time')) body.push(`会议时间与参会人:${get('time')}`)
      if (get('points')) body.push(`讨论要点:${get('points')}`)
      if (get('action')) body.push(`决议与待办:${get('action')}`)
    body.push(tr('docgen.ruleMeeting'))
      break
    case 'email':
      if (get('to')) body.push(`收件人:${get('to')}`)
      if (get('subject')) body.push(`邮件主题:${get('subject')}`)
      if (get('purpose')) body.push(`邮件目的:${get('purpose')}`)
      if (get('points')) body.push(`正文要点:${get('points')}`)
    if (tone) body.push(tr('docgen.toneLine', { tone }))
    body.push(tr('docgen.ruleEmail'))
      break
    case 'report':
      if (get('topic')) body.push(`分析主题:${get('topic')}`)
      if (get('data')) body.push(`数据与依据:${get('data')}`)
      if (get('dims')) body.push(`分析维度:${get('dims')}`)
      if (get('bias')) body.push(`篇幅与结论倾向:${get('bias')}`)
    body.push(tr('docgen.ruleReport'))
      break
  }

  if (body.length) lines.push(body.join('\n'))
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
    title="tr('docgen.modalTitle')"
    style="width: 640px; max-width: 94vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
    @after-enter="onOpen"
  >
    <div class="dg-types">
      <div
        v-for="t in DOC_TYPES"
        :key="t.key"
        class="dg-type"
        :class="{ active: activeType.key === t.key }"
        @click="switchType(t)"
      >
        <div class="dg-type-label">{{ t.label }}</div>
        <div class="dg-type-desc">{{ t.desc }}</div>
      </div>
    </div>

    <div class="dg-fields">
      <div v-for="f in activeType.fields" :key="f.key" class="dg-field">
        <label class="dg-label">
          {{ f.label }}<span v-if="activeType.required.includes(f.key)" class="dg-req">*</span>
        </label>
        <n-input
          v-if="f.type === 'input'"
          v-model:value="form[f.key]"
          size="small"
          :placeholder="f.placeholder"
        />
        <n-input
          v-else-if="f.type === 'textarea'"
          v-model:value="form[f.key]"
          type="textarea"
          size="small"
          :rows="3"
          :placeholder="f.placeholder"
        />
        <n-select
          v-else
          v-model:value="form[f.key]"
          size="small"
          :options="f.options"
        />
      </div>
    </div>

    <template #footer>
      <div class="dg-footer">
      <span class="dg-hint">{{ tr('docgen.fillHint', { filled: filledCount, total: activeType.fields.length }) }}</span>
        <div style="display: flex; gap: 8px">
          <n-button @click="emit('update:show', false)">取消</n-button>
      <n-button type="primary" @click="onSubmit">{{ tr('docgen.generate') }}</n-button>
        </div>
      </div>
    </template>
  </n-modal>
</template>

<style scoped>
.dg-types { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 16px; }
.dg-type {
  border: 1px solid var(--border-soft); border-radius: var(--radius-sm);
  padding: 10px 12px; cursor: pointer; transition: border-color .15s, background .15s;
}
.dg-type:hover { border-color: var(--border-strong); }
.dg-type.active { border-color: var(--accent-text); background: var(--accent-soft); }
.dg-type-label { font-size: 14px; font-weight: 600; color: var(--text); margin-bottom: 2px; }
.dg-type-desc { font-size: 11px; color: var(--text-3); line-height: 1.4; }
.dg-fields { display: flex; flex-direction: column; gap: 12px; max-height: 46vh; overflow-y: auto; }
.dg-field { display: flex; flex-direction: column; gap: 4px; }
.dg-label { font-size: 13px; color: var(--text-2); }
.dg-req { color: var(--danger-text, #d03050); margin-left: 2px; }
.dg-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.dg-hint { font-size: 12px; color: var(--text-3); }
</style>
