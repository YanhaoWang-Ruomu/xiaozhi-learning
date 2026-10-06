<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import AppointmentPanel from './AppointmentPanel.vue'
import { fetchConversationDrafts, streamChat } from '@/api/chat.js'
import { createConversation, fetchConversations, fetchHistory } from '@/api/conversations.js'

const props = defineProps({ userId: { type: String, required: true } })
const POINTER_KEY = 'xiaozhi.course-ui.active-conversation.v3.' + props.userId
const conversationId = ref('')
const messages = ref([])
const conversations = ref([])
const conversationCursor = ref(null)
const historyCursor = ref(null)
const historyBusy = ref(false)
const historyReady = ref(false)
const historyVerified = ref(false)
const serverProcessing = ref(false)
const listBusy = ref(false)
const listNotice = ref('')
const inputMessage = ref('')
const isSending = ref(false)
const isSyncing = ref(false)
const status = ref('正在读取服务端会话……')
const draftNotice = ref('')
const storageNotice = ref('')
const drafts = ref([])
const draftsVerified = ref(false)
const verifiedAt = ref('')
const messageList = ref(null)
const appointmentBusy = ref(false)
const busy = computed(() => isSending.value || isSyncing.value || appointmentBusy.value || historyBusy.value)
const canSend = computed(() => historyReady.value && historyVerified.value && !busy.value && !serverProcessing.value)
let activeController = null
let disposed = false
let pollTimer

