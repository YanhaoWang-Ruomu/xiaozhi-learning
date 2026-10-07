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
const online = ref(navigator.onLine)
const search = ref('')
const sidebarOpen = ref(false)
const showAppointments = ref(false)
const followingLatest = ref(true)
const copyNotice = ref('')
const visibleConversations = computed(() => conversations.value.filter(item => item.title.toLowerCase().includes(search.value.trim().toLowerCase())))
const currentTitle = computed(() => conversations.value.find(item => item.conversationId === conversationId.value)?.title || '新的导诊会话')
const pendingCount = computed(() => drafts.value.filter(item => item.status === 'PENDING_CONFIRMATION').length)
const quickPrompts = [
  { title: '了解导诊范围', text: '你能提供哪些就医准备帮助？' },
  { title: '查阅就诊资料', text: '请根据演示资料介绍就诊前需要做哪些准备。' },
  { title: '查询医生时段', text: '请查询 DEMO001 内科明天的医生和时段，先不要生成草稿。' },
]
function fillPrompt(text) { inputMessage.value = text; nextTick(() => document.getElementById('chat-message')?.focus()) }
function trackScroll() { const el = messageList.value; if (el) followingLatest.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80 }
async function copyReply(text) { try { await navigator.clipboard.writeText(text); copyNotice.value = '回复已复制。' } catch { copyNotice.value = '无法自动复制，请选中文字后复制。' } }
function wentOffline() { online.value = false; historyVerified.value = false; draftsVerified.value = false; activeController?.abort(); status.value = '网络已断开。恢复连接后会同步结果，不会自动重发消息。' }
function wentOnline() { online.value = true; if (!busy.value && conversationId.value) void synchronizeHistory(); else if (!conversationId.value) void initialize() }

const busy = computed(() => isSending.value || isSyncing.value || appointmentBusy.value || historyBusy.value)
const canSend = computed(() => online.value && historyReady.value && historyVerified.value && !busy.value && !serverProcessing.value)
let activeController = null
let disposed = false
let pollTimer

function savePointer() {
  try { localStorage.setItem(POINTER_KEY, conversationId.value) }
  catch { storageNotice.value = '浏览器未能保存当前会话位置；仍可从服务端会话列表选择。' }
}
async function scrollToBottom(force = false) {
  await nextTick()
  if (messageList.value && !messages.value.length) { messageList.value.scrollTop = 0; return }
  if (messageList.value && (force || followingLatest.value)) { messageList.value.scrollTop = messageList.value.scrollHeight; followingLatest.value = true }
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
  sidebarOpen.value = false
  followingLatest.value = true
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
  followingLatest.value = true
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
  window.addEventListener('offline', wentOffline)
  window.addEventListener('online', wentOnline)
  initialize()
  pollTimer = setInterval(() => {
    if (online.value && !document.hidden && (serverProcessing.value || !historyVerified.value) && conversationId.value && !busy.value) synchronizeHistory()
  }, 5000)
})
onBeforeUnmount(() => {
  disposed = true
  window.removeEventListener('offline', wentOffline)
  window.removeEventListener('online', wentOnline)
  activeController?.abort()
  clearInterval(pollTimer)
})
</script>

