<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { chatApi } from '@/api/chat'
import { templateIconInner } from '@/utils/themeIcons'

/** 空会话欢迎板:场景化快捷提问,点击把模板填入输入框供编辑发送。
 *  优先显示模板库(管理员可维护);模板库为空时才回退到内置常用场景,避免重复。 */
interface Scenario {
  icon: string
  title: string
  desc: string
  prompt: string
}

const emit = defineEmits<{ (e: 'select', prompt: string): void }>()

/** 模板库场景(动态):title=模板名,desc=分类,prompt=模板内容 */
const templateScenarios = ref<Scenario[]>([])

/** 展示的场景:用户启用的模板(homeTemplates 无启用时后端已兜底返回内置) */
const displayScenarios = computed(() => templateScenarios.value)

onMounted(async () => {
  try {
    const templates = await chatApi.homeTemplates()
    templateScenarios.value = templates.map((t) => ({
      icon: t.icon || '📋',
      title: t.title,
      desc: t.category || '通用',
      prompt: t.content,
    }))
  } catch {
    // 模板加载失败不阻塞欢迎页
    templateScenarios.value = []
  }
})

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
        v-for="s in displayScenarios"
        :key="s.title"
        class="scenario-card"
        @click="onSelect(s)"
      >
        <div class="scenario-icon">
          <svg
            v-if="templateIconInner(s.icon)"
            width="20" height="20" viewBox="0 0 24 24" fill="none"
            stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"
            v-html="templateIconInner(s.icon)"
          ></svg>
          <span v-else>{{ s.icon }}</span>
        </div>
        <div class="scenario-title">{{ s.title }}</div>
        <div class="scenario-desc">{{ s.desc }}</div>
      </div>
    </div>
    <p v-if="displayScenarios.length" class="welcome-tip">💡 提示:点击卡片会把模板填入输入框,你可以补充自己的内容后按 Enter 发送。上方「模板库」可管理更多模板。</p>
  </div>
</template>

<style scoped>
.welcome-board {
  max-width: 880px; margin: 0 auto; padding: 48px 24px 24px;
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
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px;
  width: 100%;
}
@media (max-width: 1024px) { .scenario-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 768px) { .scenario-grid { grid-template-columns: repeat(2, 1fr); } }
.scenario-card {
  display: flex; flex-direction: column; gap: 8px;
  padding: 18px 16px; border-radius: var(--radius);
  background: var(--surface); border: 1px solid var(--border-soft);
  cursor: pointer; position: relative;
  transition: transform .18s ease, border-color .18s, background .18s, box-shadow .18s;
  overflow: hidden;
}
.scenario-card::before {
  content: ''; position: absolute; inset: 0; pointer-events: none;
  background: radial-gradient(120px 60px at 15% 0%, var(--accent-soft), transparent 70%);
  opacity: 0; transition: opacity .18s;
}
.scenario-card:hover {
  transform: translateY(-3px);
  border-color: var(--accent); background: var(--surface-hover);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}
.scenario-card:hover::before { opacity: 1; }
.scenario-icon {
  width: 40px; height: 40px; border-radius: 12px; font-size: 20px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-soft); border: 1px solid var(--border-soft);
  color: var(--accent);
  margin-bottom: 2px;
}
.scenario-title { font-size: 14px; font-weight: 600; color: var(--text); }
.scenario-desc { font-size: 12px; color: var(--text-3); line-height: 1.5; }
.welcome-tip { margin-top: 24px; font-size: 12px; color: var(--text-3); text-align: center; }
</style>
