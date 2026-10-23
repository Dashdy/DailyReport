import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn' // 日期选择器、分页等组件显示中文
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

export function setupElement(app) {
  app.use(ElementPlus, { locale: zhCn })
  // 图标注册成全局组件，菜单里可以直接用字符串图标名
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }
}
