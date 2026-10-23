<template>
  <div v-loading="loading">
    <div class="page-banner">
      <div>
        <div class="crumb">每日战报 / 工序战报</div>
        <h1>📢 工序战报看板</h1>
      </div>
      <div class="banner-tools">
        <el-radio-group id="hub-filter" v-model="filter" size="default">
          <el-radio-button value="all">全部 {{ cards.length }}</el-radio-button>
          <el-radio-button value="mine">我负责的 {{ mineCount }}</el-radio-button>
          <el-radio-button value="fail">未达标 {{ failCount }}</el-radio-button>
          <el-radio-button value="empty">未填报 {{ emptyCount }}</el-radio-button>
        </el-radio-group>
        <el-date-picker id="hub-date" v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
                        :disabled-date="d => d > new Date()" @change="load" />
      </div>
    </div>

    <div class="card-grid">
      <div v-for="c in shown" :key="c.id" class="proc-card" :class="'card-' + cardStatus(c).type"
           :title="c.canEdit ? '点击查看并填报' : '点击查看'" tabindex="0" @click="open(c)" @keyup.enter="open(c)">
        <!-- 第一行：工序名 + 整体状态 -->
        <div class="card-head">
          <span class="horn" aria-hidden="true">📢</span>
          <span class="proc-name">{{ c.name }}</span>
          <span v-if="isMine(c)" class="mine-badge">我负责</span>
          <span class="status-pill">{{ cardStatus(c).text }}</span>
        </div>

        <!-- 第二行：六项指标，每项一个小方块，颜色表示达标情况 -->
        <div class="metric-chips">
          <span v-for="m in METRICS" :key="m" class="chip" :class="'chip-' + metricState(c, m)"
                :title="m + '：' + STATE_TEXT[metricState(c, m)]">{{ m }}</span>
        </div>

        <!-- 第三行：负责人 + 操作提示 -->
        <div class="card-foot">
          <span class="owners">
            <el-icon><User /></el-icon>{{ c.owners.map(o => o.username).join('、') || '未分配负责人' }}
          </span>
          <span class="go">
            {{ c.canEdit ? '填报' : '查看' }}<el-icon><ArrowRight /></el-icon>
          </span>
        </div>
      </div>
    </div>
    <el-empty v-if="!loading && !shown.length" description="没有符合条件的工序" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '@/api'
import { useAuth } from '@/composables/useAuth'
import { todayStr } from '@/utils/format'

const router = useRouter()
const { state } = useAuth()

const date = ref(todayStr())
const cards = ref([])
const loading = ref(false)
const filter = ref('all')

async function load() {
  loading.value = true
  try {
    const res = await api.processCards(date.value)
    if (res.success) cards.value = res.data
    else ElMessage.error(res.message)
  } finally {
    loading.value = false
  }
}
onMounted(load)

const isMine = c => c.owners.some(o => o.id === state.user?.id)
const mineCount = computed(() => cards.value.filter(isMine).length)
const failCount = computed(() => cards.value.filter(c => c.fail > 0).length)
const emptyCount = computed(() => cards.value.filter(c => c.filled === 0).length)

const shown = computed(() => {
  switch (filter.value) {
    case 'mine': return cards.value.filter(isMine)
    case 'fail': return cards.value.filter(c => c.fail > 0)
    case 'empty': return cards.value.filter(c => c.filled === 0)
    default: return cards.value
  }
})

const METRICS = ['安全', '品质', '交付', '报废', '成本', '设备']
const STATE_TEXT = { pass: '达标', fail: '未达标', empty: '未填报', notarget: '已填，未设目标' }

/** 某项指标在这张卡片上的状态 */
function metricState(c, m) {
  if (c.failMetrics.includes(m)) return 'fail'
  if (c.passMetrics.includes(m)) return 'pass'
  if (c.emptyMetrics.includes(m)) return 'empty'
  return 'notarget'
}

/**
 * 卡片整体状态：未达标 > 全部达标 > 部分已填 > 未填报
 * （有未达标就先亮红，方便追责；全部达标亮绿，方便奖励）
 */
function cardStatus(c) {
  if (c.fail) return { type: 'fail', text: `${c.fail}项未达标` }
  if (c.pass === 6) return { type: 'pass', text: '全部达标' }
  if (c.filled > 0) return { type: 'part', text: `已填 ${c.filled}/6` }
  return { type: 'empty', text: '未填报' }
}

function open(c) {
  router.push({ path: `/processes/${c.id}`, query: { date: date.value } })
}
</script>

<style scoped>
/* ---------- 卡片网格：等宽、等高 ---------- */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 14px;
}

/* 白底卡片，左侧色条表示整体状态：红 未达标 / 绿 全部达标 / 橙 部分已填 / 灰 未填报 */
.proc-card {
  --accent: #c0c4cc;
  --accent-soft: #f4f5f7;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px 10px;
  border-radius: 8px;
  border: 1px solid #e8ecf1;
  border-left: 4px solid var(--accent);
  background: #fff;
  box-shadow: 0 1px 3px rgba(16, 36, 77, 0.06);
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s, border-color 0.15s;
  min-width: 0;
}

.proc-card:hover,
.proc-card:focus-visible {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(16, 36, 77, 0.12);
  outline: none;
}

.card-fail { --accent: #e5484d; --accent-soft: #fdecec; }
.card-pass { --accent: #2e9e5b; --accent-soft: #e6f5ec; }
.card-part { --accent: #e6a23c; --accent-soft: #fdf3e2; }
.card-empty { --accent: #b8bec8; --accent-soft: #f2f3f5; }

/* ---------- 第一行：名称 + 状态 ---------- */
.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.horn {
  font-size: 16px;
  line-height: 1;
  flex-shrink: 0;
}

.proc-name {
  font-size: 15px;
  font-weight: 700;
  color: #1a2b4c;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mine-badge {
  flex-shrink: 0;
  padding: 0 6px;
  border-radius: 9px;
  font-size: 11px;
  line-height: 18px;
  background: #fff0f2;
  color: #e0475e;
}

.status-pill {
  margin-left: auto;
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  background: var(--accent-soft);
  color: var(--accent);
}

.card-empty .status-pill {
  color: #7d8592;
}

/* ---------- 第二行：六项指标小方块 ---------- */
.metric-chips {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 4px;
}

.chip {
  height: 24px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 22px;
  text-align: center;
  border: 1px solid transparent;
  white-space: nowrap;
}

.chip-pass {
  background: #e6f5ec;
  color: #1f7a45;
}

.chip-fail {
  background: #e5484d;
  color: #fff;
  font-weight: 600;
}

.chip-empty {
  background: #fff;
  color: #b0b6bf;
  border: 1px dashed #d5d9df;
}

.chip-notarget {
  background: #fdf6e3;
  color: #a67c00;
}

/* ---------- 第三行：负责人 + 操作 ---------- */
.card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px dashed #edf0f4;
  font-size: 12px;
}

.owners {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  color: #8a96a3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.go {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: #409eff;
  opacity: 0.75;
  transition: opacity 0.15s;
}

.proc-card:hover .go {
  opacity: 1;
}
</style>