function savePointer() {
  try { localStorage.setItem(POINTER_KEY, conversationId.value) }
  catch { storageNotice.value = '浏览器未能保存当前会话位置；仍可从服务端会话列表选择。' }
}
async function scrollToBottom() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}
async function refreshConversations(more = false) {
  if (listBusy.value) return
  listBusy.value = true
  try {
    const data = await fetchConversations(more ? conversationCursor.value : null)
    if (disposed) return
    conversations.value = more ? [...new Map([...conversations.value, ...data.items].map(item => [item.conversationId, item])).values()] : data.items
    conversationCursor.value = data.nextCursor
    listNotice.value = '按创建时间从新到旧排列。'
  } catch (error) { listNotice.value = error.message }
  finally { listBusy.value = false }
}
async function synchronizeDrafts() {
  if (isSyncing.value || !conversationId.value) return
  const id = conversationId.value
  isSyncing.value = true
  draftsVerified.value = false
  draftNotice.value = '正在查询当前会话草稿……'
  try {
    const list = await fetchConversationDrafts(id)
    if (disposed || id !== conversationId.value) return
    drafts.value = list
    draftsVerified.value = true
    verifiedAt.value = new Date().toLocaleTimeString()
    draftNotice.value = `本次查询到 ${list.length} 份草稿。`
  } catch {
    if (!disposed && id === conversationId.value) draftNotice.value = '同步失败，当前草稿状态未核实，请再次同步。'
  } finally { if (!disposed && id === conversationId.value) isSyncing.value = false }
}
async function readHistory(more = false) {
  const id = conversationId.value
  const before = more ? historyCursor.value : null
  const oldHeight = messageList.value?.scrollHeight || 0
  const data = await fetchHistory(id, before)
  if (disposed || id !== conversationId.value) return
  messages.value = more ? [...new Map([...data.messages, ...messages.value].map(item => [item.id, item])).values()] : data.messages
  historyCursor.value = data.nextCursor
  serverProcessing.value = data.processing
  historyReady.value = true
  historyVerified.value = true
  await nextTick()
  if (more && messageList.value) messageList.value.scrollTop += messageList.value.scrollHeight - oldHeight
  else await scrollToBottom()
}
async function synchronizeHistory(more = false) {
  if (busy.value || !conversationId.value) return
  historyBusy.value = true
  try {
    await readHistory(more)
    status.value = serverProcessing.value ? '后台仍在回复，将自动查询完成结果；请勿重复发送。' : '已读取服务端历史。'
    await synchronizeDrafts()
  } catch (error) { historyVerified.value = false; status.value = error.message }
  finally { historyBusy.value = false }
}
async function selectConversation(id) {
  if (busy.value) return
  if (inputMessage.value.trim() && !window.confirm('切换会话将清空尚未发送的输入，继续吗？')) return
  historyBusy.value = true
  conversationId.value = id
  messages.value = []
  drafts.value = []
  inputMessage.value = ''
  historyReady.value = false
  historyVerified.value = false
  serverProcessing.value = false
  historyCursor.value = null
  draftsVerified.value = false
  verifiedAt.value = ''
  status.value = '正在读取此会话……'
  savePointer()
  try {
    await readHistory()
    await synchronizeDrafts()
    status.value = serverProcessing.value ? '后台仍在回复，将自动查询完成结果。' : '已读取当前会话的服务端历史和草稿。'
  } catch (error) { status.value = error.message }
  finally { historyBusy.value = false }
}
async function newChat() {
  if (busy.value) return
  if (inputMessage.value.trim() && !window.confirm('新建会话将清空尚未发送的输入，继续吗？')) return
  historyBusy.value = true
  let created
  try { created = await createConversation(uuidv4()); await refreshConversations() }
  catch (error) { status.value = error.message }
  finally { historyBusy.value = false }
  if (created) { inputMessage.value = ''; await selectConversation(created.conversationId) }
}
async function initialize() {
  if (busy.value) return
  historyBusy.value = true
  let id = ''
  try {
    try { id = localStorage.getItem(POINTER_KEY) || '' }
    catch { storageNotice.value = '浏览器会话位置无法读取，请从列表选择已有会话。' }
    const page = await fetchConversations()
    if (disposed) return
    conversations.value = page.items
    conversationCursor.value = page.nextCursor
    if (!id) id = page.items[0]?.conversationId || (await createConversation(uuidv4())).conversationId
    await refreshConversations()
  } catch (error) { status.value = error.message; id = '' }
  finally { historyBusy.value = false }
  if (id) await selectConversation(id)
}
async function sendMessage() {
  const text = inputMessage.value.trim()
  if (!canSend.value || !text) return
  if (text.length > 2000) { status.value = '消息最多 2000 字符。'; return }
  const id = conversationId.value
  isSending.value = true
  inputMessage.value = ''
  messages.value.push({ id: uuidv4(), role: 'user', content: text, sources: [], state: 'complete' })
  messages.value.push({ id: uuidv4(), role: 'assistant', content: '', sources: [], state: 'streaming' })
  const bot = messages.value.at(-1)
  activeController = new AbortController()
  status.value = '正在连接后端……'
  scrollToBottom()
  try {
    const result = await streamChat({ conversationId: id, message: text, signal: activeController.signal,
      onEvent(name, data) {
        if (disposed || conversationId.value !== id) return
        if (name === 'token') {
          if (bot.content.length + data.text.length > 200000) throw new Error('回复过长，接收已中断。')
          bot.content += data.text
          scrollToBottom()
        }
        if (name === 'sources') bot.sources = data.sources
        if (name === 'status' && typeof data.message === 'string') status.value = data.message
      },
    })
    if (disposed) return
    bot.content = result.reply
    bot.sources = result.sources
    bot.state = 'complete'
    status.value = '回复已完成并保存到服务端。'
  } catch (error) {
    if (disposed) return
    bot.state = 'interrupted'
    status.value = error.message || '网络请求失败。'
  } finally {
    activeController = null
    if (!disposed) {
      try {
        await readHistory()
        if (serverProcessing.value) status.value = '连接已结束，后台仍在处理。正在定期同步历史，请勿重复提交。'
      } catch { historyVerified.value = false; status.value += ' 历史同步失败，请手动同步后再继续。' }
      await synchronizeDrafts()
      await refreshConversations()
      isSending.value = false
    }
  }
}
function onInputKey(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    sendMessage()
  }
}
onMounted(() => {
  initialize()
  pollTimer = setInterval(() => {
    if (!document.hidden && serverProcessing.value && !busy.value) synchronizeHistory()
  }, 5000)
})
onBeforeUnmount(() => {
  disposed = true
  activeController?.abort()
  clearInterval(pollTimer)
})
</script>

