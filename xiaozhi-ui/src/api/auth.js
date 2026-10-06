let csrf = null
let pending = null
export function resetCsrf() { csrf = null; pending = null }
async function token() {
  if (csrf) return csrf
  if (!pending) pending = fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store', signal: AbortSignal.timeout(15000) })
    .then(async response => {
      if (!response.ok) throw new Error('无法获取安全校验信息，请刷新页面。')
      const data = await response.json()
      if (typeof data.token !== 'string' || typeof data.headerName !== 'string') throw new Error('安全校验信息格式错误。')
      csrf = data
      return data
    }).finally(() => { pending = null })
  return pending
}
export async function apiFetch(url, options = {}) {
  const headers = new Headers(options.headers)
  if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
    const value = await token()
    headers.set(value.headerName, value.token)
  }
  const response = await fetch(url, { ...options, headers, credentials: 'same-origin' })
  if (response.status === 403) resetCsrf()
  if (response.status === 401 && !['/api/auth/login', '/api/auth/me'].includes(url)) window.dispatchEvent(new Event('xiaozhi-auth-expired'))
  return response // 不自动重试写操作：失败或断线后由用户核实状态。
}
async function request(url, body) {
  const response = await apiFetch(url, { signal: AbortSignal.timeout(30000), method: body === undefined ? 'GET' : 'POST', cache: 'no-store',
    headers: { 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  if (!response.ok) {
    const hints = { 400: '请检查用户名和密码格式', 401: '账号或密码错误，或登录已失效', 403: '安全校验失败，请刷新后重试', 409: '用户名已存在', 429: '请求过多，请5分钟后再试' }
    throw new Error(hints[response.status] || `操作失败（HTTP ${response.status}）`)
  }
  return response.json()
}
export async function currentUser() {
  const response = await apiFetch('/api/auth/me', { cache: 'no-store', signal: AbortSignal.timeout(15000) })
  if (response.status === 401) return null
  if (!response.ok) throw new Error('无法连接账号服务，请确认后端已启动。')
  return response.json()
}
export async function login(value) { const user = await request('/api/auth/login', value); resetCsrf(); return user }
export const register = value => request('/api/auth/register', value)
export async function logout() { await request('/api/auth/logout', {}); resetCsrf() }
export const importBrowserHistory = legacyKey => request('/api/conversations/import-browser', { legacyKey })
export function announceAuthChange() {
  try { localStorage.setItem('xiaozhi.auth.changed', `${Date.now()}:${Math.random()}`) } catch { /* 当前标签页仍会清空 */ }
}
