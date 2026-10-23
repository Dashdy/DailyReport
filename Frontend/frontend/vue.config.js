const { defineConfig } = require('@vue/cli-service')
module.exports = defineConfig({
  transpileDependencies: true,
  // 跟 Dashboard 项目一致：关闭编译时的严格语法检查
  lintOnSave: false,
  devServer: {
    // 前端端口 8084（Dashboard 前端 8081、后端 8082，本项目后端 8083）
    port: 8084,
    open: false,
    client: {
      overlay: {
        warnings: false,
        errors: true,
        // 过滤浏览器无害的 ResizeObserver 警告，Element Plus 表格很容易触发
        runtimeErrors: (error) => !(error && error.message && error.message.includes('ResizeObserver loop'))
      }
    }
  },
  chainWebpack: config => {
    config.plugin('html').tap(args => {
      args[0].title = '每日战报系统'
      return args
    })
  }
})
