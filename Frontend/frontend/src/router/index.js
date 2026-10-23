// 路由 + 全局守卫：没登录去 /login；meta.admin 的页面只有系统管理员 / 管理员能进，meta.sysAdmin 只有系统管理员能进
// “系统管理”分组下的页面全部是 meta.admin，普通用户整组都看不到、进不去
import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '@/composables/useAuth'

const routes = [
  { path: '/login', component: () => import('@/views/login/UserLogin.vue'), meta: { standalone: true, title: '登录' } },
  { path: '/', component: () => import('@/views/home/HomePage.vue'), meta: { title: '全厂达标汇总', icon: 'DataAnalysis', menu: 'report' } },
  { path: '/processes', component: () => import('@/views/process/ProcessHub.vue'), meta: { title: '工序战报', icon: 'Grid', menu: 'report' } },
  { path: '/processes/:id', component: () => import('@/views/process/ProcessDetail.vue'), meta: { title: '工序详情', activeMenu: '/processes' } },
  // 工序趋势图轮播大屏（工序详情页“查看趋势图”进入）：不套侧边栏，登录用户都能看
  { path: '/processes/:id/carousel', component: () => import('@/views/process/ProcessCarousel.vue'), meta: { title: '趋势图轮播', standalone: true } },
  { path: '/system/users', component: () => import('@/views/system/UserManage.vue'), meta: { title: '用户管理', icon: 'User', menu: 'system', admin: true } },
  { path: '/system/assign', component: () => import('@/views/system/ProcessAssign.vue'), meta: { title: '工序负责人分配', icon: 'Connection', menu: 'system', admin: true } },
  { path: '/system/processes', component: () => import('@/views/system/ProcessConfig.vue'), meta: { title: '工序配置', icon: 'SetUp', menu: 'system', admin: true } },
  { path: '/system/settings', component: () => import('@/views/system/SystemSettings.vue'), meta: { title: '系统设置', icon: 'Tools', menu: 'system', admin: true } },
  { path: '/system/audit', component: () => import('@/views/system/AuditLog.vue'), meta: { title: '操作日志', icon: 'Document', menu: 'system', admin: true } },
  { path: '/account/password', component: () => import('@/views/account/ChangePassword.vue'), meta: { title: '修改密码' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(process.env.BASE_URL),
  routes
})

router.beforeEach(to => {
  const { isLoggedIn, isAdmin, isSysAdmin } = useAuth()
  if (to.path === '/login') {
    return isLoggedIn.value ? '/' : true
  }
  if (!isLoggedIn.value) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if ((to.meta.admin && !isAdmin.value) || (to.meta.sysAdmin && !isSysAdmin.value)) {
    return '/'
  }
  return true
})

router.afterEach(to => {
  document.title = (to.meta.title ? to.meta.title + ' - ' : '') + '每日战报系统'
})

/** 侧边栏菜单：按 meta.menu 分组；meta.admin 只给管理员看，meta.sysAdmin 只给系统管理员看 */
export function buildMenu(isAdmin, isSysAdmin) {
  const groups = [
    { key: 'report', title: '每日战报', icon: 'TrendCharts', items: [] },
    { key: 'system', title: '系统管理', icon: 'Setting', items: [] }
  ]
  for (const r of routes) {
    if (!r.meta || !r.meta.menu || (r.meta.admin && !isAdmin) || (r.meta.sysAdmin && !isSysAdmin)) continue
    groups.find(g => g.key === r.meta.menu).items.push({ path: r.path, title: r.meta.title, icon: r.meta.icon })
  }
  return groups.filter(g => g.items.length)
}

export default router
