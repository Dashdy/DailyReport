// 插件总管：Element Plus、Pinia、ECharts
import { setupElement } from './element'
import { setupPinia } from './pinia'
import { setupEcharts } from './echarts'

export function setupPlugins(app) {
  setupElement(app)
  setupPinia(app)
  setupEcharts(app)
}
