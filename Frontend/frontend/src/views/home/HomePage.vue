<template>
  <div v-loading="loading">
    <div class="page-banner">
      <div>
        <div class="crumb">每日战报 / 全厂达标汇总</div>
        <h1>全厂六项指标达标情况</h1>
      </div>
      <div class="banner-tools">
        <el-date-picker id="home-date" v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
                        :disabled-date="d => d > new Date()" @change="load" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <template v-if="data">
      <!-- 总览 -->
      <section class="kpis">
        <div class="kpi">
          <div class="kpi-label">全厂达标率</div>
          <div class="kpi-value num" :class="rateClass(data.overall.rate)">{{ fmtRate(data.overall.rate) }}</div>
          <div class="kpi-note">达标 {{ data.overall.pass }} 项 / 已判定 {{ data.overall.pass + data.overall.fail }} 项</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">已填报工序</div>
          <div class="kpi-value num">{{ data.overall.reported }}<small> / {{ data.overall.processCount }}</small></div>
          <div class="kpi-note">未填报 {{ data.overall.processCount - data.overall.reported }} 道工序</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">六项全部达标</div>
          <div class="kpi-value num pass">{{ data.overall.allPass }}<small> 道工序</small></div>
          <div class="kpi-note">安全、品质、交付、报废、成本、设备都达标</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">未达标项</div>
          <div class="kpi-value num" :class="data.overall.fail ? 'fail' : 'pass'">{{ data.overall.fail }}<small> 项</small></div>
          <div class="kpi-note">点下方矩阵中的红格可进入工序查看</div>
        </div>
      </section>

      <!-- 六项指标 -->
      <section class="metric-grid">
        <div v-for="m in data.metrics" :key="m.metric" class="metric-card">
          <div class="metric-head">
            <span class="metric-name">{{ m.label }}</span>
            <span class="metric-rate num" :class="rateClass(m.rate)">{{ fmtRate(m.rate) }}</span>
          </div>
          <el-progress :percentage="m.rate === null ? 0 : Math.round(m.rate * 1000) / 10" :show-text="false"
                       :stroke-width="8" :color="m.rate === null ? '#c0c4cc' : (m.rate >= 1 ? '#2e9e5b' : (m.rate >= 0.8 ? '#e6a23c' : '#e23b3b'))" />
          <div class="metric-counts num">
            <span class="pass">达标 {{ m.pass }}</span>
            <span class="fail">未达标 {{ m.fail }}</span>
            <span class="muted">未填 {{ m.empty }}</span>
          </div>
        </div>
      </section>

      <!-- 当月走势 -->
      <section class="panel">
        <h2 class="panel-title">
          <span>{{ monthLabel }} 每日达标率走势</span>
          <span class="hint">达标率 = 达标工序数 ÷ 已填报工序数，未填报不计入</span>
        </h2>
        <EChart :option="trendOption" height="300px" />
      </section>

      <!-- 达标矩阵 -->
      <section class="panel">
        <h2 class="panel-title">
          <span>工序达标矩阵（{{ data.date }}）</span>
          <span class="matrix-tools">
            <span class="legend"><i class="dot pass-bg"></i>达标</span>
            <span class="legend"><i class="dot fail-bg"></i>未达标</span>
            <span class="legend"><i class="dot empty-bg"></i>未填报</span>
            <el-switch id="home-only-fail" v-model="onlyProblem" active-text="只看未达标 / 未填报" />
          </span>
        </h2>
        <div class="matrix-wrap">
          <table class="matrix">
            <thead>
              <tr>
                <th class="col-name">工序</th>
                <th class="col-owner">负责人</th>
                <th v-for="m in data.metrics" :key="m.metric">{{ m.label }}</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.processId" @click="router.push(`/processes/${row.processId}`)">
                <td class="col-name">{{ row.name }}</td>
                <td class="col-owner">{{ row.owners.join('、') || '未分配' }}</td>
                <td v-for="m in data.metrics" :key="m.metric" :class="'cell-' + row.cells[m.metric].status"
                    :title="row.cells[m.metric].teamTarget ? '班组目标：' + row.cells[m.metric].teamTarget : ''">
                  <!-- 拆了子项的：每个子项一行各自红绿，格子底色是整项结果（所有子项都达标才是绿色） -->
                  <template v-if="row.cells[m.metric].items.length > 1">
                    <div v-for="(it, i) in row.cells[m.metric].items" :key="i" class="sub-line" :class="'sub-' + it.status">
                      <span class="sub-name">{{ it.name }}</span>{{ it.value === null ? '—' : fmtNum(it.value) }}
                    </div>
                  </template>
                  <template v-else-if="row.cells[m.metric].value !== null">
                    {{ fmtNum(row.cells[m.metric].value) }}<small>{{ row.cells[m.metric].unit }}</small>
                  </template>
                  <template v-else>—</template>
                </td>
                <td class="col-result">
                  <el-tag v-if="!row.reported" type="info" size="small">未填报</el-tag>
                  <el-tag v-else-if="row.fail" type="danger" size="small">{{ row.fail }} 项未达标</el-tag>
                  <el-tag v-else-if="row.pass === 6" type="success" size="small">全部达标</el-tag>
                  <el-tag v-else type="warning" size="small">已填 {{ 6 - row.empty }}/6</el-tag>
                </td>
              </tr>
              <tr v-if="!rows.length">
                <td :colspan="data.metrics.length + 3" class="no-data">没有需要关注的工序</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import api from '@/api'
