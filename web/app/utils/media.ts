import type { ApiErrorResponse, MediaShareConfig } from '~/types/api'

export const RETENTION_PRESETS = [5, 10, 30, 60, 180, 360, 720, 1440] as const
export const DEFAULT_MEDIA_CONFIG: MediaShareConfig = {
  enabled: true,
  accessTier: 'ANONYMOUS',
  defaultRetentionHours: 1,
  maxRetentionDays: 365,
  maxFileSizeBytes: 20 * 1024 * 1024,
  maxFileSizeMb: 20,
  maxVideoFileSizeBytes: 100 * 1024 * 1024,
  maxVideoFileSizeMb: 100,
  maxVideoDurationSeconds: 5 * 60,
  expiredShareRetentionDays: 30,
  allowDateDefaultPassword: true,
  minPasswordLength: 4,
  maxPasswordLength: 128,
}

const imageTypes = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp'])
const videoTypes = new Set(['video/mp4', 'video/webm'])

export function isVideoFile(file: Pick<File, 'name' | 'type'>): boolean {
  return videoTypes.has(file.type.toLowerCase()) || /\.(?:mp4|webm)$/i.test(file.name)
}

export function isSupportedMedia(file: Pick<File, 'name' | 'type'>): boolean {
  const mime = file.type.toLowerCase()
  return imageTypes.has(mime)
    || videoTypes.has(mime)
    || /\.(?:png|jpe?g|gif|webp|mp4|webm)$/i.test(file.name)
}

export function validateMediaFile(
  file: Pick<File, 'name' | 'type' | 'size'>,
  config: MediaShareConfig,
): string | null {
  if (!isSupportedMedia(file)) return '僅支援 PNG、JPEG、GIF、WebP、MP4 與 WebM 檔案。'
  if (isVideoFile(file) && file.size > config.maxVideoFileSizeBytes) return '影片超過伺服器允許的大小。'
  if (!isVideoFile(file) && file.size > config.maxFileSizeBytes) return '圖片超過伺服器允許的大小。'
  return null
}

export function maxVideoDurationMinutes(config: MediaShareConfig): number {
  return Math.max(1, Math.ceil(config.maxVideoDurationSeconds / 60))
}

export function defaultPassword(now = new Date()): string {
  return `${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`
}

export function toLocalDateTimeValue(timestamp: number): string {
  const date = new Date(timestamp)
  return new Date(timestamp - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

export function validateCustomExpiration(value: string, maxRetentionDays: number, now = Date.now()): number | null {
  const timestamp = Date.parse(value)
  const max = now + Math.max(1, maxRetentionDays) * 24 * 60 * 60 * 1000
  return Number.isFinite(timestamp) && timestamp > now && timestamp <= max ? timestamp : null
}

export function mediaErrorMessage(payload: ApiErrorResponse, config = DEFAULT_MEDIA_CONFIG): string {
  const code = payload.errorCode?.trim().toUpperCase() || ''
  const messages: Record<string, string> = {
    IMAGE_REQUIRED: '請選擇圖片或影片檔案。',
    IMAGE_TOO_LARGE: '圖片超過伺服器允許的大小。',
    VIDEO_TOO_LARGE: '影片超過伺服器允許的大小。',
    MEDIA_TOO_LARGE: '上傳檔案超過伺服器允許的大小。',
    UNSUPPORTED_IMAGE: '僅支援 PNG、JPEG、GIF 與 WebP 圖片。',
    UNSUPPORTED_MEDIA: '僅支援 PNG、JPEG、GIF、WebP、MP4 與 WebM 檔案。',
    RETENTION_TOO_LONG: '保存時間超過伺服器允許的範圍。',
    MEDIA_PASSWORD_REQUIRED: '啟用密碼保護時必須設定密碼。',
    MEDIA_UPLOAD_RATE_LIMITED: '上傳速度過快，請稍後再試。',
    MEDIA_DAILY_QUOTA_EXCEEDED: '今日上傳次數已達上限。',
    MEDIA_ACTIVE_STORAGE_QUOTA_EXCEEDED: '目前使用中的媒體已達儲存配額。',
    MEDIA_MANAGED_STORAGE_FULL: '站台媒體儲存空間已滿，暫停接受新上傳。',
    MEDIA_FILESYSTEM_FULL: '伺服器磁碟使用量過高，暫停接受新上傳。',
    IMAGE_SHARING_DISABLED: '媒體分享功能目前未開啟。',
    MEDIA_STORAGE_FAILED: '媒體檔案儲存失敗，請確認伺服器儲存空間與權限。',
    MEDIA_PERSISTENCE_FAILED: '媒體分享紀錄儲存失敗，請確認資料庫連線。',
    INVALID_MEDIA_UPLOAD: '媒體上傳內容無效。',
    IMAGE_CREATE_FAILED: '建立媒體分享連結失敗。',
  }
  if (code === 'VIDEO_TOO_LONG') return `影片長度不可超過 ${maxVideoDurationMinutes(config)} 分鐘。`
  if (code === 'INVALID_PASSWORD') return `密碼需為 ${config.minPasswordLength}～${config.maxPasswordLength} 個字元。`
  if (code === 'MEDIA_GATEWAY_FAILED') return `上傳請求被上游網關拒絕（HTTP ${payload.status || '5xx'}）。`
  return messages[code] || payload.error?.trim() || '媒體上傳失敗，請稍後再試。'
}

export function shortUrlErrorMessage(payload: ApiErrorResponse): string {
  const code = payload.errorCode?.trim().toUpperCase() || ''
  const messages: Record<string, string> = {
    MISSING_URL: '請輸入要縮短的網址。',
    INVALID_URL_OR_CODE: '網址或自訂短碼無效，請確認後再試。',
    INVALID_CUSTOM_CODE: '自訂代碼只能使用英文字母、數字、-、_，長度需為 3～32 個字元。',
    RESERVED_CUSTOM_CODE: '此名稱由系統保留，請使用其他代碼。',
    CUSTOM_CODE_ALREADY_EXISTS: '此自訂代碼已被使用。',
    METHOD_NOT_ALLOWED: '不支援這個請求方式。',
  }
  return messages[code] || payload.error?.trim() || '建立短網址失敗，請稍後再試。'
}

export function readVideoDuration(file: File): Promise<number> {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video')
    const objectUrl = URL.createObjectURL(file)
    let timeoutId = 0
    const finish = (error?: Error, duration?: number) => {
      window.clearTimeout(timeoutId)
      video.onloadedmetadata = null
      video.onerror = null
      video.removeAttribute('src')
      video.load()
      URL.revokeObjectURL(objectUrl)
      if (error) reject(error)
      else resolve(duration || 0)
    }
    timeoutId = window.setTimeout(() => finish(new Error('讀取影片資訊逾時。')), 10_000)
    video.preload = 'metadata'
    video.onloadedmetadata = () => {
      const duration = Number(video.duration)
      finish(Number.isFinite(duration) && duration > 0 ? undefined : new Error('影片長度無效。'), duration)
    }
    video.onerror = () => finish(new Error('無法讀取影片長度。'))
    video.src = objectUrl
  })
}