<template>
  <div class="app-layout">
    <aside class="sidebar">
      <div class="brand">
        <img src="@/assets/logo.png" alt="课程小智图标" width="88" height="88" />
        <div><strong>小智医疗导诊</strong><span>课程前端 · 学习演示</span></div>
      </div>
      <el-button class="wide" :disabled="busy" @click="newChat">新会话</el-button>
      <nav class="conversation-list" aria-label="会话列表">
        <div class="actions">
          <button :disabled="listBusy || busy" @click="refreshConversations(false)">刷新会话列表</button>
          <button v-if="!historyReady" :disabled="busy" @click="initialize">重新连接</button>
        </div>
        <p class="muted">{{ listNotice }}</p>
        <button v-for="item in conversations" :key="item.conversationId" class="conversation-choice"
          :class="{ selected: item.conversationId === conversationId }" :disabled="busy"
          :aria-current="item.conversationId === conversationId ? 'true' : undefined"
          @click="selectConversation(item.conversationId)">{{ item.title }}</button>
        <button v-if="conversationCursor" :disabled="listBusy || busy" @click="refreshConversations(true)">更多会话</button>
      </nav>
      <p class="muted">可交流就医需求、查阅演示资料、准备预约草稿。不能替代医生诊断。</p>
      <span>当前账号的会话与演示预约</span>
      <details class="session-info">
        <summary>当前会话编号</summary>
        <p class="identifier">{{ conversationId }}</p>
        <p class="muted">聊天历史保存在服务端，与模型记忆窗口分开。登录后可查看当前账号的会话；清除浏览器站点数据后，需要重新登录。</p>
      </details>
      <p v-if="storageNotice" class="warning" role="alert">{{ storageNotice }}</p>
    </aside>

    <main class="main-content">
      <section class="chat-container" aria-label="与小智交流">
        <header><h1>与小智交流</h1><span class="muted">预约业务时区：Asia/Shanghai</span></header>
        <div class="history-actions">
          <button :disabled="busy || !conversationId" @click="synchronizeHistory(false)">同步聊天历史</button>
          <button v-if="historyCursor" :disabled="busy" @click="synchronizeHistory(true)">加载更早消息</button>
        </div>
        <div ref="messageList" class="message-list" aria-label="聊天记录">
          <div v-if="!messages.length" class="welcome">
            <h2>你好，我是小智</h2>
            <p>可以先问“你能做什么”，也可以查询 DEMO001 内科的演示排班。</p>
            <p class="muted">页面打开时不会自动发送消息。</p>
          </div>
          <article v-for="message in messages" :key="message.id" class="message"
            :class="message.role === 'user' ? 'user-message' : 'bot-message'">
            <div class="message-label">{{ message.role === 'user' ? '你' : '小智' }}
              <span v-if="['streaming', 'processing'].includes(message.state)"> · 正在生成…</span>
              <span v-if="message.state === 'interrupted'"> · 回复未完成</span>
            </div>
            <!-- 纯文本插值，用户输入和模型输出均不能作为 HTML 执行。 -->
            <div class="message-text">{{ message.content || (['streaming', 'processing'].includes(message.state) ? '正在准备回复……' : '本轮未完整保存回复，请核实草稿后再继续。') }}</div>
            <p v-if="message.state === 'interrupted'" class="warning">本轮未完整取得；不会自动重发。后台可能已生成草稿，请稍后同步核实。</p>
            <details v-if="message.sources.length" class="sources">
              <summary>本轮检索参考片段（{{ message.sources.length }}）</summary>
              <p class="muted">这些是检索候选片段，不代表每句话都有依据，也不代表真实医院资料。</p>
              <div v-for="(source, index) in message.sources" :key="index" class="source">
                <strong>{{ source.source }} · 片段 {{ source.index }}</strong>
                <div class="message-text">{{ source.text }}</div>
              </div>
            </details>
          </article>
        </div>
        <form class="input-container" @submit.prevent="sendMessage">
          <label for="chat-message">你的消息</label>
          <el-input id="chat-message" v-model="inputMessage" type="textarea" :rows="3" maxlength="2000"
            show-word-limit placeholder="请输入消息；Enter 发送，Shift+Enter 换行" :disabled="!canSend" @keydown="onInputKey" />
          <div class="actions">
            <el-button type="primary" native-type="submit" :disabled="!canSend || !inputMessage.trim()">发送消息</el-button>
            <el-button v-if="isSending" :disabled="!activeController" @click="activeController?.abort()">停止接收</el-button>
            <el-button :disabled="busy" @click="inputMessage = '请查询 DEMO001 内科明天的医生和时段，先不要生成草稿。'">填入排班查询</el-button>
          </div>
        </form>
        <p class="status" role="status">{{ status }}</p>
      </section>

      <AppointmentPanel v-if="historyReady" :key="conversationId" :conversation-id="conversationId" :drafts="drafts"
        :verified="draftsVerified" :verified-at="verifiedAt" :notice="draftNotice"
        :blocked="isSending || isSyncing || historyBusy || !historyVerified || serverProcessing" :synchronize="synchronizeDrafts"
        @busy="appointmentBusy = $event" />
    </main>
  </div>
