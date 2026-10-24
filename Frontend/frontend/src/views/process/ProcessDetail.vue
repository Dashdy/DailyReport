<template>
  <div v-loading="loading" class="detail-page">
    <div class="page-banner">
      <div>
        <div class="crumb">
          <router-link to="/processes" class="crumb-link">工序战报</router-link> / {{ processName }}
        </div>
        <h1>{{ fullName }} · 每日战报</h1>
        <div class="owner-line">
          负责人：{{ owners || '未分配' }}
          <el-tag v-if="d && d.canEdit" type="success" size="small" effect="dark">可编辑</el-tag>
          <el-tag v-else-if="d && d.process.deleted" type="danger" size="small" effect="dark">已删除（只读）</el-tag>
          <el-tag v-else-if="d && !d.process.active" type="danger" size="small" effect="dark">已停用（只读）</el-tag>
          <el-tag v-else-if="d" type="info" size="small" effect="dark">只读：你不是该工序负责人</el-tag>
        </div>
      </div>
      <div class="banner-tools">
        <el-date-picker id="detail-month" v-model="month" type="month" value-format="YYYY-MM" :clearable="false"
                        :disabled-date="dt => dt > new Date()" @change="onMonthChange" />
        <el-button :icon="TrendCharts" type="primary" @click="router.push({ path: `/processes/${processId}/carousel`, query: { month } })">查看趋势图</el-button>
        <el-button v-if="d && d.canEdit" :icon="Promotion" type="success" @click="openPush">推送到企业微信</el-button>
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
                {{ fullName }} 六项自主保证书
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
            <!-- 指标名按整项结果着色（拆了子项的：所有子项都达标才算达标）；本班当前每个子项单独着色 -->
            <tr v-for="t in d.targets" :key="t.metric">
              <td class="cert-metric" :class="'m-' + metricStatusOf(selectedDay, t)" :title="METRIC_TIP[metricStatusOf(selectedDay, t)]">
                {{ t.label }}
              </td>
              <td>{{ t.baseline || '—' }}</td>
              <td>{{ t.teamTarget || '—' }}</td>
              <td class="cur-cell">
                <div v-for="it in t.items" :key="it.id" class="cur-line" :class="'cur-' + statusOf(selectedDay, it.id)">
                  <span v-if="t.items.length > 1" class="cur-name">{{ it.name }}</span>
                  <template v-if="cellValue(selectedDay, it.id) !== ''">
                    {{ cellValue(selectedDay, it.id) }}{{ unitText(it.unit) }}
                  </template>
                  <span v-else class="muted">未填</span>
                </div>
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

        <div v-if="d.board && d.board.updatedBy" class="board-meta">
          看板最后保存：{{ d.board.updatedBy }} · {{ fmtTime(d.board.updatedAt) }}
        </div>

        <!-- 今日行动措施 / 今日亮点 / 明日行动计划：跟着走势表选中的日期走，锁定规则跟走势表一样 -->
        <div class="notes">
          <div class="notes-head">
            <span>行动与亮点<span class="notes-date">{{ monthNum }}月{{ selectedDay }}号</span></span>
            <template v-if="isEditable(selectedDay)">
              <el-button v-if="!noteEditing" size="small" @click="startNoteEdit">编辑</el-button>
              <span v-else class="board-btns">
                <el-button size="small" @click="noteEditing = false">取消</el-button>
                <el-button size="small" type="primary" :loading="savingNote" @click="saveNote">保存</el-button>
              </span>
            </template>
            <span v-else-if="d.canEdit && isLocked(selectedDay)" class="notes-lock">🔒 已锁定</span>
          </div>
          <div v-for="f in NOTE_FIELDS" :key="f.key" class="note">
            <div class="note-title">【{{ f.label }}】</div>
            <el-input v-if="noteEditing" :id="'note-' + f.key" v-model="noteForm[f.key]" type="textarea"
                      :autosize="{ minRows: 2, maxRows: 8 }" maxlength="1000" :placeholder="f.placeholder" />
            <div v-else class="note-text" :class="{ muted: !currentNote[f.key] }">{{ currentNote[f.key] || '无' }}</div>
          </div>
          <div v-if="currentNote.updatedBy" class="board-meta note-meta">
            最后保存：{{ currentNote.updatedBy }} · {{ fmtTime(currentNote.updatedAt) }}
          </div>
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
                <th class="month-cell" colspan="2">{{ monthNum }}月</th>
                <th :colspan="days.length" class="trend-title"><span>每日走势表</span></th>
              </tr>
              <tr>
                <th class="goal-head">班组目标</th>
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
              <!-- 每个子项一行；拆了子项的指标最后多一行“整项”：所有子项都达标才打 ✓ -->
              <tr v-for="r in gridRows" :key="r.key" :class="{ 'metric-end': r.last, 'sum-row': r.type === 'sum' }">
                <!-- 班组目标（参考，放最左边）：填数时直接对照，红绿就是按下面这行小字的规则判定的 -->
                <!-- 班组目标（由子项自动生成）：拆了子项的上面一行是子项名、下面是它的目标 -->
                <td v-if="r.type === 'item'" class="goal-cell">
                  <div v-if="r.multi" class="goal-name">{{ r.it.name }}</div>
                  <div class="goal-text">{{ ruleText(r.it) }}</div>
                </td>
                <td v-else class="goal-cell sum-goal">整项（全部达标）</td>
                <th v-if="r.first" class="row-head" :rowspan="r.span">{{ r.t.label }}</th>
                <template v-if="r.type === 'item'">
                  <td v-for="day in days" :key="day"
                      :class="['cell', 'st-' + statusOf(day, r.it.id), { dirty: isDirty(day, r.it.id), selected: day === selectedDay, locked: d.canEdit && isLocked(day) }]"
                      :title="cellTitle(day, r.it.id)">
                    <input v-if="isEditable(day)" :id="`cell-${day}-${r.it.id}`" v-model="grid[key(day, r.it.id)]"
                           inputmode="decimal" :aria-label="`${monthNum}月${day}号 ${r.it.label}`"
                           @focus="selectedDay = day" @change="normalize(day, r.it.id)" />
                    <span v-else>{{ grid[key(day, r.it.id)] }}</span>
                  </td>
                </template>
                <template v-else>
                  <td v-for="day in days" :key="day" class="sum-cell" :class="['sum-' + metricStatusOf(day, r.t), { selected: day === selectedDay }]"
                      :title="METRIC_TIP[metricStatusOf(day, r.t)]">
                    {{ SUM_MARK[metricStatusOf(day, r.t)] }}
                  </td>
                </template>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 折线走势图 -->
        <div class="charts">
          <div v-for="t in d.targets" :key="t.metric" class="chart-card">
            <div class="chart-title">
              <span>{{ t.label }}走势</span>
              <span class="chart-target">目标 {{ metricRuleText(t) }}</span>
            </div>
            <EChart :option="chartOption(t)" :height="chartHeight(t)" />
          </div>
        </div>
      </div>
    </div>

    <!-- ================= 编辑六项目标 ================= -->
    <el-dialog v-model="targetsVisible" title="编辑六项自主保证书" width="1020px" top="5vh">
      <p class="dialog-tip">
        <b>班组目标</b>只在这里填一次：保证书的“班组自定”、走势表、推送都按它显示，系统也按它判红绿。
        一项可以拆成多个子项（如 成本 → 铜球、锡球），名称只写子项名，目标值填在后面；
        每个子项单独判定红绿，<b>所有子项都达标，这项才算达标</b>。
      </p>
      <div class="tf">
        <div class="tf-row tf-head">
          <div>指标</div><div>公司底线</div><div>班组目标：子项名称 / 单位 / 达标判定（当前值 … 目标值）</div>
        </div>
        <div v-for="t in targetForm" :key="t.metric" class="tf-row">
          <div class="tf-metric">{{ t.label }}</div>
          <div><el-input v-model="t.baseline" maxlength="100" /></div>
          <div class="tf-items">
            <div v-for="(it, i) in t.items" :key="it.key" class="tf-item">
              <el-input :id="`tf-${t.metric}-name-${i}`" v-model="it.name" maxlength="30" style="width: 136px"
                        :placeholder="t.items.length > 1 ? '子项名称，如 锡球' : '不拆可不填'" />
              <el-input :id="`tf-${t.metric}-unit-${i}`" v-model="it.unit" maxlength="20" placeholder="单位" style="width: 76px" />
              <el-select v-model="it.compareOp" style="width: 64px">
                <el-option label="≤" value="LE" />
                <el-option label="≥" value="GE" />
              </el-select>
              <el-input-number :id="`tf-${t.metric}-value-${i}`" v-model="it.targetValue" :controls="false" :precision="4" style="width: 110px" />
              <el-button link type="danger" :disabled="t.items.length === 1" title="至少保留一个子项" @click="t.items.splice(i, 1)">删除</el-button>
            </div>
            <el-button link type="primary" :icon="Plus" :disabled="t.items.length >= 6" @click="addItem(t)">添加子项</el-button>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="targetsVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingTargets" @click="saveTargets">保存</el-button>
      </template>
    </el-dialog>

    <!-- ================= 推送到企业微信 ================= -->
    <el-dialog v-model="pushVisible" :title="`推送 ${monthNum}月${selectedDay}号 战报到企业微信`" width="640px" top="6vh">
      <div v-loading="pushLoading">
        <template v-if="pushData">
          <div class="push-to">
            <div><b>发给：</b>
              <template v-if="pushData.appConfigured">
                {{ pushData.receivers.length ? pushData.receivers.join('、') : '（没有已登记企业微信账号的人）' }}
              </template>
              <span v-else class="muted-text">ID助手未配置</span>
              <template v-if="pushData.webhookConfigured">，以及战报群（群机器人）</template>
            </div>
            <div v-if="pushData.missing.length" class="warn-text">
              没登记企业微信账号、收不到：{{ pushData.missing.join('、') }}（管理员在“用户管理”里填）
            </div>
            <div v-if="!pushData.appConfigured && !pushData.webhookConfigured" class="warn-text">
              企业微信推送还没配置好，请联系管理员在“系统设置”里配置。
            </div>
          </div>
          <div class="push-preview">
            <div v-for="(line, i) in previewLines" :key="i" class="pv-line" :class="{ quote: line.quote, blank: !line.parts.length }">
              <template v-for="(part, j) in line.parts" :key="j">
                <a v-if="part.href" :href="part.href" target="_blank" rel="noopener">{{ part.text }}</a>
                <span v-else :class="[part.color ? 'pv-' + part.color : '', { 'pv-bold': part.bold }]">{{ part.text }}</span>
              </template>
            </div>
          </div>
          <div v-if="pushData.history.length" class="push-history">
            这一天已推送 {{ pushData.history.filter(h => h.success).length }} 次，最近一次：
            {{ fmtTime(pushData.history[0].createdAt) }} · {{ pushData.history[0].operator }} ·
            <span :class="pushData.history[0].success ? 'ok-text' : 'warn-text'">{{ pushData.history[0].success ? '成功' : '失败' }}</span>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="pushVisible = false">取消</el-button>
        <el-button type="success" :loading="pushing" :disabled="!pushData" @click="doPush">确认推送</el-button>
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
                <el-table-column label="指标" prop="label" width="96" />
                <el-table-column label="数值" width="90">
                  <template #default="{ row }">{{ row.value === null ? '—' : fmtNum(row.value) + (row.unit || '') }}</template>
                </el-table-column>
                <el-table-column label="班组目标" prop="teamTarget" min-width="100" />
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
import { Clock, Plus, Promotion, TrendCharts } from '@element-plus/icons-vue'
import api from '@/api'
import EChart from '@/components/EChart.vue'
import ImageUpload from '@/components/ImageUpload.vue'
import { fmtNum, fmtTime, todayStr, STATUS_TEXT } from '@/utils/format'
import { combine, escapeHtml, groupByUnit, judge, metricRuleText, ruleText, SERIES_COLORS } from '@/utils/metric'

