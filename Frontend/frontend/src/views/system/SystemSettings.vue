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

    <!-- 企业微信推送：系统管理员、管理员都能配置 -->
    <div class="panel storage">
      <h2 class="panel-title">企业微信推送</h2>
      <p class="tip">
        把工序战报推送到企业微信：<b>ID助手应用</b>推给个人（负责人 + 下面的接收人），<b>群机器人</b>推到群里。
        两个渠道配了哪个就用哪个，都配了就都发。
      </p>
      <template v-if="push">
        <div class="facts push-facts">
          <div class="fact">
            <div class="fact-label">ID助手应用（推送给个人）</div>
            <div class="fact-value">
              <el-tag v-if="push.appConfigured" type="success">已配置</el-tag>
              <el-tag v-else type="info">未配置</el-tag>
            </div>
            <div class="hint">三个参数写在服务器的配置文件 application-prod.properties 里（app.wecom.*），页面上不显示</div>
          </div>
          <div class="fact">
            <div class="fact-label">群机器人（推送到群）</div>
            <div class="fact-value">
              <el-tag v-if="push.webhookConfigured" type="success">已设置</el-tag>
              <el-tag v-else type="info">未设置</el-tag>
            </div>
          </div>
        </div>

        <el-form label-width="120px" class="form push-form" @submit.prevent>
          <el-form-item label="自动推送">
            <el-switch id="push-enabled" v-model="pushForm.enabled" active-text="开启" inactive-text="关闭" />
            <el-time-picker id="push-time" v-model="pushForm.time" format="HH:mm" value-format="HH:mm" :clearable="false"
                            style="width: 120px; margin-left: 16px" />
            <div class="hint">
              每天到这个时间，推送当天已填报的各工序战报 + 一条全厂汇总（没填报的工序合成一条“今日未填报”）。
              负责人手动推过、之后数据没变的工序不再重复推；推过之后又改了数据的，会再推一次最新的。
              <template v-if="push.lastAuto">上次自动推送：{{ push.lastAuto }}</template>
            </div>
          </el-form-item>
          <el-form-item label="活动名称">
            <el-input id="push-title" v-model="pushForm.title" maxlength="30" placeholder="可不填，例如：xx年前闪电战" style="width: 320px" />
            <div class="hint">填了会加在战报标题前面：【xx年前闪电战】开料 10-24 日战报</div>
          </el-form-item>
          <el-form-item label="接收人">
            <el-select id="push-receivers" v-model="pushForm.receivers" multiple filterable placeholder="选择每天要收到战报的人（如领导）"
                       style="width: 100%">
              <el-option v-for="u in users" :key="u.id" :value="u.id" :disabled="!u.wecomUserId"
                         :label="u.username + (u.wecomUserId ? '' : '（未登记企业微信账号）')" />
            </el-select>
            <div class="hint">接收人会收到所有工序的战报和全厂汇总；每道工序的负责人另外会收到自己工序的战报。没登记企业微信账号的人选不了，去“用户管理”里填。</div>
          </el-form-item>
          <el-form-item label="群机器人地址">
            <el-input id="push-webhook" v-model="pushForm.webhook" :placeholder="push.webhookPrefix + '…（在群机器人资料页复制，可不填）'" clearable />
            <div class="hint">相当于密码，保存后只显示开头一部分。清空并保存 = 不再推送到群。</div>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingPush" @click="savePush">保存推送设置</el-button>
            <el-button :loading="testingPush" @click="testPush">发送测试消息</el-button>
            <el-button :loading="runningPush" @click="runPush">立即推送今天的战报</el-button>
          </el-form-item>
        </el-form>

        <div class="push-logs">
          <div class="fact-label">最近推送记录</div>
          <el-table :data="push.logs" size="small" border max-height="280">
            <el-table-column label="时间" width="150"><template #default="{ row }">{{ fmtTime(row.createdAt) }}</template></el-table-column>
            <el-table-column label="内容" width="150">
              <template #default="{ row }">{{ row.kind === 'SUMMARY' ? '全厂汇总' : row.kind === 'TEST' ? '测试消息' : (row.processName || '') + ' 战报' }}</template>
            </el-table-column>
            <el-table-column label="方式" width="90">
              <template #default="{ row }">{{ { MANUAL: '手动', AUTO: '自动', TEST: '测试' }[row.trigger] }}</template>
            </el-table-column>
            <el-table-column label="操作人" prop="operator" width="90" />
            <el-table-column label="结果" width="70" align="center">
              <template #default="{ row }"><el-tag :type="row.success ? 'success' : 'danger'" size="small">{{ row.success ? '成功' : '失败' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="说明" prop="message" min-width="260" />
          </el-table>
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
import { h, onMounted, reactive, ref } from 'vue'
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

const push = ref(null)
const users = ref([])
const pushForm = reactive({ enabled: false, time: '20:30', title: '', receivers: [], webhook: '' })
const savingPush = ref(false)
const testingPush = ref(false)
const runningPush = ref(false)

function fillPush(data) {
  push.value = data
  Object.assign(pushForm, { enabled: data.enabled, time: data.time, title: data.title, receivers: [...data.receivers], webhook: data.webhookMasked })
}

async function savePush() {
  savingPush.value = true
  try {
    const res = await api.savePushSettings({ ...pushForm })
    if (!res.success) return ElMessage.error(res.message)
    ElMessage.success(res.message)
    fillPush(res.data)
  } finally {
    savingPush.value = false
  }
}

async function testPush() {
  testingPush.value = true
  try {
    const res = await api.pushTest()
    if (!res.success) return ElMessage.error(res.message)
    // 每个渠道一行；用 VNode 显示，不把服务器返回的文字当 HTML
    ElMessageBox.alert(h('div', res.message.split('；').map(line => h('p', { style: 'margin: 4px 0' }, line))), '测试结果', { confirmButtonText: '知道了' })
    fillPush(res.data)
  } finally {
    testingPush.value = false
  }
}

async function runPush() {
  try {
    await ElMessageBox.confirm('马上把今天已填报的各工序战报和全厂汇总推送出去？（等同于到点自动推送）', '立即推送', { type: 'warning', confirmButtonText: '推送' })
  } catch {
    return
  }
  runningPush.value = true
  try {
    const res = await api.pushRun()
    if (!res.success) return ElMessage.error(res.message)
    ElMessage.success(res.message)
    setTimeout(async () => { const r = await api.pushSettings(); if (r.success) fillPush(r.data) }, 5000)
  } finally {
    runningPush.value = false
  }
}

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
    const [s, l, ps, us] = await Promise.all([api.storage(), api.lockSetting(), api.pushSettings(), api.users()])
    if (ps.success) fillPush(ps.data)
    if (us.success) users.value = us.data
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

/* 两个渠道并排，不要第一个独占一整行 */
.push-facts .fact:first-child {
  grid-column: auto;
}

.push-form {
  max-width: 820px;
}

.push-logs {
  margin-top: 8px;
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
