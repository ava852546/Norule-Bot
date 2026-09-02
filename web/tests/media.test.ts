import { describe, expect, it } from 'vitest'
import { DEFAULT_MEDIA_CONFIG, defaultPassword, isSupportedMedia, mediaErrorMessage, shortUrlErrorMessage, validateCustomExpiration, validateMediaFile } from '../app/utils/media'

describe('媒體分享驗證', () => {
  it('接受既有 API 支援的圖片與影片格式', () => {
    expect(isSupportedMedia({ name: 'photo.webp', type: 'image/webp' })).toBe(true)
    expect(isSupportedMedia({ name: 'clip.mp4', type: 'video/mp4' })).toBe(true)
    expect(isSupportedMedia({ name: 'archive.zip', type: 'application/zip' })).toBe(false)
  })

  it('依圖片與影片限制分別驗證檔案大小', () => {
    expect(validateMediaFile({ name: 'photo.png', type: 'image/png', size: DEFAULT_MEDIA_CONFIG.maxFileSizeBytes + 1 }, DEFAULT_MEDIA_CONFIG)).toContain('圖片')
    expect(validateMediaFile({ name: 'clip.webm', type: 'video/webm', size: DEFAULT_MEDIA_CONFIG.maxVideoFileSizeBytes + 1 }, DEFAULT_MEDIA_CONFIG)).toContain('影片')
  })

  it('只接受未來且未超過伺服器上限的自訂期限', () => {
    const now = Date.UTC(2026, 7, 9, 4)
    expect(validateCustomExpiration(new Date(now + 60_000).toISOString(), 7, now)).toBe(now + 60_000)
    expect(validateCustomExpiration(new Date(now - 1).toISOString(), 7, now)).toBeNull()
    expect(validateCustomExpiration(new Date(now + 8 * 86_400_000).toISOString(), 7, now)).toBeNull()
  })

  it('產生 MMDD 預設密碼', () => {
    expect(defaultPassword(new Date(2026, 7, 9))).toBe('0809')
  })
})

describe('後端錯誤映射', () => {
  it('映射短網址與媒體 errorCode', () => {
    expect(shortUrlErrorMessage({ errorCode: 'INVALID_URL_OR_CODE' })).toContain('網址')
    expect(shortUrlErrorMessage({ errorCode: 'INVALID_CUSTOM_CODE' })).toContain('3～32')
    expect(shortUrlErrorMessage({ errorCode: 'RESERVED_CUSTOM_CODE' })).toContain('系統保留')
    expect(shortUrlErrorMessage({ errorCode: 'CUSTOM_CODE_ALREADY_EXISTS' })).toContain('已被使用')
    expect(mediaErrorMessage({ errorCode: 'VIDEO_TOO_LONG' }, DEFAULT_MEDIA_CONFIG)).toContain('5 分鐘')
    expect(mediaErrorMessage({ errorCode: 'MEDIA_GATEWAY_FAILED', status: 503 })).toContain('503')
  })

  it('保留後端可讀錯誤作為 fallback', () => {
    expect(mediaErrorMessage({ error: 'custom error' })).toBe('custom error')
  })
})
