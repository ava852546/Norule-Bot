export const CUSTOM_SHORT_CODE_MIN_LENGTH = 3
export const CUSTOM_SHORT_CODE_MAX_LENGTH = 32

const allowedCustomCode = /^[A-Za-z0-9_-]+$/
const reservedCustomCodes = new Set([
  'admin', 'api', 'auth', 'login', 'logout', 'dashboard', 'stats', 'oauth', 'callback',
  'privacy', 'terms', 'privacy-policy', 'terms-of-service', 'robots.txt', 'sitemap.xml',
  'favicon.ico', 'health', 'status', 'assets', 'static', 'media', 'images',
  'web', 'short-url', 'my-content', 'session', '_nuxt', 'share-expired', 'index', '404',
])

export function normalizeCustomShortCode(value: string): string {
  return value.trim().toLowerCase()
}

export function validateCustomShortCode(value: string): string | null {
  if (!value) return null
  const trimmed = value.trim()
  if (!trimmed) return '自訂代碼至少需要 3 個字元。'
  if (!allowedCustomCode.test(trimmed)) return '只能使用英文字母、數字、- 與 _。'
  if (trimmed.length < CUSTOM_SHORT_CODE_MIN_LENGTH) return '自訂代碼至少需要 3 個字元。'
  if (trimmed.length > CUSTOM_SHORT_CODE_MAX_LENGTH) return '自訂代碼最多只能有 32 個字元。'
  if (reservedCustomCodes.has(normalizeCustomShortCode(trimmed))) return '此名稱由系統保留，請使用其他代碼。'
  return null
}
