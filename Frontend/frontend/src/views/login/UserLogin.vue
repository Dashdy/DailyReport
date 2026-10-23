<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-head">
        <el-icon class="logo"><Promotion /></el-icon>
        <div>
          <h1>每日战报系统</h1>
          <p>安全 · 品质 · 交付 · 报废 · 成本 · 设备</p>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="onSubmit">
        <el-form-item prop="account">
          <el-input id="login-account" v-model="form.account" placeholder="账号" :prefix-icon="User" autocomplete="username" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input id="login-password" v-model="form.password" type="password" show-password placeholder="密码"
                    :prefix-icon="Lock" autocomplete="current-password" @keyup.enter="onSubmit" />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" native-type="submit">登 录</el-button>
      </el-form>
      <p class="tip">账号由系统管理员 / 管理员在“用户管理”中创建</p>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import api from '@/api'
import { useAuth } from '@/composables/useAuth'

const router = useRouter()
const route = useRoute()
const { setSession } = useAuth()

const formRef = ref()
const loading = ref(false)
const form = reactive({ account: '', password: '' })
const rules = {
  account: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function onSubmit() {
  if (!(await formRef.value.validate().catch(() => false))) return
  loading.value = true
  try {
    const res = await api.login({ account: form.account.trim(), password: form.password })
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    setSession(res.data)
    ElMessage.success(`欢迎回来，${res.data.username}`)
    router.replace(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background:
    radial-gradient(circle at 20% 20%, rgba(59, 130, 246, 0.35), transparent 45%),
    linear-gradient(135deg, #0b1733 0%, #13306b 55%, #1d4ed8 100%);
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 36px 36px 24px;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35);
}

.login-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.logo {
  font-size: 42px;
  color: #ff5a5f;
}

h1 {
  margin: 0;
  font-size: 24px;
  color: #10244d;
  letter-spacing: 2px;
}

.login-head p {
  margin: 6px 0 0;
  font-size: 13px;
  color: #7a8899;
  letter-spacing: 1px;
}

.submit {
  width: 100%;
  margin-top: 6px;
}

.tip {
  margin: 18px 0 0;
  text-align: center;
  font-size: 12px;
  color: #a0a8b3;
}
</style>