/** “整项”那一行的标记 */
const SUM_MARK = { PASS: '✓', FAIL: '✗', EMPTY: '', NO_TARGET: '', INVALID: '?' }
const METRIC_TIP = {
  PASS: '这一项达标（所有子项都达标）',
  FAIL: '这一项未达标（有子项未达标）',
  EMPTY: '这一项还没填完',
  NO_TARGET: '有子项还没设判定值',
  INVALID: '有格子不是有效数字'
}
const unitText = unit => (!unit ? '' : unit === '%' ? '%' : ' ' + unit)

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
// 标题用：名字本身以“工序”结尾就不再加“工序”（避免“新工序测试工序”这种重复）
const fullName = computed(() => (processName.value.endsWith('工序') ? processName.value : processName.value + '工序'))
const owners = computed(() => (d.value ? d.value.owners.map(o => o.username).join('、') : ''))
const monthNum = computed(() => Number(month.value.slice(5)))
const days = computed(() => (d.value ? Array.from({ length: d.value.daysInMonth }, (_, i) => i + 1) : []))

// ---------------- 走势表数据 ----------------
// grid / original：key = "日|子项id" → 输入框里的文字；original 用来判断哪些格子改过
const grid = reactive({})
const original = reactive({})
const meta = reactive({})
const key = (day, itemId) => `${day}|${itemId}`

