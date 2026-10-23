// 应用入口：组装插件、路由，挂载到 #app
import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { setupPlugins } from './plugins'
import './assets/global.css'

const app = createApp(App)
setupPlugins(app)
app.use(router)
app.mount('#app')
