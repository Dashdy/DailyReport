// axios 实例：自动带 Token；后端返回 401（未登录 / 过期 / 账号被停用）时清掉本地登录状态并回到登录页
import axios from 'axios'
import { ElMessage } from 'element-plus'

const request = axios.create({
  baseURL: process.env.VUE_APP_API_BASE_URL,
  timeout: 15000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('authToken')
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

let redirecting = false

request.interceptors.response.use(
  response => response,
  error => {
    const status = error.response && error.response.status
    const message = error.response && error.response.data && error.response.data.message
    if (status === 401 && !redirecting) {
      redirecting = true
      ElMessage.error(message || '登录已过期，请重新登录')
      localStorage.removeItem('authToken')
      localStorage.removeItem('userInfo')
      setTimeout(() => { window.location.href = '/login' }, 800)
    } else if (status === 403) {
      ElMessage.error(message || '权限不足')
    } else if (status !== 401) {
      ElMessage.error(message || '网络异常，请检查后端服务是否启动')
    }
    return Promise.reject(error)
  }
)

export default request
