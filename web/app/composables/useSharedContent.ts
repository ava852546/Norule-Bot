import { readonly, ref } from 'vue'
import type { ApiErrorResponse, OwnerContentStats, PublicMediaContent, RequestStatus } from '~/types/api'
import { readJson } from '~/utils/api'

export function useSharedContent() {
  const status = ref<RequestStatus>('idle')
  const publicContent = ref<PublicMediaContent | null>(null)
  const ownerStats = ref<OwnerContentStats | null>(null)
  const error = ref('')
  const errorStatus = ref(0)

  async function load(code: string, statsMode: boolean) {
    status.value = 'loading'
    publicContent.value = null
    ownerStats.value = null
    error.value = ''
    errorStatus.value = 0
    try {
      const suffix = statsMode ? '/stats' : ''
      const response = await fetch(`/api/short/${encodeURIComponent(code)}${suffix}`, {
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      })
      const payload = await readJson<(PublicMediaContent | OwnerContentStats) & ApiErrorResponse>(response)
      if (!response.ok) {
        errorStatus.value = response.status
        if (response.status === 403) throw new Error('你沒有權限查看此內容的統計資訊。')
        if (response.status === 404) throw new Error('此分享不存在、已刪除或已失效。')
        if (response.status === 401) throw new Error('請先登入以查看擁有者資訊。')
        throw new Error(payload?.error || '無法載入分享內容。')
      }
      if (statsMode) ownerStats.value = payload as OwnerContentStats
      else publicContent.value = payload as PublicMediaContent
      status.value = 'success'
    } catch (caught) {
      error.value = caught instanceof Error ? caught.message : '無法載入分享內容。'
      status.value = 'error'
    }
  }

  async function unlock(code: string, password: string): Promise<boolean> {
    const response = await fetch(`/api/short/image/access/${encodeURIComponent(code)}`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify({ password }),
    })
    const payload = await readJson<ApiErrorResponse>(response)
    if (!response.ok) {
      throw new Error(payload?.error || '密碼驗證失敗。')
    }
    return true
  }

  return {
    status: readonly(status),
    publicContent: readonly(publicContent),
    ownerStats: readonly(ownerStats),
    error: readonly(error),
    errorStatus: readonly(errorStatus),
    load,
    unlock,
  }
}
