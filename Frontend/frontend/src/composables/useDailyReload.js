// 大屏每天定时整页刷新一次（照搬 Dashboard 项目同名 composable），
// 防止电视/大屏长时间不关机导致内存越积越多、日期跨天后显示还停在昨天
import { onMounted, onUnmounted } from 'vue'

export function useDailyReload(hour = 4) {
  let timer = null

  const scheduleReload = () => {
    const now = new Date()
    const next = new Date(now.getFullYear(), now.getMonth(), now.getDate(), hour, 0, 0, 0)
    if (next <= now) next.setDate(next.getDate() + 1)
    timer = setTimeout(() => window.location.reload(), next.getTime() - now.getTime())
  }

  onMounted(scheduleReload)
  onUnmounted(() => timer && clearTimeout(timer))
}
