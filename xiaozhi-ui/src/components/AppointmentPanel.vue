<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { bookingLabels, draftLabels, fetchSessions, fetchDraft, createSessionDraft, changeDraft } from '@/api/appointments.js'

const props = defineProps({
  conversationId: { type: String, required: true }, drafts: { type: Array, required: true },
  verified: Boolean, verifiedAt: String, notice: String, blocked: Boolean,
  synchronize: { type: Function, required: true },
})
const emit = defineEmits(['busy'])
const schedules = ref(null)
const loading = ref(false)
const acting = ref(false)
const review = ref(null)
const reviewElement = ref(null)
const pending = ref(null)
const operationNotice = ref('')
const scheduleNotice = ref('')
const date = ref('')
const doctor = ref('')
const now = ref(performance.now())
const loadedAt = ref(-Infinity)
const fresh = computed(() => schedules.value && now.value - loadedAt.value < 90000)
const locked = computed(() => props.blocked || acting.value || !!review.value)
const dates = computed(() => [...new Set(schedules.value?.sessions.map(item => item.visitDate) || [])].sort())
const doctors = computed(() => [...new Map((schedules.value?.sessions || [])
  .filter(item => item.visitDate === date.value).map(item => [item.doctorId, item.doctorName]))])
const sessions = computed(() => (schedules.value?.sessions || []).filter(item =>
  item.visitDate === date.value && (!doctor.value || item.doctorId === doctor.value)))
const actionLabels = { confirm: '确认创建演示预约', cancelDraft: '取消当前草稿', cancelAppointment: '取消已确认的演示预约' }
let disposed = false
let refreshSequence = 0
let interval
let clock
const journalKey = () => 'xiaozhi.course-ui.appointment.pending.' + props.conversationId
watch([acting, review, pending], () => emit('busy', acting.value || !!review.value || !!pending.value), { flush: 'sync' })
watch(date, () => { if (!doctors.value.some(([id]) => id === doctor.value)) doctor.value = '' })

function readPending() {
  try {
    const raw = sessionStorage.getItem(journalKey())
    pending.value = raw ? JSON.parse(raw) : null
    if (pending.value) operationNotice.value = '上次操作结果需要核实。请同步草稿，核对状态后解除锁定；本页不会自动重发。'
  } catch {
    pending.value = { label: '无法读取的上次操作记录' }
    operationNotice.value = '操作记录无法读取，请先同步核对已有草稿。'
  }
}
watch(() => props.conversationId, () => {
  review.value = null
  operationNotice.value = ''
  readPending()
})