/**
 * 走势表的行：每个子项一行；拆了子项的指标最后再加一行“整项”。
 * first / span 用来让“指标”那一格跨行合并，last 用来画指标之间的分隔线。
 */
const gridRows = computed(() => {
  if (!d.value) return []
  const rows = []
  for (const t of d.value.targets) {
    const multi = t.items.length > 1
    const span = t.items.length + (multi ? 1 : 0)
    t.items.forEach((it, i) => rows.push({
      key: 'i' + it.id, type: 'item', t, it, multi, first: i === 0, span, last: !multi && i === t.items.length - 1
    }))
    if (multi) rows.push({ key: 's' + t.metric, type: 'sum', t, multi, first: false, span, last: true })
  }
  return rows
})
const itemMap = computed(() => Object.fromEntries((d.value ? d.value.targets : []).flatMap(t => t.items).map(it => [it.id, it])))
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
    for (const id of Object.keys(itemMap.value)) {
      grid[key(day, id)] = ''
      original[key(day, id)] = ''
    }
  }
  for (const r of d.value.records) {
    const k = key(Number(String(r.date).slice(8)), r.itemId)
    grid[k] = fmtNum(r.value)
    original[k] = fmtNum(r.value)
    meta[k] = r
  }
}

const cellValue = (day, itemId) => (grid[key(day, itemId)] || '').trim()
const isDirty = (day, itemId) => (grid[key(day, itemId)] || '').trim() !== (original[key(day, itemId)] || '')
const dirtyKeys = computed(() => Object.keys(grid).filter(k => (grid[k] || '').trim() !== (original[k] || '')))
const dirtyCount = computed(() => dirtyKeys.value.length)

