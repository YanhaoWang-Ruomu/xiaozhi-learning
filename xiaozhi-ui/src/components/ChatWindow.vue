<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { v4 as uuidv4 } from 'uuid'
import { fetchConversationDrafts, streamChat, validateSources } from '@/api/chat.js'

const CACHE_KEY = 'xiaozhi.course-ui.chat.v1'
const conversationId = ref(uuidv4())
const messages = ref([])
const inputMessage = ref('')
const isSending = ref(false)
const isSyncing = ref(false)
const status = ref('请输入消息开始交流。')
const draftNotice = ref('')
const storageNotice = ref('')
const drafts = ref([])
const draftsVerified = ref(false)
const verifiedAt = ref('')
const messageList = ref(null)
const busy = computed(() => isSending.value || isSyncing.value)
const draftStates = {
  PENDING_CONFIRMATION: '待确认', CONFIRMING: '确认处理中',
  CONFIRMED: '已确认', CANCELLED: '草稿已取消', APPOINTMENT_CANCELLED: '预约已取消',
}
let activeController = null
let saveTimer = null
let disposed = false

function persist() {
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify({
      version: 1, conversationId: conversationId.value, messages: messages.value.slice(-100),
    }))
  } catch {
    storageNotice.value = '浏览器缓存不可用或已满；刷新可能丢失页面记录，请先保存会话编号和草稿编号。'
  }
}

