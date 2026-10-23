// 大屏全屏切换（照搬 Dashboard 项目同名 composable）
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'

export function useFullscreen() {
  const containerRef = ref(null)
  const isFullscreen = ref(false)

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      if (containerRef.value) {
        containerRef.value.requestFullscreen()
          .then(() => ElMessage.success('已切入大屏模式'))
          .catch(err => ElMessage.error(`全屏失败：${err.message}`))
      }
    } else {
      document.exitFullscreen()
    }
  }

  const handleFullscreenChange = () => {
    isFullscreen.value = !!document.fullscreenElement
  }

  onMounted(() => document.addEventListener('fullscreenchange', handleFullscreenChange))
  onUnmounted(() => document.removeEventListener('fullscreenchange', handleFullscreenChange))

  return { containerRef, isFullscreen, toggleFullscreen }
}