<template>
  <div class="app-layout" :class="{ 'sidebar-open': sidebarOpen, 'appointments-open': showAppointments }">
    <div class="mobile-toolbar"><button :aria-expanded="sidebarOpen" @click="sidebarOpen = !sidebarOpen">会话列表</button><button :aria-expanded="showAppointments" @click="showAppointments = !showAppointments">{{ showAppointments ? '返回对话' : '排班与预约' }}<span v-if="pendingCount"> · {{ pendingCount }} 待确认</span></button></div>
    <aside class="sidebar">
      <div class="sidebar-heading"><span class="eyebrow">我的空间</span><h2>会话记录</h2></div>
      <el-button class="wide" :disabled="busy" @click="newChat">新会话</el-button>
      <label class="conversation-search"><span class="sr-only">搜索已加载会话</span><input v-model="search" placeholder="搜索已加载会话" type="search" /></label>
      <nav class="conversation-list" aria-label="会话列表">
        <div class="actions">
          <button :disabled="listBusy || busy" @click="refreshConversations(false)">刷新会话列表</button>
          <button v-if="!historyReady" :disabled="busy" @click="initialize">重新连接</button>
        </div>
        <p v-if="listNotice && !listNotice.startsWith('按创建时间')" class="muted">{{ listNotice }}</p>
        <p v-if="search && !visibleConversations.length" class="muted">已加载的会话中没有匹配项。</p>
        <button v-for="item in visibleConversations" :key="item.conversationId" class="conversation-choice"
          :class="{ selected: item.conversationId === conversationId }" :disabled="busy"
          :aria-current="item.conversationId === conversationId ? 'true' : undefined"
          @click="selectConversation(item.conversationId)">{{ item.title }}</button>
        <button v-if="conversationCursor" :disabled="listBusy || busy" @click="refreshConversations(true)">更多会话</button>
      </nav>
      <p class="muted">可交流就医需求、查阅演示资料、准备预约草稿。不能替代医生诊断。</p>
      <span class="sidebar-footnote">记录仅对当前账号可见</span>
      <details class="session-info">
        <summary>当前会话编号</summary>
        <p class="identifier">{{ conversationId }}</p>
        <p class="muted">聊天历史保存在服务端，与模型记忆窗口分开。登录后可查看当前账号的会话；清除浏览器站点数据后，需要重新登录。</p>
      </details>
      <p v-if="storageNotice" class="warning" role="alert">{{ storageNotice }}</p>
    </aside>

    <main class="main-content">
      <section class="chat-container" aria-label="与小智交流">
        <header class="chat-heading"><div><span class="eyebrow">导诊对话</span><h1>{{ currentTitle }}</h1></div><span class="connection-state" :class="{ offline: !online }">{{ !online ? '网络已断开' : isSending || serverProcessing ? '正在处理' : !historyVerified ? '待同步' : '就绪' }}</span></header>
        <p class="scope-note">提供就医方向参考与演示资料，不能替代医生诊断。</p>
        <div v-if="!online" class="network-notice" role="alert">网络已断开，发送与预约操作已暂停。联网后将同步服务端结果，不会自动重发。</div>
        <div class="history-actions">
          <button :disabled="busy || !conversationId" @click="synchronizeHistory(false)">同步聊天历史</button>
          <button v-if="historyCursor" :disabled="busy" @click="synchronizeHistory(true)">加载更早消息</button>
        </div>
        <div ref="messageList" class="message-list" aria-label="聊天记录" @scroll="trackScroll">
          <div v-if="!messages.length" class="welcome">
            <span class="welcome-mark" aria-hidden="true">✚</span><span class="eyebrow">小智 · 就医准备助手</span>
            <h2>今天，有什么可以帮你？</h2>
            <p>从一个问题开始，逐步理清需求。<br />也可以选择下面的入口，编辑后再发送。</p>
            <div class="quick-prompts"><button v-for="(prompt, index) in quickPrompts" :key="prompt.title" :disabled="!canSend" @click="fillPrompt(prompt.text)"><span class="prompt-number">0{{ index + 1 }}</span><strong>{{ prompt.title }}</strong><span aria-hidden="true">↗</span></button></div>
            <p class="muted">所有排班和预约均为演示数据，不连接真实医院。</p>
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
            <button v-if="message.role === 'assistant' && message.content && message.state === 'complete'" class="copy-reply" @click="copyReply(message.content)">复制回复</button>
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
        <button v-if="!followingLatest" class="latest-button" @click="scrollToBottom(true)">回到最新消息 ↓</button>
        <span class="sr-only" role="status">{{ copyNotice }}</span>
        <form class="input-container" @submit.prevent="sendMessage">
          <label for="chat-message">描述你的需求</label>
          <el-input id="chat-message" v-model="inputMessage" type="textarea" :rows="3" maxlength="2000"
            show-word-limit placeholder="请输入消息；Enter 发送，Shift+Enter 换行" :disabled="!canSend" @keydown="onInputKey" />
          <div class="actions composer-actions">
            <span class="composer-hint">Enter 发送 · Shift+Enter 换行</span>
            <el-button type="primary" native-type="submit" :disabled="!canSend || !inputMessage.trim()">发送消息</el-button>
            <el-button v-if="isSending" :disabled="!activeController" @click="activeController?.abort()">停止接收</el-button>

          </div>
        </form>
        <p class="status" role="status">{{ status }}</p>
      </section>

      <AppointmentPanel v-if="historyReady" :key="conversationId" :conversation-id="conversationId" :drafts="drafts"
        :verified="draftsVerified" :verified-at="verifiedAt" :notice="draftNotice"
        :blocked="!online || isSending || isSyncing || historyBusy || !historyVerified || serverProcessing" :synchronize="synchronizeDrafts"
        @busy="appointmentBusy = $event" />
    </main>
  </div>
