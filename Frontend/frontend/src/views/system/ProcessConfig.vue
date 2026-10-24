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
      <p class="tip">
        <b>停用</b>：不出现在选项卡和首页汇总里，工序还在这个列表，随时可以重新启用。
        <b>删除</b>：从各个页面去掉（包括这个列表），但填过的数据、目标、看板、修改记录、留底都保留在系统里，可以在下方“已删除的工序”里恢复。
        排序数字越小越靠前。
      </p>
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
        <el-table-column label="操作" width="150" align="center">
          <template #default="{ row }">
            <el-button type="primary" size="small" :disabled="!changed(row)" @click="save(row)">保存</el-button>
            <el-button type="danger" size="small" plain @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="deleted.length" class="panel deleted-panel">
      <div class="panel-head">已删除的工序（{{ deleted.length }}）</div>
      <p class="tip">这些工序的历史数据都还在。恢复后会回到上面的列表并重新启用，负责人需要重新分配。</p>
      <el-table :data="deleted" border size="small">
        <el-table-column label="工序名称" prop="name" min-width="160" />
        <el-table-column label="删除时间" width="180">
          <template #default="{ row }">{{ fmtTime(row.deletedAt) }}</template>
        </el-table-column>
        <el-table-column label="删除人" prop="deletedBy" width="120" />
        <el-table-column label="操作" width="160" align="center">
          <template #default="{ row }">
            <el-button size="small" @click="router.push(`/processes/${row.id}`)">查看数据</el-button>
            <el-button type="success" size="small" @click="restore(row)">恢复</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import api from '@/api'
import { fmtTime } from '@/utils/format'

const router = useRouter()
const processes = ref([])
const deleted = ref([])
const loading = ref(false)
const newName = ref('')
let snapshot = {}

async function load() {
  loading.value = true
  try {
    const [res, del] = await Promise.all([api.processAll(), api.deletedProcesses()])
    if (res.success) {
      processes.value = res.data
      snapshot = Object.fromEntries(res.data.map(p => [p.id, JSON.stringify([p.name, p.sortOrder, p.active])]))
    }
    if (del.success) deleted.value = del.data
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

async function remove(row) {
  const owners = row.owners.map(o => o.username).join('、')
  try {
    await ElMessageBox.confirm(
      `删除后“${row.name}”不再出现在任何页面，${owners ? `负责人（${owners}）也会解除；` : ''}` +
      '填过的数据、修改记录、留底都会保留，可以在“已删除的工序”里恢复。确定删除吗？',
      '删除工序', { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' })
  } catch {
    return
  }
  const res = await api.deleteProcess(row.id)
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(res.message)
  await load()
}

async function restore(row) {
  const res = await api.restoreProcess(row.id)
  if (!res.success) {
    ElMessage.error(res.message)
    return
  }
  ElMessage.success(res.message)
  await load()
}
</script>

<style scoped>
.tip {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.7;
  color: #6b7785;
}

.deleted-panel {
  margin-top: 16px;
}

.panel-head {
  margin-bottom: 6px;
  font-weight: 700;
  color: #4a5562;
}
</style>
