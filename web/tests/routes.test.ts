import { describe, expect, it } from 'vitest'
import { hasStatsQuery, shortUrlLoginPath } from '../app/utils/routes'

describe('短網址 route helpers', () => {
  it('以 query key 是否存在判斷空值 stats mode', () => {
    expect(hasStatsQuery({})).toBe(false)
    expect(hasStatsQuery({ stats: '' })).toBe(true)
    expect(hasStatsQuery({ stats: null })).toBe(true)
  })

  it('登入連結保留原始 stats 或我的內容路徑', () => {
    expect(shortUrlLoginPath('/ABC123?stats')).toContain(encodeURIComponent('/ABC123?stats'))
    expect(shortUrlLoginPath('/my-content')).toContain(encodeURIComponent('/my-content'))
  })
})
