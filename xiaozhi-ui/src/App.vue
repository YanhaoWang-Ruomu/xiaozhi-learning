<script setup>
import { onMounted, onBeforeUnmount, ref, defineAsyncComponent } from 'vue'
import './style.css'
const EngineeringLab = defineAsyncComponent(() => import('@/components/EngineeringLab.vue'))
import ChatWindow from '@/components/ChatWindow.vue'
import { currentUser, login, register, logout, importBrowserHistory, resetCsrf, announceAuthChange } from '@/api/auth.js'
import { readLegacyBrowserKey } from '@/api/browserIdentity.js'
const user = ref(null)
const showLab = ref(false)
const checking = ref(true)
const busy = ref(false)
const registering = ref(false)
const username = ref('')
const password = ref('')
const confirmation = ref('')
const notice = ref('')
const revision = ref(0)
const legacyAvailable = ref(Boolean(readLegacyBrowserKey()))
let generation = 0
function expired() { generation++; user.value = null; showLab.value = false; password.value = ''; confirmation.value = ''; resetCsrf() }
async function restore() {
  const ticket = ++generation
  checking.value = true
  try { const value = await currentUser(); if (ticket === generation) user.value = value }
  catch (error) { notice.value = error.message }
  finally { checking.value = false }
}
async function submit() {
  if (busy.value) return
  const name = username.value.trim().toLowerCase()
  if (!/^[a-z0-9_]{3,32}$/.test(name)) { notice.value = '用户名使用3到32位英文字母、数字或下划线。'; return }
  if (password.value.length < 12 || new TextEncoder().encode(password.value).length > 72) { notice.value = '密码至少12个字符，UTF-8长度不超过72字节。'; return }
  if (registering.value && password.value !== confirmation.value) { notice.value = '两次密码不一致。'; return }
  busy.value = true
  notice.value = ''
  try {
    const credentials = { username: name, password: password.value }
    if (registering.value) { await register(credentials); registering.value = false; notice.value = '注册成功，请输入密码登录。' }
    else { user.value = await login(credentials); revision.value++; announceAuthChange() }
  } catch (error) { notice.value = error.message }
  finally { password.value = ''; confirmation.value = ''; busy.value = false }
}
async function signOut() {
  if (busy.value || !window.confirm('退出登录？已经提交的请求可能仍在后台处理，重新登录后可查询结果。')) return
  busy.value = true
  try { await logout(); expired(); notice.value = '已退出登录。'; announceAuthChange() }
  catch (error) { notice.value = error.message }
  finally { busy.value = false }
}
async function importOld() {
  if (busy.value || !window.confirm('将本浏览器密钥对应的旧会话及关联草稿归入当前账号？导入后其他账号不能再次领取。请确认这些记录属于你。')) return
  busy.value = true
  try {
    const key = readLegacyBrowserKey()
    if (!key) throw new Error('此浏览器没有可用的旧会话凭据。')
    const result = await importBrowserHistory(key)
    notice.value = `已导入 ${result.importedCount} 个旧会话。没有归属凭据的旧记录不会自动分配。`
    revision.value++
  } catch (error) { notice.value = error.message }
  finally { busy.value = false }
}
function changed(event) {
  if (event.key === 'xiaozhi.auth.changed') { expired(); restore() }
}
onMounted(() => { window.addEventListener('xiaozhi-auth-expired', expired); window.addEventListener('storage', changed); restore() })
onBeforeUnmount(() => { window.removeEventListener('xiaozhi-auth-expired', expired); window.removeEventListener('storage', changed) })
</script>
<template>
  <main v-if="checking" class="auth-card loading-card" role="status">正在检查登录状态……</main>
  <template v-else-if="user">
    <header class="account-bar">
      <a class="wordmark" href="#main-workspace" @click.prevent="showLab = false"><span class="brand-mark" aria-hidden="true">✚</span><strong>小智</strong><span class="brand-subtitle">医疗导诊</span></a>
      <span class="demo-pill">学习演示</span>
      <nav class="workspace-nav" aria-label="工作区"><button :class="{ active: !showLab }" :aria-pressed="!showLab" @click="showLab = false">导诊工作台</button><button :class="{ active: showLab }" :aria-pressed="showLab" @click="showLab = true">AI 工程实验室</button></nav>
      <div class="account-actions"><span class="account-name" :title="user.username"><span class="avatar" aria-hidden="true">{{ user.username.slice(0, 1).toUpperCase() }}</span>{{ user.username }}</span>
        <button v-if="legacyAvailable" :disabled="busy" @click="importOld">导入本浏览器旧会话</button><button :disabled="busy" @click="signOut">退出登录</button></div>
    </header>
    <p v-if="notice" class="global-notice" role="status">{{ notice }}</p>
    <div id="main-workspace">
      <EngineeringLab v-if="showLab" :key="user.userId" :user-id="user.userId" />
      <ChatWindow v-show="!showLab" :key="user.userId + ':' + revision" :user-id="user.userId" />
    </div>
  </template>
  <main v-else class="login-layout">
    <section class="login-intro" aria-label="小智介绍">
      <div class="wordmark"><span class="brand-mark" aria-hidden="true">✚</span><strong>小智</strong><span>医疗导诊 · 学习演示</span></div>
      <div class="intro-copy"><span class="eyebrow">从一个问题开始</span><h1>把就医准备，<br />一步步理清。</h1><p>交流就医需求，查阅资料，核对预约安排。<br />每一步都有清楚的依据与状态。</p>
      <ol class="intro-steps"><li><span>01</span><div><strong>先聊需求</strong><p>用自然语言描述需要的帮助</p></div></li><li><span>02</span><div><strong>查看依据</strong><p>展开回复中的检索资料，核对来源</p></div></li><li><span>03</span><div><strong>亲自确认</strong><p>演示预约经核对后才会提交</p></div></li></ol></div>
      <p class="login-boundary">不替代医生诊断，不办理真实就医预约。</p>
    </section>
    <section class="auth-card" aria-label="账号登录">
      <span class="eyebrow">个人工作台</span><h2>{{ registering ? '创建你的账号' : '欢迎回来' }}</h2><p class="muted">登录后继续会话，查看自己的演示预约。</p>
      <form @submit.prevent="submit">
        <h3 class="sr-only">{{ registering ? '注册账号' : '登录账号' }}</h3>
        <label>用户名<input v-model="username" autocomplete="username" maxlength="32" placeholder="输入用户名" required :disabled="busy" /></label>
        <small>3–32位英文字母、数字或下划线，不区分大小写。</small>
        <label>密码<input v-model="password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" minlength="12" maxlength="72" placeholder="输入密码" required :disabled="busy" /></label>
        <small>至少12个字符，UTF-8长度不超过72字节。</small>
        <label v-if="registering">确认密码<input v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" /></label>
        <p v-if="notice" class="form-notice" role="status">{{ notice }}</p>
        <button class="login-submit" type="submit" :disabled="busy">{{ busy ? '处理中……' : registering ? '注册' : '登录' }}</button>
        <div class="login-secondary"><button type="button" :disabled="busy" @click="registering = !registering; password = ''; confirmation = ''; notice = ''">{{ registering ? '返回登录' : '创建账号' }}</button><button type="button" :disabled="busy" @click="restore">重新检查连接</button></div>
      </form>
      <p class="auth-footer">会话与演示预约按账号保存。</p>
    </section>
  </main>
</template>
