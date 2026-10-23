<template>
  <div>
    <div class="page-banner">
      <div>
        <div class="crumb">个人设置 / 修改密码</div>
        <h1>修改密码</h1>
      </div>
    </div>
    <div class="panel pwd-panel">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input id="pwd-old" v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input id="pwd-new" v-model="form.newPassword" type="password" show-password autocomplete="new-password" placeholder="至少 4 位" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirm">
          <el-input id="pwd-confirm" v-model="form.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api'

const formRef = ref()
const saving = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [{ required: true, min: 4, message: '新密码至少 4 位', trigger: 'blur' }],
  confirm: [{
    validator: (_, v, cb) => (v === form.newPassword ? cb() : cb(new Error('两次输入的新密码不一致'))),
    trigger: 'blur'
  }]
}

async function submit() {
  if (!(await formRef.value.validate().catch(() => false))) return
  saving.value = true
  try {
    const res = await api.changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    Object.assign(form, { oldPassword: '', newPassword: '', confirm: '' })
    formRef.value.clearValidate()
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.pwd-panel {
  max-width: 480px;
}
</style>
