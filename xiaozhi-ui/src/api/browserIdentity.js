const KEY = 'xiaozhi.course-ui.browser-key.v1'
export function browserHeaders() {
  let key = localStorage.getItem(KEY)
  if (!key) {
    const bytes = crypto.getRandomValues(new Uint8Array(32))
    key = [...bytes].map(n => n.toString(16).padStart(2, '0')).join('')
    localStorage.setItem(KEY, key)
    // 再读取一次，两个标签页同时初始化时尽量使用同一已持久化密钥。
    key = localStorage.getItem(KEY)
  }
  if (!key || !/^[0-9a-f]{64}$/.test(key)) throw new Error('浏览器访问密钥不可用，请检查浏览器存储设置。')
  return { 'X-Conversation-Key': key }
}
