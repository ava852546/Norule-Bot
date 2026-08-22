export type RequestStatus = 'idle' | 'loading' | 'success' | 'error' | 'disabled'

export interface ApiErrorResponse {
  error?: string
  errorCode?: string
  status?: number
}

export interface ShortUrlResponse {
  code: string
  shortUrl: string
  targetUrl: string
  viewCount: number
}

export interface MediaShareConfig {
  enabled: boolean
  accessTier: 'ANONYMOUS' | 'AUTHENTICATED' | 'API_TOKEN'
  defaultRetentionHours: number
  maxRetentionDays: number
  maxFileSizeBytes: number
  maxFileSizeMb: number
  maxVideoFileSizeBytes: number
  maxVideoFileSizeMb: number
  maxVideoDurationSeconds: number
  expiredShareRetentionDays: number
  allowDateDefaultPassword: boolean
  minPasswordLength: number
  maxPasswordLength: number
}

export interface MediaShareResponse {
  code: string
  shortUrl: string
  expiresAt: number
  passwordProtected: boolean
  viewCount: number
}

export interface MediaUploadInput {
  file: File
  retentionMinutes?: number
  expiresAt?: number
  passwordProtected: boolean
  password?: string
}

export interface PublicMediaContent {
  code: string
  type: 'MEDIA_SHARE'
  mediaType: 'IMAGE' | 'VIDEO'
  contentUrl: string
  passwordRequired: boolean
}

export interface OwnerContentStats {
  code: string
  shareType: 'SHORT_URL' | 'MEDIA_SHARE'
  viewCount: number
  createdAt: number
  lastAccessedAt: number
  expiresAt: number
  active: boolean
  targetUrl?: string
  mediaType?: 'IMAGE' | 'VIDEO'
  contentType?: string
  fileSize?: number
  passwordProtected?: boolean
  contentUrl?: string
}

export interface OwnedContentItem extends OwnerContentStats {
  publicUrl: string
  statsUrl: string
}

export interface OwnedContentPage {
  items: OwnedContentItem[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}
