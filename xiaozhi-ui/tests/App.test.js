import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import App from '../src/App.vue'
import * as auth from '../src/api/auth.js'
import { button, deferred } from './helpers.js'
vi.mock('../src/api/auth.js', () => ({
  currentUser: vi.fn(), login: vi.fn(), register: vi.fn(), logout: vi.fn(),
  importBrowserHistory: vi.fn(), resetCsrf: vi.fn(), announceAuthChange: vi.fn(),
}))
vi.mock('../src/api/browserIdentity.js', () => ({ readLegacyBrowserKey: () => null }))
let wrapper
async function open() {
  wrapper = mount(App, { global: { stubs: { ChatWindow: { props: ['userId'], template: '<div class="chat-stub">{{ userId }}</div>' } } } })
  await flushPromises()
}
async function credentials() {
  await wrapper.get('input[autocomplete="username"]').setValue('  XiaoZhi  ')
  await wrapper.get('input[type="password"]').setValue('test-password-123')
}
beforeEach(() => {
  for (const mock of Object.values(auth)) mock.mockReset()
  auth.currentUser.mockResolvedValue(null)
  auth.login.mockResolvedValue({ userId: 'user-1', username: 'xiaozhi' })
})
afterEach(() => wrapper?.unmount())

describe('account UI state', () => {
  it('normalizes username and ignores duplicate login submissions while pending', async () => {
    const pending = deferred()
    auth.login.mockReturnValue(pending.promise)
    await open()
    await credentials()
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(auth.login).toHaveBeenCalledExactlyOnceWith({ username: 'xiaozhi', password: 'test-password-123' })
    expect(wrapper.get('input[type="password"]').element.disabled).toBe(true)
    pending.resolve({ userId: 'user-1', username: 'xiaozhi' })
    await flushPromises()
    expect(wrapper.get('.chat-stub').text()).toBe('user-1')
  })
  it('failed login clears password and stays outside private chat', async () => {
    auth.login.mockRejectedValue(new Error('账号或密码错误'))
    await open()
    await credentials()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('input[type="password"]').element.value).toBe('')
    expect(wrapper.text()).toContain('账号或密码错误')
    expect(wrapper.find('.chat-stub').exists()).toBe(false)
  })
  it('registration requires matching passwords before request', async () => {
    await open()
    await button(wrapper, '创建账号').trigger('click')
    await credentials()
    await wrapper.findAll('input[type="password"]')[1].setValue('different-password')
    await wrapper.get('form').trigger('submit')
    expect(auth.register).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('两次密码不一致')
  })
  it('401 notification removes private chat and resets CSRF', async () => {
    auth.currentUser.mockResolvedValue({ userId: 'user-1', username: 'xiaozhi' })
    await open()
    window.dispatchEvent(new Event('xiaozhi-auth-expired'))
    await flushPromises()
    expect(wrapper.find('.chat-stub').exists()).toBe(false)
    expect(wrapper.text()).toContain('登录账号')
    expect(auth.resetCsrf).toHaveBeenCalledOnce()
  })
  it('cross-tab account change unmounts old chat and reloads the new account', async () => {
    auth.currentUser.mockResolvedValueOnce({ userId: 'user-1', username: 'xiaozhi' })
    await open()
    const next = deferred()
    auth.currentUser.mockReturnValue(next.promise)
    window.dispatchEvent(new StorageEvent('storage', { key: 'xiaozhi.auth.changed', newValue: 'change' }))
    await flushPromises()
    expect(wrapper.find('.chat-stub').exists()).toBe(false)
    next.resolve({ userId: 'user-2', username: 'other' })
    await flushPromises()
    expect(wrapper.get('.chat-stub').text()).toBe('user-2')
  })
  it('declining sign out keeps account; accepting clears it after successful logout', async () => {
    auth.currentUser.mockResolvedValue({ userId: 'user-1', username: 'xiaozhi' })
    auth.logout.mockResolvedValue({})
    await open()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await button(wrapper, '退出登录').trigger('click')
    expect(auth.logout).not.toHaveBeenCalled()
    confirm.mockReturnValue(true)
    await button(wrapper, '退出登录').trigger('click')
    await flushPromises()
    expect(auth.logout).toHaveBeenCalledOnce()
    expect(wrapper.find('.chat-stub').exists()).toBe(false)
  })
})
