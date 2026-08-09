<script setup lang="ts">
/** 空会话欢迎板:面向非专业用户,场景化快捷提问,点击把模板填入输入框供编辑发送。 */
interface Scenario {
  icon: string
  title: string
  desc: string
  prompt: string
}

const emit = defineEmits<{ (e: 'select', prompt: string): void }>()

const scenarios: Scenario[] = [
  {
    icon: '📊',
    title: '数据分析',
    desc: '上传 Excel/CSV,让 AI 总结趋势',
    prompt:
      '我会给你一份数据文件,请帮我分析并输出:①关键指标 ②主要趋势与结论 ③值得关注的风险点。分析要条理清晰,便于汇报。',
  },
  {
    icon: '📝',
    title: '写周报',
    desc: '把工作内容整理成专业周报',
    prompt:
      '请帮我把我的工作内容整理成一份周报,包含:本周完成、下周计划、风险与求助三部分。下面是这周我做的事情,请先看内容再组织:',
  },
  {
    icon: '📋',
    title: '会议纪要',
    desc: '把讨论整理成结构化纪要',
    prompt:
      '请把下面这段讨论整理成会议纪要,包含:会议主题、参会人、讨论要点、结论、待办事项(注明负责人与截止时间)。',
  },
  {
    icon: '🐛',
    title: '调试代码',
    desc: '排查代码报错或逻辑问题',
    prompt:
      '我有一段代码出了问题,请帮我:先定位报错根因,再给出修复方案,最后给出修改后的完整代码。下面是我的代码和报错:',
  },
  {
    icon: '🛠️',
    title: '解读报错',
    desc: '粘贴报错信息,给出修复步骤',
    prompt:
      '下面是我的报错信息,请解读:①发生了什么 ②可能的原因 ③具体的修复步骤。回答请通俗易懂,分步说明。',
  },
  {
    icon: '✉️',
    title: '写邮件',
    desc: '生成专业得体的邮件',
    prompt:
      '请帮我写一封邮件。我会告诉你收件人、目的和要点,请用专业得体的语气组织成完整邮件,主题清晰、正文分段、结尾礼貌。',
  },
  {
    icon: '📄',
    title: '总结文档',
    desc: '上传文档,提炼核心要点',
    prompt:
      '请总结我上传的文档:①核心要点 ②关键数据/结论 ③需要我关注的行动项。保留原始信息,不要编造内容。',
  },
  {
    icon: '💡',
    title: '头脑风暴',
    desc: '针对问题给出多个创意方案',
    prompt:
      '请围绕我下面提出的问题做头脑风暴:给出多个有创意的方案,并从可行性、成本、效果三个维度做简要点评,最后给一个推荐。',
  },
]

function onSelect(s: Scenario) {
  emit('select', s.prompt)
}
</script>

<template>
  <div class="welcome-board">
    <div class="welcome-head">
      <div class="welcome-logo">✦</div>
      <h1 class="welcome-title">你好,我是你的 AI 助手</h1>
      <p class="welcome-sub">选择下面的场景快速开始,也可以直接在输入框提问。</p>
    </div>
    <div class="scenario-grid">
      <div
        v-for="s in scenarios"
        :key="s.title"
        class="scenario-card"
        @click="onSelect(s)"
      >
        <div class="scenario-icon">{{ s.icon }}</div>
        <div class="scenario-title">{{ s.title }}</div>
        <div class="scenario-desc">{{ s.desc }}</div>
      </div>
    </div>
    <p class="welcome-tip">💡 提示:点击卡片会把模板填入输入框,你可以补充自己的内容后按 Enter 发送。</p>
  </div>
</template>

<style scoped>
.welcome-board {
  max-width: 760px; margin: 0 auto; padding: 48px 24px 24px;
  display: flex; flex-direction: column; align-items: center;
}
.welcome-head { text-align: center; margin-bottom: 28px; }
.welcome-logo {
  width: 56px; height: 56px; margin: 0 auto 16px; border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  font-size: 26px; color: #fff;
  background: linear-gradient(135deg, var(--accent), #7d6bfd);
  box-shadow: 0 6px 20px rgba(107, 138, 253, 0.35);
}
.welcome-title { font-size: 20px; font-weight: 700; color: var(--text); margin: 0 0 8px; }
.welcome-sub { font-size: 13px; color: var(--text-3); margin: 0; }

.scenario-grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
  width: 100%;
}
@media (max-width: 900px) { .scenario-grid { grid-template-columns: repeat(2, 1fr); } }
.scenario-card {
  display: flex; flex-direction: column; gap: 6px;
  padding: 16px; border-radius: var(--radius);
  background: var(--surface); border: 1px solid var(--border-soft);
  cursor: pointer; transition: transform .15s, border-color .15s, background .15s;
}
.scenario-card:hover {
  transform: translateY(-2px);
  border-color: var(--accent); background: var(--surface-hover);
}
.scenario-icon { font-size: 22px; }
.scenario-title { font-size: 14px; font-weight: 600; color: var(--text); }
.scenario-desc { font-size: 12px; color: var(--text-3); line-height: 1.5; }
.welcome-tip { margin-top: 24px; font-size: 12px; color: var(--text-3); text-align: center; }
</style>
