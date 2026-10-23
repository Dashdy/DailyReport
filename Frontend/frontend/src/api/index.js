// 后端接口汇总。后端统一返回 { success, message, data }
import request from '@/utils/request'

const get = (url, params) => request.get(url, { params }).then(r => r.data)
const post = (url, body) => request.post(url, body).then(r => r.data)
const put = (url, body) => request.put(url, body).then(r => r.data)
const del = url => request.delete(url).then(r => r.data)

export default {
  // 登录
  login: body => post('/api/login', body),
  logout: () => post('/api/logout'),
  me: () => get('/api/me'),
  changePassword: body => put('/api/me/password', body),

  // 首页
  dashboard: date => get('/api/dashboard', { date }),

  // 工序
  processCards: date => get('/api/processes', { date }),
  processAll: () => get('/api/processes/all'),
  createProcess: body => post('/api/processes', body),
  updateProcess: (id, body) => put(`/api/processes/${id}`, body),
  setOwners: (id, ids) => put(`/api/processes/${id}/owners`, { ids }),

  // 工序详情
  detail: (id, month) => get(`/api/processes/${id}/detail`, { month }),
  saveRecords: (id, cells) => put(`/api/processes/${id}/records`, { cells }),
  saveTargets: (id, targets) => put(`/api/processes/${id}/targets`, { targets }),
  saveBoard: (id, body) => put(`/api/processes/${id}/board`, body),
  snapshots: (id, date) => get(`/api/processes/${id}/snapshots`, { date }),
  processLogs: (id, params) => get(`/api/processes/${id}/logs`, params),

  // 用户
  users: () => get('/api/users'),
  createUser: body => post('/api/users', body),
  updateUser: (id, body) => put(`/api/users/${id}`, body),
  deleteUser: id => del(`/api/users/${id}`),
  resetPassword: (id, password) => put(`/api/users/${id}/password`, { password }),
  assignProcesses: (id, ids) => put(`/api/users/${id}/processes`, { ids }),

  // 系统设置（系统管理员、管理员可进；锁定时间仅系统管理员可改）
  storage: () => get('/api/settings/storage'),
  saveStorage: body => put('/api/settings/storage', body),
  lockSetting: () => get('/api/settings/lock'),
  saveLockSetting: (lockTime, lockDay) => put('/api/settings/lock', { lockTime, lockDay }),
  saveLockEnabled: enabled => put('/api/settings/lock/enabled', { enabled }),
  saveHistoryLock: enabled => put('/api/settings/lock/history', { enabled }),

  // 操作日志（系统管理，仅系统管理员 / 管理员）
  auditLogs: params => get('/api/audit-logs', params),

  // 图片上传
  upload: file => {
    const form = new FormData()
    form.append('file', file)
    return request.post('/api/files', form).then(r => r.data)
  }
}