function restore() {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY)
    if (!raw) return
    const saved = JSON.parse(raw)
    if (saved.version !== 1 || typeof saved.conversationId !== 'string' ||
        !/^[0-9a-f-]{36}$/i.test(saved.conversationId) || !Array.isArray(saved.messages)) {
      throw new Error('Invalid cache')
    }
    // 先全部校验，再替换当前会话，避免混入另一会话的半份缓存。
    const restored = saved.messages.slice(-100).map(item => {
      if (!item || !['user', 'assistant'].includes(item.role) ||
          typeof item.content !== 'string' || typeof item.id !== 'string' ||
          !['complete', 'streaming', 'interrupted'].includes(item.state)) throw new Error('Invalid message')
      return {
        id: item.id, role: item.role, content: item.content,
        sources: validateSources(item.sources),
        state: item.state === 'streaming' ? 'interrupted' : item.state,
      }
    })
    conversationId.value = saved.conversationId
    messages.value = restored
    status.value = '已恢复当前标签页记录；草稿状态将从后端重新查询。'
  } catch {
    storageNotice.value = '旧页面缓存无法读取，已新建会话；这不会删除后端已有数据。'
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

watch(messages, () => {
  scrollToBottom()
  // 避免每个 token 都同步写入浏览器存储。
  if (!saveTimer) saveTimer = setTimeout(() => { saveTimer = null; persist() }, 200)
}, { deep: true })

async function synchronizeDrafts() {
  if (isSyncing.value) return
  const id = conversationId.value
  isSyncing.value = true
  draftsVerified.value = false
  draftNotice.value = '正在查询当前会话最近 100 份草稿……'
  try {
    const list = await fetchConversationDrafts(id)
    if (disposed || id !== conversationId.value) return
    drafts.value = list
    draftsVerified.value = true
    verifiedAt.value = new Date().toLocaleTimeString()
    draftNotice.value = list.length ? `本次查询到 ${list.length} 份草稿。` : '本次查询未找到当前会话的草稿。'
  } catch {
    if (!disposed && id === conversationId.value) {
      draftNotice.value = '同步失败，已显示的详情仅供参考，当前状态未核实。请检查后端后再次同步。'
    }
  } finally {
    if (!disposed && id === conversationId.value) isSyncing.value = false
  }
}

async function sendMessage() {
  const text = inputMessage.value.trim()
  if (busy.value || !text) return
  if (text.length > 2000) { status.value = '消息最多 2000 字符。'; return }
  isSending.value = true
  inputMessage.value = ''
  messages.value.push({ id: uuidv4(), role: 'user', content: text, sources: [], state: 'complete' })
  messages.value.push({ id: uuidv4(), role: 'assistant', content: '', sources: [], state: 'streaming' })
  messages.value = messages.value.slice(-100)
  const bot = messages.value.at(-1)
  activeController = new AbortController()
  status.value = '正在连接后端……'
  persist()
  try {
    const result = await streamChat({
      conversationId: conversationId.value, message: text, signal: activeController.signal,
      onEvent(name, data) {
        if (disposed) return
        if (name === 'token') {
          if (bot.content.length + data.text.length > 200000) throw new Error('回复过长，接收已中断。')
          bot.content += data.text
        }
        if (name === 'sources') bot.sources = data.sources
        if (name === 'status' && typeof data.message === 'string') status.value = data.message
      },
    })
    if (disposed) return
    // 最终正文覆盖增量，避免把工具调用前的过渡语重复保留下来。
    bot.content = result.reply
    bot.sources = result.sources
    bot.state = 'complete'
    const current = new Map(drafts.value.map(draft => [draft.draftId, draft]))
    for (const draft of result.drafts) current.set(draft.draftId, draft)
    drafts.value = [...current.values()]
    status.value = result.drafts.length ? '回复完成，工具返回的草稿已显示。' : '回复完成。'
  } catch (error) {
    if (disposed) return
    bot.state = 'interrupted'
    status.value = error.message || '网络请求失败。'
  } finally {
    activeController = null
    if (!disposed) {
      persist()
      // 只查询草稿，不重发聊天；模型若仍在执行，需要稍后手动再同步。
      await synchronizeDrafts()
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

function newChat() {
  if (busy.value) return
  if (messages.value.length && !window.confirm('开始新会话会清空本标签页的聊天显示。后端已有草稿不会被删除，请先保存需要的草稿编号。继续吗？')) return
  clearTimeout(saveTimer)
  saveTimer = null
  conversationId.value = uuidv4()
  messages.value = []
  drafts.value = []
  inputMessage.value = ''
  verifiedAt.value = ''
  draftsVerified.value = false
  status.value = '新会话已开始。'
  persist()
  synchronizeDrafts()
}

async function copyId(id) {
  try {
    await navigator.clipboard.writeText(id)
    draftNotice.value = '已复制。打开演示页，在“手动加载已有草稿”中粘贴编号并加载。'
  } catch {
    draftNotice.value = '自动复制不可用，请选中卡片中的完整编号，按 Ctrl+C 复制。'
  }
}

onMounted(() => {
  restore()
  persist()
  scrollToBottom()
  synchronizeDrafts()
  window.addEventListener('beforeunload', persist)
})

onBeforeUnmount(() => {
  disposed = true
  activeController?.abort()
  clearTimeout(saveTimer)
  persist()
  window.removeEventListener('beforeunload', persist)
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
      <p class="muted">可交流就医需求、查阅演示资料、准备预约草稿。不能替代医生诊断。</p>
      <a href="http://localhost:8081/demo.html" target="_blank" rel="noopener noreferrer">打开预约演示页 ↗</a>
      <details class="session-info">
        <summary>当前会话编号</summary>
        <p class="identifier">{{ conversationId }}</p>
        <p class="muted">本标签页暂存最近 100 条消息。新会话不会删除后端草稿。完整服务端历史尚未接入。</p>
      </details>
      <p v-if="storageNotice" class="warning" role="alert">{{ storageNotice }}</p>
    </aside>

    <main class="main-content">
      <section class="chat-container" aria-label="与小智交流">
        <header><h1>与小智交流</h1><span class="muted">预约业务时区：Asia/Shanghai</span></header>
        <div ref="messageList" class="message-list" aria-label="聊天记录">
          <div v-if="!messages.length" class="welcome">
            <h2>你好，我是小智</h2>
            <p>可以先问“你能做什么”，也可以查询 DEMO001 内科的演示排班。</p>
            <p class="muted">页面打开时不会自动发送消息。</p>
          </div>
          <article v-for="message in messages" :key="message.id" class="message"
            :class="message.role === 'user' ? 'user-message' : 'bot-message'">
            <div class="message-label">{{ message.role === 'user' ? '你' : '小智' }}
              <span v-if="message.state === 'streaming'"> · 正在生成…</span>
              <span v-if="message.state === 'interrupted'"> · 回复未完成</span>
            </div>
            <!-- 纯文本插值，用户输入和模型输出均不能作为 HTML 执行。 -->
            <div class="message-text">{{ message.content || (message.state === 'streaming' ? '正在准备回复……' : '未收到回复正文。') }}</div>
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
            show-word-limit placeholder="请输入消息；Enter 发送，Shift+Enter 换行" :disabled="busy" @keydown="onInputKey" />
          <div class="actions">
            <el-button type="primary" native-type="submit" :disabled="busy || !inputMessage.trim()">发送消息</el-button>
            <el-button v-if="isSending" :disabled="!activeController" @click="activeController?.abort()">停止接收</el-button>
            <el-button :disabled="busy" @click="inputMessage = '请查询 DEMO001 内科明天的医生和时段，先不要生成草稿。'">填入排班查询</el-button>
          </div>
        </form>
        <p class="status" role="status">{{ status }}</p>
      </section>

      <aside class="draft-panel" aria-label="当前会话草稿">
        <h2>当前会话草稿</h2>
        <el-button :disabled="busy" @click="synchronizeDrafts">同步当前会话草稿</el-button>
        <p class="muted">这里只查询和展示。确认或取消，请复制编号后打开预约演示页，在“手动加载已有草稿”中加载并核对。</p>
        <p class="status" role="status">{{ draftNotice }}</p>
        <p v-if="draftsVerified" class="muted">查询时间（本机）：{{ verifiedAt }}。状态可能在其他页面改变。</p>
        <article v-for="draft in drafts" :key="draft.draftId" class="draft-card">
          <strong>{{ draft.visitDate }} · {{ draft.department }}</strong>
          <p>{{ draft.hospitalId }} · {{ draft.timeZone }}</p>
          <p v-if="draft.session">{{ draft.session.doctorName }} · {{ draft.session.slotName }} {{ draft.session.startTime }}–{{ draft.session.endTime }}</p>
          <p v-else>未指定医生和时段（旧版按日期预约）</p>
          <p>{{ draftsVerified ? '查询时状态：' : '之前返回的状态（未重新核实）：' }}{{ draftStates[draft.status] || draft.status }}</p>
          <p class="identifier">{{ draft.draftId }}</p>
          <p v-if="draft.appointmentId" class="identifier">关联预约：{{ draft.appointmentId }}</p>
          <el-button size="small" @click="copyId(draft.draftId)">复制草稿编号</el-button>
        </article>
        <p v-if="!drafts.length && !isSyncing" class="muted">本页尚无可展示的当前会话草稿。</p>
      </aside>
    </main>
  </div>
</template>

<style scoped>
.app-layout { display: flex; min-height: 100vh; }
.sidebar { width: 220px; flex-shrink: 0; padding: 24px 18px; background: #eaf1fb; border-right: 1px solid #dce5f2; }
.brand { text-align: center; margin-bottom: 24px; }
.brand img { object-fit: contain; }
.brand strong { display: block; font-size: 19px; }
.brand span { display: block; font-size: 13px; color: #60748c; }
.wide { width: 100%; }
.muted { color: #60748c; font-size: 13px; }
.session-info { margin-top: 24px; font-size: 13px; }
.identifier { overflow-wrap: anywhere; user-select: text; font-size: 13px; }
.main-content { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 20px; flex: 1; min-width: 0; padding: 20px; }
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
  .sidebar { width: 190px; }
  .main-content { grid-template-columns: minmax(0, 1fr); }
  .chat-container { height: 78vh; }
  .draft-panel { max-height: none; }
}
@media (max-width: 640px) {
  .app-layout { flex-direction: column; }
  .sidebar { width: 100%; padding: 16px; }
  .brand { display: flex; align-items: center; gap: 12px; text-align: left; margin-bottom: 12px; }
  .brand img { width: 48px; height: 48px; }
  .session-info { margin-top: 12px; }
  .main-content { padding: 12px; gap: 12px; }
  .chat-container, .draft-panel { padding: 16px; }
  .chat-container { height: 80vh; }
}
</style>
