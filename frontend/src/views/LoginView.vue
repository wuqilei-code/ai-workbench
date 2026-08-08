<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const msg = useMessage()

const form = reactive({ usernameOrEmail: '', password: '' })
const loading = ref(false)

async function onSubmit() {
  if (!form.usernameOrEmail || !form.password) {
    msg.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.usernameOrEmail, form.password)
    msg.success('登录成功')
    router.push('/')
  } catch (e: any) {
    msg.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-wrap">
    <n-card class="auth-card" title="登录 AI Workbench" size="large">
      <n-form @keyup.enter="onSubmit">
        <n-form-item label="用户名 / 邮箱">
          <n-input v-model:value="form.usernameOrEmail" placeholder="用户名或邮箱" />
        </n-form-item>
        <n-form-item label="密码">
          <n-input v-model:value="form.password" type="password" show-password-on="click" placeholder="密码" />
        </n-form-item>
        <n-button type="primary" block :loading="loading" @click="onSubmit">登录</n-button>
      </n-form>
      <template #footer>
        <span>还没有账号？</span>
        <n-button text type="primary" @click="router.push('/register')">去注册</n-button>
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
  background: linear-gradient(160deg, #14161a, #1a1d24);
}
.auth-card { width: 380px; }
</style>