/** 某天某个子项的结果（跟后端同一套判定），输入框里不是数字时为 INVALID */
function statusOf(day, itemId) {
  const raw = cellValue(day, itemId)
  if (raw === '') return 'EMPTY'
  const v = Number(raw)
  if (Number.isNaN(v)) return 'INVALID'
  return judge(v, itemMap.value[itemId])
}

/** 某天一整项指标的结果：所有子项都达标才算达标 */
const metricStatusOf = (day, t) => combine(t.items.map(it => statusOf(day, it.id)))

function cellTitle(day, itemId) {
  const r = meta[key(day, itemId)]
  const lines = []
  if (r) {
    lines.push(`首次填报：${r.createdBy || '—'}  ${fmtTime(r.createdAt)}`)
    lines.push(`最后保存：${r.updatedBy || '—'}  ${fmtTime(r.updatedAt)}`)
  }
  if (isDirty(day, itemId)) lines.push(`未保存（原值：${original[key(day, itemId)] || '空'}）`)
  return lines.join('\n')
}

/** 输入框失焦时整理一下：全角转半角、去空格 */
function normalize(day, itemId) {
  const k = key(day, itemId)
  grid[k] = (grid[k] || '').replace(/[０-９．－]/g, c => String.fromCharCode(c.charCodeAt(0) - 0xfee0)).replace('。', '.').trim()
}

function resetGrid() {
  for (const k of Object.keys(grid)) grid[k] = original[k]
}

