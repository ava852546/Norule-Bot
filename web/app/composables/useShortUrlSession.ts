export function useShortUrlSession() {
  const authenticated = useState('short-url-authenticated', () => false)
  const checking = useState('short-url-session-checking', () => true)
  const turnstileEnabled = useState('short-url-turnstile-enabled', () => false)
  const turnstileSiteKey = useState('short-url-turnstile-site-key', () => '')

  async function loadSession() {
    checking.value = true
    try {
      const response = await fetch('/api/short/session', {
        credentials: 'same-origin', headers: { Accept: 'application/json' },
      })
      if (!response.ok) throw new Error('Session unavailable')
      const session = await response.json() as {
        authenticated?: boolean; turnstileEnabled?: boolean; turnstileSiteKey?: string
      }
      authenticated.value = session.authenticated === true
      turnstileEnabled.value = session.turnstileEnabled === true
      turnstileSiteKey.value = session.turnstileSiteKey || ''
    } catch {
      authenticated.value = false
    } finally {
      checking.value = false
    }
  }

  return { authenticated, checking, turnstileEnabled, turnstileSiteKey, loadSession }
}