</template>

<style scoped>
.conversation-list { margin: 16px 0; }
.conversation-list button, .history-actions button { border: 1px solid #cfdbeb; border-radius: 7px; background: #fff; padding: 8px; color: #365c8b; cursor: pointer; font: inherit; font-size: 13px; }
.conversation-choice { display: block; width: 100%; margin: 6px 0; text-align: left; overflow-wrap: anywhere; }
.conversation-choice.selected { background: #dce9fd; border-color: #638cca; }
button:disabled { opacity: .5; cursor: not-allowed; }
.history-actions { display: flex; gap: 8px; margin: 6px 0; }

.app-layout { display: flex; min-height: 100vh; }
.sidebar { overflow-y: auto; max-height: 100vh; width: 220px; flex-shrink: 0; padding: 24px 18px; background: #eaf1fb; border-right: 1px solid #dce5f2; }
.brand { text-align: center; margin-bottom: 24px; }
.brand img { object-fit: contain; }
.brand strong { display: block; font-size: 19px; }
.brand span { display: block; font-size: 13px; color: #60748c; }
.wide { width: 100%; }
.muted { color: #60748c; font-size: 13px; }
.session-info { margin-top: 24px; font-size: 13px; }
.identifier { overflow-wrap: anywhere; user-select: text; font-size: 13px; }
.main-content { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 20px; flex: 1; min-width: 0; padding: 20px; }
.chat-container { display: flex; flex-direction: column; min-width: 0; height: calc(100vh - 40px); background: #fff; border-radius: 16px; padding: 20px; }
header { display: flex; flex-wrap: wrap; align-items: baseline; justify-content: space-between; gap: 8px; }
h1, h2 { margin: 0 0 12px; font-size: 21px; }
.message-list { overflow-y: auto; flex: 1; min-height: 180px; padding: 12px 4px; }
.welcome { padding: 24px 12px; }
.message { border-radius: 12px; padding: 16px; margin: 0 0 16px; overflow-wrap: anywhere; }
.user-message { background: #e9f1ff; margin-left: 12%; }
.bot-message { background: #f4f7fa; margin-right: 4%; }
.message-label { font-weight: bold; font-size: 13px; color: #58718e; margin-bottom: 8px; }
.message-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.warning { color: #9a5012; font-size: 13px; }
.sources { border-top: 1px solid #dce4ee; padding-top: 10px; margin-top: 14px; font-size: 13px; }
summary { cursor: pointer; }
.source { padding: 12px 0; border-top: 1px solid #dce4ee; }
.source strong { display: block; margin-bottom: 8px; }
.input-container { padding-top: 10px; }
.input-container label { display: block; margin-bottom: 8px; font-size: 14px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.actions .el-button + .el-button { margin-left: 0; }
.status { color: #536e8f; font-size: 13px; margin: 12px 0 0; overflow-wrap: anywhere; }
.draft-panel { padding: 20px; background: white; border-radius: 16px; max-height: calc(100vh - 40px); overflow-y: auto; }
.draft-card { border: 1px solid #d8e3f4; background: #f7faff; padding: 14px; border-radius: 12px; margin-top: 16px; font-size: 14px; }
.draft-card p { margin: 8px 0; }
@media (max-width: 1100px) {
  .sidebar { overflow-y: auto; max-height: 100vh; width: 190px; }
  .main-content { grid-template-columns: minmax(0, 1fr); }
  .chat-container { height: 78vh; }
  .draft-panel { max-height: none; }
}
@media (max-width: 640px) {
  .app-layout { flex-direction: column; }
  .sidebar { overflow-y: auto; max-height: 100vh; width: 100%; padding: 16px; }
  .brand { display: flex; align-items: center; gap: 12px; text-align: left; margin-bottom: 12px; }
  .brand img { width: 48px; height: 48px; }
  .session-info { margin-top: 12px; }
  .main-content { padding: 12px; gap: 12px; }
  .chat-container, .draft-panel { padding: 16px; }
  .chat-container { height: 80vh; }
}
</style>