const savingGrid = ref(false)
async function saveGrid() {
  const cells = []
  for (const k of dirtyKeys.value) {
    const [day, itemId] = k.split('|')
    const raw = (grid[k] || '').trim()
    const item = itemMap.value[itemId]
    if (!item) continue // 子项刚被删掉，这一格不用保存
    if (raw !== '' && Number.isNaN(Number(raw))) {
      ElMessage.error(`${monthNum.value}月${day}号 ${item.label} 不是有效数字：${raw}`)
      return
    }
    cells.push({ date: dateOf(Number(day)), itemId: Number(itemId), value: raw === '' ? null : Number(raw) })
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

let itemSeq = 0
function openTargets() {
  targetForm.value = d.value.targets.map(t => ({
    metric: t.metric,
    label: t.label,
    baseline: t.baseline,
    items: t.items.map(it => ({ ...it, key: 'k' + it.id, targetValue: it.targetValue ?? undefined }))
  }))
  targetsVisible.value = true
}

function addItem(t) {
  const last = t.items[t.items.length - 1]
  // 新子项默认沿用上一个的单位和判定方向，通常同一项的子项是同一类
  t.items.push({ id: null, key: 'n' + ++itemSeq, name: '', unit: last ? last.unit : '', compareOp: last ? last.compareOp : 'LE', targetValue: undefined })
}

async function saveTargets() {
  for (const t of targetForm.value) {
    const names = t.items.map(it => (it.name || '').trim())
    if (t.items.length > 1 && names.some(n => !n)) {
      ElMessage.warning(`“${t.label}”拆成了 ${t.items.length} 个子项，每个子项都要填名称（如 锡球、铜球）`)
      return
    }
    if (new Set(names.filter(Boolean)).size !== names.filter(Boolean).length) {
      ElMessage.warning(`“${t.label}”的子项名称有重复`)
      return
    }
  }
  // 删掉的子项如果已经填过数据，提醒一下：数据不会丢，只是页面上不再显示
  const kept = new Set(targetForm.value.flatMap(t => t.items.map(it => it.id)).filter(Boolean))
  const removed = d.value.targets.flatMap(t => t.items).filter(it => !kept.has(it.id))
  if (removed.length) {
    try {
      await ElMessageBox.confirm(
        `要删除子项：${removed.map(it => it.label).join('、')}。删除后走势表和图表里不再显示，已经填过的数据会保留在系统里（修改记录可查）。确定吗？`,
        '删除子项', { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '再想想' })
    } catch {
      return
    }
  }
  savingTargets.value = true
  try {
    const res = await api.saveTargets(processId, targetForm.value.map(t => ({
      metric: t.metric,
      baseline: t.baseline, // 班组目标由子项生成，不传文字，库里原来写的保持不变
      items: t.items.map(it => ({
        id: it.id || null,
        name: (it.name || '').trim() || null,
        unit: it.unit,
        compareOp: it.compareOp,
        targetValue: it.targetValue === undefined ? null : it.targetValue
      }))
    })))
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    targetsVisible.value = false
    const dirty = Object.fromEntries(dirtyKeys.value.map(k => [k, grid[k]]))
    await load()
    // 保留走势表里还没保存的输入（被删掉的子项那几格不要了）
    for (const [k, v] of Object.entries(dirty)) {
      if (k in grid) grid[k] = v
    }
  } finally {
    savingTargets.value = false
  }
}

// ---------------- 今日行动措施 / 今日亮点 / 明日行动计划 ----------------
const NOTE_FIELDS = [
  { key: 'actions', label: '今日行动措施', placeholder: '如：1.结存不足，找前站要板，提升机台嫁动率' },
  { key: 'highlights', label: '今日亮点', placeholder: '没有可以不填' },
  { key: 'plans', label: '明日行动计划', placeholder: '如：1.重点提升机台嫁动率' }
]
const currentNote = computed(() => {
  const date = dateOf(selectedDay.value)
  return (d.value && (d.value.notes || []).find(n => String(n.reportDate) === date)) || {}
})
const noteEditing = ref(false)
const savingNote = ref(false)
const noteForm = reactive({ actions: '', highlights: '', plans: '' })

function startNoteEdit() {
  for (const f of NOTE_FIELDS) noteForm[f.key] = currentNote.value[f.key] || ''
  noteEditing.value = true
}

// 换了日期就退出编辑，免得把这天写的内容存到另一天
watch(selectedDay, () => { noteEditing.value = false })

async function saveNote() {
  savingNote.value = true
  try {
    const res = await api.saveNote(processId, { date: dateOf(selectedDay.value), ...noteForm })
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    const res2 = await api.detail(processId, month.value)
    if (res2.success) d.value.notes = res2.data.notes
    noteEditing.value = false
  } finally {
    savingNote.value = false
  }
}

// ---------------- 班组看板：四功能小组四个人 ----------------
const board = reactive({ members: [] })
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
}

function startBoardEdit() {
  resetBoard()
  boardEditing.value = true
}

watch(boardEditing, editing => {
  if (!editing && d.value) resetBoard()
})

async function saveBoard() {
  savingBoard.value = true
  try {
    const body = {
      members: board.members.map(m => ({ photo: m.photo || null, name: m.name || null, role: m.role || null }))
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
// 每个子项一条线；单位一样的子项画在同一张图里，单位不一样的上下分开画（刻度差太多会挤成一条线）
const CHART_PART = 150 // 分开画时每张小图的高度

function chartHeight(t) {
  const n = groupByUnit(t.items).length
  if (n > 1) return n * CHART_PART + 30 + 'px'
  return t.items.length > 1 ? '224px' : '200px'
}

function chartOption(t) {
  const multi = t.items.length > 1
  const groups = groupByUnit(t.items)
  const legendTop = multi ? 24 : 0
  const partHeight = groups.length > 1 ? CHART_PART : 200 - 16 - 26
  const xData = days.value.map(day => day + '号')
  const grids = [], xAxis = [], yAxis = [], series = []
  groups.forEach((g, gi) => {
    const top = 16 + legendTop + gi * partHeight
    grids.push({ left: 44, right: 16, top, height: partHeight - (groups.length > 1 ? 30 : 0) })
    xAxis.push({
      type: 'category', data: xData, gridIndex: gi, boundaryGap: false,
      axisLabel: { interval: 4, show: gi === groups.length - 1 }
    })
    // 安全（事故起数）、品质（流出片数）是计数，刻度只取整数
    yAxis.push({
      type: 'value', gridIndex: gi, scale: true,
      name: groups.length > 1 ? g.unit : '', nameTextStyle: { color: '#8a96a3', align: 'left' },
      minInterval: ['SAFETY', 'QUALITY'].includes(t.metric) ? 1 : undefined,
      splitLine: { lineStyle: { color: '#eef0f3' } }
    })
    for (const it of g.items) {
      const color = multi ? SERIES_COLORS[t.items.indexOf(it) % SERIES_COLORS.length] : '#0070c0'
      const hasTarget = it.targetValue !== null && it.targetValue !== undefined
      series.push({
        type: 'line',
        name: it.name || t.label,
        color, // 图例的颜色
        xAxisIndex: gi,
        yAxisIndex: gi,
        data: days.value.map(day => {
          const raw = cellValue(day, it.id)
          return raw === '' || Number.isNaN(Number(raw)) ? null : Number(raw)
        }),
        connectNulls: true,
        symbolSize: 7,
        lineStyle: { width: 2, color },
        // 未达标的点标红：每个子项按自己的目标判定
        itemStyle: { color: p => (judge(p.value ?? null, it) === 'FAIL' ? '#e23b3b' : color) },
        markLine: !hasTarget ? undefined : {
          symbol: 'none',
          lineStyle: { type: 'dashed', color: multi ? color : '#e6a23c' },
          label: { show: false }, // 目标值已经写在图表标题右侧
          tooltip: { show: false },
          data: [{ yAxis: it.targetValue }]
        }
      })
    }
  })
  return {
    grid: grids,
    legend: multi ? { top: 0, itemHeight: 8, textStyle: { fontSize: 12 } } : undefined,
    tooltip: {
      trigger: 'axis',
      formatter: ps => {
        const lines = ps.map(p => {
          const it = t.items.find(x => (x.name || t.label) === p.seriesName) || {}
          const val = p.value === null || p.value === undefined ? '未填' : p.value + (it.unit || '')
          return multi ? `${p.marker}${escapeHtml(p.seriesName)}：${escapeHtml(val)}` : escapeHtml(val)
        })
        return multi ? `${ps[0].name}<br/>${lines.join('<br/>')}` : `${ps[0].name}：${lines[0]}`
      }
    },
    xAxis,
    yAxis,
    series
  }
}

// ---------------- 推送到企业微信 ----------------
const pushVisible = ref(false)
const pushLoading = ref(false)
const pushing = ref(false)
const pushData = ref(null)

async function openPush() {
  if (dirtyCount.value) {
    ElMessage.warning('走势表还有没保存的修改，先保存再推送')
    return
  }
  pushVisible.value = true
  pushLoading.value = true
  pushData.value = null
  try {
    const res = await api.pushPreview(processId, dateOf(selectedDay.value))
    if (res.success) pushData.value = res.data
    else ElMessage.error(res.message)
  } finally {
    pushLoading.value = false
  }
}

async function doPush() {
  pushing.value = true
  try {
    const res = await api.pushProcess(processId, dateOf(selectedDay.value))
    if (res.success) {
      ElMessage.success({ message: res.message, duration: 5000 })
      pushVisible.value = false
    } else {
      ElMessage.error({ message: res.message, duration: 8000 })
    }
  } finally {
    pushing.value = false
  }
}

/**
 * 把企业微信 markdown 拆成一段段文字来显示（不当 HTML 渲染，防注入）：
 * 支持 <font color="info|warning|comment">、**加粗**、[文字](链接)、以“> ”开头的引用行
 */
const previewLines = computed(() => {
  if (!pushData.value) return []
  const re = /<font color="(\w+)">(.*?)<\/font>|\*\*(.*?)\*\*|\[(.*?)\]\((.*?)\)/g
  return pushData.value.content.split('\n').map(raw => {
    const quote = raw.startsWith('> ')
    const text = quote ? raw.slice(2) : raw
    const parts = []
    let last = 0
    let m
    while ((m = re.exec(text))) {
      if (m.index > last) parts.push({ text: text.slice(last, m.index) })
      if (m[1]) parts.push({ text: m[2], color: m[1] })
      else if (m[3] !== undefined) parts.push({ text: m[3], bold: true })
      else parts.push({ text: m[4], href: m[5] })
      last = re.lastIndex
    }
    if (last < text.length) parts.push({ text: text.slice(last) })
    return { quote, parts }
  })
})

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
  white-space: nowrap; /* 班组自定文字长的时候，指标名也别被挤成竖排 */
}

.cert thead th {
  white-space: nowrap;
}

.cert tbody tr:nth-child(even) td:not(.cert-metric) {
  background: #f7fafc;
}

/* 指标名按整项结果着色：所有子项都达标才是绿色 */
.cert-metric.m-PASS { background: var(--dr-pass-bg); color: #1d7a43; }
.cert-metric.m-FAIL { background: var(--dr-fail-bg); color: var(--dr-fail); }

/* 本班当前：每个子项一行，各自着色 */
.cur-cell { padding: 3px 8px; }
.cur-line { line-height: 1.5; white-space: nowrap; }
.cur-name {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 400;
  background: #eef2f6;
  color: #5c6b7a;
}

.cur-PASS { color: var(--dr-pass); font-weight: 700; }
.cur-FAIL { color: var(--dr-fail); font-weight: 700; }
.cur-FAIL .cur-name { background: var(--dr-fail-bg); color: var(--dr-fail); }
.cur-PASS .cur-name { background: var(--dr-pass-bg); color: #1d7a43; }
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

.board-meta {
  padding: 4px 14px 10px;
  background: var(--dr-blue-bright);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.75);
}

/* 今日行动措施 / 今日亮点 / 明日行动计划 */
.notes {
  padding: 10px 14px 12px;
  background: #f5f8fa;
}

.notes-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  font-weight: 700;
  color: #1f2d3d;
}

.notes-date {
  margin-left: 8px;
  padding: 0 8px;
  border-radius: 10px;
  background: #ffb020;
  color: #10244d;
  font-size: 12px;
  font-weight: 600;
}

.notes-lock {
  font-size: 12px;
  font-weight: 400;
  color: #d48806;
}

.note + .note {
  margin-top: 8px;
}

.note-title {
  font-size: 14px;
  font-weight: 700;
  color: #0a5a9a;
  margin-bottom: 2px;
}

.note-text {
  font-size: 14px;
  line-height: 1.7;
  color: #1f2d3d;
  white-space: pre-wrap; /* 保留换行：1. 2. 分行写 */
  word-break: break-word;
}

.note-meta {
  margin-top: 8px;
  padding: 0;
  background: none;
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
  /* 跟下面的“班组目标 + 指标”两列一起固定在左边，横向拖动时不会滚走 */
  position: sticky;
  left: 0;
  z-index: 2;
}

.trend-title {
  font-size: 19px;
  letter-spacing: 3px;
  text-align: left;
}

/* 31 列很宽，标题贴着可视区域左侧，横向滚动时也一直看得到 */
.trend-title span {
  position: sticky;
  left: 176px;
  padding-left: 16px;
}

.row-head {
  position: sticky;
  left: 104px; /* 让出最左边的班组目标列 */
  z-index: 1;
  width: 52px;
  min-width: 52px;
  max-width: 52px;
}

/* 班组目标列：固定在最左边（参考用），横向拖动表格时一直看得到 */
.goal-head,
.goal-cell {
  position: sticky;
  left: 0;
  z-index: 1;
  width: 104px;
  min-width: 104px;
  max-width: 104px;
}

.goal-cell {
  padding: 2px 4px;
  text-align: center;
  background: #fff7e0;
  border: 1px solid #b9d7bc;
  box-shadow: inset -2px 0 0 #e6c26a;
  line-height: 1.3;
}

.goal-text {
  font-size: 13px;
  font-weight: 700;
  color: #7a5200;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.goal-name {
  font-size: 11px;
  font-weight: 600;
  color: #b07d10;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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

/* 拆了子项的指标：最后一行“整项”，所有子项都达标打 ✓（绿），有子项未达标打 ✗（红） */
.sum-row td {
  height: 22px;
  font-size: 13px;
  font-weight: 700;
  text-align: center;
  border: 1px solid #b9d7bc;
}

.sum-goal {
  font-size: 11px !important;
  font-weight: 600 !important;
  color: #7a5200;
  background: #fbefcf !important;
}

.sum-cell { background: #f7f9f7; color: #b0b6bf; }
.sum-cell.selected { box-shadow: inset 0 0 0 2px #ffb020; }
.sum-PASS { background: #c9ecd5; color: #1d7a43; }
.sum-FAIL { background: #f7c4c4; color: var(--dr-fail); }
.sum-INVALID { background: #ffe58f; color: #ad4e00; }

/* 指标之间画一条粗一点的分隔线，拆了子项的几行看得出是一组 */
.metric-end td,
.metric-end th {
  border-bottom: 2px solid #6fa77a !important;
}

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

/* ---------- 推送到企业微信 ---------- */
.push-to {
  font-size: 13px;
  line-height: 1.7;
  margin-bottom: 10px;
}

.push-preview {
  padding: 12px 14px;
  border-radius: 6px;
  background: #f4f6f9;
  border: 1px solid #e3e7ee;
  font-size: 14px;
  line-height: 1.7;
  max-height: 52vh;
  overflow: auto;
}

.pv-line.quote {
  padding-left: 10px;
  border-left: 3px solid #d0d7e2;
  color: #4a5562;
}

.pv-line.blank {
  height: 10px;
}

.pv-bold { font-weight: 700; }
.pv-info { color: #2e9e5b; }
.pv-warning { color: #e6a23c; }
.pv-comment { color: #8a96a3; }

.push-history {
  margin-top: 10px;
  font-size: 12px;
  color: #8a96a3;
}

.warn-text { color: #d48806; }
.ok-text { color: #2e9e5b; }
.muted-text { color: #a0a8b3; }

/* ---------- 弹窗 / 抽屉 ---------- */
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  color: #6b7785;
}

/* 编辑六项目标：每项一行，右边是子项列表 */
.tf {
  border: 1px solid var(--dr-border);
  border-radius: 4px;
  max-height: 64vh;
  overflow: auto;
}

.tf-row {
  display: grid;
  grid-template-columns: 56px 170px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
  padding: 8px 10px;
  border-bottom: 1px solid #eef0f3;
}

.tf-row:last-child { border-bottom: none; }

.tf-head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: #f5f7fa;
  font-size: 13px;
  font-weight: 700;
  color: #4a5562;
}

.tf-metric {
  line-height: 32px;
  font-weight: 700;
  text-align: center;
}

.tf-items {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.tf-item {
  display: flex;
  align-items: center;
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
