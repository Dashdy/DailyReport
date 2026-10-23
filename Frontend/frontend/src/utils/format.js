// 显示格式化的小工具

/** 后端返回的图片相对路径（/uploads/...）拼成完整地址 */
export function fileUrl(path) {
  if (!path) return ''
  if (/^https?:\/\//.test(path)) return path
  return (process.env.VUE_APP_API_BASE_URL || '') + path
}

/** 数值去掉多余的 0：3.10 → 3.1 */
export function fmtNum(v) {
  if (v === null || v === undefined || v === '') return ''
  return String(Number(v))
}

/** 0~1 的比例 → 百分比文字，null 显示 — */
export function fmtRate(v) {
  if (v === null || v === undefined) return '—'
  return (v * 100).toFixed(1).replace(/\.0$/, '') + '%'
}

/** 2026-10-23T09:43:20.527 → 2026-10-23 09:43:20 */
export function fmtTime(v) {
  if (!v) return ''
  return String(v).replace('T', ' ').slice(0, 19)
}

/** Date → yyyy-MM-dd（本地时区） */
export function toDateStr(d) {
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

export function todayStr() {
  return toDateStr(new Date())
}

export const STATUS_TEXT = { PASS: '达标', FAIL: '未达标', EMPTY: '未填报', NO_TARGET: '未设目标' }
