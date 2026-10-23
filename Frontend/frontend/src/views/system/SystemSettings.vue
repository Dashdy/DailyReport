<template>
  <div v-loading="loading">
    <div class="page-banner">
      <div>
        <div class="crumb">系统管理 / 系统设置</div>
        <h1>系统设置</h1>
      </div>
      <div class="banner-tools banner-note">仅系统管理员、管理员可见</div>
    </div>

    <!-- 数据锁定：两个开关系统管理员、管理员都能拨；锁定时间只有系统管理员能改 -->
    <div class="panel storage">
      <h2 class="panel-title">数据锁定</h2>
      <p class="tip">
        控制工序负责人什么时候还能填写、修改每日走势表。系统管理员、管理员不受锁定限制，修改都会记入操作日志。
      </p>
      <template v-if="lock">
        <div class="lock-rows">
          <!-- 历史数据锁定 -->
          <div class="lock-row">
            <div class="lock-main">
              <div class="lock-name">历史数据锁定</div>
              <div class="lock-desc">开启后，前一天及以前的数据负责人不能修改（锁定时间设为“次日”时，前一天的数据在锁定时间前仍可补填）。</div>
              <div v-if="lock.historyUpdatedBy" class="lock-meta">最后操作：{{ lock.historyUpdatedBy }} · {{ fmtTime(lock.historyUpdatedAt) }}</div>
            </div>
            <el-switch id="setting-history-lock" :model-value="lock.historyLock" :loading="switching === 'history'"
                       inline-prompt active-text="开" inactive-text="关" size="large"
                       @change="v => toggle('history', v)" />
          </div>

          <!-- 锁定时间（当天 / 次日） -->
          <div class="lock-row">
            <div class="lock-main">
              <div class="lock-name">
                锁定时间
                <el-tag v-if="!isSysAdmin" size="small" type="info">时间仅系统管理员可修改</el-tag>
              </div>
              <div class="lock-desc">
                开启后，每天的数据到 <b class="num">{{ lock.lockRule }}</b> 锁定，负责人不能再填写或修改；关闭则当天全天可填。
              </div>
              <div class="lock-desc example">
                <template v-if="lock.lockDay === 1">例：23 号的数据，负责人可以填到 24 号 {{ lock.lockTime }}（适合夜班第二天早上补填）。</template>
                <template v-else>例：23 号的数据，23 号 {{ lock.lockTime }} 以后负责人就不能再改。</template>
              </div>
              <div v-if="lock.enabledUpdatedBy" class="lock-meta">开关最后操作：{{ lock.enabledUpdatedBy }} · {{ fmtTime(lock.enabledUpdatedAt) }}</div>
              <div v-if="lock.updatedBy" class="lock-meta">时间最后修改：{{ lock.updatedBy }} · {{ fmtTime(lock.updatedAt) }}</div>
              <div v-if="isSysAdmin" class="lock-time-edit">
                <el-select id="setting-lock-day" v-model="newLockDay" style="width: 90px">
                  <el-option label="当天" :value="0" />
                  <el-option label="次日" :value="1" />
                </el-select>
                <el-time-picker id="setting-lock-time" v-model="newLockTime" format="HH:mm" value-format="HH:mm"
                                placeholder="选择时间" :clearable="false" style="width: 140px" />
                <el-button type="primary" :loading="savingLock"
                           :disabled="!newLockTime || (newLockTime === lock.lockTime && newLockDay === lock.lockDay)"
                           @click="saveLock">保存锁定时间</el-button>
              </div>
            </div>
            <el-switch id="setting-lock-enabled" :model-value="lock.enabled" :loading="switching === 'time'"
                       inline-prompt active-text="开" inactive-text="关" size="large"
                       @change="v => toggle('time', v)" />
          </div>
        </div>

        <div class="lock-now">
          此刻负责人：
          <span :class="lock.ownerTodayEditable ? 'ok' : 'locked'">{{ lock.ownerTodayEditable ? '可以填写今天的数据' : '今天的数据已锁定' }}</span>；
          <span :class="lock.ownerYesterdayEditable ? 'ok' : 'locked'">{{ lock.ownerYesterdayEditable ? '昨天的数据还能补填' : '昨天的数据已锁定' }}</span>；
          <span :class="lock.historyLock ? 'locked' : 'ok'">{{ lock.historyLock ? '更早的数据已锁定' : '以前的数据可以修改' }}</span>
        </div>
      </template>
    </div>

    <div class="panel storage">
      <h2 class="panel-title">图片存储路径</h2>
      <p class="tip">
        班组照片、组员签名照片等上传的图片保存在后端服务器的这个文件夹里（按月份分子文件夹）。
        建议放在空间充足、会定期备份的磁盘上。
      </p>

      <template v-if="info">
        <div class="facts">
          <div class="fact">
            <div class="fact-label">当前路径</div>
            <div class="fact-value path">
              {{ info.uploadDir }}
              <el-tag v-if="info.isDefault" size="small" type="info">默认</el-tag>
              <el-tag v-if="!info.exists" size="small" type="warning">文件夹还没创建，第一次上传时会自动创建</el-tag>
            </div>
          </div>
          <div class="fact">
            <div class="fact-label">已存图片</div>
            <div class="fact-value num">{{ info.fileCount }} 张 · {{ fmtSize(info.totalBytes) }}</div>
          </div>
          <div class="fact">
            <div class="fact-label">所在磁盘剩余</div>
            <div class="fact-value num">
              {{ info.usableBytes === null ? '—' : fmtSize(info.usableBytes) + ' / ' + fmtSize(info.totalSpaceBytes) }}
            </div>
          </div>
          <div v-if="info.updatedBy" class="fact">
            <div class="fact-label">最后修改</div>
            <div class="fact-value">{{ info.updatedBy }} · {{ fmtTime(info.updatedAt) }}</div>
          </div>
        </div>

        <el-form label-width="110px" class="form" @submit.prevent>
          <el-form-item label="新的存储路径">
            <el-input id="setting-upload-dir" v-model="newDir" placeholder="完整路径，例如 D:\DailyReport\uploads" clearable />
          </el-form-item>
          <el-form-item label="已有图片">
            <el-checkbox id="setting-migrate" v-model="migrate">把已有图片复制到新路径</el-checkbox>
            <div class="hint">
              不勾选也不会丢图：系统会继续到原来的路径里找旧图片，只是新图片存到新路径。
            </div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" :disabled="!newDir.trim()" @click="save">保存路径</el-button>
          </el-form-item>
        </el-form>

        <div v-if="info.history.length" class="history">
          <div class="fact-label">以前用过的路径（仍会到这里找旧图片）</div>
          <ul>
            <li v-for="h in info.history" :key="h" class="path">{{ h }}</li>
          </ul>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '@/api'
