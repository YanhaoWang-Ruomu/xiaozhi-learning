import { browserHeaders } from './browserIdentity.js'
import { validateSources } from './chat.js'

async function request(url, body) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(url, { method: body === undefined ? 'GET' : 'POST', cache: 'no-store',
      signal: controller.signal, headers: { ...browserHeaders(), Accept: 'application/json', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
    if (!response.ok) throw new Error(`会话接口失败（HTTP ${response.status}），请确认已重启新版后端。`)
    return await response.json()
  } catch (error) {
    if (controller.signal.aborted) throw new Error('会话查询超时，请重试。')
    throw error
  } finally { clearTimeout(timer) }
}
function conversation(item) {
  if (!item || !['conversationId', 'title', 'createdAt', 'updatedAt'].every(k => typeof item[k] === 'string') ||
      !item.conversationId || item.conversationId.length > 128) throw new Error('会话数据格式异常。')
  return item
}
function cursor(value) {
  if (value !== null && (typeof value !== 'string' || !/^[0-9a-f]{24}$/i.test(value))) throw new Error('历史分页数据异常。')
  return value
}
export async function createConversation(conversationId) {
  const result = conversation(await request('/api/conversations', { conversationId }))
  if (result.conversationId !== conversationId) throw new Error('会话编号不一致。')
  return result
}
export async function fetchConversations(before = null) {
  const data = await request('/api/conversations?limit=20' + (before ? '&before=' + encodeURIComponent(before) : ''))
  if (!Array.isArray(data.items)) throw new Error('会话列表格式异常。')
  data.items.forEach(conversation)
  cursor(data.nextCursor)
  return data
}
export async function fetchHistory(id, before = null) {
  const data = await request('/api/conversations/' + encodeURIComponent(id) + '/messages?limit=20' +
    (before ? '&before=' + encodeURIComponent(before) : ''))
  conversation(data.conversation)
  cursor(data.nextCursor)
  if (data.conversation.conversationId !== id || typeof data.processing !== 'boolean' || !Array.isArray(data.messages)) throw new Error('历史会话不匹配。')
  const ids = new Set()
  for (const item of data.messages) {
    if (!item || typeof item.id !== 'string' || ids.has(item.id) || !['user', 'assistant'].includes(item.role) ||
        typeof item.content !== 'string' || !['complete', 'processing', 'interrupted'].includes(item.state)) throw new Error('历史消息格式异常。')
    validateSources(item.sources)
    ids.add(item.id)
  }
  return data
}
