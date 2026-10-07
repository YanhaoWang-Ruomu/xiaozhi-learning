export class Backend {
  constructor({ baseUrl = 'http://127.0.0.1:8081', username, password } = {}) {
    const url = new URL(baseUrl)
    if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error('Only a loopback backend origin is allowed')
    if (!username || !password) throw new Error('MCP account credentials are required')
    this.origin = url.origin; this.username = username; this.password = password; this.cookies = new Map(); this.ready = null
  }
  async request(path, options = {}) {
    const response = await fetch(this.origin + path, { ...options, redirect: 'error', signal: AbortSignal.timeout(90000), headers: { ...options.headers, cookie: [...this.cookies].map(([k,v]) => `${k}=${v}`).join('; ') } })
    for (const line of response.headers.getSetCookie()) { const item = line.split(';')[0]; const at = item.indexOf('='); this.cookies.set(item.slice(0,at),item.slice(at+1)) }
    if (!response.ok) throw new Error(`Backend request failed (${response.status})`)
    return response.json()
  }
  async login() {
    const csrf = await this.request('/api/auth/csrf')
    await this.request('/api/auth/login', { method: 'POST', headers: { 'content-type': 'application/json', [csrf.headerName]: csrf.token }, body: JSON.stringify({ username: this.username, password: this.password }) })
  }
  async read(path) {
    if (!this.ready) this.ready = this.login().catch(error => { this.ready = null; throw error })
    await this.ready
    return this.request(path)
  }
  search(query) { return this.read('/api/knowledge/compare?' + new URLSearchParams({ query, mode: 'hybrid' })) }
  sessions(hospitalId, department) { return this.read('/api/appointment/sessions?' + new URLSearchParams({ hospitalId, department })) }
}
