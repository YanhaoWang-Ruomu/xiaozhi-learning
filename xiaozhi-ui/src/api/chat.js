import { browserHeaders } from './browserIdentity.js'
// 对应后端 a5d496d。连接中断不自动重发聊天，工具可能已生成草稿。
const hints = {
  400: '输入格式不正确，请检查消息。',
  409: '上一条请求仍在后台处理，请稍后同步草稿，勿重复生成。',
  429: '当前聊天请求较多，请稍后再试。',
  503: '后端暂时无法处理请求，请稍后再试。',
}

function checkHttp(response) {
  if (!response.ok) throw new Error(hints[response.status] || `请求失败（HTTP ${response.status}），请检查后端是否正常运行。`)
}

export function validateSources(sources) {
  if (!Array.isArray(sources) || sources.some(item =>
    !item || typeof item.source !== 'string' || !item.source.trim() ||
    !Number.isInteger(item.index) || item.index < 0 ||
    typeof item.text !== 'string' || !item.text.trim())) {
    throw new Error('参考片段格式异常。')
  }
  return sources
}

export function validateDrafts(drafts) {
  const states = ['PENDING_CONFIRMATION', 'CONFIRMING', 'CONFIRMED', 'CANCELLED', 'APPOINTMENT_CANCELLED']
  if (!Array.isArray(drafts) || drafts.some(item =>
    !item || typeof item.draftId !== 'string' || !/^DRAFT-[0-9a-f-]{36}$/i.test(item.draftId) ||
    !states.includes(item.status) ||
    !['hospitalId', 'department', 'visitDate', 'timeZone'].every(key => typeof item[key] === 'string') ||
    (item.session != null && !['sessionId', 'doctorId', 'doctorName', 'slotId', 'slotName', 'startTime', 'endTime']
      .every(key => typeof item.session[key] === 'string')))) {
    throw new Error('草稿数据格式异常，请到演示页按编号核实。')
  }
  return drafts
}

export async function fetchConversationDrafts(conversationId) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`/api/appointments/drafts?conversationId=${encodeURIComponent(conversationId)}`, {
      cache: 'no-store', signal: controller.signal, headers: { Accept: 'application/json' },
    })
    checkHttp(response)
    return validateDrafts(await response.json())
  } finally {
    clearTimeout(timer)
  }
}

export async function streamChat({ conversationId, message, signal, onEvent }) {
  if (!conversationId || conversationId.length > 128 || !message.trim() || message.length > 2000) {
    throw new Error('会话编号或消息不合法；消息最多 2000 字符。')
  }
  const controller = new AbortController()
  let timedOut = false
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  if (signal?.aborted) abort()
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, 200000)
  let reader
  try {
    const response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { ...browserHeaders(), 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify({ conversationId, message }),
      cache: 'no-store', signal: controller.signal,
    })
    checkHttp(response)
    if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
      throw new Error('没有收到 SSE 响应，请检查代理地址和后端版本。')
    }
    reader = response.body.getReader()
    // 保留跨网络分块的 UTF-8 字节，避免中文或表情乱码。
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (true) {
      const part = await reader.read()
      buffer += part.done ? decoder.decode() : decoder.decode(part.value, { stream: true })
      if (buffer.length > 1000000) throw new Error('流式事件过大或缺少结束分隔符。')
      let boundary
      while ((boundary = /\r?\n\r?\n/.exec(buffer)) !== null) {
        const frame = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        let name = 'message'
        const lines = []
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith('event:')) name = line.slice(6).trim()
          if (line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /, ''))
        }
        if (!lines.length) continue
        if (!['status', 'token', 'sources', 'done', 'failed'].includes(name)) continue
        const data = JSON.parse(lines.join('\n'))
        if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('流式事件格式异常。')
        if (name === 'failed') throw new Error(typeof data.message === 'string' ? data.message : '本轮回复未完成。')
        if (name === 'done') {
          if (typeof data.reply !== 'string' || !data.reply.trim()) throw new Error('完整回复正文为空。')
          validateSources(data.sources)
          validateDrafts(data.drafts)
          return data
        }
        if (name === 'token' && typeof data.text !== 'string') throw new Error('回复片段格式异常。')
        if (name === 'sources') validateSources(data.sources)
        onEvent(name, data)
      }
      if (part.done) throw new Error('连接已结束，但未收到 done 完成事件。')
    }
  } catch (error) {
    if (timedOut) throw new Error('等待回复超时；后台工具可能仍在执行，请稍后同步草稿。')
    if (signal?.aborted) throw new Error('已停止接收回复；这不表示后台工具已停止，请稍后同步草稿。')
    throw error
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
    if (reader) await reader.cancel().catch(() => {})
    controller.abort()
  }
}
