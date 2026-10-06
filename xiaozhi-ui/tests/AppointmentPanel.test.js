import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AppointmentPanel from '../src/components/AppointmentPanel.vue'
import { fetchSessions, fetchDraft, createSessionDraft, changeDraft } from '../src/api/appointments.js'
import { button, deferred, draft, schedules, appointmentId } from './helpers.js'
vi.mock('../src/api/appointments.js', async importOriginal => ({
  ...await importOriginal(), fetchSessions: vi.fn(), fetchDraft: vi.fn(),
  createSessionDraft: vi.fn(), changeDraft: vi.fn(),
}))
let wrapper
let synchronize
const journal = 'xiaozhi.course-ui.appointment.pending.chat-1'
async function open(props = {}) {
  wrapper = mount(AppointmentPanel, { props: { conversationId: 'chat-1', drafts: [draft], verified: true,
    blocked: false, synchronize, ...props }, attachTo: document.body })
  await flushPromises()
}
beforeEach(() => {
  synchronize = vi.fn().mockResolvedValue(undefined)
  fetchSessions.mockReset().mockResolvedValue(schedules)
  fetchDraft.mockReset().mockResolvedValue(draft)
  createSessionDraft.mockReset().mockResolvedValue(draft)
  changeDraft.mockReset().mockResolvedValue({ appointmentId })
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.useRealTimers() })

describe('appointment explicit confirmation and uncertain results', () => {
  it.each([
    ['confirm', '确认创建演示预约', draft],
    ['cancelDraft', '取消当前草稿', draft],
    ['cancelAppointment', '取消已确认的演示预约', { ...draft, status: 'CONFIRMED', appointmentId }],
  ])('%s is only posted after detail recheck and final human confirmation', async (action, label, current) => {
    fetchDraft.mockResolvedValue(current)
    const pending = deferred()
    changeDraft.mockImplementation(() => {
      expect(JSON.parse(sessionStorage.getItem(journal)).draftId).toBe(draft.draftId)
      return pending.promise
    })
    await open({ drafts: [current] })
    await button(wrapper, label).trigger('click')
    await flushPromises()
    expect(fetchDraft).toHaveBeenCalledWith(draft.draftId)
    expect(changeDraft).not.toHaveBeenCalled()
    const finalButton = button(wrapper, '核对无误，' + label)
    await finalButton.trigger('click')
    await finalButton.trigger('click')
    expect(changeDraft).toHaveBeenCalledExactlyOnceWith(action, current)
    pending.resolve({ appointmentId })
    await flushPromises()
    expect(sessionStorage.getItem(journal)).toBeNull()
    expect(synchronize).toHaveBeenCalledOnce()
    expect(wrapper.find('.review').exists()).toBe(false)
  })
  it('return from confirmation makes no write request', async () => {
    await open()
    await button(wrapper, '确认创建演示预约').trigger('click')
    await flushPromises()
    await button(wrapper, '返回，不操作').trigger('click')
    expect(changeDraft).not.toHaveBeenCalled()
    expect(wrapper.find('.review').exists()).toBe(false)
  })
  it('changed server state prevents opening confirmation and synchronizes', async () => {
    fetchDraft.mockResolvedValue({ ...draft, status: 'CANCELLED' })
    await open()
    await button(wrapper, '确认创建演示预约').trigger('click')
    await flushPromises()
    expect(wrapper.find('.review').exists()).toBe(false)
    expect(synchronize).toHaveBeenCalledOnce()
    expect(changeDraft).not.toHaveBeenCalled()
  })
  it('uncertain POST leaves a journal across remount and requires explicit acknowledgment; no automatic retry', async () => {
    changeDraft.mockRejectedValue(new Error('网络断开'))
    await open()
    await button(wrapper, '确认创建演示预约').trigger('click')
    await flushPromises()
    await button(wrapper, '核对无误，确认创建演示预约').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('不会自动重发')
    expect(sessionStorage.getItem(journal)).not.toBeNull()
    wrapper.unmount()
    await open()
    expect(button(wrapper, '确认创建演示预约').element.disabled).toBe(true)
    expect(changeDraft).toHaveBeenCalledOnce()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await button(wrapper, '已核对结果，解除操作锁定').trigger('click')
    expect(sessionStorage.getItem(journal)).not.toBeNull()
    confirm.mockReturnValue(true)
    await button(wrapper, '已核对结果，解除操作锁定').trigger('click')
    expect(sessionStorage.getItem(journal)).toBeNull()
    expect(changeDraft).toHaveBeenCalledOnce()
  })
  it('storage failure prevents sending a write without a recovery journal', async () => {
    await open()
    await button(wrapper, '确认创建演示预约').trigger('click')
    await flushPromises()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储已满') })
    await button(wrapper, '核对无误，确认创建演示预约').trigger('click')
    await flushPromises()
    expect(changeDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('存储已满')
  })
  it('expired schedule cannot create a draft until refreshed', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'performance'] })
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    await open()
    await vi.advanceTimersByTimeAsync(91000)
    expect(wrapper.text()).toContain('排班已过期')
    expect(button(wrapper, '选择此场次，生成草稿').element.disabled).toBe(true)
    await button(wrapper, '刷新排班').trigger('click')
    await flushPromises()
    expect(button(wrapper, '选择此场次，生成草稿').element.disabled).toBe(false)
    await button(wrapper, '选择此场次，生成草稿').trigger('click')
    await flushPromises()
    expect(createSessionDraft).toHaveBeenCalledOnce()
    expect(changeDraft).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('尚未创建预约，也未占用名额')
  })
  it('full sessions and unverified drafts disable mutations', async () => {
    fetchSessions.mockResolvedValue({ ...schedules, sessions: [{ ...schedules.sessions[0], bookingStatus: 'FULL', referenceRemaining: 0 }] })
    await open({ verified: false })
    expect(button(wrapper, '选择此场次，生成草稿').element.disabled).toBe(true)
    expect(button(wrapper, '确认创建演示预约').element.disabled).toBe(true)
    expect(changeDraft).not.toHaveBeenCalled()
  })
  it('cancelled appointment retains its ID and exposes no confirm or cancel action', async () => {
    await open({ drafts: [{ ...draft, status: 'APPOINTMENT_CANCELLED', appointmentId }] })
    const card = wrapper.get('.draft-card')
    expect(card.text()).toContain(appointmentId)
    expect(card.text()).toContain('预约已取消')
    expect(card.findAll('button').map(b => b.text())).toEqual(['复制草稿编号'])
  })
})
