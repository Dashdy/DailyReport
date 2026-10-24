<template>
  <!--
    工序走势轮播大屏：路由 /processes/:id/carousel，meta.standalone（不套侧边栏 / 顶栏），
    样式和播放控制照搬 Dashboard 的钻孔监控中心。
    一共 8 页，按 SLIDE_SECONDS 自动轮播：
      四功能小组 → 每日走势表 → 安全 / 品质 / 交付 / 报废 / 成本 / 设备 走势图
    ?page=TEAM / GRID / SAFETY … 可以直接从某一页开始（旧链接 ?page=CERT 也认）。
  -->
  <div ref="containerRef" class="screen">
    <div class="header-banner">
      <span class="header-left">{{ monthLabel }} · 负责人：{{ owners || '未分配' }}</span>
      <span class="deco">◄◄◄</span>
      <h1 class="banner-title">{{ processName }} · {{ page ? page.title : '' }}</h1>
      <span class="deco">►►►</span>
      <span class="header-clock">{{ clock }}</span>
    </div>

    <!-- ===== 第 1 页：四功能小组（六项数据看第 2 页走势表） ===== -->
    <div v-if="page && page.type === 'team'" class="page-viewport team-page">
      <div class="panel team-panel">
        <div class="members">
          <div v-for="(m, i) in members" :key="i" class="member-card">
            <img v-if="m.photo" :src="fileUrl(m.photo)" class="member-photo" alt="" />
            <div v-else class="member-photo empty">暂无照片</div>
            <div class="member-name">{{ m.name || '未填写姓名' }}</div>
            <div class="member-role">{{ m.role || '职能未填' }}</div>
          </div>
        </div>
        <div class="sign-block">
          <div class="sign-row"><span class="sign-label">全员签名：</span><span class="sign-line">{{ board.signers }}</span></div>
          <div class="sign-row"><span class="sign-label">日期：</span><span class="sign-line num">{{ board.signDate ? String(board.signDate).replaceAll('-', '.') : '' }}</span></div>
        </div>
      </div>
    </div>

    <!-- ===== 第 2 页：每日走势表 ===== -->
    <div v-else-if="page && page.type === 'grid'" class="page-viewport grid-page">
      <div class="panel grid-panel">
        <div class="panel-title">
          <span>{{ monthLabel }} 每日走势表</span>
          <span class="legend">
            红绿按“班组目标”判定：
            <i class="lg lg-pass"></i>达标 <i class="lg lg-fail"></i>未达标 <i class="lg lg-empty"></i>未填
          </span>
        </div>
        <div class="grid-wrap">
          <table class="trend-table">
            <thead>
              <tr>
                <th class="base-col">公司底线</th>
                <th class="goal-col">班组目标</th>
                <th class="row-head">日期</th>
                <th v-for="day in dayList" :key="day" :class="{ today: day === todayDay && isCurrentMonth }">{{ day }}</th>
                <th class="rate-col">达标率</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in metricSlides" :key="s.metric">
                <td class="base-col">{{ s.baseline || '—' }}</td>
                <td class="goal-col">
                  <div class="goal-text">{{ s.teamTarget || '—' }}</div>
                  <div class="goal-rule">{{ ruleText(s) }}</div>
                </td>
                <th class="row-head">{{ s.label }}</th>
                <td v-for="day in dayList" :key="day" class="num"
                    :class="['cell-' + cellStatus(s, day), { today: day === todayDay && isCurrentMonth }]">
                  {{ s.values[day - 1] === null ? '' : fmtNum(s.values[day - 1]) }}
                </td>
                <td class="rate-col num" :class="rateClass(s.rate)">{{ fmtRate(s.rate) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- ===== 第 3~8 页：单项指标走势图 ===== -->
    <div v-else-if="slide" class="page-viewport">
      <!-- 左：当月折线图 -->
      <div class="panel chart-panel">
        <div class="panel-title">
          <span>{{ slide.label }}每日走势</span>
          <span class="target-text">目标 {{ slide.compareOp === 'GE' ? '≥' : '≤' }} {{ fmtNum(slide.targetValue) }}{{ slide.unit }}</span>
        </div>
        <EChart :option="chartOption" height="100%" class="big-chart" />
      </div>

      <!-- 右：关键数字 -->
      <div class="side">
        <div class="panel stat-today" :class="'st-' + slide.todayStatus">
          <div class="stat-label">今日（{{ todayDay }}号）</div>
          <div class="stat-big num">
            <template v-if="slide.todayValue !== null">{{ fmtNum(slide.todayValue) }}<small>{{ slide.unit }}</small></template>
            <template v-else>未填</template>
          </div>
          <div class="stat-sub">{{ STATUS_TEXT[slide.todayStatus] || '' }}</div>
        </div>

        <div class="panel">
          <div class="stat-label">本月达标率</div>
          <div class="stat-big num" :class="rateClass(slide.rate)">{{ fmtRate(slide.rate) }}</div>
          <div class="count-row num">
            <span class="c-pass">达标 {{ slide.pass }} 天</span>
            <span class="c-fail">未达标 {{ slide.fail }} 天</span>
            <span class="c-empty">未填 {{ slide.empty }} 天</span>
          </div>
        </div>

        <div class="panel recent">
          <div class="stat-label">最近 7 天</div>
          <div v-for="r in slide.recent" :key="r.day" class="recent-row num" :class="'st-' + r.status">
            <span>{{ r.day }}号</span>
            <span class="recent-val">{{ r.value === null ? '—' : fmtNum(r.value) + (slide.unit || '') }}</span>
            <span class="recent-tag">{{ STATUS_TEXT[r.status] }}</span>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="page-viewport loading">{{ loadError || '正在加载…' }}</div>

    <!-- 页码 + 播放控制（同 Dashboard 钻孔看板） -->
    <div class="footer">
      <div class="dots">
        <span v-for="(p, i) in pages" :key="p.key" class="dot" :class="{ active: i === index, fail: p.todayStatus === 'FAIL' }"
              @click="goTo(i)">{{ p.label }}</span>
      </div>
      <div class="playback-bar">
        <el-button circle size="small" class="playback-btn" title="上一页" @click="retreat"><el-icon><DArrowLeft /></el-icon></el-button>
        <el-button circle size="small" class="playback-btn" :title="paused ? '继续轮播' : '暂停'" @click="paused = !paused">
          <el-icon><VideoPlay v-if="paused" /><VideoPause v-else /></el-icon>
        </el-button>
        <el-button circle size="small" class="playback-btn" title="下一页" @click="advance"><el-icon><DArrowRight /></el-icon></el-button>
        <el-popover trigger="click" placement="top" :width="200">
          <template #reference>
            <el-button circle size="small" class="playback-btn" title="页面列表"><el-icon><Menu /></el-icon></el-button>
          </template>
          <div class="page-list">
            <div v-for="(p, i) in pages" :key="p.key" class="page-list-item" :class="{ active: i === index }" @click="goTo(i)">
              页面{{ i + 1 }}：{{ p.title }}
            </div>
          </div>
        </el-popover>
        <el-button circle size="small" class="playback-btn" :title="isFullscreen ? '退出全屏' : '全屏'" @click="toggleFullscreen">
          <el-icon><FullScreen v-if="!isFullscreen" /><Aim v-else /></el-icon>
        </el-button>
        <el-button circle size="small" class="playback-btn" title="返回工序详情" @click="goBack"><el-icon><Back /></el-icon></el-button>
      </div>
      <div class="progress"><div class="progress-bar" :style="{ width: progress + '%' }"></div></div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DArrowLeft, DArrowRight, VideoPlay, VideoPause, Menu, FullScreen, Aim, Back } from '@element-plus/icons-vue'
import api from '@/api'
import EChart from '@/components/EChart.vue'
import { useFullscreen } from '@/composables/useFullscreen'
import { useDailyReload } from '@/composables/useDailyReload'
import { fileUrl, fmtNum, fmtRate, todayStr, STATUS_TEXT } from '@/utils/format'

const SLIDE_SECONDS = 20          // 每页停留秒数
const REFRESH_MINUTES = 5         // 数据自动刷新间隔

const route = useRoute()
const router = useRouter()
const processId = Number(route.params.id)
const { containerRef, isFullscreen, toggleFullscreen } = useFullscreen()
useDailyReload(4)

const d = ref(null)
const loadError = ref('')
const index = ref(0)
const paused = ref(false)
const elapsed = ref(0)

// 名字本身以“工序”结尾就不再加“工序”
const processName = computed(() => {
  if (!d.value) return ''
  const n = d.value.process.name
  return n.endsWith('工序') ? n : n + '工序'
})
const owners = computed(() => (d.value ? d.value.owners.map(o => o.username).join('、') : ''))
const monthLabel = computed(() => (d.value ? `${d.value.month.slice(0, 4)}年${Number(d.value.month.slice(5))}月` : ''))
const serverToday = computed(() => (d.value && d.value.today ? String(d.value.today) : todayStr()))
const todayDay = computed(() => Number(serverToday.value.slice(8)))

function judge(v, t) {
  if (v === null || v === undefined) return 'EMPTY'
  if (!t || t.targetValue === null || t.targetValue === undefined) return 'NO_TARGET'
  return (t.compareOp === 'GE' ? v >= t.targetValue : v <= t.targetValue) ? 'PASS' : 'FAIL'
}

const isCurrentMonth = computed(() => !!d.value && serverToday.value.slice(0, 7) === d.value.month)
const dayList = computed(() => (d.value ? Array.from({ length: d.value.daysInMonth }, (_, i) => i + 1) : []))

/** 六项指标：当月每天的值 + 统计（保证书页、走势表页、六个走势图页共用） */
const metricSlides = computed(() => {
  if (!d.value) return []
  const v = d.value
  const days = v.daysInMonth
  const lastDay = isCurrentMonth.value ? todayDay.value : days // 当月只统计到今天
  return v.targets.map(t => {
    const values = Array.from({ length: days }, () => null)
    for (const r of v.records) {
      if (r.metric === t.metric) values[Number(String(r.date).slice(8)) - 1] = r.value
    }
    let pass = 0, fail = 0, empty = 0
    for (let i = 0; i < lastDay; i++) {
      const s = judge(values[i], t)
      if (s === 'PASS') pass++
      else if (s === 'FAIL') fail++
      else if (s === 'EMPTY') empty++
    }
    const recent = []
    for (let day = lastDay; day >= Math.max(1, lastDay - 6); day--) {
      recent.push({ day, value: values[day - 1], status: judge(values[day - 1], t) })
    }
    const todayValue = isCurrentMonth.value ? values[todayDay.value - 1] : null
    return {
      metric: t.metric, label: t.label, unit: t.unit, compareOp: t.compareOp, targetValue: t.targetValue,
      baseline: t.baseline, teamTarget: t.teamTarget,
      values, pass, fail, empty, recent,
      rate: pass + fail === 0 ? null : pass / (pass + fail),
      todayValue, todayStatus: isCurrentMonth.value ? judge(todayValue, t) : 'EMPTY'
    }
  })
})

/** 轮播的 8 页：四功能小组 → 走势表 → 六项走势图 */
const pages = computed(() => {
  if (!d.value) return []
  return [
    { key: 'TEAM', type: 'team', label: '四功能小组', title: '四功能小组' },
    { key: 'GRID', type: 'grid', label: '走势表', title: '每日走势表' },
    ...metricSlides.value.map(s => ({ ...s, key: s.metric, type: 'metric', title: s.label + '走势' }))
  ]
})
const page = computed(() => pages.value[index.value] || null)
const slide = computed(() => (page.value && page.value.type === 'metric' ? page.value : null))

/** 系统判定红绿用的规则，如“达标 ≤ 2.6%”“达标 ≥ 320片” */
function ruleText(s) {
  if (s.targetValue === null || s.targetValue === undefined) return '未设判定值'
  return `达标 ${s.compareOp === 'GE' ? '≥' : '≤'} ${fmtNum(s.targetValue)}${s.unit || ''}`
}

/** 走势表某一格的状态；未来日期单独标出来 */
function cellStatus(s, day) {
  if (isCurrentMonth.value && day > todayDay.value) return 'FUTURE'
  const t = d.value.targets.find(x => x.metric === s.metric)
  return judge(s.values[day - 1], t)
}

/** 四功能小组：固定 4 个位置 */
const board = computed(() => (d.value && d.value.board) || {})
const members = computed(() => {
  const saved = board.value.members || []
  return Array.from({ length: 4 }, (_, i) => saved[i] || {})
})

function rateClass(rate) {
  if (rate === null || rate === undefined) return 'c-empty'
  return rate >= 1 ? 'c-pass' : rate >= 0.8 ? 'c-warn' : 'c-fail'
}

const chartOption = computed(() => {
  const s = slide.value
  if (!s) return {}
  const pass = v => s.targetValue === null || s.targetValue === undefined || (s.compareOp === 'GE' ? v >= s.targetValue : v <= s.targetValue)
  const axisText = { color: '#8fb4d9', fontSize: 14 }
  return {
    grid: { left: 64, right: 40, top: 40, bottom: 44 },
    tooltip: {
      trigger: 'axis',
      formatter: ps => {
        const p = ps[0]
        return p.value === null || p.value === undefined ? `${p.name}：未填` : `${p.name}：${p.value}${s.unit || ''}`
      }
    },
    xAxis: {
      type: 'category',
      data: s.values.map((_, i) => i + 1 + '号'),
      boundaryGap: false,
      axisLabel: { ...axisText, interval: 1 },
      axisLine: { lineStyle: { color: 'rgba(94,178,255,0.4)' } }
    },
    yAxis: {
      type: 'value',
      scale: true,
      minInterval: ['SAFETY', 'QUALITY'].includes(s.metric) ? 1 : undefined,
      axisLabel: axisText,
      splitLine: { lineStyle: { color: 'rgba(94,178,255,0.12)' } }
    },
    series: [{
      type: 'line',
      data: s.values,
      connectNulls: true,
      symbolSize: 10,
      lineStyle: { width: 3, color: '#3ea6ff' },
      itemStyle: { color: p => (p.value !== null && p.value !== undefined && !pass(p.value) ? '#ff5b5b' : '#3ea6ff') },
      label: {
        show: true,
        position: 'top',
        fontSize: 13,
        color: '#dce9f7',
        formatter: p => (p.value === null || p.value === undefined ? '' : String(p.value))
      },
      areaStyle: {
        color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(62,166,255,0.35)' }, { offset: 1, color: 'rgba(62,166,255,0)' }] }
      },
      markLine: s.targetValue === null || s.targetValue === undefined ? undefined : {
        symbol: 'none',
        lineStyle: { type: 'dashed', color: '#ffb020', width: 2 },
        label: { formatter: '目标 ' + fmtNum(s.targetValue), color: '#ffb020', fontSize: 14, position: 'insideEndTop' },
        data: [{ yAxis: s.targetValue }]
      }
    }]
  }
})

// ---------------- 数据 ----------------
async function load() {
  try {
    // 从工序详情进来会带上当时看的月份（?month=2026-10），没带就是当月
    const res = await api.detail(processId, route.query.month)
    if (res.success) {
      d.value = res.data
      loadError.value = ''
      if (index.value >= pages.value.length) index.value = 0
    } else {
      loadError.value = res.message
    }
  } catch {
    loadError.value = '数据加载失败，稍后自动重试'
  }
}

// ---------------- 轮播 ----------------
function goTo(i) {
  index.value = i
  elapsed.value = 0
}
function advance() {
  if (pages.value.length) goTo((index.value + 1) % pages.value.length)
}
function retreat() {
  if (pages.value.length) goTo((index.value - 1 + pages.value.length) % pages.value.length)
}
const progress = computed(() => Math.min(100, (elapsed.value / SLIDE_SECONDS) * 100))

// 地址栏跟着当前页走（?page=CERT / GRID / SAFETY …），方便把某一页的链接单独发给别人
watch(page, p => {
  if (p && route.query.page !== p.key) {
    const query = { ...route.query, page: p.key }
    delete query.metric // 旧版链接用的是 ?metric=，统一换成 ?page=
    router.replace({ query })
  }
})

function goBack() {
  if (document.fullscreenElement) document.exitFullscreen()
  // 看的是以前的月份就回到那个月；当月直接回去（默认选今天）
  const m = route.query.month
  router.push({ path: `/processes/${processId}`, query: m && m !== todayStr().slice(0, 7) ? { date: m + '-01' } : {} })
}

const clock = ref('')
const WEEKDAYS = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
let tickTimer = null
let refreshTimer = null

function tick() {
  const now = new Date()
  const p = n => String(n).padStart(2, '0')
  clock.value = `${now.getFullYear()}-${p(now.getMonth() + 1)}-${p(now.getDate())} ${p(now.getHours())}:${p(now.getMinutes())}:${p(now.getSeconds())} ${WEEKDAYS[now.getDay()]}`
  if (!paused.value && pages.value.length) {
    elapsed.value += 1
    if (elapsed.value >= SLIDE_SECONDS) advance()
  }
}

onMounted(async () => {
  await load()
  const wanted = route.query.page === 'CERT' ? 'TEAM' : (route.query.page || route.query.metric) // CERT 是改版前的链接
  const start = pages.value.findIndex(p => p.key === wanted)
  if (start >= 0) index.value = start
  tick()
  tickTimer = setInterval(tick, 1000)
  refreshTimer = setInterval(load, REFRESH_MINUTES * 60 * 1000)
})

onUnmounted(() => {
  clearInterval(tickTimer)
  clearInterval(refreshTimer)
})
</script>

<style scoped>
.screen {
  width: 100vw;
  height: 100vh;
  box-sizing: border-box;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 16px 24px 12px;
  color: #dce9f7;
  background: radial-gradient(circle at 50% 0%, #10254a 0%, #050b1a 70%);
}

.screen:fullscreen {
  background: radial-gradient(circle at 50% 0%, #10254a 0%, #050b1a 70%);
}

/* ---------- 顶部横幅 ---------- */
.header-banner {
  flex-shrink: 0;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 60px;
  margin-bottom: 16px;
  background: linear-gradient(90deg, rgba(15, 30, 60, 0) 0%, rgba(30, 70, 150, 0.5) 50%, rgba(15, 30, 60, 0) 100%);
  border-bottom: 1px solid rgba(94, 178, 255, 0.4);
}

.banner-title {
  margin: 0;
  font-size: 30px;
  font-weight: 800;
  color: #eaf4ff;
  letter-spacing: 4px;
  text-shadow: 0 0 12px rgba(64, 158, 255, 0.6);
}

.deco {
  margin: 0 18px;
  color: #2b82ff;
  font-size: 18px;
  letter-spacing: 2px;
  text-shadow: 0 0 8px rgba(43, 130, 255, 0.8);
}

.header-left,
.header-clock {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  font-size: 14px;
  color: #8fd5ff;
}

.header-left { left: 0; }
.header-clock { right: 0; font-family: 'Courier New', Courier, monospace; }

/* ---------- 主体 ---------- */
.page-viewport {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 16px;
}

.page-viewport.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #8fb4d9;
}

.panel {
  padding: 14px 18px;
  border-radius: 8px;
  background: rgba(12, 28, 58, 0.72);
  border: 1px solid rgba(94, 178, 255, 0.28);
  box-shadow: inset 0 0 24px rgba(43, 130, 255, 0.08);
}

.chart-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.panel-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  font-size: 18px;
  font-weight: 700;
  color: #eaf4ff;
}

.target-text {
  font-size: 16px;
  font-weight: 600;
  color: #ffb020;
}

.big-chart {
  flex: 1;
  min-height: 0;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 0;
}

.stat-label {
  font-size: 15px;
  color: #8fb4d9;
}

.stat-big {
  margin: 6px 0 4px;
  font-size: 44px;
  font-weight: 800;
  line-height: 1.1;
  color: #eaf4ff;
}

.stat-big small {
  margin-left: 4px;
  font-size: 18px;
  font-weight: 600;
  opacity: 0.8;
}

.stat-sub {
  font-size: 16px;
  font-weight: 700;
}

.stat-today.st-PASS { border-color: rgba(46, 204, 113, 0.6); }
.stat-today.st-PASS .stat-big,
.stat-today.st-PASS .stat-sub { color: #3ddc84; }
.stat-today.st-FAIL { border-color: rgba(255, 91, 91, 0.7); box-shadow: 0 0 18px rgba(255, 91, 91, 0.25); }
.stat-today.st-FAIL .stat-big,
.stat-today.st-FAIL .stat-sub { color: #ff5b5b; }
.stat-today.st-EMPTY .stat-big { color: #6f86a3; }

.count-row {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  font-size: 14px;
}

.c-pass { color: #3ddc84; }
.c-fail { color: #ff5b5b; }
.c-warn { color: #ffb020; }
.c-empty { color: #6f86a3; }

.recent {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.recent-row {
  display: grid;
  grid-template-columns: 52px 1fr 70px;
  align-items: center;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 16px;
}

.recent-val {
  text-align: right;
  font-weight: 700;
  padding-right: 12px;
}

.recent-tag {
  text-align: center;
  font-size: 13px;
  border-radius: 10px;
  padding: 1px 0;
}

.recent-row.st-PASS .recent-tag { background: rgba(61, 220, 132, 0.18); color: #3ddc84; }
.recent-row.st-FAIL .recent-tag { background: rgba(255, 91, 91, 0.2); color: #ff5b5b; }
.recent-row.st-FAIL .recent-val { color: #ff5b5b; }
.recent-row.st-EMPTY .recent-tag,
.recent-row.st-NO_TARGET .recent-tag { background: rgba(255, 255, 255, 0.06); color: #6f86a3; }

/* ---------- 底部：页码 + 播放控制 + 进度 ---------- */
.footer {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}

.dots {
  display: flex;
  gap: 10px;
}

.dot {
  padding: 3px 14px;
  border-radius: 12px;
  font-size: 14px;
  color: #8fb4d9;
  border: 1px solid rgba(94, 178, 255, 0.3);
  cursor: pointer;
  transition: all 0.2s;
}

.dot.fail { border-color: rgba(255, 91, 91, 0.6); color: #ff8a8a; }
.dot.active { background: #2b82ff; border-color: #2b82ff; color: #fff; }

.playback-bar {
  display: flex;
  justify-content: center;
  gap: 10px;
}

.playback-btn {
  background: rgba(15, 30, 60, 0.7) !important;
  border: 1px solid rgba(94, 178, 255, 0.4) !important;
  color: #8fd5ff !important;
}

.playback-btn:hover { background: rgba(43, 130, 255, 0.3) !important; }

.progress {
  width: 240px;
  height: 3px;
  border-radius: 2px;
  background: rgba(94, 178, 255, 0.15);
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  background: #2b82ff;
  transition: width 1s linear;
}

.page-list { display: flex; flex-direction: column; gap: 4px; }
.page-list-item { padding: 6px 8px; border-radius: 4px; cursor: pointer; font-size: 13px; color: #334155; }
.page-list-item:hover { background: #f0f4f8; }
.page-list-item.active { color: #2b82ff; font-weight: 700; }
/* ================= 第 1 页：四功能小组 ================= */
.team-page {
  grid-template-columns: minmax(0, 1fr);
}

.team-panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-height: 0;
  padding: 24px 32px 18px;
}

.members {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: clamp(16px, 2vw, 40px);
}

.member-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 0;
}

.member-photo {
  width: 100%;
  flex: 1;
  min-height: 0;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid rgba(94, 178, 255, 0.35);
}

.member-photo.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  border-style: dashed;
  color: #6f86a3;
  font-size: 15px;
  background: rgba(255, 255, 255, 0.03);
}

.member-name {
  font-size: clamp(22px, 1.8vw, 34px);
  font-weight: 800;
  color: #eaf4ff;
  max-width: 100%;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.member-role {
  padding: 2px 18px;
  border-radius: 14px;
  font-size: clamp(15px, 1.1vw, 20px);
  background: rgba(43, 130, 255, 0.25);
  color: #8fd5ff;
}

.sign-block {
  display: grid;
  gap: 14px;
  padding-top: 6px;
}

.sign-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  font-size: 18px;
  color: #8fb4d9;
}

.sign-label {
  flex-shrink: 0;
  width: 5em;
  text-align: right;
}

.sign-line {
  flex: 1;
  min-height: 26px;
  border-bottom: 1px solid rgba(143, 180, 217, 0.6);
  color: #eaf4ff;
  font-size: 20px;
}

/* ================= 第 2 页：每日走势表 ================= */
.grid-page {
  grid-template-columns: minmax(0, 1fr);
}

.grid-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
}

.legend {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 400;
  color: #8fb4d9;
}

.lg {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  margin-left: 10px;
}

.lg-pass { background: rgba(61, 220, 132, 0.35); }
.lg-fail { background: rgba(255, 91, 91, 0.75); }
.lg-empty { background: rgba(255, 255, 255, 0.08); border: 1px solid rgba(143, 180, 217, 0.3); }

.grid-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.trend-table {
  width: 100%;
  height: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  /* 31 天挤在一行：字号跟着屏幕宽度走，1920 宽约 17px，1366 宽约 12px，数字不会挤到一起 */
  font-size: clamp(11px, 0.88vw, 17px);
  text-align: center;
}

.trend-table th {
  padding: 8px 2px;
  background: rgba(43, 130, 255, 0.35);
  color: #eaf4ff;
  font-weight: 700;
  border: 1px solid rgba(94, 178, 255, 0.35);
}

.trend-table td {
  padding: 0 1px;
  line-height: 1.25;
  border: 1px solid rgba(94, 178, 255, 0.18);
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
}

.trend-table .row-head {
  width: clamp(48px, 3.4vw, 64px);
  font-size: clamp(15px, 1.05vw, 20px);
}

.trend-table .rate-col {
  width: clamp(70px, 4.8vw, 92px);
  font-size: clamp(14px, 0.95vw, 18px);
}

.trend-table .base-col {
  width: clamp(76px, 5.6vw, 108px);
  font-weight: 600;
  color: #b8cde3;
  background: rgba(255, 255, 255, 0.03);
}

.trend-table .goal-col {
  width: clamp(92px, 6.6vw, 128px);
  background: rgba(255, 176, 32, 0.08);
}

.trend-table th.base-col,
.trend-table th.goal-col {
  background: rgba(43, 130, 255, 0.35);
  color: #eaf4ff;
}

.goal-text {
  font-weight: 800;
  color: #ffcf73;
}

.goal-rule {
  margin-top: 2px;
  font-size: 0.78em;
  font-weight: 600;
  color: #ffb020;
  opacity: 0.85;
}

.trend-table th.today {
  background: #ffb020;
  color: #10244d;
}

.trend-table td.today {
  box-shadow: inset 0 0 0 2px #ffb020;
}

.cell-PASS { background: rgba(61, 220, 132, 0.2); color: #3ddc84; }
.cell-FAIL { background: rgba(255, 91, 91, 0.7); color: #fff; }
.cell-EMPTY { background: rgba(255, 255, 255, 0.03); }
.cell-NO_TARGET { background: rgba(255, 176, 32, 0.15); color: #ffcf73; }
.cell-FUTURE { background: transparent; }
</style>