import EChart from '@/components/EChart.vue'
import { fmtNum, fmtRate, todayStr } from '@/utils/format'

const router = useRouter()
const date = ref(todayStr())
const data = ref(null)
const loading = ref(false)
const onlyProblem = ref(false)

async function load() {
  loading.value = true
  try {
    const res = await api.dashboard(date.value)
    if (res.success) data.value = res.data
    else ElMessage.error(res.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const monthLabel = computed(() => {
  const [y, m] = date.value.split('-')
  return `${y}年${Number(m)}月`
})

const rows = computed(() => {
  if (!data.value) return []
  return onlyProblem.value ? data.value.rows.filter(r => r.fail > 0 || r.empty > 0) : data.value.rows
})

function rateClass(rate) {
  if (rate === null || rate === undefined) return 'muted'
  return rate >= 1 ? 'pass' : rate >= 0.8 ? 'warn' : 'fail'
}

const COLORS = ['#2f6fd6', '#18a058', '#e6a23c', '#d03050', '#7c4dff', '#0e9aa7']

const trendOption = computed(() => {
  if (!data.value) return {}
  const days = data.value.trend.map(t => Number(String(t.date).slice(8)) + '号')
  const pct = v => (v === null ? null : Math.round(v * 1000) / 10)
  const series = data.value.metrics.map((m, i) => ({
    name: m.label,
    type: 'line',
    data: data.value.trend.map(t => pct(t[m.metric])),
    connectNulls: true,
    symbolSize: 5,
    lineStyle: { width: 1.5 },
    color: COLORS[i]
  }))
  series.unshift({
    name: '全厂',
    type: 'line',
    data: data.value.trend.map(t => pct(t.ALL)),
    connectNulls: true,
    symbolSize: 7,
    lineStyle: { width: 3 },
    color: '#10244d',
    z: 10
  })
  return {
    tooltip: { trigger: 'axis', valueFormatter: v => (v === null || v === undefined ? '—' : v + '%') },
    legend: { top: 0 },
    grid: { left: 48, right: 24, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: days, boundaryGap: false },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
    series
  }
})
</script>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.kpi {
  padding: 18px 20px;
  border-radius: 6px;
  background: #fff;
  border-left: 4px solid var(--dr-blue);
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}

.kpi-label {
  font-size: 13px;
  color: #6b7785;
}

.kpi-value {
  margin: 6px 0 4px;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.1;
}

.kpi-value small {
  font-size: 15px;
  font-weight: 400;
  color: #6b7785;
}

.kpi-note {
  font-size: 12px;
  color: #8a96a3;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.metric-card {
  padding: 14px 16px;
  border-radius: 6px;
  background: #fff;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.metric-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.metric-name {
  padding: 2px 10px;
  border-radius: 4px;
  background: var(--dr-blue);
  color: #fff;
  font-weight: 700;
  letter-spacing: 2px;
}

.metric-rate {
  font-size: 22px;
  font-weight: 700;
}

.metric-counts {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}

.pass { color: var(--dr-pass); }
.fail { color: var(--dr-fail); }
.warn { color: #d48806; }
.muted { color: var(--dr-empty); }

.panel {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  font-weight: 400;
  color: #8a96a3;
}

.matrix-tools {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 13px;
  font-weight: 400;
}

.legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #5c6b7a;
}

.dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  display: inline-block;
}

.pass-bg { background: var(--dr-pass-bg); border: 1px solid var(--dr-pass); }
.fail-bg { background: var(--dr-fail-bg); border: 1px solid var(--dr-fail); }
.empty-bg { background: #f4f5f7; border: 1px solid #c0c4cc; }

.matrix-wrap {
  overflow-x: auto;
}

.matrix {
  width: 100%;
  min-width: 760px;
  border-collapse: collapse;
  font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.matrix th {
  padding: 9px 8px;
  background: var(--dr-blue);
  color: #fff;
  font-weight: 600;
  border: 1px solid #0a5a9a;
  white-space: nowrap;
}

.matrix td {
  padding: 8px;
  text-align: center;
  border: 1px solid var(--dr-border);
  white-space: nowrap;
}

.matrix tbody tr {
  cursor: pointer;
}

.matrix tbody tr:hover td {
  filter: brightness(0.96);
}

.matrix td.col-name {
  text-align: left;
  font-weight: 600;
}

.matrix td.col-owner {
  text-align: left;
  color: #5c6b7a;
  font-size: 13px;
}

.matrix td small {
  margin-left: 2px;
  font-size: 11px;
  opacity: 0.75;
}

.cell-PASS { background: var(--dr-pass-bg); color: #1d7a43; }
.cell-FAIL { background: var(--dr-fail-bg); color: var(--dr-fail); font-weight: 700; }
.cell-EMPTY { background: #f8f9fb; color: #b0b6bf; }
.cell-NO_TARGET { background: #fffbe6; color: #8a6d00; }

.sub-line {
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
}

.sub-name {
  margin-right: 4px;
  font-weight: 400;
  opacity: 0.75;
}

.sub-PASS { color: #1d7a43; font-weight: 700; }
.sub-FAIL { color: var(--dr-fail); font-weight: 700; }
.sub-EMPTY { color: #b0b6bf; font-weight: 400; }

.no-data {
  padding: 24px !important;
  color: #8a96a3;
}
</style>
