import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ElButton, ElInput } from 'element-plus'
import ChatWindow from '../src/components/ChatWindow.vue'
import { fetchConversationDrafts, streamChat } from '../src/api/chat.js'
import { createConversation, fetchConversations, fetchHistory } from '../src/api/conversations.js'
import { button, deferred, source } from './helpers.js'
vi.mock('../src/api/chat.js', () => ({ fetchConversationDrafts: vi.fn(), streamChat: vi.fn() }))
vi.mock('../src/api/conversations.js', () => ({ createConversation: vi.fn(), fetchConversations: vi.fn(), fetchHistory: vi.fn() }))

let wrapper
const conversation = { conversationId: 'chat-1', title: '我的会话' }
const history = (extra = {}) => ({ messages: [], nextCursor: null, processing: false, ...extra })
async function open() {
  wrapper = mount(ChatWindow, { props: { userId: 'user-1' }, attachTo: document.body,
    global: { components: { ElButton, ElInput }, stubs: { AppointmentPanel: true } } })
  await flushPromises()
  return wrapper
}
async function send(text = '你好') {
  await wrapper.get('textarea').setValue(text)
  await wrapper.get('form').trigger('submit')
}
beforeEach(() => {
  fetchConversations.mockReset().mockResolvedValue({ items: [conversation], nextCursor: null })
  fetchHistory.mockReset().mockResolvedValue(history())
  fetchConversationDrafts.mockReset().mockResolvedValue([])
  createConversation.mockReset()
  streamChat.mockReset()
})
afterEach(() => { wrapper?.unmount(); document.body.innerHTML = ''; vi.useRealTimers() })

describe('ChatWindow user interactions with real Element Plus inputs', () => {
  it('restores this account pointer and history without sending a model request', async () => {
    localStorage.setItem('xiaozhi.course-ui.active-conversation.v3.user-1', 'saved-chat')
    localStorage.setItem('xiaozhi.course-ui.active-conversation.v3.other', 'other-chat')
    await open()
    expect(fetchHistory).toHaveBeenCalledWith('saved-chat', null)
    expect(streamChat).not.toHaveBeenCalled()
    expect(createConversation).not.toHaveBeenCalled()
  })
  it('renders model text and sources as text, blocks duplicate send and then reloads persisted history', async () => {
    const pending = deferred()
    streamChat.mockImplementation(({ onEvent }) => {
      onEvent('token', { text: '<img src=x onerror=alert(1)>' })
      onEvent('sources', { sources: [source] })
      return pending.promise
    })
    await open()
    await send()
    await wrapper.get('form').trigger('submit')
    expect(streamChat).toHaveBeenCalledOnce()
    expect(wrapper.get('textarea').element.disabled).toBe(true)
    expect(wrapper.find('.message-text img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.text()).toContain('visit-handbook.pdf')
    fetchHistory.mockResolvedValue(history({ messages: [{ id: 'saved', role: 'assistant', content: '服务端保存的最终回答', state: 'complete', sources: [source] }] }))
    pending.resolve({ reply: '最终回答', sources: [source], drafts: [] })
    await flushPromises()
    expect(wrapper.text()).toContain('服务端保存的最终回答')
    expect(wrapper.get('textarea').element.disabled).toBe(false)
  })
  it('does not send on IME composition or Shift+Enter; normal Enter sends once', async () => {
    streamChat.mockReturnValue(deferred().promise)
    await open()
    await wrapper.get('textarea').setValue('输入法文字')
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter', isComposing: true })
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter', keyCode: 229 })
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(streamChat).not.toHaveBeenCalled()
    await wrapper.get('textarea').trigger('keydown', { key: 'Enter' })
    expect(streamChat).toHaveBeenCalledOnce()
  })
  it('on receive failure never resends; backend processing blocks send until polling finds completion', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    streamChat.mockRejectedValue(new Error('连接断开'))
    await open()
    fetchHistory.mockResolvedValue(history({ processing: true }))
    await send()
    await flushPromises()
    expect(wrapper.get('textarea').element.disabled).toBe(true)
    expect(wrapper.text()).toContain('后台仍在处理')
    fetchHistory.mockResolvedValue(history())
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(wrapper.get('textarea').element.disabled).toBe(false)
    expect(streamChat).toHaveBeenCalledOnce()
  })
  it('failed history reconciliation keeps sending locked until manual synchronization succeeds', async () => {
    streamChat.mockResolvedValue({ reply: '回答', sources: [], drafts: [] })
    await open()
    fetchHistory.mockRejectedValueOnce(new Error('历史不可用'))
    await send()
    await flushPromises()
    expect(wrapper.get('textarea').element.disabled).toBe(true)
    expect(wrapper.text()).toContain('历史同步失败')
    await button(wrapper, '同步聊天历史').trigger('click')
    await flushPromises()
    expect(wrapper.get('textarea').element.disabled).toBe(false)
    expect(streamChat).toHaveBeenCalledOnce()
  })
  it('cancelled switch retains unsent input and does not fetch another conversation', async () => {
    fetchConversations.mockResolvedValue({ items: [conversation, { conversationId: 'chat-2', title: '另一会话' }], nextCursor: null })
    await open()
    await wrapper.get('textarea').setValue('还没发送')
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    await button(wrapper, '另一会话').trigger('click')
    expect(wrapper.get('textarea').element.value).toBe('还没发送')
    expect(fetchHistory).toHaveBeenCalledTimes(1)
    expect(streamChat).not.toHaveBeenCalled()
  })
  it('unmount aborts reception and ignores late events without fetching more private data', async () => {
    const pending = deferred()
    let options
    streamChat.mockImplementation(value => { options = value; return pending.promise })
    await open()
    await send()
    wrapper.unmount()
    wrapper = null
    expect(options.signal.aborted).toBe(true)
    options.onEvent('token', { text: 'late' })
    pending.resolve({ reply: 'late', sources: [], drafts: [] })
    await flushPromises()
    expect(fetchHistory).toHaveBeenCalledTimes(1)
    expect(fetchConversationDrafts).toHaveBeenCalledTimes(1)
  })
  it('initial history failure does not enable message or appointment actions', async () => {
    fetchHistory.mockRejectedValue(new Error('无权访问此会话'))
    await open()
    expect(wrapper.get('textarea').element.disabled).toBe(true)
    expect(wrapper.text()).toContain('无权访问')
    expect(wrapper.find('appointment-panel-stub').exists()).toBe(false)
  })
})
