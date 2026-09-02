import { readonly, ref } from 'vue'
import type { ApiErrorResponse, RequestStatus, ShortUrlResponse } from '~/types/api'
import { readJson } from '~/utils/api'
import { shortUrlErrorMessage } from '~/utils/media'
import { normalizeCustomShortCode } from '~/utils/shortCode'

export function useShortUrl() {
  const status = ref<RequestStatus>('idle')
  const result = ref<ShortUrlResponse | null>(null)
  const error = ref('')

  async function create(targetUrl: string, customCode: string) {
    status.value = 'loading'
    result.value = null
    error.value = ''
    try {
      const body: { url: string; customCode?: string } = { url: targetUrl.trim() }
      const normalizedCustomCode = normalizeCustomShortCode(customCode)
      if (normalizedCustomCode) body.customCode = normalizedCustomCode
      const response = await fetch('/api/short', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const payload = await readJson<ShortUrlResponse & ApiErrorResponse>(response)
      if (!response.ok) throw new Error(shortUrlErrorMessage({ ...payload, status: response.status }))
      if (!payload?.shortUrl) throw new Error('伺服器回應缺少短網址欄位。')
      result.value = payload
      status.value = 'success'
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : '請求失敗，請稍後再試。'
      status.value = 'error'
    }
  }

  return { status: readonly(status), result: readonly(result), error: readonly(error), create }
}
