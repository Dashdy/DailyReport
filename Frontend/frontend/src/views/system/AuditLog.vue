<template>
  <div>
    <div class="page-banner">
      <div>
        <div class="crumb">系统管理 / 操作日志</div>
        <h1>操作日志</h1>
      </div>
      <div class="banner-tools banner-note">每一次新增、修改、删除都会记录操作人、原数据和新数据，日志不可修改</div>
    </div>

    <div class="panel">
      <div class="filters">
        <el-select id="audit-module" v-model="q.module" placeholder="全部模块" clearable style="width: 140px" @change="search">
          <el-option v-for="m in MODULES" :key="m" :label="m" :value="m" />
        </el-select>
        <el-select id="audit-process" v-model="q.processName" placeholder="全部工序" clearable filterable style="width: 160px" @change="search">
          <el-option v-for="p in processNames" :key="p" :label="p" :value="p" />
        </el-select>
        <el-date-picker id="audit-range" v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期"
                        end-placeholder="结束日期" style="width: 260px" @change="search" />
        <el-input id="audit-keyword" v-model="q.keyword" placeholder="操作人 / 操作对象" clearable style="width: 200px"
                  @keyup.enter="search" @clear="search" />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      </div>

      <el-table v-loading="loading" :data="list" border stripe>
        <el-table-column label="时间" width="165">
          <template #default="{ row }"><span class="num">{{ fmtTime(row.createdAt) }}</span></template>
        </el-table-column>
        <el-table-column label="操作人" prop="operatorName" width="110" />
        <el-table-column label="模块" width="100">
          <template #default="{ row }"><el-tag size="small" :type="MODULE_TYPE[row.module] || 'info'">{{ row.module }}</el-tag></template>
        </el-table-column>
        <el-table-column label="动作" prop="action" width="90" />
        <el-table-column label="工序" prop="processName" width="110" />
        <el-table-column label="操作对象" prop="target" min-width="170" />
        <el-table-column label="原数据" min-width="220">
          <template #default="{ row }"><div class="val old">{{ show(row.beforeValue) }}</div></template>
        </el-table-column>
        <el-table-column label="新数据" min-width="220">
          <template #default="{ row }"><div class="val">{{ show(row.afterValue) }}</div></template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="q.page" v-model:page-size="q.size" class="pager" :total="total"
                     :page-sizes="[20, 50, 100]" layout="total, sizes, prev, pager, next, jumper"
                     @current-change="load" @size-change="search" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import api from '@/api'
import { fmtTime } from '@/utils/format'

const MODULES = ['每日数据', '目标设定', '班组看板', '行动与亮点', '企业微信推送', '工序分配', '工序配置', '用户管理', '系统设置', '登录']
const MODULE_TYPE = { 每日数据: 'primary', 目标设定: 'warning', 班组看板: 'success', 用户管理: 'danger', 系统设置: 'danger' }

const q = reactive({ page: 1, size: 20, module: '', processName: '', keyword: '' })
const range = ref(null)
const list = ref([])
const total = ref(0)
const loading = ref(false)
const processNames = ref([])

async function load() {
  loading.value = true
  try {
    const res = await api.auditLogs({
      ...q,
      startDate: range.value ? range.value[0] : undefined,
      endDate: range.value ? range.value[1] : undefined
    })
    if (res.success) {
      list.value = res.data.list
      total.value = res.data.total
    }
  } finally {
    loading.value = false
  }
}

function search() {
  q.page = 1
  load()
}

onMounted(async () => {
  load()
  const res = await api.processCards()
  if (res.success) processNames.value = res.data.map(p => p.name)
})

/** JSON 格式的原 / 新数据显示成“字段：值”一行一项 */
function show(v) {
  if (v === null || v === undefined || v === '') return '—'
  if (v.startsWith('{')) {
    try {
      return Object.entries(JSON.parse(v)).map(([k, val]) => `${k}：${val === '' ? '（空）' : val}`).join('\n')
    } catch {
      return v
    }
  }
  return v
}
</script>

<style scoped>
.banner-note {
  font-size: 13px;
  opacity: 0.9;
}

.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.val {
  white-space: pre-line;
  font-size: 13px;
  line-height: 1.5;
}

.val.old {
  color: #8a96a3;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