</template>

<style scoped>
.app-layout {
  display:flex;
  height:calc(100dvh - 69px);
  min-height:560px
}
.sidebar {
  width:230px;
  flex-shrink:0;
  background:#f8faf8;
  border-right:1px solid var(--line);
  padding:28px 20px;
  display:flex;
  flex-direction:column;
  overflow:auto
}
.sidebar-heading h2 {
  font-size:19px;
  margin:4px 0 22px;
  font-weight:600
}
.wide {
  width:100%;
  min-height:42px;
  color:#08786d;
  border-color:#b6d5c9;
  background:#edf5f0
}
.conversation-search input {
  margin:18px 0 8px;
  width:100%;
  font-size:12px;
  padding:10px 12px;
  background:white;
  border:1px solid var(--line);
  border-radius:8px;
  color:var(--ink)
}
.conversation-list {
  flex:1;
  min-height:120px;
  overflow:auto;
  margin-bottom:16px
}
.conversation-list .actions {
  margin:0 0 14px
}
.conversation-list button,.history-actions button {
  border:0;
  background:transparent;
  color:#59736c;
  padding:6px;
  cursor:pointer;
  font-size:12px
}
.conversation-list .conversation-choice {
  display:block;
  width:100%;
  margin:5px 0;
  padding:11px 12px;
  text-align:left;
  border-radius:8px;
  font-size:13px;
  overflow-wrap:anywhere;
  border:1px solid transparent
}
.conversation-choice.selected {
  color:#066f64;
  background:#e6f0e9!important;
  border-color:#d0e2d6!important;
  font-weight:600
}
.sidebar>.muted,.sidebar-footnote {
  font-size:12px;
  color:#75877f
}
.session-info {
  font-size:12px;
  color:#667e74;
  margin-top:14px
}
.identifier {
  overflow-wrap:anywhere
}
.main-content {
  flex:1;
  min-width:0;
  display:grid;
  grid-template-columns:minmax(0,1fr) 340px;
  gap:18px;
  padding:22px
}
.chat-container {
  position:relative;
  display:flex;
  flex-direction:column;
  min-width:0;
  min-height:0;
  background:white;
  border:1px solid var(--line);
  border-radius:16px;
  padding:24px
}
.chat-heading {
  display:flex;
  justify-content:space-between;
  gap:12px;
  align-items:center
}
.chat-heading h1 {
  font-size:20px;
  font-weight:600;
  margin:3px 0;
  overflow-wrap:anywhere;
  display:-webkit-box;
  -webkit-line-clamp:2;
  -webkit-box-orient:vertical;
  overflow:hidden
}
.connection-state {
  display:flex;
  align-items:center;
  gap:6px;
  font-size:12px;
  white-space:nowrap;
  color:#4f796b;
  background:#f0f7f2;
  padding:4px 9px;
  border-radius:20px
}
.connection-state::before {
  content:'';
  width:6px;
  height:6px;
  background:#4f8870;
  border-radius:50%
}
.connection-state.offline {
  color:#975e1f;
  background:#fff3dd
}
.scope-note {
  font-size:12px;
  color:#73847b;
  margin:4px 0 14px
}
.history-actions {
  display:flex;
  gap:12px;
  border-top:1px solid #edf1ee;
  padding-top:8px
}
.history-actions button {
  padding:4px 0
}
.message-list {
  overflow-y:auto;
  flex:1;
  min-height:140px;
  padding:20px 4px;
  scrollbar-width:thin;
  scrollbar-color:#c5d5cd transparent
}
.welcome {
  max-width:590px;
  margin:auto;
  padding:clamp(24px,6vh,64px) 12px 20px
}
.welcome-mark {
  display:grid;
  place-items:center;
  width:48px;
  height:48px;
  margin-bottom:24px;
  border-radius:16px;
  background:#e6f2ec;
  color:#087f75;
  font-size:29px
}
.welcome h2 {
  font-size:clamp(23px,2vw,30px);
  font-weight:550;
  letter-spacing:-.03em;
  margin:10px 0
}
.welcome p {
  color:#6c8177;
  font-size:14px;
  line-height:1.9
}
.welcome .muted {
  font-size:12px
}
.quick-prompts {
  display:grid;
  gap:9px;
  margin:25px 0
}
.quick-prompts button {
  display:flex;
  align-items:center;
  gap:14px;
  text-align:left;
  padding:13px 15px;
  background:white;
  border:1px solid #dfe9e2;
  border-radius:10px;
  color:#375c4e;
  font-size:13px
}
.quick-prompts strong {
  font-weight:500;
  flex:1
}
.prompt-number {
  font-size:11px;
  color:#7d9487
}
.message {
  padding:18px 20px;
  border-radius:12px;
  margin:0 0 22px;
  overflow-wrap:anywhere;
  font-size:14px;
  line-height:1.9
}
.user-message {
  background:#edf5f0;
  margin-left:16%;
  border:1px solid #dfece3
}
.bot-message {
  background:#fff;
  padding-left:0;
  padding-right:8px
}
.message-label {
  font-size:12px;
  font-weight:650;
  color:#557c6e;
  margin-bottom:9px
}
.message-text {
  white-space:pre-wrap;
  overflow-wrap:anywhere
}
.copy-reply {
  font-size:11px;
  margin-top:12px;
  padding:3px 0;
  color:#748b7f;
  background:transparent;
  border:0
}
.sources {
  font-size:12px;
  margin-top:12px;
  border:1px solid #e0e8e1;
  border-radius:8px;
  background:#f8faf7;
  padding:10px 12px
}
.sources summary {
  color:#54745d;
  cursor:pointer
}
.sources .muted {
  font-size:12px
}
.source {
  border-top:1px solid #e0e8e1;
  padding:12px 0
}
.source strong {
  display:block;
  margin-bottom:6px;
  font-weight:600
}
.source .message-text {
  max-height:240px;
  overflow:auto
}
.input-container {
  border:1px solid #d3e1d8;
  border-radius:12px;
  padding:12px 14px 10px;
  box-shadow:0 3px 16px #183d2210
}
.input-container label {
  display:block;
  font-size:12px;
  color:#6a8073;
  margin-bottom:8px
}
.input-container :deep(.el-textarea__inner) {
  box-shadow:none;
  padding:0;
  background:transparent;
  resize:none;
  font-size:14px;
  line-height:1.8
}
.input-container :deep(.el-textarea.is-disabled .el-textarea__inner) {
  background:#f7f9f7
}
.input-container :deep(.el-input__count) {
  background:transparent;
  font-size:10px
}
.actions {
  display:flex;
  flex-wrap:wrap;
  gap:8px;
  margin-top:12px
}
.actions .el-button+.el-button {
  margin-left:0
}
.composer-actions {
  align-items:center
}
.composer-hint {
  font-size:10px;
  color:#73877a;
  margin-right:auto
}
.composer-actions .el-button {
  font-size:12px
}
.status {
  color:#637d6d;
  font-size:11px;
  margin:10px 2px 0;
  overflow-wrap:anywhere
}
.warning,.network-notice {
  color:#8d5e24;
  font-size:12px
}
.network-notice {
  background:#fff4e0;
  padding:10px;
  border-radius:8px;
  margin-bottom:8px
}
.latest-button {
  position:absolute;
  bottom:218px;
  left:50%;
  transform:translateX(-50%);
  padding:7px 14px;
  border:1px solid #bdd6c5;
  border-radius:20px;
  background:#fff;
  color:#3e7151;
  box-shadow:0 2px 10px #254a2320;
  font-size:12px
}
.mobile-toolbar {
  display:none
}
@media(min-width:1600px) {
  .main-content {
    grid-template-columns:minmax(0,1fr) 380px;
    max-width:1700px;
    margin:0 auto;
    width:100%
  }
  .chat-container {
    padding:28px 36px
  }
}
@media(max-width:1200px) {
  .sidebar {
    width:200px;
    padding:24px 14px
  }
  .main-content {
    grid-template-columns:minmax(0,1fr) 300px;
    padding:14px;
    gap:12px
  }
  .chat-container {
    padding:18px
  }
  .composer-hint {
    display:none
  }
}
@media(max-width:1000px) {
  .app-layout {
    height:auto;
    min-height:calc(100dvh - 69px);
    flex-wrap:wrap;
    align-content:flex-start;
  }
  .mobile-toolbar {
    display:flex;
    width:100%;
    gap:8px;
    padding:10px 16px;
    background:#f7faf7;
    border-bottom:1px solid var(--line)
  }
  .mobile-toolbar button {
    background:#fff;
    border:1px solid var(--line);
    border-radius:8px;
    font-size:12px;
    padding:7px 12px;
    color:#436450
  }
  .sidebar {
    display:none;
    width:100%;
    max-height:45vh;
    border-bottom:1px solid var(--line)
  }
  .sidebar-open .sidebar {
    display:flex
  }
  .main-content {
    grid-template-columns:minmax(0,1fr);
    width:100%;
    padding:12px
  }
  .chat-container {
    height:calc(100dvh - 160px);
    min-height:420px
  }
  .main-content :deep(.appointment-panel) {
    display:none;
    max-height:none
  }
  .appointments-open .main-content :deep(.appointment-panel) {
    display:block
  }
  .appointments-open .chat-container {
    display:none
  }
  .sidebar-heading h2 {
    margin-bottom:12px
  }
}
@media(max-width:640px) {
  .main-content {
    padding:8px
  }
  .chat-container {
    height:calc(100dvh - 208px);
    padding:16px 12px;
    border-radius:12px;
    min-height:430px
  }
  .chat-heading h1 {
    font-size:17px
  }
  .scope-note {
    font-size:11px
  }
  .welcome {
    padding:20px 6px
  }
  .welcome-mark {
    margin-bottom:15px
  }
  .welcome h2 {
    font-size:24px
  }
  .quick-prompts {
    margin:18px 0
  }
  .message {
    font-size:14px;
    padding:12px
  }
  .bot-message {
    padding-left:0
  }
  .input-container {
    padding:10px
  }
  .status {
    font-size:10px
  }
  .user-message {
    margin-left:10%
  }
  .composer-actions {
    margin-top:8px
  }
  .mobile-toolbar {
    padding:8px 12px
  }
}
</style>
