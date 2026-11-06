import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

describe('fixed auto-leave policy', () => {
  it('has no editable field in either dashboard or the legacy form schema', () => {
    for (const file of [
      '../src/dashboard/components/tabs/MusicSettings.vue',
      '../src/dashboard/types/dashboard.ts',
      '../src/dashboard/composables/useDashboard.ts',
      '../src/domain/music/music-schema.js',
      '../src/templates/tabs/music.html',
    ]) {
      const source = readFileSync(new URL(file, import.meta.url), 'utf8')
      expect(source).not.toMatch(/autoLeaveEnabled|autoLeaveMinutes/)
    }
  })

  it('has no auto-leave toggle option or mutation in Discord settings menus', () => {
    for (const name of ['SettingsMusicMenuHandler', 'SettingsModuleMenuHandler']) {
      const source = readFileSync(new URL(`../../src/main/java/com/norule/musicbot/discord/bot/gateway/command/settings/menu/${name}.java`, import.meta.url), 'utf8')
      expect(source).not.toMatch(/isAutoLeaveEnabled|withAutoLeaveEnabled|A_AUTO_LEAVE_TOGGLE/)
      expect(source).not.toMatch(/SelectOption\.of\([^\n]*(auto-leave-toggle|music-auto-leave)/)
    }
  })
})
