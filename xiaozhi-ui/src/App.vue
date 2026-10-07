<script setup>
import { onMounted, onBeforeUnmount, ref } from 'vue'
import EngineeringLab from '@/components/EngineeringLab.vue'
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
function expired() { generation++; user.value = null; password.value = ''; confirmation.value = ''; resetCsrf() }
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
  <main v-if="checking" class="auth-card">正在检查登录状态……</main>
  <template v-else-if="user">
    <header class="account-bar"><span>当前账号：{{ user.username }}</span>
      <button v-if="legacyAvailable" :disabled="busy" @click="importOld">导入本浏览器旧会话</button>
      <button @click="showLab = !showLab">{{ showLab ? '返回聊天' : 'AI 工程实验室' }}</button>
      <button :disabled="busy" @click="signOut">退出登录</button>
      <span role="status">{{ notice }}</span>
    </header>
    <EngineeringLab v-if="showLab" :key="user.userId" :user-id="user.userId" />
    <ChatWindow v-show="!showLab" :key="user.userId + ':' + revision" :user-id="user.userId" />
  </template>
  <main v-else class="auth-card">
    <h1>小智医疗导诊学习演示</h1>
    <p>登录后查看自己的会话与演示预约。本系统不办理真实就医预约。</p>
    <form @submit.prevent="submit">
      <h2>{{ registering ? '注册账号' : '登录账号' }}</h2>
      <label>用户名<input v-model="username" autocomplete="username" maxlength="32" required :disabled="busy" /></label>
      <small>3到32位英文字母、数字或下划线，不区分大小写。</small>
      <label>密码<input v-model="password" type="password" :autocomplete="registering ? 'new-password' : 'current-password'" minlength="12" maxlength="72" required :disabled="busy" /></label>
      <small>至少12个字符，UTF-8长度不超过72字节。</small>
      <label v-if="registering">确认密码<input v-model="confirmation" type="password" autocomplete="new-password" required :disabled="busy" /></label>
      <p role="status">{{ notice }}</p>
      <button type="submit" :disabled="busy">{{ busy ? '处理中……' : registering ? '注册' : '登录' }}</button>
      <button type="button" :disabled="busy" @click="registering = !registering; password = ''; confirmation = ''; notice = ''">{{ registering ? '返回登录' : '创建账号' }}</button>
      <button type="button" :disabled="busy" @click="restore">重新检查连接</button>
    </form>
  </main>
</template>
<style>
* { box-sizing: border-box; }
html, body, #app { margin: 0; min-width: 320px; min-height: 100%; }
body { font-family: 'Microsoft YaHei', system-ui, sans-serif; color: #26374b; background: #f4f7fb; line-height: 1.6; }
button, input, textarea { font: inherit; }
a { color: #245ecc; }
.auth-card { max-width: 540px; margin: 6vh auto; background: white; padding: 28px; border-radius: 14px; }
.auth-card h1 { font-size: 24px; }
.auth-card label { display: block; margin-top: 16px; }
.auth-card input { display: block; width: 100%; padding: 10px; border: 1px solid #b7c5d8; border-radius: 6px; }
.auth-card small { color: #596a80; }
.auth-card button, .account-bar button { padding: 8px 14px; margin: 6px; border: 1px solid #b7c5d8; border-radius: 6px; background: #eef4ff; cursor: pointer; }
button:disabled { opacity: .6; cursor: wait; }
.account-bar { display: flex; align-items: center; flex-wrap: wrap; padding: 6px 20px; background: white; border-bottom: 1px solid #dce4ee; gap: 10px; }
</style>
