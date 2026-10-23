<template>
  <div v-loading="loading" class="detail-page">
    <div class="page-banner">
      <div>
        <div class="crumb">
          <router-link to="/processes" class="crumb-link">工序战报</router-link> / {{ processName }}
        </div>
        <h1>{{ processName }}工序 · 每日战报</h1>
        <div class="owner-line">
          负责人：{{ owners || '未分配' }}
          <el-tag v-if="d && d.canEdit" type="success" size="small" effect="dark">可编辑</el-tag>
          <el-tag v-else-if="d" type="info" size="small" effect="dark">只读：你不是该工序负责人</el-tag>
        </div>
      </div>
      <div class="banner-tools">
        <el-date-picker id="detail-month" v-model="month" type="month" value-format="YYYY-MM" :clearable="false"
                        :disabled-date="dt => dt > new Date()" @change="onMonthChange" />
        <el-button :icon="TrendCharts" type="primary" @click="router.push({ path: `/processes/${processId}/carousel`, query: { month } })">查看趋势图</el-button>
        <el-button :icon="Clock" @click="openHistory">修改记录 / 留底</el-button>
      </div>
    </div>

    <div v-if="d" class="sheet">
      <!-- ================= 左：六项自主保证书 + 班组看板 ================= -->
      <div class="left">
        <table class="cert">
          <thead>
            <tr>
              <th colspan="4" class="cert-title">
                {{ processName }}工序 六项自主保证书
                <el-button v-if="d.canEdit" size="small" class="title-btn" @click="openTargets">编辑目标</el-button>
              </th>
            </tr>
            <tr>
              <th>指标</th>
              <th>公司底线</th>
              <th>班组自定</th>
              <th>本班当前<span class="cur-date">{{ selectedDay }}号</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in d.targets" :key="t.metric">
              <td class="cert-metric">{{ t.label }}</td>
              <td>{{ t.baseline || '—' }}</td>
              <td>{{ t.teamTarget || '—' }}</td>
              <td :class="'cur-' + statusOf(selectedDay, t.metric)">
                <template v-if="cellValue(selectedDay, t.metric) !== ''">
                  {{ cellValue(selectedDay, t.metric) }}{{ t.unit === '%' ? '%' : ' ' + (t.unit || '') }}
                </template>
                <span v-else class="muted">未填</span>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- 四功能小组：四个人的照片、姓名、职能 -->
        <div class="board">
          <div class="board-head">
            <span>四功能小组</span>
            <template v-if="d.canEdit">
              <el-button v-if="!boardEditing" size="small" @click="startBoardEdit">编辑</el-button>
              <span v-else class="board-btns">
                <el-button size="small" @click="boardEditing = false">取消</el-button>
                <el-button size="small" type="warning" :loading="savingBoard" @click="saveBoard">保存</el-button>
              </span>
            </template>
          </div>
          <div class="board-body">
            <div v-for="(m, i) in board.members" :key="i" class="member">
              <ImageUpload v-model="m.photo" :editable="boardEditing" :placeholder="'成员 ' + (i + 1) + ' 照片'" />
              <template v-if="boardEditing">
                <el-input :id="'board-member-name-' + i" v-model="m.name" size="small" placeholder="姓名" maxlength="20" />
                <el-input :id="'board-member-role-' + i" v-model="m.role" size="small" placeholder="职能，如：安全" maxlength="20" />
              </template>
              <template v-else>
                <div class="member-name">{{ m.name || '（未填写姓名）' }}</div>
                <div class="member-role">{{ m.role || '职能未填' }}</div>
              </template>
            </div>
          </div>
        </div>

        <!-- 全员签名、日期：上下两行 -->
        <div class="sign-block">
          <div class="sign-row">
            <span class="sign-label">全员签名：</span>
            <el-input v-if="boardEditing" id="board-signers" v-model="board.signers" class="sign-input" placeholder="组员姓名，用空格隔开" maxlength="500" />
            <span v-else class="sign-line">{{ board.signers }}</span>
          </div>
          <div class="sign-row">
            <span class="sign-label">日期：</span>
            <el-date-picker v-if="boardEditing" id="board-sign-date" v-model="board.signDate" type="date"
                            value-format="YYYY-MM-DD" class="sign-input" style="width: 100%" />
            <span v-else class="sign-line num">{{ board.signDate ? board.signDate.replaceAll('-', '.') : '' }}</span>
          </div>
        </div>
        <div v-if="d.board && d.board.updatedBy" class="board-meta">
          看板最后保存：{{ d.board.updatedBy }} · {{ fmtTime(d.board.updatedAt) }}
        </div>
      </div>

      <!-- ================= 右：每日走势表 ================= -->
      <div class="right">
        <div class="grid-tools">
          <span class="grid-tip">
            点日期可切换左侧“本班当前”；<span class="tip-pass">绿色</span>达标，<span class="tip-fail">红色</span>未达标；鼠标停在格子上可看填报人
            <template v-if="lockTip">
              <br /><el-icon class="lock-icon"><Lock /></el-icon>{{ lockTip }}
            </template>
          </span>
          <span v-if="d.canEdit" class="grid-btns">
            <span v-if="dirtyCount" class="dirty-tip">有 {{ dirtyCount }} 处未保存</span>
            <el-button :disabled="!dirtyCount" @click="resetGrid">撤销修改</el-button>
            <el-button type="primary" :disabled="!dirtyCount" :loading="savingGrid" @click="saveGrid">保存走势表</el-button>
          </span>
        </div>
        <div class="grid-wrap">
          <table class="trend">
            <thead>
              <tr>
                <th class="month-cell">{{ monthNum }}月</th>
                <th :colspan="days.length" class="trend-title"><span>每日走势表</span></th>
              </tr>
              <tr>
                <th class="row-head">日期</th>
                <th v-for="day in days" :key="day" class="day-head"
                    :class="{ selected: day === selectedDay, future: isFuture(day), locked: d.canEdit && isLocked(day) }"
                    :title="d.canEdit && isLocked(day) ? '已锁定：只有系统管理员、管理员可以修改' : ''"
                    @click="!isFuture(day) && (selectedDay = day)">
                  {{ day }}号
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in d.metrics" :key="m.key">
                <th class="row-head">{{ m.label }}</th>
                <td v-for="day in days" :key="day"
                    :class="['cell', 'st-' + statusOf(day, m.key), { dirty: isDirty(day, m.key), selected: day === selectedDay, locked: d.canEdit && isLocked(day) }]"
                    :title="cellTitle(day, m.key)">
                  <input v-if="isEditable(day)" :id="`cell-${day}-${m.key}`" v-model="grid[key(day, m.key)]"
                         inputmode="decimal" :aria-label="`${monthNum}月${day}号 ${m.label}`"
                         @focus="selectedDay = day" @change="normalize(day, m.key)" />
                  <span v-else>{{ grid[key(day, m.key)] }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 折线走势图 -->
        <div class="charts">
          <div v-for="t in d.targets" :key="t.metric" class="chart-card">
            <div class="chart-title">
              <span>{{ t.label }}走势</span>
              <span class="chart-target">目标 {{ t.compareOp === 'GE' ? '≥' : '≤' }} {{ fmtNum(t.targetValue) }}{{ t.unit }}</span>
            </div>
            <EChart :option="chartOption(t)" height="200px" />
          </div>
        </div>
      </div>
    </div>

    <!-- ================= 编辑六项目标 ================= -->
    <el-dialog v-model="targetsVisible" title="编辑六项自主保证书" width="860px" top="6vh">
      <p class="dialog-tip">“公司底线”“班组自定”是给人看的文字；“达标判定”是系统自动判断红绿的规则。</p>
      <el-table :data="targetForm" border size="small">
        <el-table-column label="指标" prop="label" width="70" align="center" />
        <el-table-column label="公司底线" min-width="140">
          <template #default="{ row }"><el-input v-model="row.baseline" maxlength="100" /></template>
        </el-table-column>
        <el-table-column label="班组自定" min-width="140">
          <template #default="{ row }"><el-input v-model="row.teamTarget" maxlength="100" /></template>
        </el-table-column>
        <el-table-column label="单位" width="90">
          <template #default="{ row }"><el-input v-model="row.unit" maxlength="20" /></template>
        </el-table-column>
        <el-table-column label="达标判定（当前值 … 目标值）" width="260">
          <template #default="{ row }">
            <div class="judge">
              <el-select v-model="row.compareOp" style="width: 76px">
                <el-option label="≤" value="LE" />
                <el-option label="≥" value="GE" />
              </el-select>
              <el-input-number v-model="row.targetValue" :controls="false" :precision="4" style="width: 120px" />
            </div>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="targetsVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTargets" @click="saveTargets">保存</el-button>
      </template>
    </el-dialog>

    <!-- ================= 修改记录 / 留底版本 ================= -->
    <el-drawer v-model="historyVisible" :title="processName + ' · 修改记录与留底'" size="640px">
      <el-tabs v-model="historyTab">
        <el-tab-pane label="修改记录" name="logs">
          <el-table :data="logs.list" size="small" border>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作人" prop="operatorName" width="90" />
            <el-table-column label="内容" min-width="150">
              <template #default="{ row }">
                <div><el-tag size="small" type="info">{{ row.module }}</el-tag> {{ row.action }} {{ row.target }}</div>
              </template>
            </el-table-column>
            <el-table-column label="原数据 → 新数据" min-width="160">
              <template #default="{ row }">
                <span class="old-val">{{ shortVal(row.beforeValue) }}</span> → <span class="new-val">{{ shortVal(row.afterValue) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination v-model:current-page="logs.page" class="pager" layout="total, prev, pager, next" :page-size="15"
                         :total="logs.total" small @current-change="loadLogs" />
        </el-tab-pane>
        <el-tab-pane :label="`留底版本（${monthNum}月${selectedDay}号）`" name="snapshots">
          <el-empty v-if="!snapshots.length" description="这一天还没有保存过战报" />
          <el-collapse v-else>
            <el-collapse-item v-for="s in snapshots" :key="s.id" :name="s.id">
              <template #title>
                <b>第 {{ s.version }} 版</b>&nbsp;· {{ s.savedBy }} 保存于 {{ fmtTime(s.savedAt) }}
                &nbsp;<el-tag size="small" type="success">达标 {{ s.parsed.pass }}</el-tag>
                &nbsp;<el-tag size="small" type="danger">未达标 {{ s.parsed.fail }}</el-tag>
              </template>
              <el-table :data="s.parsed.items" size="small" border>
                <el-table-column label="指标" prop="label" width="60" />
                <el-table-column label="数值" width="90">
                  <template #default="{ row }">{{ row.value === null ? '—' : fmtNum(row.value) + (row.unit || '') }}</template>
                </el-table-column>
                <el-table-column label="班组自定" prop="teamTarget" min-width="100" />
                <el-table-column label="结果" width="80">
                  <template #default="{ row }">
                    <span :class="'txt-' + row.status">{{ STATUS_TEXT[row.status] }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="首次填报 / 最后保存" min-width="120">
                  <template #default="{ row }">{{ row.createdBy || '—' }} / {{ row.updatedBy || '—' }}</template>
                </el-table-column>
              </el-table>
            </el-collapse-item>
          </el-collapse>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, TrendCharts } from '@element-plus/icons-vue'
import api from '@/api'
import EChart from '@/components/EChart.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import { fmtNum, fmtTime, todayStr, STATUS_TEXT } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const processId = Number(route.params.id)

const today = todayStr()
const initDate = /^\d{4}-\d{2}-\d{2}$/.test(route.query.date || '') ? route.query.date : today
const month = ref(initDate.slice(0, 7))
const selectedDay = ref(Number(initDate.slice(8)))

const d = ref(null)
const loading = ref(false)

const processName = computed(() => (d.value ? d.value.process.name : ''))
const owners = computed(() => (d.value ? d.value.owners.map(o => o.username).join('、') : ''))
const monthNum = computed(() => Number(month.value.slice(5)))
const days = computed(() => (d.value ? Array.from({ length: d.value.daysInMonth }, (_, i) => i + 1) : []))

// ---------------- 走势表数据 ----------------
// grid / original：key = "日|指标" → 输入框里的文字；original 用来判断哪些格子改过
const grid = reactive({})
const original = reactive({})
const meta = reactive({})
const key = (day, metric) => `${day}|${metric}`
const dateOf = day => `${month.value}-${String(day).padStart(2, '0')}`
// “今天”以服务器日期为准（后端也按服务器日期判断），没加载到时先用本机日期
const serverToday = computed(() => (d.value && d.value.today ? String(d.value.today) : today))
const isFuture = day => dateOf(day) > serverToday.value
/** 服务器“昨天”的日期 yyyy-MM-dd */
const serverYesterday = computed(() => {
  const [y, m, dd] = serverToday.value.split('-').map(Number)
  const t = new Date(y, m - 1, dd - 1)
  return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
})

/**
 * 负责人视角的锁定（规则在“系统设置 → 数据锁定”里配置，后端按同样规则拦截）：
 * - 今天：ownerTodayEditable（当天锁定时间开关开着且过了“当天 xx:xx”就锁）；
 * - 昨天：历史数据锁定关着就能改；开着的话，只有“次日 xx:xx”之前能改（ownerYesterdayEditable）；
 * - 更早：历史数据锁定开着就锁。
 * 系统管理员 / 管理员（canEditPast）不受限制。
 */
const isLocked = day => {
  if (isFuture(day) || !d.value || d.value.canEditPast) return false
  const date = dateOf(day)
  if (date === serverToday.value) return !d.value.ownerTodayEditable
  if (date === serverYesterday.value) return !d.value.ownerYesterdayEditable
  return d.value.historyLock
}

const lockTip = computed(() => {
  const v = d.value
  if (!v || !v.canEdit || v.canEditPast || (!v.historyLock && !v.lockEnabled)) return ''
  const parts = []
  if (!v.lockEnabled) parts.push('今天全天可填')
  else parts.push(v.ownerTodayEditable ? `今天的数据${v.lockRule}锁定` : `今天的数据已按“${v.lockRule}”锁定`)
  if (v.historyLock && v.ownerYesterdayEditable) parts.push(`昨天的数据还能补填（${v.lockRule}锁定）`)
  parts.push(v.historyLock ? '更早的数据已锁定' : '以前的数据可以修改')
  return parts.join('；') + '；需要更正请联系管理员'
})
const isEditable = day => !!d.value && d.value.canEdit && !isFuture(day) && !isLocked(day)

function fillGrid() {
  for (const k of Object.keys(grid)) delete grid[k]
  for (const k of Object.keys(original)) delete original[k]
  for (const k of Object.keys(meta)) delete meta[k]
  for (const day of days.value) {
    for (const m of d.value.metrics) {
      grid[key(day, m.key)] = ''
      original[key(day, m.key)] = ''
    }
  }
  for (const r of d.value.records) {
    const k = key(Number(String(r.date).slice(8)), r.metric)
    grid[k] = fmtNum(r.value)
    original[k] = fmtNum(r.value)
    meta[k] = r
  }
}

const cellValue = (day, metric) => (grid[key(day, metric)] || '').trim()
const isDirty = (day, metric) => (grid[key(day, metric)] || '').trim() !== original[key(day, metric)]
const dirtyKeys = computed(() => Object.keys(grid).filter(k => (grid[k] || '').trim() !== original[k]))
const dirtyCount = computed(() => dirtyKeys.value.length)

const targetMap = computed(() => Object.fromEntries((d.value ? d.value.targets : []).map(t => [t.metric, t])))

/** 跟后端同一套判定：LE 当前值 ≤ 目标为达标，GE 当前值 ≥ 目标为达标 */
function statusOf(day, metric) {
  const raw = cellValue(day, metric)
  if (raw === '') return 'EMPTY'
  const v = Number(raw)
  if (Number.isNaN(v)) return 'INVALID'
  const t = targetMap.value[metric]
  if (!t || t.targetValue === null || t.targetValue === undefined) return 'NO_TARGET'
  return (t.compareOp === 'GE' ? v >= t.targetValue : v <= t.targetValue) ? 'PASS' : 'FAIL'
}

function cellTitle(day, metric) {
  const r = meta[key(day, metric)]
  const lines = []
  if (r) {
    lines.push(`首次填报：${r.createdBy || '—'}  ${fmtTime(r.createdAt)}`)
    lines.push(`最后保存：${r.updatedBy || '—'}  ${fmtTime(r.updatedAt)}`)
  }
  if (isDirty(day, metric)) lines.push(`未保存（原值：${original[key(day, metric)] || '空'}）`)
  return lines.join('\n')
}

/** 输入框失焦时整理一下：全角转半角、去空格 */
function normalize(day, metric) {
  const k = key(day, metric)
  grid[k] = (grid[k] || '').replace(/[０-９．－]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xfee0)).replace('。', '.').trim()
}

function resetGrid() {
  for (const k of Object.keys(grid)) grid[k] = original[k]
}

const savingGrid = ref(false)
async function saveGrid() {
  const cells = []
  for (const k of dirtyKeys.value) {
    const [day, metric] = k.split('|')
    const raw = (grid[k] || '').trim()
    if (raw !== '' && Number.isNaN(Number(raw))) {
      ElMessage.error(`${monthNum.value}月${day}号 ${targetMap.value[metric].label} 不是有效数字：${raw}`)
      return
    }
    cells.push({ date: dateOf(Number(day)), metric, value: raw === '' ? null : Number(raw) })
  }
  savingGrid.value = true
  try {
    const res = await api.saveRecords(processId, cells)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    await load()
  } finally {
    savingGrid.value = false
  }
}

// ---------------- 加载 ----------------
async function load() {
  loading.value = true
  try {
    const res = await api.detail(processId, month.value)
    if (!res.success) {
      ElMessage.error(res.message)
      router.replace('/processes')
      return
    }
    d.value = res.data
    fillGrid()
    resetBoard()
    if (selectedDay.value > d.value.daysInMonth) selectedDay.value = d.value.daysInMonth
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function onMonthChange(val) {
  if (dirtyCount.value && !(await confirmDiscard())) {
    month.value = d.value.month
    return
  }
  month.value = val
  // 切到当月默认选今天，切到以前的月份默认选最后一天
  selectedDay.value = val === today.slice(0, 7) ? Number(today.slice(8)) : 31
  await load()
}

async function confirmDiscard() {
  try {
    await ElMessageBox.confirm(`走势表还有 ${dirtyCount.value} 处修改没有保存，确定放弃吗？`, '未保存的修改', {
      type: 'warning', confirmButtonText: '放弃修改', cancelButtonText: '继续编辑'
    })
    return true
  } catch {
    return false
  }
}

onBeforeRouteLeave(async () => (dirtyCount.value ? await confirmDiscard() : true))

// ---------------- 六项目标 ----------------
const targetsVisible = ref(false)
const savingTargets = ref(false)
const targetForm = ref([])

function openTargets() {
  targetForm.value = d.value.targets.map(t => ({ ...t, targetValue: t.targetValue ?? undefined }))
  targetsVisible.value = true
}

async function saveTargets() {
  savingTargets.value = true
  try {
    const res = await api.saveTargets(processId, targetForm.value.map(t => ({
      metric: t.metric,
      baseline: t.baseline,
      teamTarget: t.teamTarget,
      unit: t.unit,
      compareOp: t.compareOp,
      targetValue: t.targetValue === undefined ? null : t.targetValue
    })))
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    targetsVisible.value = false
    const dirty = Object.fromEntries(dirtyKeys.value.map(k => [k, grid[k]]))
    await load()
    Object.assign(grid, dirty) // 保留走势表里还没保存的输入
  } finally {
    savingTargets.value = false
  }
}

// ---------------- 班组看板：四功能小组四个人 + 全员签名 + 日期 ----------------
const board = reactive({ members: [], signers: '', signDate: '' })
const boardEditing = ref(false)
const savingBoard = ref(false)

function resetBoard() {
  const b = d.value.board || {}
  const saved = b.members || []
  // 固定显示 4 个位置，没填的位置空着
  board.members = Array.from({ length: 4 }, (_, i) => ({
    photo: (saved[i] && saved[i].photo) || '',
    name: (saved[i] && saved[i].name) || '',
    role: (saved[i] && saved[i].role) || ''
  }))
  board.signers = b.signers || ''
  board.signDate = b.signDate || ''
}

function startBoardEdit() {
  resetBoard()
  if (!board.signDate) board.signDate = today
  boardEditing.value = true
}

watch(boardEditing, editing => {
  if (!editing && d.value) resetBoard()
})

async function saveBoard() {
  savingBoard.value = true
  try {
    const body = {
      members: board.members.map(m => ({ photo: m.photo || null, name: m.name || null, role: m.role || null })),
      signers: board.signers || null,
      signDate: board.signDate || null
    }
    const res = await api.saveBoard(processId, body)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    const res2 = await api.detail(processId, month.value)
    if (res2.success) d.value.board = res2.data.board
    boardEditing.value = false
  } finally {
    savingBoard.value = false
  }
}

// ---------------- 折线图 ----------------
function chartOption(t) {
  const values = days.value.map(day => {
    const raw = cellValue(day, t.metric)
    return raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw)
  })
  const pass = v => t.targetValue === null || t.targetValue === undefined || (t.compareOp === 'GE' ? v >= t.targetValue : v <= t.targetValue)
  return {
    grid: { left: 44, right: 16, top: 16, bottom: 26 },
    tooltip: {
      trigger: 'axis',
      formatter: ps => {
        const p = ps[0]
        return p.value === null || p.value === undefined ? `${p.name}：未填` : `${p.name}：${p.value}${t.unit || ''}`
      }
    },
    xAxis: { type: 'category', data: days.value.map(day => day + '号'), axisLabel: { interval: 4 }, boundaryGap: false },
    // 安全（事故起数）、品质（流出片数）是计数，刻度只取整数
    yAxis: {
      type: 'value',
      scale: true,
      minInterval: ['SAFETY', 'QUALITY'].includes(t.metric) ? 1 : undefined,
      splitLine: { lineStyle: { color: '#eef0f3' } }
    },
    series: [{
      type: 'line',
      data: values,
      connectNulls: true,
      symbolSize: 7,
      lineStyle: { width: 2, color: '#0070c0' },
      itemStyle: { color: p => (p.value !== null && p.value !== undefined && !pass(p.value) ? '#e23b3b' : '#0070c0') },
      markLine: t.targetValue === null || t.targetValue === undefined ? undefined : {
        symbol: 'none',
        lineStyle: { type: 'dashed', color: '#e6a23c' },
        label: { show: false }, // 目标值已经写在图表标题右侧
        tooltip: { show: false },
        data: [{ yAxis: t.targetValue }]
      }
    }]
  }
}

// ---------------- 修改记录 / 留底 ----------------
const historyVisible = ref(false)
const historyTab = ref('logs')
const logs = reactive({ list: [], total: 0, page: 1 })
const snapshots = ref([])

async function openHistory() {
  historyVisible.value = true
  logs.page = 1
  await Promise.all([loadLogs(), loadSnapshots()])
}

async function loadLogs() {
  const res = await api.processLogs(processId, { page: logs.page, size: 15 })
  if (res.success) {
    logs.list = res.data.list
    logs.total = res.data.total
  }
}

async function loadSnapshots() {
  const res = await api.snapshots(processId, dateOf(selectedDay.value))
  snapshots.value = res.success ? res.data.map(s => ({ ...s, parsed: JSON.parse(s.content || '{"items":[]}') })) : []
}

watch(selectedDay, () => {
  if (historyVisible.value) loadSnapshots()
})

/** 日志里的原 / 新数据：JSON 转成“字段: 值”的短文字 */
function shortVal(v) {
  if (v === null || v === undefined || v === '') return '空'
  if (v.startsWith('{')) {
    try {
      return Object.entries(JSON.parse(v)).map(([k, val]) => `${k}: ${val === '' ? '空' : val}`).join('；')
    } catch {
      return v
    }
  }
  return v
}
</script>

<style scoped>
.crumb-link {
  color: #fff;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.owner-line {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  opacity: 0.95;
}

.sheet {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.left {
  width: 520px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid var(--dr-border);
}

.right {
  flex: 1;
  min-width: 0;
}

@media (max-width: 1280px) {
  .sheet {
    flex-direction: column;
  }

  .left {
    width: 100%;
  }

  .right {
    width: 100%;
  }
}

/* ---------- 六项自主保证书（截图左上） ---------- */
.cert {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.cert th,
.cert td {
  border: 1px solid #b7c6d6;
  padding: 6px 8px;
  text-align: center;
}

.cert thead th {
  background: var(--dr-blue);
  color: #fff;
  font-weight: 600;
}

.cert-title {
  position: relative;
  font-size: 19px !important;
  padding: 10px 8px !important;
  letter-spacing: 2px;
}

.title-btn {
  position: absolute;
  right: 8px;
  top: 50%;
  transform: translateY(-50%);
  letter-spacing: 0;
}

.cur-date {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 400;
  opacity: 0.85;
}

.cert-metric {
  background: #edf3f9;
  font-weight: 700;
}

.cert tbody tr:nth-child(even) td:not(.cert-metric) {
  background: #f7fafc;
}

.cur-PASS { color: var(--dr-pass); font-weight: 700; }
.cur-FAIL { color: var(--dr-fail); font-weight: 700; }
.muted { color: #b0b6bf; }

/* ---------- 班组看板（截图左下蓝色区域） ---------- */
.board {
  background: var(--dr-blue-bright);
  color: #fff;
}

.board-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 14px;
  font-weight: 700;
  border-bottom: 1px solid rgba(255, 255, 255, 0.25);
}

.board-btns {
  display: flex;
  gap: 6px;
}

/* 四功能小组：四个人一排，每人 照片 + 姓名 + 职能；窄屏两两一排 */
.board-body {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
}

@media (max-width: 640px) {
  .board-body {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.member {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  text-align: center;
}

/* 人像照片用竖版比例 */
.member :deep(.img),
.member :deep(.placeholder) {
  aspect-ratio: 3 / 4;
}

.member :deep(.actions) {
  justify-content: center;
}

.member-name {
  font-size: 15px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-role {
  align-self: center;
  max-width: 100%;
  padding: 0 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.2);
  font-size: 12px;
  line-height: 20px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 全员签名、日期：上下两行 */
.sign-block {
  display: grid;
  gap: 14px;
  padding: 18px 14px 8px;
  background: #f5f8fa;
}

.sign-row {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  font-size: 14px;
  color: #4a5562;
}

.sign-label {
  flex-shrink: 0;
  width: 5em;
  text-align: right;
}

.sign-line {
  flex: 1;
  min-width: 0;
  min-height: 22px;
  border-bottom: 1px solid #333;
  color: #1f2d3d;
}

.sign-input {
  flex: 1;
  min-width: 0;
}

.board-meta {
  padding: 4px 14px 10px;
  background: #f5f8fa;
  font-size: 12px;
  color: #8a96a3;
}

/* ---------- 每日走势表（截图右侧绿色表格） ---------- */
.grid-tools {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
}

.grid-tip {
  font-size: 12px;
  color: #6b7785;
}

.tip-pass { color: var(--dr-pass); font-weight: 700; }
.tip-fail { color: var(--dr-fail); font-weight: 700; }

.grid-btns {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dirty-tip {
  font-size: 13px;
  color: #d48806;
}

.grid-wrap {
  overflow-x: auto;
  border: 1px solid #0a5a9a;
  background: var(--dr-sheet-green);
}

.trend {
  border-collapse: collapse;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.trend th {
  background: var(--dr-blue);
  color: #fff;
  font-weight: 700;
  border: 1px solid #0a5a9a;
  padding: 6px 4px;
  white-space: nowrap;
}

.month-cell {
  font-size: 15px;
}

.trend-title {
  font-size: 19px;
  letter-spacing: 3px;
  text-align: left;
}

/* 31 列很宽，标题贴着可视区域左侧，横向滚动时也一直看得到 */
.trend-title span {
  position: sticky;
  left: 72px;
  padding-left: 16px;
}

.row-head {
  position: sticky;
  left: 0;
  z-index: 1;
  min-width: 52px;
}

.day-head {
  min-width: 50px;
  cursor: pointer;
}

.day-head.selected {
  background: #ffb020;
  color: #10244d;
}

/* 负责人视角下已锁定的历史日期：表头加锁，格子不可编辑 */
.day-head.locked::after {
  content: " 🔒";
  font-size: 10px;
}

.cell.locked {
  cursor: not-allowed;
}

.lock-icon {
  vertical-align: -2px;
  margin-right: 4px;
  color: #d48806;
}

.day-head.future {
  cursor: default;
  opacity: 0.55;
}

.cell {
  border: 1px solid #b9d7bc;
  height: 32px;
  padding: 0;
  text-align: center;
  background: #fff;
}

.cell.selected {
  box-shadow: inset 0 0 0 2px #ffb020;
}

.cell input {
  width: 50px;
  height: 30px;
  border: none;
  background: transparent;
  text-align: center;
  font: inherit;
  color: inherit;
  outline: none;
}

.cell input:focus {
  background: #fffbe6;
  box-shadow: inset 0 0 0 2px #409eff;
}

.st-EMPTY { background: #fff; }
.st-PASS { background: var(--dr-pass-bg); color: #1d7a43; }
.st-FAIL { background: var(--dr-fail-bg); color: var(--dr-fail); font-weight: 700; }
.st-NO_TARGET { background: #fffbe6; }
.st-INVALID { background: #ffe58f; color: #ad4e00; }

.cell.dirty {
  position: relative;
}

.cell.dirty::after {
  content: "";
  position: absolute;
  top: 0;
  right: 0;
  border-top: 7px solid #e6a23c;
  border-left: 7px solid transparent;
}

/* ---------- 折线图 ---------- */
.charts {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}

.chart-card {
  background: #fff;
  border-radius: 6px;
  padding: 10px 12px 4px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  min-width: 0;
}

.chart-title {
  display: flex;
  justify-content: space-between;
  font-weight: 700;
  font-size: 14px;
}

.chart-target {
  font-weight: 400;
  font-size: 12px;
  color: #b7791f;
}

/* ---------- 弹窗 / 抽屉 ---------- */
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: #6b7785;
}

.judge {
  display: flex;
  gap: 6px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.old-val {
  color: #8a96a3;
  text-decoration: line-through;
}

.new-val {
  color: #1f2d3d;
  font-weight: 600;
}

.txt-PASS { color: var(--dr-pass); }
.txt-FAIL { color: var(--dr-fail); font-weight: 700; }
.txt-EMPTY { color: #b0b6bf; }
</style>
