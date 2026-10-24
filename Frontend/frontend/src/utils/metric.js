// 指标子项的判定和显示（跟后端 MetricStatus / MetricJudge 同一套规则）
// 每项指标至少一个子项；拆成多个子项时（如 成本 → 锡球、铜球）每个子项单独判定红绿，
// 所有子项都达标，这项才算达标。
import { fmtNum } from '@/utils/format'

/** 一个子项的结果：v 是数字或 null；LE 当前值 ≤ 目标为达标，GE 当前值 ≥ 目标为达标 */
export function judge(v, item) {
  if (v === null || v === undefined) return 'EMPTY'
  if (!item || item.targetValue === null || item.targetValue === undefined) return 'NO_TARGET'
  return (item.compareOp === 'GE' ? v >= item.targetValue : v <= item.targetValue) ? 'PASS' : 'FAIL'
}

/** 一项指标的结果 = 子项结果合起来：有未达标 → 未达标；有没填 → 没填完；有没设目标 → 未设目标；都达标 → 达标 */
export function combine(statuses) {
  if (!statuses.length) return 'EMPTY'
  for (const s of ['INVALID', 'FAIL', 'EMPTY', 'NO_TARGET']) {
    if (statuses.includes(s)) return s
  }
  return 'PASS'
}

/** 子项的目标，如“≤ 0.22%”“≥ 2000 ㎡”（跟后端 MetricJudge.ruleText 一样）；withName 时前面带子项名 */
export function ruleText(item, withName = false) {
  const name = withName && item.name ? item.name + ' ' : ''
  if (item.targetValue === null || item.targetValue === undefined) return name + '未设'
  const unit = !item.unit ? '' : item.unit === '%' ? '%' : ' ' + item.unit
  return `${name}${item.compareOp === 'GE' ? '≥' : '≤'} ${fmtNum(item.targetValue)}${unit}`
}

/** 一项指标所有子项的规则，多个子项用“；”隔开（跟后端 MetricJudge.targetText 一样） */
export function metricRuleText(t) {
  const multi = t.items.length > 1
  return t.items.map(it => ruleText(it, multi)).join('；')
}

/** 折线颜色：一张图里多个子项时依次取用 */
export const SERIES_COLORS = ['#0070c0', '#13a8a8', '#8e5bd6', '#d4880f', '#c2387a', '#4b8f29']
export const SERIES_COLORS_DARK = ['#3ea6ff', '#3fe0d0', '#b38bff', '#ffb020', '#ff7ab8', '#8fe36a']

/** 图表提示框是按 HTML 显示的，子项名称、单位是用户填的，先转义 */
export function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

/** 把子项按单位分组：单位一样的画在一张图里，单位不一样的分开画（刻度差太多会挤在一起） */
export function groupByUnit(items) {
  const groups = []
  for (const it of items) {
    const unit = it.unit || ''
    let g = groups.find(x => x.unit === unit)
    if (!g) groups.push((g = { unit, items: [] }))
    g.items.push(it)
  }
  return groups
}
