export function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

export function button(wrapper, text) {
  const found = wrapper.findAll('button').find(item => item.text() === text)
  if (!found) throw new Error('Button not found: ' + text)
  return found
}

export const source = { source: 'visit-handbook.pdf', index: 0, text: '演示资料，不是真实医院信息。' }
export const draft = {
  draftId: 'DRAFT-11111111-1111-4111-8111-111111111111',
  status: 'PENDING_CONFIRMATION', hospitalId: 'DEMO001', department: '内科',
  visitDate: '2026-10-08', timeZone: 'Asia/Shanghai', session: null,
}
export const appointmentId = 'DEMO-22222222-2222-4222-8222-222222222222'
export const session = {
  sessionId: 'session-1', doctorId: 'doctor-1', doctorName: '演示医生甲',
  slotId: 'morning', slotName: '上午', startTime: '09:00:00', endTime: '12:00:00',
  visitDate: '2026-10-08', referenceRemaining: 5, sessionRemaining: 5,
  totalCapacity: 5, dailyRemaining: 20, activeAppointmentCount: 0,
  bookingStatus: 'AVAILABLE', releaseStatus: 'RELEASED', bookable: true,
  releaseAt: '2026-10-07T08:00:00+08:00',
}
export const schedules = {
  status: 'DEMO_DATA', hospitalId: 'DEMO001', department: '内科',
  timeZone: 'Asia/Shanghai', businessDate: '2026-10-07', bookingEnabled: true,
  sessions: [session],
}
