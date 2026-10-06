// 仅用于用户主动导入升级前的浏览器会话，不再作为登录凭据。
export function readLegacyBrowserKey() {
  try {
    const value = localStorage.getItem('xiaozhi.course-ui.browser-key.v1')
    return /^[0-9a-f]{64}$/.test(value || '') ? value : null
  } catch { return null }
}
