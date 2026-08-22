export function hasStatsQuery(query: Record<string, unknown>): boolean {
  return Object.prototype.hasOwnProperty.call(query, 'stats')
}

export function shortUrlLoginPath(returnTo = ''): string {
  if (!returnTo) return '/api/short/session/login'
  return `/api/short/session/login?returnTo=${encodeURIComponent(returnTo)}`
}
