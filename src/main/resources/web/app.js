import { createAppRuntime } from '/web/core/bootstrap.js';
import { bindAppEvents } from '/web/core/bindings.js';
import { createWelcomeModule } from '/web/domain/welcome/welcome.js';
import { createNotificationsModule } from '/web/domain/notifications/notifications.js';
import { createMusicModule } from '/web/domain/music/music.js';
import { createTicketModule } from '/web/domain/ticket/ticket.js';
import { createGuildsModule } from '/web/domain/guild/guilds.js';
import { createSettingsFormModule } from '/web/domain/settings/settings-form.js';
import { createAppActionsModule } from '/web/domain/general/app-actions.js';
import { createGeneralTab } from '/web/pages/tabs/general-tab.js';
import { createNotificationsTab } from '/web/pages/tabs/notifications-tab.js';
import { createLogsTab } from '/web/pages/tabs/logs-tab.js';
import { createMusicTab } from '/web/pages/tabs/music-tab.js';
import { createPrivateRoomTab } from '/web/pages/tabs/private-room-tab.js';
import { createWelcomeTab } from '/web/pages/tabs/welcome-tab.js';
import { createNumberChainTab } from '/web/pages/tabs/number-chain-tab.js';
import { createTicketTab } from '/web/pages/tabs/ticket-tab.js';
import { createCustomSelectComponent } from '/web/ui/components/custom-select.js';
import { createSectionActionsComponent } from '/web/ui/components/section-actions.js';
import { renderHistoryList } from '/web/ui/components/history-list.js';

const app = createAppRuntime({
  createWelcomeModule,
  createNotificationsModule,
  createMusicModule,
  createTicketModule,
  createGuildsModule,
  createSettingsFormModule,
  createAppActionsModule,
  createGeneralTab,
  createNotificationsTab,
  createLogsTab,
  createMusicTab,
  createPrivateRoomTab,
  createWelcomeTab,
  createNumberChainTab,
  createTicketTab,
  createCustomSelectComponent,
  renderHistoryList
});
bindAppEvents(app, { createSectionActionsComponent });
app.init();
