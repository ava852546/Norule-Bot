interface ApiErrorPayload {
  error?: string
  message?: string
  errorCode?: string
  csrfToken?: string
}

let csrfToken = ''

export async function dashboardApi<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers || {})
  headers.set('Accept', 'application/json')
  const method = String(options.method || 'GET').toUpperCase()
  if (csrfToken && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
    headers.set('X-CSRF-Token', csrfToken)
  }

  const response = await fetch(url, {
    credentials: 'same-origin',
    ...options,
    headers,
  })

  const contentType = response.headers.get('content-type') || ''
  const payload = contentType.includes('application/json')
    ? await response.json() as T & ApiErrorPayload
    : null

  if (!response.ok) {
    if (response.status === 401) csrfToken = ''
    const errorPayload = payload as ApiErrorPayload | null
    throw new Error(errorPayload?.error || errorPayload?.message || `${response.status} ${response.statusText}`)
  }

  if (url.split('?', 1)[0] === '/api/me') {
    const mePayload = payload as ApiErrorPayload | null
    csrfToken = typeof mePayload?.csrfToken === 'string' ? mePayload.csrfToken : ''
  }

  return payload as T
}
