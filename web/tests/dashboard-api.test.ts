import { beforeEach, describe, expect, it, vi } from 'vitest'

describe('dashboardApi CSRF handling', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.unstubAllGlobals()
  })

  it('keeps the token in runtime memory and adds it to mutations', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, {
        id: '1',
        username: 'tester',
        avatarUrl: '',
        csrfToken: 'session-csrf-token',
      }))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    const { dashboardApi } = await import('../src/dashboard/composables/useDashboardApi')

    await dashboardApi('/api/me')
    await dashboardApi('/api/guild/1/settings', { method: 'POST', body: '{}' })

    const mutationOptions = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(new Headers(mutationOptions.headers).get('X-CSRF-Token')).toBe('session-csrf-token')
  })

  it('does not retry a mutation rejected for an invalid token', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { csrfToken: 'stale-token' }))
      .mockResolvedValueOnce(jsonResponse(403, {
        error: 'Invalid CSRF token',
        errorCode: 'INVALID_CSRF_TOKEN',
      }))
    vi.stubGlobal('fetch', fetchMock)
    const { dashboardApi } = await import('../src/dashboard/composables/useDashboardApi')

    await dashboardApi('/api/me')
    await expect(dashboardApi('/api/guild/1/number-chain/reset', { method: 'POST' }))
      .rejects.toThrow('Invalid CSRF token')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})

function jsonResponse(status: number, payload: object) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}