import { useAuth } from '@/composables/useAuth'
import { fmtTime } from '@/utils/format'

const { isSysAdmin } = useAuth()

const info = ref(null)
const loading = ref(false)
const saving = ref(false)
const newDir = ref('')
const migrate = ref(true)

const lock = ref(null)
const newLockTime = ref('')
const newLockDay = ref(0) // 0 = 当天，1 = 次日
const savingLock = ref(false)
const switching = ref('')

/** 拨开关：history = 历史数据锁定，time = 锁定时间 */
async function toggle(which, enabled) {
  switching.value = which
  try {
    const res = which === 'history' ? await api.saveHistoryLock(enabled) : await api.saveLockEnabled(enabled)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    lock.value = res.data
  } finally {
    switching.value = ''
  }
}

async function load() {
  loading.value = true
  try {
    const [s, l] = await Promise.all([api.storage(), api.lockSetting()])
    if (s.success) info.value = s.data
    else ElMessage.error(s.message)
    if (l.success) {
      lock.value = l.data
      newLockTime.value = l.data.lockTime
      newLockDay.value = l.data.lockDay
    }
  } finally {
    loading.value = false
  }
}

async function saveLock() {
  try {
    const rule = (newLockDay.value === 1 ? '次日 ' : '当天 ') + newLockTime.value
    await ElMessageBox.confirm(`改为每天的数据在“${rule}”锁定？保存后立即生效。`, '修改锁定时间', {
      type: 'warning', confirmButtonText: '确定修改'
    })
  } catch {
    return
  }
  savingLock.value = true
  try {
    const res = await api.saveLockSetting(newLockTime.value, newLockDay.value)
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    lock.value = res.data
  } finally {
    savingLock.value = false
  }
}
onMounted(load)

async function save() {
  try {
    await ElMessageBox.confirm(
      `把图片存储路径改为：\n${newDir.value.trim()}\n\n${migrate.value ? '会把已有图片复制过去（原文件保留）。' : '已有图片留在原路径，不复制。'}`,
      '修改存储路径', { type: 'warning', confirmButtonText: '确定修改' })
  } catch {
    return
  }
  saving.value = true
  try {
    const res = await api.saveStorage({ uploadDir: newDir.value.trim(), migrate: migrate.value })
    if (!res.success) {
      ElMessage.error(res.message)
      return
    }
    ElMessage.success(res.message)
    info.value = res.data
    newDir.value = ''
  } finally {
    saving.value = false
  }
}

function fmtSize(bytes) {
  if (bytes === null || bytes === undefined) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let v = bytes
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return (i === 0 ? v : v.toFixed(1)) + ' ' + units[i]
}
</script>

<style scoped>
.banner-note {
  font-size: 13px;
  opacity: 0.9;
}

.storage {
  max-width: 900px;
  margin-bottom: 16px;
}

.lock-rows {
  display: grid;
  gap: 12px;
}

.lock-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 16px;
  border-radius: 6px;
  background: #f5f8fb;
  border: 1px solid #e4ebf2;
}

.lock-main {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.lock-name {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 8px;
}

.lock-desc.example {
  color: #8a96a3;
  font-size: 12px;
}

.lock-desc {
  font-size: 13px;
  color: #4a5562;
}

.lock-meta {
  font-size: 12px;
  color: #8a96a3;
}

.lock-time-edit {
  display: flex;
  gap: 10px;
  margin-top: 6px;
  flex-wrap: wrap;
}

.lock-now {
  margin-top: 14px;
  font-size: 13px;
  color: #4a5562;
}

.ok {
  color: var(--dr-pass);
}

.locked {
  color: var(--dr-fail);
}

.tip {
  margin: 0 0 18px;
  font-size: 13px;
  color: #6b7785;
  line-height: 1.6;
}

.facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px;
  margin-bottom: 22px;
}

.fact {
  padding: 12px 14px;
  border-radius: 6px;
  background: #f5f8fb;
  border: 1px solid #e4ebf2;
  min-width: 0;
}

.fact:first-child {
  grid-column: 1 / -1;
}

.fact-label {
  font-size: 12px;
  color: #8a96a3;
  margin-bottom: 4px;
}

.fact-value {
  font-size: 15px;
  font-weight: 600;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.path {
  font-family: Consolas, "Courier New", monospace;
  word-break: break-all;
}

.form {
  max-width: 640px;
}

.hint {
  width: 100%;
  font-size: 12px;
  color: #8a96a3;
  line-height: 1.5;
}

.history ul {
  margin: 6px 0 0;
  padding-left: 20px;
  font-size: 13px;
  color: #5c6b7a;
}
</style>
