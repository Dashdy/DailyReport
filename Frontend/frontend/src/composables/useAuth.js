// 登录状态：Token 和用户资料存在 localStorage，刷新页面不掉线；
// 每次进入系统会调 /api/me 拉一次最新的角色和负责工序（管理员可能刚改过）。
import { reactive, computed } from 'vue'
import api from '@/api'

const TOKEN_KEY = 'authToken'
const USER_KEY = 'userInfo'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

const state = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  user: readUser()
})

function setSession(data) {
  const { token, ...user } = data
  if (token) {
    state.token = token
    localStorage.setItem(TOKEN_KEY, token)
  }
  state.user = user
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

function clearSession() {
  state.token = ''
  state.user = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

async function refreshMe() {
  const res = await api.me()
  if (res.success) setSession(res.data)
}

async function logout() {
  try {
    await api.logout()
  } finally {
    clearSession()
  }
}

const isLoggedIn = computed(() => !!state.token && !!state.user)
const isAdmin = computed(() => !!state.user && (state.user.role === 'SYS_ADMIN' || state.user.role === 'ADMIN'))
const isSysAdmin = computed(() => !!state.user && state.user.role === 'SYS_ADMIN')

export function useAuth() {
  return { state, isLoggedIn, isAdmin, isSysAdmin, setSession, clearSession, refreshMe, logout }
}
