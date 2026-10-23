<template>
  <div>
    <div class="page-banner">
      <div>
        <div class="crumb">系统管理 / 工序配置</div>
        <h1>工序配置</h1>
      </div>
      <div class="banner-tools">
        <el-input id="process-new-name" v-model="newName" placeholder="新工序名称" style="width: 180px" maxlength="50" @keyup.enter="create" />
        <el-button type="primary" :icon="Plus" @click="create">新增工序</el-button>
      </div>
    </div>

    <div class="panel">
      <p class="tip">工序不能删除，只能停用：停用后不再出现在选项卡和首页汇总里，但历史战报和修改记录都会保留。排序数字越小越靠前。</p>
      <el-table v-loading="loading" :data="processes" border>
        <el-table-column label="排序" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.sortOrder" :min="0" :step="10" size="small" controls-position="right" style="width: 100px" />
          </template>
        </el-table-column>
        <el-table-column label="工序名称" min-width="180">
          <template #default="{ row }"><el-input v-model="row.name" maxlength="50" /></template>
        </el-table-column>
        <el-table-column label="启用" width="100" align="center">
          <template #default="{ row }"><el-switch v-model="row.active" /></template>
        </el-table-column>
        <el-table-column label="负责人" min-width="160">
          <template #default="{ row }">{{ row.owners.map(o => o.username).join('、') || '未分配' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" align="center">
          <template #default="{ row }">
            <el-button type="primary" size="small" :disabled="!changed(row)" @click="save(row)">保存</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import api from '@/api'

const processes = ref([])
const loading = ref(false)
const newName = ref('')
let snapshot = {}

async function load() {
  loading.value = true
  try {
    const res = await api.processAll()
    if (res.success) {
      processes.value = res.data
      snapshot = Object.fromEntries(res.data.map(p => [p.id, JSON.stringify([p.name, p.sortOrder, p.active])]))
    }
  } finally {
    loading.value = false
  }
}
onMounted(load)

const changed = row => snapshot[row.id] !== JSON.stringify([row.name, row.sortOrder, row.active])

async function save(row) {
  const res = await api.updateProcess(row.id, { name: row.name, sortOrder: row.sortOrder, active: row.active })
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(res.message)
  await load()
}

async function create() {
  if (!newName.value.trim()) {
    ElMessage.warning('请输入工序名称')
    return
  }
  const res = await api.createProcess({ name: newName.value.trim() })
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(res.message + '，已带上六项默认目标')
  newName.value = ''
  await load()
}
</script>

<style scoped>
.tip {
  margin: 0 0 14px;
  font-size: 13px;
  color: #6b7785;
}
</style>
