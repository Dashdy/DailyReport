<template>
  <div>
    <div class="page-banner">
      <div>
        <div class="crumb">系统管理 / 用户管理</div>
        <h1>用户管理</h1>
      </div>
      <div class="banner-tools">
        <el-input id="user-search" v-model="keyword" placeholder="搜索用户名 / 账号 / 备注" clearable :prefix-icon="Search" style="width: 240px" />
        <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="shown" border stripe>
        <el-table-column label="用户名" prop="username" min-width="110" />
        <el-table-column label="账号" prop="account" min-width="110" />
        <el-table-column label="企业微信账号" min-width="120">
          <template #default="{ row }">
            <span v-if="row.wecomUserId">{{ row.wecomUserId }}</span>
            <span v-else class="muted">未登记</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <el-tag :type="row.role === 'SYS_ADMIN' ? 'danger' : row.role === 'ADMIN' ? 'warning' : 'info'">{{ row.roleLabel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="负责工序" min-width="260">
          <template #default="{ row }">
            <el-tag v-for="n in row.processNames" :key="n" size="small" class="proc-tag">{{ n }}</el-tag>
            <span v-if="!row.processNames.length" class="muted">
              {{ row.role === 'USER' ? '未分配（只能查看）' : '管理员可编辑全部工序' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="备注" prop="remark" min-width="120" />
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <template v-if="canManage(row)">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="warning" @click="openReset(row)">重置密码</el-button>
              <el-button link type="danger" :disabled="row.id === me.id" @click="remove(row)">删除</el-button>
            </template>
            <span v-else class="muted">仅系统管理员可修改</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新增 / 编辑 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑用户' : '新增用户'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input id="user-form-name" v-model="form.username" maxlength="50" placeholder="系统里显示的名字，建议用真实姓名" />
        </el-form-item>
        <el-form-item label="账号" prop="account">
          <el-input id="user-form-account" v-model="form.account" maxlength="50" placeholder="登录用，如工号，不能重复" />
        </el-form-item>
        <el-form-item :label="form.id ? '新密码' : '密码'" prop="password">
          <el-input id="user-form-password" v-model="form.password" type="password" show-password
                    :placeholder="form.id ? '不修改请留空' : '至少 4 位'" autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="form.role" :disabled="form.id === me.id">
            <el-radio v-if="isSysAdmin" value="SYS_ADMIN">系统管理员</el-radio>
            <el-radio value="ADMIN">管理员</el-radio>
            <el-radio value="USER">用户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" :disabled="form.id === me.id" />
        </el-form-item>
        <el-form-item label="负责工序">
          <el-select v-model="form.processIds" multiple filterable placeholder="选择该用户负责（可编辑）的工序" style="width: 100%">
            <el-option v-for="p in processes" :key="p.id" :label="p.name + (p.active ? '' : '（已停用）')" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="企业微信账号">
          <el-input id="user-form-wecom" v-model="form.wecomUserId" maxlength="64"
                    placeholder="企业微信通讯录里的“账号”，用于推送战报；不推送可不填" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" maxlength="200" placeholder="如：开料班长" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetVisible" :title="`重置 ${resetTarget?.username}（${resetTarget?.account}）的密码`" width="440px">
      <el-input id="user-reset-password" v-model="resetPwd" type="password" show-password placeholder="新密码，至少 4 位" autocomplete="new-password" />
      <p class="muted small">重置后该用户会被强制退出，需要用新密码重新登录。</p>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确定重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import api from '@/api'
import { useAuth } from '@/composables/useAuth'
import { fmtTime } from '@/utils/format'

const auth = useAuth()
const me = computed(() => auth.state.user || {})
const isSysAdmin = computed(() => auth.isSysAdmin.value)

const users = ref([])
const processes = ref([])
const loading = ref(false)
const keyword = ref('')

async function load() {
  loading.value = true
  try {
    const [u, p] = await Promise.all([api.users(), api.processAll()])
    if (u.success) users.value = u.data
    if (p.success) processes.value = p.data
  } finally {
    loading.value = false
  }
}
onMounted(load)

const shown = computed(() => {
  const k = keyword.value.trim()
  return k ? users.value.filter(u => u.username.includes(k) || u.account.includes(k) || (u.remark || '').includes(k)) : users.value
})

/** 系统管理员账号只有系统管理员能改 */
const canManage = row => row.role !== 'SYS_ADMIN' || isSysAdmin.value

// ---------- 新增 / 编辑 ----------
const formVisible = ref(false)
const formRef = ref()
const saving = ref(false)
const form = reactive({ id: null, account: '', username: '', password: '', role: 'USER', enabled: true, processIds: [], remark: '', wecomUserId: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  account: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^\S+$/, message: '账号不能包含空格', trigger: 'blur' }
  ],
  password: [{
    validator: (_, v, cb) => {
      if (!form.id && !v) cb(new Error('请输入密码'))
      else if (v && v.length < 4) cb(new Error('密码至少 4 位'))
      else cb()
    },
    trigger: 'blur'
  }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

function openCreate() {
  Object.assign(form, { id: null, account: '', username: '', password: '', role: 'USER', enabled: true, processIds: [], remark: '', wecomUserId: '' })
  formVisible.value = true
  formRef.value && formRef.value.clearValidate()
}

function openEdit(row) {
  Object.assign(form, {
    id: row.id, account: row.account, username: row.username, password: '', role: row.role, enabled: row.enabled,
    processIds: [...row.processIds], remark: row.remark || '', wecomUserId: row.wecomUserId || ''
  })
  formVisible.value = true
  formRef.value && formRef.value.clearValidate()
}

async function submit() {
  if (!(await formRef.value.validate().catch(() => false))) return
  saving.value = true
  try {
    const body = { ...form }
    delete body.id
    const res = form.id ? await api.updateUser(form.id, body) : await api.createUser(body)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    formVisible.value = false
    await load()
    if (form.id === me.value.id) auth.refreshMe()
  } finally {
    saving.value = false
  }
}

// ---------- 重置密码 ----------
const resetVisible = ref(false)
const resetTarget = ref(null)
const resetPwd = ref('')

function openReset(row) {
  resetTarget.value = row
  resetPwd.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (resetPwd.value.length < 4) {
    ElMessage.warning('密码至少 4 位')
    return
  }
  saving.value = true
  try {
    const res = await api.resetPassword(resetTarget.value.id, resetPwd.value)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    resetVisible.value = false
  } finally {
    saving.value = false
  }
}

// ---------- 删除 ----------
async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除用户“${row.username}”（账号 ${row.account}）吗？他填过的历史数据和操作日志会保留。`, '删除用户', {
      type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger'
    })
  } catch {
    return
  }
  const res = await api.deleteUser(row.id)
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(res.message)
  await load()
}
</script>

<style scoped>
.proc-tag {
  margin: 2px 4px 2px 0;
}

.muted {
  color: #a0a8b3;
  font-size: 13px;
}

.small {
  font-size: 12px;
  margin: 10px 0 0;
}
</style>
