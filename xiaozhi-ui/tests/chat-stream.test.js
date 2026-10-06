import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { streamChat } from '../src/api/chat.js'
import { apiFetch } from '../src/api/auth.js'
import { source } from './helpers.js'
vi.mock('../src/api/auth.js', () => ({ apiFetch: vi.fn() }))

const done = { reply: '完整回答你好🙂', drafts: [], sources: [source] }
const frame = (name, data, ending = '\n') => 'event: ' + name + ending + 'data: ' + JSON.stringify(data) + ending + ending
let cancel
function respond(text, byteByByte = false) {
  const bytes = new TextEncoder().encode(text)
  const chunks = byteByByte ? Array.from(bytes, b => Uint8Array.of(b)) : [bytes]
  cancel = vi.fn().mockResolvedValue(undefined)
  const reader = {
    read: vi.fn(async () => chunks.length ? { value: chunks.shift(), done: false } : { done: true }),
    cancel,
  }
  apiFetch.mockResolvedValue({
    ok: true, status: 200, headers: new Headers({ 'content-type': 'text/event-stream;charset=UTF-8' }),
    body: { getReader: () => reader },
  })
}
function call(extra = {}) { return streamChat({ conversationId: 'conversation-1', message: '你好', onEvent: vi.fn(), ...extra }) }
beforeEach(() => { apiFetch.mockReset() })
afterEach(() => vi.useRealTimers())

describe('real SSE parser at the HTTP reader boundary', () => {
  it('preserves split UTF-8 and CRLF; consumes status, sources, tokens and final reply', async () => {
    respond(': heartbeat\r\n\r\n' + frame('status', { message: '正在回复' }, '\r\n') +
      frame('sources', { sources: [source] }, '\r\n') + frame('token', { text: '你好🙂' }, '\r\n') +
      frame('done', done, '\r\n'), true)
    const onEvent = vi.fn()
    expect(await call({ onEvent })).toEqual(done)
    expect(onEvent.mock.calls.map(([name]) => name)).toEqual(['status', 'sources', 'token'])
    expect(onEvent).toHaveBeenCalledWith('token', { text: '你好🙂' })
    expect(cancel).toHaveBeenCalledOnce()
    const [url, options] = apiFetch.mock.calls[0]
    expect(url).toBe('/api/chat/stream')
    expect(JSON.parse(options.body)).toEqual({ conversationId: 'conversation-1', message: '你好' })
    expect(options.signal.aborted).toBe(true)
  })
  it('supports multiline data and ignores unknown events', async () => {
    respond('event: future\ndata: invalid-json\n\nevent: token\ndata: {\ndata: "text":"你好"}\n\n' + frame('done', done))
    const onEvent = vi.fn()
    await call({ onEvent })
    expect(onEvent.mock.calls).toEqual([['token', { text: '你好' }]])
  })
  it.each([
    ['missing done', frame('token', { text: 'partial' }), 'done'],
    ['failed event', frame('failed', { message: '保存失败' }), '保存失败'],
    ['invalid JSON', 'event: token\ndata: nope\n\n', null],
    ['invalid token', frame('token', { text: 12 }), '片段'],
    ['invalid source', frame('sources', { sources: [{ ...source, index: -1 }] }), '参考片段'],
    ['empty final reply', frame('done', { ...done, reply: ' ' }), '正文为空'],
    ['invalid final draft', frame('done', { ...done, drafts: [{ draftId: 'fake' }] }), '草稿数据'],
    ['oversize frame', 'x'.repeat(1000001), '事件过大'],
  ])('rejects %s, cancels reader and never resends', async (_name, text, hint) => {
    respond(text)
    if (hint) await expect(call()).rejects.toThrow(hint)
    else await expect(call()).rejects.toBeInstanceOf(Error)
    expect(cancel).toHaveBeenCalledOnce()
    expect(apiFetch).toHaveBeenCalledOnce()
  })
  it.each([401, 409, 429, 503])('rejects HTTP %i without retrying', async status => {
    apiFetch.mockResolvedValue({ ok: false, status })
    await expect(call()).rejects.toThrow()
    expect(apiFetch).toHaveBeenCalledOnce()
  })
  it('rejects non-SSE content before reading', async () => {
    apiFetch.mockResolvedValue({ ok: true, body: {}, headers: new Headers({ 'content-type': 'application/json' }) })
    await expect(call()).rejects.toThrow('SSE')
  })
  it('reports user abort without claiming the backend stopped', async () => {
    const controller = new AbortController()
    apiFetch.mockImplementation((_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }))
    const result = call({ signal: controller.signal })
    const check = expect(result).rejects.toThrow('不表示后台工具已停止')
    controller.abort()
    await check
    expect(apiFetch).toHaveBeenCalledOnce()
  })
  it('times out with an uncertain-result warning and no resend', async () => {
    vi.useFakeTimers()
    apiFetch.mockImplementation((_url, { signal }) => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }))
    const check = expect(call()).rejects.toThrow('后台工具可能仍在执行')
    await vi.advanceTimersByTimeAsync(200000)
    await check
    expect(apiFetch).toHaveBeenCalledOnce()
    expect(vi.getTimerCount()).toBe(0)
  })
})
