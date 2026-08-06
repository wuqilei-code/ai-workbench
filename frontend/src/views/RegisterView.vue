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
</script>

<template>
  <div class="auth-wrap">
    <n-card class="auth-card" title="注册" size="large">
      <n-form @keyup.enter="onSubmit">
        <n-form-item label="用户名">
          <n-input v-model:value="form.username" placeholder="3-32 字符" />
        </n-form-item>
        <n-form-item label="邮箱">
          <n-input v-model:value="form.email" placeholder="email@example.com" />
        </n-form-item>
        <n-form-item label="密码">
          <n-input v-model:value="form.password" type="password" show-password-on="click" placeholder="至少 6 位" />
        </n-form-item>
        <n-button type="primary" block :loading="loading" @click="onSubmit">注册</n-button>
      </n-form>
      <template #footer>
        <span>已有账号？</span>
        <n-button text type="primary" @click="router.push('/login')">去登录</n-button>
      </template>
    </n-card>
  </div>
</template>

<style scoped>
.auth-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #18181c;
}
.auth-card { width: 380px; }
</style>
