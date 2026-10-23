<template>
  <!-- 登录页单独占满屏幕，其余页面套上侧边栏 + 顶栏外壳 -->
  <router-view v-if="route.meta.standalone" />

  <div v-else class="app-layout">
    <!-- ============ 左侧导航 ============ -->
    <aside class="sidebar" :class="{ collapsed: isCollapse }">
      <div class="brand">
        <el-icon class="brand-icon"><Promotion /></el-icon>
        <span v-show="!isCollapse" class="brand-text">每日战报系统</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        :collapse-transition="false"
        :default-openeds="['report', 'system']"
        background-color="#001529"
        text-color="#a6adb4"
        active-text-color="#409EFF"
        router
      >
        <el-sub-menu v-for="group in menu" :key="group.key" :index="group.key">
          <template #title>
            <el-icon><component :is="group.icon" /></el-icon>
            <span>{{ group.title }}</span>
          </template>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </aside>

    <!-- ============ 右侧主体 ============ -->
    <div class="main">
      <header class="topbar">
        <div class="top-left">
          <el-button text class="collapse-btn" @click="isCollapse = !isCollapse">
            <el-icon :size="18"><Fold v-if="!isCollapse" /><Expand v-else /></el-icon>
          </el-button>
          <span class="platform">WellTek 每日战报</span>
          <el-tag :type="shift.isDay ? 'success' : 'info'" effect="dark" size="small">
            当前班次：{{ shift.label }}
          </el-tag>
        </div>
        <div class="clock">厂区时间：{{ now }}</div>
        <el-dropdown trigger="click" @command="onCommand">
          <div class="user-box">
            <el-avatar :size="28" :icon="UserFilled" />
            <span class="user-name">{{ auth.state.user?.username }}</span>
            <span class="user-role">[{{ auth.state.user?.roleLabel }}]</span>
            <el-icon><ArrowDown /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password" :icon="Lock">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="content">
        <router-view :key="route.fullPath" />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { UserFilled, Lock, SwitchButton } from '@element-plus/icons-vue'
import { buildMenu } from '@/router'
import { useAuth } from '@/composables/useAuth'

const route = useRoute()
const router = useRouter()
const auth = useAuth()

const isCollapse = ref(false)
const menu = computed(() => buildMenu(auth.isAdmin.value, auth.isSysAdmin.value))
const activeMenu = computed(() => route.meta.activeMenu || route.path)

// 顶栏时钟 + 班次（白班 08:00-20:00，夜班 20:00-次日 08:00）
const now = ref('')
const shift = ref({ isDay: true, label: '' })
function tick() {
  const d = new Date()
  const p = n => String(n).padStart(2, '0')
  now.value = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  const isDay = d.getHours() >= 8 && d.getHours() < 20
  shift.value = { isDay, label: isDay ? '白班 (08:00 - 20:00)' : '夜班 (20:00 - 08:00)' }
}
let timer
onMounted(() => {
  tick()
  timer = setInterval(tick, 1000)
})
onBeforeUnmount(() => clearInterval(timer))

// 进入系统后拉一次最新的角色 / 负责工序
watch(() => auth.isLoggedIn.value, loggedIn => {
  if (loggedIn) auth.refreshMe().catch(() => {})
}, { immediate: true })

async function onCommand(cmd) {
  if (cmd === 'password') {
    router.push('/account/password')
  } else if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    } catch {
      return
    }
    await auth.logout()
    router.replace('/login')
  }
}
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: var(--dr-navy);
  display: flex;
  flex-direction: column;
  transition: width 0.2s;
  overflow-y: auto;
}

.sidebar.collapsed {
  width: 64px;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 20px;
  color: #fff;
  font-weight: 700;
  font-size: 16px;
  white-space: nowrap;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-icon {
  font-size: 22px;
  color: #ff5a5f;
}

.sidebar :deep(.el-menu) {
  border-right: none;
}

.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px 0 8px;
  background: #0f1f3d;
  color: #fff;
}

.top-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.collapse-btn {
  color: #cfd8e3;
}

.platform {
  font-weight: 700;
  font-size: 16px;
}

.clock {
  padding: 4px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.08);
  color: #9cc8ff;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.user-box {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px 4px 4px;
  border-radius: 18px;
  background: #fff;
  color: #1f2d3d;
  cursor: pointer;
}

.user-name {
  font-weight: 600;
}

.user-role {
  color: #8a96a3;
  font-size: 13px;
}

.content {
  flex: 1;
  overflow: auto;
  padding: 20px 24px;
}

@media (max-width: 900px) {
  .clock,
  .platform {
    display: none;
  }
}
</style>
