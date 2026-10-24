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
        <!-- 今日行动措施 / 今日亮点 / 明日行动计划（当月看今天的，以前的月份看最后一天的） -->
        <div class="notes-row">
          <div v-for="f in NOTE_FIELDS" :key="f.key" class="note-card">
            <div class="note-title">【{{ f.label }}】<span class="note-day">{{ noteDay }}号</span></div>
            <div class="note-text" :class="{ empty: !teamNote[f.key] }">{{ teamNote[f.key] || '无' }}</div>
          </div>
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
            <!-- 每个子项一行；拆了子项的指标最后多一行“整项”（所有子项都达标才打 ✓），达标率按整项算 -->
            <tbody>
              <tr v-for="r in gridRows" :key="r.key" :class="{ 'metric-end': r.last }">
                <td v-if="r.first" class="base-col" :rowspan="r.span">{{ r.s.baseline || '—' }}</td>
                <td v-if="r.type === 'item'" class="goal-col">
                  <div v-if="r.multi" class="goal-rule">{{ r.it.name }}</div>
                  <div class="goal-text">{{ ruleText(r.it) }}</div>
                </td>
                <td v-else class="goal-col sum-goal">整项</td>
                <th v-if="r.first" class="row-head" :rowspan="r.span">{{ r.s.label }}</th>
                <template v-if="r.type === 'item'">
                  <td v-for="day in dayList" :key="day" class="num"
                      :class="['cell-' + cellStatus(r.it.values[day - 1], r.it, day), { today: day === todayDay && isCurrentMonth, long: fmtNum(r.it.values[day - 1]).length > 4 }]">
                    {{ r.it.values[day - 1] === null ? '' : fmtNum(r.it.values[day - 1]) }}
                  </td>
                </template>
                <template v-else>
                  <td v-for="day in dayList" :key="day" class="sum-cell"
                      :class="['sum-' + (isFuture(day) ? 'FUTURE' : r.s.dayStatus[day - 1]), { today: day === todayDay && isCurrentMonth }]">
                    {{ isFuture(day) ? '' : SUM_MARK[r.s.dayStatus[day - 1]] }}
                  </td>
                </template>
                <td v-if="r.first" class="rate-col num" :rowspan="r.span" :class="rateClass(r.s.rate)">{{ fmtRate(r.s.rate) }}</td>
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
          <span class="target-text">目标 {{ metricRuleText(slide) }}</span>
        </div>
        <EChart :option="chartOption" height="100%" class="big-chart" />
      </div>

      <!-- 右：关键数字（拆了子项的：每个子项一行各自红绿，下面是整项结果） -->
      <div class="side">
        <div class="panel stat-today" :class="'st-' + slide.todayStatus">
          <div class="stat-label">今日（{{ todayDay }}号）</div>
          <div v-if="!slide.multi" class="stat-big num">
            <template v-if="slide.today[0].value !== null">{{ fmtNum(slide.today[0].value) }}<small>{{ slide.items[0].unit }}</small></template>
            <template v-else>未填</template>
          </div>
          <div v-else class="today-items">
            <div v-for="p in slide.today" :key="p.id" class="today-item num" :class="'st-' + p.status">
              <span class="today-name">{{ p.name }}</span>
              <span class="today-val">
                <template v-if="p.value !== null">{{ fmtNum(p.value) }}<small>{{ p.unit }}</small></template>
                <template v-else>未填</template>
              </span>
            </div>
          </div>
          <div class="stat-sub">{{ slide.multi ? '整项：' : '' }}{{ TODAY_TEXT[slide.todayStatus] || '' }}</div>
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
          <div class="stat-label">最近 7 天<template v-if="slide.multi">（{{ slide.items.map(it => it.name).join(' / ') }}）</template></div>
          <div v-for="r in slide.recent" :key="r.day" class="recent-row num" :class="'st-' + r.status">
            <span>{{ r.day }}号</span>
            <span class="recent-val">
              <template v-for="(p, i) in r.parts" :key="p.id">
                <span v-if="i" class="recent-sep">/</span>
                <span :class="'v-' + p.status">{{ p.value === null ? '—' : fmtNum(p.value) + (slide.multi ? '' : (p.unit || '')) }}</span>
              </template>
            </span>
            <span class="recent-tag">{{ TODAY_TEXT[r.status] }}</span>
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
import { fileUrl, fmtNum, fmtRate, todayStr } from '@/utils/format'
import { axisRange, combine, escapeHtml, groupByUnit, judge, metricRuleText, ruleText, SERIES_COLORS_DARK } from '@/utils/metric'

