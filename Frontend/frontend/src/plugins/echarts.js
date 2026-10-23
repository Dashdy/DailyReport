import * as echarts from 'echarts'

export function setupEcharts(app) {
  app.config.globalProperties.$echarts = echarts
}
