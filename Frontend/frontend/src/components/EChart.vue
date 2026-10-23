<template>
  <div ref="el" class="echart" :style="{ height }"></div>
</template>

<script setup>
// ECharts 简单封装：option 变了就重画，容器尺寸变了就自适应
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '260px' }
})

const el = ref()
let chart
let observer

onMounted(() => {
  chart = echarts.init(el.value)
  chart.setOption(props.option, true)
  observer = new ResizeObserver(() => chart && chart.resize())
  observer.observe(el.value)
})

watch(() => props.option, opt => chart && chart.setOption(opt, true), { deep: true })

onBeforeUnmount(() => {
  observer && observer.disconnect()
  chart && chart.dispose()
})
</script>

<style scoped>
.echart {
  width: 100%;
}
</style>
