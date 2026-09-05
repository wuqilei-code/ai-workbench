<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()

const form = reactive({ username: '', email: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  if (!form.username || !form.email || !form.password) {
    msg.warning('请填写完整')
    return
  }
  loading.value = true
  try {
    const res = await auth.register(form.username, form.email, form.password)
    msg.success(`注册成功${res.user.role === 'admin' ? '(管理员)' : ''}`)
    router.push('/')
  } catch (e: any) {
    msg.error(e.message || '注册失败')
  } finally {
    loading.value = false
  }
}

/** 项目功能展示 */
const features = [
  { icon: '💬', title: '智能对话', desc: '多模型 AI 助手，上下文感知，支持文档/代码/数据分析' },
  { icon: '🧩', title: '技能市场', desc: '一键安装/发布技能，SKILL.md 驱动的工作流扩展' },
  { icon: '📚', title: '模板库', desc: '周报、会议纪要、数据分析等常用提示词模板' },
  { icon: '⏰', title: '定时自动化', desc: '可视化 cron 定时任务，结果自动生成执行报告' },
  { icon: '📁', title: '文件工作区', desc: '内置代码编辑器、预览与 AI 联动' },
  { icon: '🔌', title: 'MCP 集成', desc: '连接外部数据源与工具，扩展 AI 能力边界' },
]
</script>

<template>
  <div class="auth-page">
    <div class="glow glow-1"></div>
    <div class="glow glow-2"></div>
    <div class="grid-bg"></div>

    <div class="auth-shell">
      <!-- 左侧品牌介绍 -->
      <div class="brand-panel">
        <div class="brand-logo">
          <div class="logo-mark">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a5 5 0 0 1 5 5v2a5 5 0 0 1-3 4.58V15a2 2 0 0 1-2 2h-0a2 2 0 0 1-2-2v-1.42A5 5 0 0 1 7 9V7a5 5 0 0 1 5-5z"/><path d="M12 21v-2"/></svg>
          </div>
          <span class="brand-name">AI Workbench</span>
        </div>
        <p class="brand-slogan">一站式 AI 工作台，开启你的高效协作之旅</p>

        <div class="feature-grid">
          <div v-for="f in features" :key="f.title" class="feature-card">
            <span class="feature-icon">{{ f.icon }}</span>
            <div class="feature-body">
              <span class="feature-title">{{ f.title }}</span>
              <span class="feature-desc">{{ f.desc }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧注册表单 -->
      <div class="form-panel">
        <div class="form-card">
          <h2 class="form-title">创建账号</h2>
          <p class="form-sub">加入 AI Workbench，探索更多可能</p>
          <n-form @keyup.enter="onSubmit">
            <n-form-item label="用户名">
              <n-input v-model:value="form.username" placeholder="3-32 字符" size="large">
                <template #prefix>
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
                </template>
              </n-input>
            </n-form-item>
            <n-form-item label="邮箱">
              <n-input v-model:value="form.email" placeholder="email@example.com" size="large">
                <template #prefix>
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="4" width="20" height="16" rx="2"/><path d="m22 7-10 6L2 7"/></svg>
                </template>
              </n-input>
            </n-form-item>
            <n-form-item label="密码">
              <n-input v-model:value="form.password" type="password" show-password-on="click" placeholder="至少 6 位" size="large">
                <template #prefix>
                  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                </template>
              </n-input>
            </n-form-item>
            <n-button type="primary" block :loading="loading" @click="onSubmit" size="large">注册</n-button>
          </n-form>
          <div class="form-footer">
            <span class="footer-text">已有账号？</span>
            <n-button text type="primary" @click="router.push('/login')">去登录</n-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: var(--auth-bg);
}
.glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}
.glow-1 {
  width: 480px; height: 480px;
  top: -120px; left: -80px;
  background: var(--auth-glow-1);
}
.glow-2 {
  width: 420px; height: 420px;
  bottom: -120px; right: -60px;
  background: var(--auth-glow-2);
}
.grid-bg {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(var(--auth-grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--auth-grid) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at 50% 40%, #000 30%, transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse at 50% 40%, #000 30%, transparent 75%);
  pointer-events: none;
}

.auth-shell {
  position: relative;
  z-index: 1;
  display: flex;
  width: min(1060px, 94vw);
  min-height: 600px;
  border-radius: 20px;
  overflow: hidden;
  background: var(--auth-shell-bg);
  border: 1px solid var(--auth-shell-border);
  box-shadow: var(--auth-shell-shadow);
  backdrop-filter: blur(8px);
}

.brand-panel {
  flex: 1.1;
  padding: 44px 40px;
  display: flex;
  flex-direction: column;
}
.brand-logo {
  display: flex; align-items: center; gap: 10px;
}
.logo-mark {
  width: 40px; height: 40px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #8B5CF6, #6D28D9);
  color: #fff;
  box-shadow: 0 6px 18px rgba(139, 92, 246, 0.35);
}
.brand-name {
  font-size: 20px; font-weight: 700; color: var(--text);
  letter-spacing: 0.3px;
}
.brand-slogan {
  margin: 14px 0 26px;
  color: var(--text-3);
  font-size: 14px;
  line-height: 1.7;
  max-width: 420px;
}
.feature-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  flex: 1;
  align-content: start;
}
.feature-card {
  display: flex; gap: 10px;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--auth-card-bg);
  border: 1px solid var(--auth-card-border);
  transition: border-color .15s, background .15s, transform .15s;
}
.feature-card:hover {
  border-color: rgba(139, 92, 246, 0.4);
  background: rgba(139, 92, 246, 0.06);
  transform: translateY(-2px);
}
.feature-icon { font-size: 18px; flex-shrink: 0; line-height: 1.4; }
.feature-body { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.feature-title { font-size: 13px; font-weight: 600; color: var(--text); }
.feature-desc { font-size: 11px; color: var(--text-3); line-height: 1.5; }

.form-panel {
  flex: 0.9;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 36px;
  background: var(--auth-panel-bg);
  border-left: 1px solid var(--auth-panel-border);
}
.form-card { width: 100%; max-width: 340px; }
.form-title {
  margin: 0 0 6px;
  font-size: 24px; font-weight: 700; color: var(--text);
}
.form-sub {
  margin: 0 0 26px;
  font-size: 13px; color: var(--text-3);
}
.form-footer {
  margin-top: 22px;
  text-align: center;
  font-size: 13px;
  color: var(--text-3);
  display: flex; align-items: center; justify-content: center; gap: 4px;
}
.footer-text { color: var(--text-3); }

:deep(.n-form-item-label) { color: var(--text-2); font-size: 13px; }
:deep(.n-input .n-input__prefix) { color: var(--text-3); }

@media (max-width: 860px) {
  .auth-shell { flex-direction: column; }
  .brand-panel { padding: 30px 28px; }
  .feature-grid { grid-template-columns: 1fr; }
  .form-panel { border-left: none; border-top: 1px solid rgba(255,255,255,0.06); }
}

/* 移动端:聚焦表单,隐藏功能介绍,允许滚动,输入框 16px 防 iOS 自动放大 */
@media (max-width: 768px) {
  .auth-page {
    overflow-y: auto;
    padding: 12px;
  }
  .auth-shell {
    width: 100%;
    min-height: 0;
    border-radius: 16px;
    margin: auto 0;
  }
  .brand-panel {
    padding: 20px 20px 4px;
    align-items: center;
    text-align: center;
  }
  .brand-slogan { margin: 8px 0 0; font-size: 13px; }
  .feature-grid { display: none; }
  .form-panel { padding: 16px 20px 24px; }
  .form-title { font-size: 20px; }
  :deep(.n-input__input-el) { font-size: 16px !important; }
}
</style>
