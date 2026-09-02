import { describe, expect, it } from 'vitest'
import {
  CUSTOM_SHORT_CODE_MAX_LENGTH,
  normalizeCustomShortCode,
  validateCustomShortCode,
} from '../app/utils/shortCode'

describe('自訂短網址代碼規則', () => {
  it('接受允許字元與長度邊界', () => {
    for (const code of ['abc', 'abcd', 'hello-world', 'hello_world', 'abc123', '123', 'a'.repeat(32)]) {
      expect(validateCustomShortCode(code)).toBeNull()
    }
    expect(CUSTOM_SHORT_CODE_MAX_LENGTH).toBe(32)
  })

  it('提供明確的長度與字元錯誤', () => {
    expect(validateCustomShortCode('a')).toContain('至少')
    expect(validateCustomShortCode('ab')).toContain('至少')
    expect(validateCustomShortCode('   ')).toContain('至少')
    expect(validateCustomShortCode('a'.repeat(33))).toContain('最多')
    for (const code of ['hello world', 'hello!', 'hello.test', 'hello/test', '中文', 'emoji-😀']) {
      expect(validateCustomShortCode(code)).toContain('只能使用')
    }
  })

  it('正規化大小寫並拒絕保留字', () => {
    expect(normalizeCustomShortCode('  My-Discord  ')).toBe('my-discord')
    for (const code of [
      'admin', 'ADMIN', 'Stats', 'LOGIN', 'api', 'auth', 'logout', 'oauth', 'callback',
      'session', 'my-content', '_nuxt', 'web', 'short-url', 'dashboard', 'share-expired',
    ]) {
      expect(validateCustomShortCode(code)).toContain('系統保留')
    }
  })
})