/** “整项”那一行的标记 */
const SUM_MARK = { PASS: '✓', FAIL: '✗', EMPTY: '', NO_TARGET: '' }
/** 拆了子项时“没填完”比“未填报”准确 */
const TODAY_TEXT = { PASS: '达标', FAIL: '未达标', EMPTY: '未填报', PARTIAL: '未填完', NO_TARGET: '未设目标' }

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

const isCurrentMonth = computed(() => !!d.value && serverToday.value.slice(0, 7) === d.value.month)
const dayList = computed(() => (d.value ? Array.from({ length: d.value.daysInMonth }, (_, i) => i + 1) : []))
const isFuture = day => isCurrentMonth.value && day > todayDay.value

/** 整项结果；拆了子项、填了一部分没填完的标成 PARTIAL（显示“未填完”） */
function dayResult(parts) {
  const s = combine(parts.map(p => p.status))
  return s === 'EMPTY' && parts.length > 1 && parts.some(p => p.value !== null) ? 'PARTIAL' : s
}

/**
 * 六项指标：每个子项当月每天的值 + 整项每天的结果 + 统计（走势表页、六个走势图页共用）。
 * 达标 / 未达标天数、达标率都按整项算：所有子项都达标那天才算达标。
 */
const metricSlides = computed(() => {
  if (!d.value) return []
  const v = d.value
  const days = v.daysInMonth
  const lastDay = isCurrentMonth.value ? todayDay.value : days // 当月只统计到今天
  return v.targets.map(t => {
    const multi = t.items.length > 1
    const items = t.items.map((it, idx) => {
      const values = Array.from({ length: days }, () => null)
      for (const r of v.records) {
        if (r.itemId === it.id) values[Number(String(r.date).slice(8)) - 1] = r.value
      }
      return { ...it, values, color: multi ? SERIES_COLORS_DARK[idx % SERIES_COLORS_DARK.length] : '#3ea6ff' }
    })
    const partsOf = i => items.map(it => ({ id: it.id, name: it.name, unit: it.unit, value: it.values[i], status: judge(it.values[i], it) }))
    const dayStatus = Array.from({ length: days }, (_, i) => combine(partsOf(i).map(p => p.status)))
    let pass = 0, fail = 0, empty = 0
    for (let i = 0; i < lastDay; i++) {
      const s = dayStatus[i]
      if (s === 'PASS') pass++
      else if (s === 'FAIL') fail++
      else if (s === 'EMPTY') empty++
    }
    const recent = []
    for (let day = lastDay; day >= Math.max(1, lastDay - 6); day--) {
      const parts = partsOf(day - 1)
      recent.push({ day, parts, status: dayResult(parts) })
    }
    const today = isCurrentMonth.value
      ? partsOf(todayDay.value - 1)
      : items.map(it => ({ id: it.id, name: it.name, unit: it.unit, value: null, status: 'EMPTY' }))
    return {
      metric: t.metric, label: t.label, baseline: t.baseline, teamTarget: t.teamTarget,
      multi, items, dayStatus, pass, fail, empty, recent,
      rate: pass + fail === 0 ? null : pass / (pass + fail),
      today, todayStatus: isCurrentMonth.value ? dayResult(today) : 'EMPTY'
    }
  })
})

