import { validateDrafts } from './chat.js'

export const bookingLabels = {
  AVAILABLE: '可预约', NOT_RELEASED: '尚未放号', FULL: '已满额', NO_SCHEDULE: '未配置当天总名额',
}
export const draftLabels = {
  PENDING_CONFIRMATION: '待确认', CONFIRMING: '确认处理中', CONFIRMED: '已确认',
  CANCELLED: '草稿已取消', APPOINTMENT_CANCELLED: '预约已取消',
}
const sessionKeys = ['sessionId', 'doctorId', 'doctorName', 'slotId', 'slotName', 'startTime', 'endTime']
const dateOK = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) &&
  Number.isFinite(Date.parse(value + 'T00:00:00Z')) && new Date(value + 'T00:00:00Z').toISOString().slice(0, 10) === value
const idOK = (value, prefix) => typeof value === 'string' && new RegExp(`^${prefix}-[0-9a-f-]{36}$`, 'i').test(value)

export function sameSession(a, b) {
  return a == null || b == null ? a == null && b == null : sessionKeys.every(key => a[key] === b[key])
}

export function validateDraft(data) {
  validateDrafts([data])
  if (!dateOK(data.visitDate) || data.timeZone !== 'Asia/Shanghai' ||
      (data.appointmentId != null && !idOK(data.appointmentId, 'DEMO')) ||
      (['CONFIRMED', 'APPOINTMENT_CANCELLED'].includes(data.status) && !data.appointmentId)) {
    throw new Error('草稿详情异常，请同步核实。')
  }
  return data
}

export function validateSessions(data) {
  if (!data || !['DEMO_DATA', 'NO_DATA'].includes(data.status) ||
      data.hospitalId !== 'DEMO001' || data.department !== '内科' || data.timeZone !== 'Asia/Shanghai' ||
      data.bookingEnabled !== true || !dateOK(data.businessDate) || !Array.isArray(data.sessions) ||
      (data.status === 'NO_DATA') !== (data.sessions.length === 0)) throw new Error('排班接口格式异常。')
  const ids = new Set()
  for (const item of data.sessions) {
    const offset = (Date.parse(item.visitDate + 'T00:00:00Z') - Date.parse(data.businessDate + 'T00:00:00Z')) / 86400000
    if (!sessionKeys.every(key => typeof item[key] === 'string' && item[key].trim()) ||
        !dateOK(item.visitDate) || ![1, 2, 3].includes(offset) || ids.has(item.sessionId) ||
        ![item.totalCapacity, item.activeAppointmentCount, item.sessionRemaining, item.dailyRemaining, item.referenceRemaining]
          .every(n => Number.isSafeInteger(n) && n >= 0) ||
        item.sessionRemaining !== Math.max(0, item.totalCapacity - item.activeAppointmentCount) ||
        item.referenceRemaining !== Math.min(item.sessionRemaining, item.dailyRemaining) ||
        !Object.hasOwn(bookingLabels, item.bookingStatus) ||
        !['RELEASED', 'NOT_RELEASED'].includes(item.releaseStatus) ||
        item.bookable !== (item.bookingStatus === 'AVAILABLE') ||
        (item.bookingStatus === 'AVAILABLE' && (item.referenceRemaining === 0 || item.releaseStatus !== 'RELEASED')) ||
        (item.bookingStatus === 'FULL' && (item.referenceRemaining !== 0 || item.releaseStatus !== 'RELEASED')) ||
        (item.bookingStatus === 'NOT_RELEASED' && item.releaseStatus !== 'NOT_RELEASED') ||
        (item.bookingStatus === 'NO_SCHEDULE' && item.dailyRemaining !== 0) ||
        typeof item.releaseAt !== 'string' || !Number.isFinite(Date.parse(item.releaseAt))) {
      throw new Error('排班日期、状态或名额异常，请刷新核实。')
    }
    ids.add(item.sessionId)
  }
  return data
}

async function request(url, body) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), body === undefined ? 15000 : 30000)
  try {
    const response = await fetch(url, {
      method: body === undefined ? 'GET' : 'POST', cache: 'no-store', signal: controller.signal,
      headers: { Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    if (!response.ok) {
      const hints = { 400: '参数或确认信息不正确', 404: '记录不存在', 409: '状态或号源已变化', 503: '服务暂不可用' }
      throw new Error(`请求失败（HTTP ${response.status}）：${hints[response.status] || '请检查后端日志'}。`)
    }
    return await response.json()
  } catch (error) {
    if (controller.signal.aborted) throw new Error('等待超时，后台操作可能仍在执行。')
    throw error
  } finally { clearTimeout(timer) }
}

export async function fetchSessions() {
  return validateSessions(await request('/api/appointment/sessions?hospitalId=DEMO001&department=' + encodeURIComponent('内科')))
}

export async function fetchDraft(id) {
  if (!idOK(id, 'DRAFT')) throw new Error('草稿编号格式不正确。')
  const data = validateDraft(await request('/api/appointments/drafts/' + encodeURIComponent(id)))
  if (data.draftId !== id) throw new Error('返回的草稿编号不一致。')
  return data
}

export async function createSessionDraft(conversationId, session) {
  const data = validateDraft(await request('/api/appointments/drafts?conversationId=' + encodeURIComponent(conversationId), {
    hospitalId: 'DEMO001', department: '内科', visitDate: session.visitDate, sessionId: session.sessionId,
  }))
  if (data.status !== 'PENDING_CONFIRMATION' || data.hospitalId !== 'DEMO001' || data.department !== '内科' ||
      data.visitDate !== session.visitDate || !sameSession(data.session, session)) throw new Error('返回草稿与所选场次不一致。')
  return data
}

export async function changeDraft(action, draft) {
  validateDraft(draft)
  if (!['confirm', 'cancelDraft', 'cancelAppointment'].includes(action)) throw new Error('未知操作。')
  const expected = action === 'cancelAppointment' ? 'CONFIRMED' : 'PENDING_CONFIRMATION'
  if (draft.status !== expected) throw new Error('当前状态不允许此操作，请同步。')
  const url = action === 'cancelAppointment'
    ? `/api/appointments/${encodeURIComponent(draft.appointmentId)}/cancel`
    : `/api/appointments/drafts/${encodeURIComponent(draft.draftId)}/${action === 'confirm' ? 'confirm' : 'cancel'}`
  const data = await request(url, action === 'cancelAppointment' ? { confirmed: true } : {})
  if (action === 'cancelDraft') {
    validateDraft(data)
    if (data.draftId !== draft.draftId || data.status !== 'CANCELLED') throw new Error('草稿取消结果异常。')
  } else if (!data || !idOK(data.appointmentId, 'DEMO') ||
      data.status !== (action === 'confirm' ? 'DEMO_CREATED' : 'DEMO_CANCELLED') ||
      (action === 'cancelAppointment' && data.appointmentId !== draft.appointmentId)) {
    throw new Error('预约操作结果异常。')
  }
  if (!data || data.hospitalId !== draft.hospitalId || data.department !== draft.department ||
      data.visitDate !== draft.visitDate || data.timeZone !== draft.timeZone || !sameSession(data.session, draft.session)) {
    throw new Error('操作返回的预约详情不一致，请同步核实。')
  }
  return data
}
