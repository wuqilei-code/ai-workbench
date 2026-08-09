<script setup lang="ts">
import { ref, computed } from 'vue'
import { useMessage } from 'naive-ui'

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
    label: '周报',
    desc: '本周工作内容 → 成果 → 问题 → 下周计划',
    fields: [
      { key: 'work', label: '本周工作内容', type: 'textarea', placeholder: '例如:完成首页改版、上线活动页、修复 3 个线上问题…' },
      { key: 'result', label: '本周成果/进展', type: 'textarea', placeholder: '例如:新版首页上线转化率提升 12%…' },
      { key: 'issue', label: '遇到的问题', type: 'textarea', placeholder: '没有可留空' },
      { key: 'plan', label: '下周计划', type: 'textarea', placeholder: '例如:推进数据看板、准备季度评审…' },
      { key: 'tone', label: '语气风格', type: 'select', options: [{ label: '简洁', value: '简洁' }, { label: '详细', value: '详细' }, { label: '正式', value: '正式' }] },
    ],
    required: ['work'],
  },
  {
    key: 'minutes',
    label: '会议纪要',
    desc: '主题 → 时间参会 → 讨论要点 → 决议待办',
    fields: [
      { key: 'topic', label: '会议主题', type: 'input', placeholder: '例如:Q3 产品路线图评审' },
      { key: 'time', label: '会议时间/参会人', type: 'input', placeholder: '例如:8月9日 10:00 · 张三/李四/王五' },
      { key: 'points', label: '讨论要点', type: 'textarea', placeholder: '逐条列出会上的关键讨论…' },
      { key: 'action', label: '决议/待办事项', type: 'textarea', placeholder: '例如:8月12日前输出原型,由张三跟进…' },
    ],
    required: ['topic'],
  },
  {
    key: 'email',
    label: '邮件',
    desc: '收件人 → 主题 → 目的 → 要点',
    fields: [
      { key: 'to', label: '收件人/对象', type: 'input', placeholder: '例如:市场部李经理' },
      { key: 'subject', label: '邮件主题', type: 'input', placeholder: '留空则由 AI 拟定' },
      { key: 'purpose', label: '邮件目的', type: 'select', options: [{ label: '通知', value: '通知' }, { label: '请求协助', value: '请求协助' }, { label: '询问信息', value: '询问信息' }, { label: '跟进催办', value: '跟进催办' }, { label: '感谢致谢', value: '感谢致谢' }] },
      { key: 'points', label: '正文要点', type: 'textarea', placeholder: '希望邮件包含的关键信息…' },
      { key: 'tone', label: '语气', type: 'select', options: [{ label: '正式', value: '正式' }, { label: '亲切自然', value: '亲切自然' }, { label: '简洁高效', value: '简洁高效' }] },
    ],
    required: ['points'],
  },
  {
    key: 'report',
    label: '分析报告',
    desc: '问题 → 数据依据 → 分析维度 → 结论建议',
    fields: [
      { key: 'topic', label: '主题/要分析的问题', type: 'input', placeholder: '例如:最近一个月客户流失率上升的原因' },
      { key: 'data', label: '数据/依据', type: 'textarea', placeholder: '粘贴相关数据、图表说明或背景信息…' },
      { key: 'dims', label: '想从哪些维度分析', type: 'textarea', placeholder: '例如:按渠道、按时间、按用户画像… 可留空由 AI 决定' },
      { key: 'bias', label: '结论倾向/篇幅', type: 'input', placeholder: '例如:约 800 字,给出可执行的改进建议' },
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
    msg.warning(`请先填写「${label}」`)
    return null
  }
  const get = (k: string) => (form.value[k] || '').trim()

  const lines: string[] = []
  lines.push(`请帮我写一份【${t.label}】。`)

  const body: string[] = []
  const tone = get('tone')
  if (tone) body.push(`语气风格:${tone}`)

  switch (t.key) {
    case 'weekly':
      if (get('work')) body.push(`本周工作内容:${get('work')}`)
      if (get('result')) body.push(`本周成果/进展:${get('result')}`)
      if (get('issue')) body.push(`遇到的问题:${get('issue')}`)
      if (get('plan')) body.push(`下周计划:${get('plan')}`)
      body.push('要求:按「本周工作/成果与进展/问题与思考/下周计划」分节,使用 Markdown 标题,内容具体、可量化。')
      break
    case 'minutes':
      if (get('topic')) body.push(`会议主题:${get('topic')}`)
      if (get('time')) body.push(`会议时间与参会人:${get('time')}`)
      if (get('points')) body.push(`讨论要点:${get('points')}`)
      if (get('action')) body.push(`决议与待办:${get('action')}`)
      body.push('要求:整理成规范的会议纪要,包含会议信息、讨论要点、决议事项与负责人/截止时间,使用 Markdown 标题与列表。')
      break
    case 'email':
      if (get('to')) body.push(`收件人:${get('to')}`)
      if (get('subject')) body.push(`邮件主题:${get('subject')}`)
      if (get('purpose')) body.push(`邮件目的:${get('purpose')}`)
      if (get('points')) body.push(`正文要点:${get('points')}`)
      if (tone) body.push(`语气:${tone}`)
      body.push('要求:开头有称呼问候,正文条理清晰、要点突出,结尾有署名占位(如「此致敬礼,XX」),直接给出邮件正文。')
      break
    case 'report':
      if (get('topic')) body.push(`分析主题:${get('topic')}`)
      if (get('data')) body.push(`数据与依据:${get('data')}`)
      if (get('dims')) body.push(`分析维度:${get('dims')}`)
      if (get('bias')) body.push(`篇幅与结论倾向:${get('bias')}`)
      body.push('要求:按「背景、数据/现状、分析、结论与建议」组织,结论要有依据,建议要可落地,使用 Markdown 标题。')
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
    title="一键生成文档"
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
        <span class="dg-hint">已填写 {{ filledCount }}/{{ activeType.fields.length }} 项,生成后填入输入框供你确认</span>
        <div style="display: flex; gap: 8px">
          <n-button @click="emit('update:show', false)">取消</n-button>
          <n-button type="primary" @click="onSubmit">生成提示词</n-button>
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