/** 走势表页的行：每个子项一行，拆了子项的指标再加一行“整项” */
const gridRows = computed(() => {
  const rows = []
  for (const s of metricSlides.value) {
    const span = s.items.length + (s.multi ? 1 : 0)
    s.items.forEach((it, i) => rows.push({
      key: 'i' + it.id, type: 'item', s, it, multi: s.multi, first: i === 0, span, last: !s.multi && i === s.items.length - 1
    }))
    if (s.multi) rows.push({ key: 's' + s.metric, type: 'sum', s, multi: true, first: false, span, last: true })
  }
  return rows
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

/** 走势表某一格（某个子项某天）的状态；未来日期单独标出来 */
function cellStatus(value, item, day) {
  if (isFuture(day)) return 'FUTURE'
  return judge(value, item)
}

/** 四功能小组页下面的三栏：当月显示今天的，以前的月份显示最后一天的 */
const NOTE_FIELDS = [
  { key: 'actions', label: '今日行动措施' },
  { key: 'highlights', label: '今日亮点' },
  { key: 'plans', label: '明日行动计划' }
]
const noteDay = computed(() => (!d.value ? 1 : isCurrentMonth.value ? todayDay.value : d.value.daysInMonth))
const teamNote = computed(() => {
  if (!d.value) return {}
  const date = `${d.value.month}-${String(noteDay.value).padStart(2, '0')}`
  return (d.value.notes || []).find(n => String(n.reportDate) === date) || {}
})

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

/**
 * 走势图：每个子项一条线，各自按自己的目标把未达标的点标红；
 * 单位一样的子项画在一张图里，单位不一样的上下分开画（用百分比布局，跟着大屏高度走）。
 */
const chartOption = computed(() => {
  const s = slide.value
  if (!s) return {}
  const axisText = { color: '#8fb4d9', fontSize: 14 }
  const groups = groupByUnit(s.items)
  const n = groups.length
  const xData = dayList.value.map(day => day + '号')
  const grids = [], xAxis = [], yAxis = [], series = []
  groups.forEach((g, gi) => {
    // 一张图：上下留固定像素；分开画：按百分比把高度均分给每张小图
    grids.push(n === 1
      ? { left: 64, right: 40, top: s.multi ? 44 : 40, bottom: 44 }
      : { left: 64, right: 40, top: `${7 + (gi * 86) / n}%`, height: `${86 / n - 9}%` })
    xAxis.push({
      type: 'category', data: xData, gridIndex: gi, boundaryGap: false,
      axisLabel: { ...axisText, interval: 1, show: gi === n - 1 },
      axisLine: { lineStyle: { color: 'rgba(94,178,255,0.4)' } }
    })
    yAxis.push({
      type: 'value', gridIndex: gi, scale: true,
      ...axisRange(g.items), // 范围包含目标值，目标线不会跑到图外面
      name: n > 1 ? g.unit : '', nameTextStyle: { color: '#8fb4d9', fontSize: 13, align: 'left' },
      minInterval: ['SAFETY', 'QUALITY'].includes(s.metric) ? 1 : undefined,
      axisLabel: axisText,
      splitLine: { lineStyle: { color: 'rgba(94,178,255,0.12)' } }
    })
    for (const it of g.items) {
      const hasTarget = it.targetValue !== null && it.targetValue !== undefined
      series.push({
        type: 'line',
        name: it.name || s.label,
        color: it.color, // 图例的颜色
        xAxisIndex: gi,
        yAxisIndex: gi,
        data: it.values,
        connectNulls: true,
        symbolSize: 10,
        lineStyle: { width: 3, color: it.color },
        itemStyle: { color: p => (judge(p.value ?? null, it) === 'FAIL' ? '#ff5b5b' : it.color) },
        label: {
          show: true,
          position: 'top',
          fontSize: 13,
          color: '#dce9f7',
          formatter: p => (p.value === null || p.value === undefined ? '' : String(p.value))
        },
        areaStyle: s.multi ? undefined : {
          color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(62,166,255,0.35)' }, { offset: 1, color: 'rgba(62,166,255,0)' }] }
        },
        markLine: !hasTarget ? undefined : {
          symbol: 'none',
          lineStyle: { type: 'dashed', color: s.multi ? it.color : '#ffb020', width: 2 },
          label: {
            formatter: (s.multi ? (it.name || '') + ' ' : '') + '目标 ' + fmtNum(it.targetValue),
            color: s.multi ? it.color : '#ffb020', fontSize: 14, position: 'insideEndTop'
          },
          data: [{ yAxis: it.targetValue }]
        }
      })
    }
  })
  return {
    grid: grids,
    legend: s.multi ? { top: 0, right: 40, textStyle: { color: '#dce9f7', fontSize: 15 }, itemHeight: 10 } : undefined,
    tooltip: {
      trigger: 'axis',
      formatter: ps => {
        const lines = ps.map(p => {
          const it = s.items.find(x => (x.name || s.label) === p.seriesName) || {}
          const val = p.value === null || p.value === undefined ? '未填' : p.value + (it.unit || '')
          return s.multi ? `${p.marker}${escapeHtml(p.seriesName)}：${escapeHtml(val)}` : escapeHtml(val)
        })
        return s.multi ? `${ps[0].name}<br/>${lines.join('<br/>')}` : `${ps[0].name}：${lines[0]}`
      }
    },
    xAxis,
    yAxis,
    series
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
.stat-today.st-PARTIAL .stat-sub { color: #ffb020; }

/* 今日：拆了子项时每个子项一行，各自红绿 */
.today-items {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 8px 0 6px;
}

.today-item {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 2px 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.04);
  color: #6f86a3;
}

.today-name { font-size: 18px; font-weight: 700; }
.today-val { font-size: 30px; font-weight: 800; }
.today-val small { margin-left: 3px; font-size: 14px; opacity: 0.8; }
.today-item.st-PASS { color: #3ddc84; background: rgba(61, 220, 132, 0.12); }
.today-item.st-FAIL { color: #ff5b5b; background: rgba(255, 91, 91, 0.14); }
.today-item.st-NO_TARGET { color: #ffcf73; }

/* 最近 7 天里每个子项的值各自着色 */
.recent-sep { margin: 0 4px; color: #6f86a3; font-weight: 400; }
.v-PASS { color: #3ddc84; }
.v-FAIL { color: #ff5b5b; }
.v-EMPTY { color: #6f86a3; }
.recent-row.st-PARTIAL .recent-tag { background: rgba(255, 176, 32, 0.18); color: #ffb020; }

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

/* 照片下面三栏：今日行动措施 / 今日亮点 / 明日行动计划 */
.notes-row {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: clamp(12px, 1.4vw, 24px);
  max-height: 34%;
  min-height: 0;
}

.note-card {
  min-height: 0;
  overflow: hidden;
  padding: 10px 14px;
  border-radius: 8px;
  background: rgba(43, 130, 255, 0.1);
  border: 1px solid rgba(94, 178, 255, 0.28);
}

.note-title {
  font-size: clamp(16px, 1.1vw, 21px);
  font-weight: 800;
  color: #ffcf73;
  margin-bottom: 4px;
}

.note-day {
  margin-left: 6px;
  font-size: 0.75em;
  font-weight: 600;
  color: #8fb4d9;
}

.note-text {
  font-size: clamp(15px, 1.05vw, 20px);
  line-height: 1.6;
  color: #eaf4ff;
  white-space: pre-wrap;
  word-break: break-word;
}

.note-text.empty {
  color: #6f86a3;
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

/* 拆了子项的指标：“整项”一行，所有子项都达标 ✓ */
.trend-table .sum-goal {
  font-size: 0.85em;
  color: #ffcf73;
}

.sum-cell { background: rgba(255, 255, 255, 0.02); color: #6f86a3; }
.sum-PASS { background: rgba(61, 220, 132, 0.32); color: #3ddc84; }
.sum-FAIL { background: rgba(255, 91, 91, 0.85); color: #fff; }
.sum-FUTURE { background: transparent; }

.metric-end td,
.metric-end th {
  border-bottom: 2px solid rgba(94, 178, 255, 0.55) !important;
}

/* 0.116、2188 这种 5 位以上的数，字号缩一点，免得跟旁边的格子挤在一起 */
.trend-table td.long { font-size: 0.78em; letter-spacing: -0.3px; }

.cell-PASS { background: rgba(61, 220, 132, 0.2); color: #3ddc84; }
.cell-FAIL { background: rgba(255, 91, 91, 0.7); color: #fff; }
.cell-EMPTY { background: rgba(255, 255, 255, 0.03); }
.cell-NO_TARGET { background: rgba(255, 176, 32, 0.15); color: #ffcf73; }
.cell-FUTURE { background: transparent; }
</style>
