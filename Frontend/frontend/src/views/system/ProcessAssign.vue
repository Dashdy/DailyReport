<template>
  <div v-loading="loading">
    <div class="page-banner">
      <div>
        <div class="crumb">系统管理 / 工序负责人分配</div>
        <h1>工序负责人分配</h1>
      </div>
      <div class="banner-tools banner-note">只有负责人（以及系统管理员、管理员）能编辑对应工序的战报数据</div>
    </div>

    <div class="panel">
      <el-tabs v-model="tab">
        <!-- 按工序分配 -->
        <el-tab-pane label="按工序分配负责人" name="byProcess">
          <el-table :data="processes" border>
            <el-table-column label="工序" prop="name" width="150">
              <template #default="{ row }">
                {{ row.name }} <el-tag v-if="!row.active" size="small" type="info">已停用</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="负责人">
              <template #default="{ row }">
                <el-select v-model="ownerDraft[row.id]" multiple filterable placeholder="选择负责人" style="width: 100%">
                  <el-option v-for="u in users" :key="u.id" :label="`${u.username}（${u.account} · ${u.roleLabel}）`" :value="u.id" :disabled="!u.enabled" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="110" align="center">
              <template #default="{ row }">
                <el-button type="primary" size="small" :disabled="!ownerChanged(row)" @click="saveOwners(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 按用户分配 -->
        <el-tab-pane label="按用户分配工序" name="byUser">
          <div class="by-user">
            <div class="user-list">
              <div v-for="u in users" :key="u.id" class="user-item" :class="{ active: u.id === currentUserId }"
                   tabindex="0" @click="pickUser(u)" @keyup.enter="pickUser(u)">
                <span>{{ u.username }} <small class="acct">{{ u.account }}</small></span>
                <span class="user-meta">{{ u.roleLabel }} · {{ u.processIds.length }} 道</span>
              </div>
            </div>
            <div v-if="currentUser" class="user-processes">
              <div class="up-head">
                <b>{{ currentUser.username }}</b> 负责的工序
                <span class="up-tools">
                  <el-button size="small" @click="userDraft = processes.map(p => p.id)">全选</el-button>
                  <el-button size="small" @click="userDraft = []">清空</el-button>
                  <el-button type="primary" size="small" @click="saveUserProcesses">保存</el-button>
                </span>
              </div>
              <el-checkbox-group v-model="userDraft" class="proc-checks">
                <el-checkbox v-for="p in processes" :key="p.id" :value="p.id" border>{{ p.name }}</el-checkbox>
              </el-checkbox-group>
            </div>
            <el-empty v-else description="从左侧选择一个用户" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api'
import { useAuth } from '@/composables/useAuth'

const auth = useAuth()
const tab = ref('byProcess')
const loading = ref(false)
const processes = ref([])
const users = ref([])
const ownerDraft = reactive({})

async function load() {
  loading.value = true
  try {
    const [p, u] = await Promise.all([api.processAll(), api.users()])
    if (p.success) processes.value = p.data
    if (u.success) users.value = u.data
    for (const row of processes.value) ownerDraft[row.id] = row.owners.map(o => o.id)
    if (currentUserId.value) pickUser(users.value.find(x => x.id === currentUserId.value))
  } finally {
    loading.value = false
  }
}
onMounted(load)

const sameSet = (a, b) => a.length === b.length && a.every(x => b.includes(x))
const ownerChanged = row => !sameSet(ownerDraft[row.id] || [], row.owners.map(o => o.id))

async function saveOwners(row) {
  const res = await api.setOwners(row.id, ownerDraft[row.id])
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(`“${row.name}”${res.message}`)
  await load()
  auth.refreshMe()
}

const currentUserId = ref(null)
const userDraft = ref([])
const currentUser = computed(() => users.value.find(u => u.id === currentUserId.value))

function pickUser(u) {
  if (!u) return
  currentUserId.value = u.id
  userDraft.value = [...u.processIds]
}

async function saveUserProcesses() {
  const res = await api.assignProcesses(currentUserId.value, userDraft.value)
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(res.message)
  await load()
  auth.refreshMe()
}
</script>

<style scoped>
.banner-note {
  font-size: 13px;
  opacity: 0.9;
}

.by-user {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

@media (max-width: 760px) {
  .by-user {
    flex-direction: column;
  }
}

.user-list {
  width: 240px;
  flex-shrink: 0;
  max-height: 560px;
  overflow-y: auto;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.user-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  cursor: pointer;
  border-bottom: 1px solid #f0f2f5;
}

.user-item:hover,
.user-item:focus-visible {
  background: #f5f9ff;
  outline: none;
}

.user-item.active {
  background: #e8f1ff;
  color: #1d5fd0;
  font-weight: 600;
}

.acct {
  color: #8a96a3;
  font-weight: 400;
}

.user-meta {
  font-size: 12px;
  color: #8a96a3;
  font-weight: 400;
}

.user-processes {
  flex: 1;
  min-width: 0;
}

.up-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 14px;
}

.up-tools {
  display: flex;
  gap: 6px;
}

.proc-checks {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

.proc-checks :deep(.el-checkbox) {
  margin-right: 0;
}
</style>