function markPending(label, draftId = '') {
  const item = { label, draftId, at: new Date().toISOString() }
  // 先记录再发 POST：刷新页面也能提醒用户核实结果。
  sessionStorage.setItem(journalKey(), JSON.stringify(item))
  pending.value = item
}
function clearPending() {
  sessionStorage.removeItem(journalKey())
  pending.value = null
}
function acknowledge() {
  if (locked.value || !props.verified) return
  if (!window.confirm('请确认已核对右侧最新草稿和预约状态。解除锁定不会撤销后台请求；若结果仍不明确，请继续等待后再同步。')) return
  try { clearPending(); operationNotice.value = '已解除操作锁定；未重发任何请求。' }
  catch { operationNotice.value = '浏览器存储不可用，暂时无法解除锁定。' }
}
async function refreshSchedules() {
  if (loading.value || disposed) return
  const sequence = ++refreshSequence
  loading.value = true
  schedules.value = null
  scheduleNotice.value = '正在查询排班……'
  try {
    const data = await fetchSessions()
    if (disposed || sequence !== refreshSequence) return
    schedules.value = data
    loadedAt.value = performance.now()
    now.value = loadedAt.value
    if (!dates.value.includes(date.value)) date.value = dates.value[0] || ''
    if (!doctors.value.some(([id]) => id === doctor.value)) doctor.value = ''
    scheduleNotice.value = data.sessions.length ? '查询不预留号源，最终结果以确认时的后端校验为准。' : '当前没有已配置的场次。'
  } catch (error) {
    if (!disposed) scheduleNotice.value = '排班查询失败：' + error.message
  } finally { if (!disposed) loading.value = false }
}
function draftAllowed(item) {
  return ['AVAILABLE', 'NOT_RELEASED'].includes(item.bookingStatus) && item.referenceRemaining > 0
}
async function synchronizeAll() {
  if (locked.value) return
  acting.value = true
  try { await props.synchronize(); await refreshSchedules() }
  finally { acting.value = false }
}
async function createDraft(item) {
  if (locked.value || loading.value || pending.value || !draftAllowed(item)) return
  now.value = performance.now()
  if (!fresh.value) {
    await refreshSchedules()
    operationNotice.value = '排班已尝试刷新，请核对后重新选择。'
    return
  }
  acting.value = true
  try {
    markPending('草稿创建请求')
    const data = await createSessionDraft(props.conversationId, item)
    clearPending()
    operationNotice.value = `已生成待确认草稿 ${data.draftId}。尚未创建预约，也未占用名额。`
  } catch (error) {
    operationNotice.value = '草稿创建未能完整核实：' + error.message + ' 请同步核对，勿直接重复创建。'
  } finally {
    if (!disposed) { await props.synchronize(); await refreshSchedules(); acting.value = false }
  }
}
async function prepare(action, draft) {
  if (locked.value || pending.value || !props.verified) return
  acting.value = true
  operationNotice.value = '正在重新核对草稿……'
  try {
    const latest = await fetchDraft(draft.draftId)
    if (disposed) return
    const expected = action === 'cancelAppointment' ? 'CONFIRMED' : 'PENDING_CONFIRMATION'
    if (latest.status !== expected) {
      operationNotice.value = '状态已变化，请查看同步后的结果。'
      await props.synchronize()
      return
    }
    review.value = { action, draft: latest }
    await nextTick()
    reviewElement.value?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
    reviewElement.value?.focus?.({ preventScroll: true })
    operationNotice.value = '请核对下方操作确认区，只有点击最终确认按钮才会提交。'
  } catch (error) { operationNotice.value = '无法核实草稿：' + error.message }
  finally { acting.value = false }
}
async function execute() {
  if (acting.value || props.blocked || !review.value || pending.value) return
  const { action, draft } = review.value
  acting.value = true
  try {
    markPending(actionLabels[action], draft.draftId)
    const result = await changeDraft(action, draft)
    clearPending()
    operationNotice.value = action === 'confirm' ? `演示预约已创建：${result.appointmentId}`
      : action === 'cancelDraft' ? '草稿已取消。' : `演示预约已取消，原记录保留：${result.appointmentId}`
  } catch (error) {
    operationNotice.value = '本次操作未能完整核实：' + error.message + ' 请同步核对；不会自动重发。'
  } finally {
    review.value = null
    if (!disposed) { await props.synchronize(); await refreshSchedules(); acting.value = false }
  }
}
function releaseTime(value) {
  return new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(value))
}
async function copyId(id) {
  try { await navigator.clipboard.writeText(id); operationNotice.value = '草稿编号已复制。' }
  catch { operationNotice.value = '请选中完整草稿编号，按 Ctrl+C 复制。' }
}
function refreshWhenVisible() {
  if (!document.hidden && !locked.value && !pending.value) void refreshSchedules()
}
onMounted(() => {
  readPending()
  void refreshSchedules()
  clock = setInterval(() => { now.value = performance.now() }, 1000)
  interval = setInterval(refreshWhenVisible, 60000)
  document.addEventListener('visibilitychange', refreshWhenVisible)
})
onBeforeUnmount(() => {
  disposed = true
  refreshSequence++
  clearInterval(clock)
  clearInterval(interval)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <aside class="appointment-panel" aria-label="演示预约管理">
    <section aria-labelledby="schedule-title">
      <span class="eyebrow">预约准备</span><h2 id="schedule-title">医生与时段</h2>
      <p class="muted">虚构医院 DEMO001 · 内科。草稿不占号，预约需要单独确认。</p>
      <button :disabled="locked || loading" @click="refreshSchedules">刷新排班</button>
      <p v-if="schedules" class="muted">上海业务日期：{{ schedules.businessDate }}</p>
      <p role="status" class="muted">{{ scheduleNotice }}</p>
      <p v-if="schedules && !fresh" class="warning">排班已过期，请刷新后再选择。</p>
      <div class="filters">
        <label>就诊日期（上海）<select v-model="date" :disabled="locked || loading || !fresh">
          <option v-if="!dates.length" value="">暂无场次</option>
          <option v-for="value in dates" :key="value" :value="value">{{ value }}</option>
        </select></label>
        <label>演示医生<select v-model="doctor" :disabled="locked || loading || !fresh">
          <option value="">全部医生</option>
          <option v-for="[id, name] in doctors" :key="id" :value="id">{{ name }}</option>
        </select></label>
      </div>
      <article v-for="item in sessions" :key="item.sessionId" class="session-card">
        <strong>{{ item.doctorName }} · {{ item.slotName }}</strong>
        <span class="badge" :class="item.bookingStatus.toLowerCase()">{{ bookingLabels[item.bookingStatus] }}</span>
        <p>{{ item.visitDate }} · {{ item.startTime.slice(0, 5) }}–{{ item.endTime.slice(0, 5) }}</p>
        <p class="availability">参考可用名额 <strong>{{ item.referenceRemaining }}</strong></p>
        <details class="capacity-detail"><summary>容量与放号时间</summary><p class="muted">本场剩余 {{ item.sessionRemaining }} / 容量 {{ item.totalCapacity }}；当天共享剩余 {{ item.dailyRemaining }}。</p>
        <p class="muted">上海时间 {{ releaseTime(item.releaseAt) }} 放号</p></details>
        <button :disabled="locked || loading || !fresh || !!pending || !draftAllowed(item)" @click="createDraft(item)">
          {{ item.bookingStatus === 'NOT_RELEASED' ? '先准备待确认草稿' : '选择此场次，生成草稿' }}
        </button>
      </article>
    </section>
    <section class="draft-section" aria-labelledby="draft-title">
      <h2 id="draft-title">当前会话草稿</h2>
      <button :disabled="locked" @click="synchronizeAll">同步当前会话草稿</button>
      <p role="status" class="muted">{{ notice }}</p>
      <p v-if="verified" class="muted">查询时间（本机）：{{ verifiedAt }}。其他页面操作后请重新同步。</p>
      <p role="status" class="operation-notice">{{ operationNotice }}</p>
      <div v-if="pending" class="warning pending-notice">
        <p>待核实操作：{{ pending.label }}。{{ pending.draftId }}</p>
        <p>请先同步核对。结果不明确时继续等待，不要重复提交。</p>
        <button :disabled="locked || !verified" @click="acknowledge">已核对结果，解除操作锁定</button>
      </div>
      <section v-if="review" ref="reviewElement" tabindex="-1" class="review" aria-label="操作确认区">
        <h3>{{ actionLabels[review.action] }}</h3>
        <p>{{ review.draft.hospitalId }} · {{ review.draft.department }}</p>
        <p>{{ review.draft.visitDate }}（{{ review.draft.timeZone }}）</p>
        <p v-if="review.draft.session">{{ review.draft.session.doctorName }} · {{ review.draft.session.slotName }}
          {{ review.draft.session.startTime }}–{{ review.draft.session.endTime }}</p>
        <p v-else>旧版按日期预约，未指定医生和时段。</p>
        <p class="identifier">草稿：{{ review.draft.draftId }}</p>
        <p v-if="review.draft.appointmentId" class="identifier">预约：{{ review.draft.appointmentId }}</p>
        <p>{{ review.action === 'confirm' ? '确认后将尝试占用演示号源，最终以后端校验为准。' : '取消后保留记录，不能通过再次确认恢复。' }}</p>
        <button class="primary" :disabled="acting || blocked" @click="execute">{{ acting ? '正在提交，请勿重复操作…' : '核对无误，' + actionLabels[review.action] }}</button>
        <button :disabled="acting" @click="review = null">返回，不操作</button>
      </section>
      <article v-for="draft in drafts" :key="draft.draftId" class="draft-card">
        <strong>{{ draft.visitDate }} · {{ draft.department }}</strong>
        <p>{{ draft.hospitalId }} · {{ draft.timeZone }}</p>
        <p v-if="draft.session">{{ draft.session.doctorName }} · {{ draft.session.slotName }} {{ draft.session.startTime }}–{{ draft.session.endTime }}</p>
        <p v-else>旧版按日期预约，未指定医生和时段</p>
        <p class="draft-state" :class="draft.status.toLowerCase()">{{ verified ? '查询时状态：' : '状态未重新核实：' }}{{ draftLabels[draft.status] }}</p>
        <p class="identifier">{{ draft.draftId }}</p>
        <p v-if="draft.appointmentId" class="identifier">关联预约：{{ draft.appointmentId }}</p>
        <button @click="copyId(draft.draftId)">复制草稿编号</button>
        <div v-if="draft.status === 'PENDING_CONFIRMATION'" class="draft-actions">
          <button class="primary" :disabled="locked || !verified || !!pending" @click="prepare('confirm', draft)">确认创建演示预约</button>
          <button :disabled="locked || !verified || !!pending" @click="prepare('cancelDraft', draft)">取消当前草稿</button>
        </div>
        <button v-if="draft.status === 'CONFIRMED'" class="danger" :disabled="locked || !verified || !!pending" @click="prepare('cancelAppointment', draft)">取消已确认的演示预约</button>
        <p v-if="draft.status === 'CONFIRMING'" class="warning">确认处理中，请稍后同步，不要重复操作。</p>
      </article>
      <div v-if="!drafts.length" class="draft-empty"><strong>尚无预约草稿</strong><p>选择一个演示场次，或在对话中说明需求。生成草稿后还需亲自确认。</p></div>
    </section>
  </aside>
</template>

<style scoped>
.appointment-panel {
  padding: 20px;
  background: #fff;
  border:1px solid var(--line);
  border-radius: 16px;
  max-height: 100%;
  overflow-y: auto;
}
h2 {
  margin: 0 0 12px;
  font-size: 18px;
}
h3 {
  margin-top: 0;
}
p {
  line-height: 1.6;
  margin: 8px 0;
}
.muted {
  color: #6d8070;
  font-size: 13px;
}
.warning {
  color: #935113;
  font-size: 13px;
}
.filters {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 12px 0;
}
label {
  font-size: 13px;
}
select {
  display: block;
  width: 100%;
  margin-top: 6px;
  padding: 8px;
  border: 1px solid #cddbed;
  border-radius: 7px;
  background: #fff;
}
button {
  background: #eef5f0;
  color: #426b54;
  padding: 9px 12px;
  margin: 5px 5px 0 0;
  border: 1px solid #d6e2d8;
  border-radius: 8px;
  cursor: pointer;
  font: inherit;
  font-size: 13px;
}
button:disabled {
  opacity: .5;
  cursor: not-allowed;
}
button:focus-visible, select:focus-visible {
  outline: 3px solid #25988d;
  outline-offset: 2px;
}
.primary {
  background: #087f75;
  color: #fff;
}
.danger {
  background: #fff0ee;
  color: #a33126;
}
.session-card, .draft-card {
  border: 1px solid #dde7dd;
  background: #f8faf7;
  padding: 14px;
  border-radius: 10px;
  margin-top: 12px;
  font-size: 14px;
}
.badge {
  display: inline-block;
  margin-left: 8px;
  color: #6b806e;
  font-size: 12px;
}
.draft-section {
  border-top: 1px solid #dfe8df;
  margin-top: 24px;
  padding-top: 24px;
}
.identifier {
  overflow-wrap: anywhere;
  font-size: 12px;
  user-select: text;
}
.operation-notice {
  color: #436e53;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.review, .pending-notice {
  background: #fff8e9;
  border: 1px solid #e4be79;
  border-radius: 12px;
  padding: 14px;
  margin-top: 14px;
}
@media (max-width: 1100px) {
  .appointment-panel {
    max-height: none;
  }
}
@media (max-width: 640px) {
  .appointment-panel {
    padding: 16px;
  }
}
.availability {
  display:flex;
  align-items:center;
  justify-content:space-between;
  color:#587462
}
.availability strong {
  font-size:22px;
  font-weight:600;
  color:#247653
}
.badge {
  padding:2px 7px;
  border-radius:4px;
  background:#eaf0e9;
  margin:6px 0;
  font-size:11px
}
.badge.available {
  color:#23714d;
  background:#e4f1e7
}
.capacity-detail {
  font-size:11px;
  color:#718471;
  margin:8px 0
}
.capacity-detail summary {
  cursor:pointer
}
.capacity-detail p {
  font-size:11px
}
.draft-empty {
  padding:22px 14px;
  text-align:center;
  border:1px dashed #d8e3d6;
  background:#fafcf8;
  border-radius:10px;
  margin-top:16px;
  color:#789077;
  font-size:12px
}
.draft-empty strong {
  font-size:13px;
  font-weight:500
}
.draft-state {
  font-size:12px;
  color:#516e51
}
.draft-state.pending_confirmation {
  color:#986621
}
.draft-state.confirmed {
  color:#237750
}
.session-card>button {
  width:100%;
  margin:8px 0 0
}
.review {
  border-color:#d5b363
}
.draft-section {
  padding-top:20px;
  margin-top:24px
}
</style>
