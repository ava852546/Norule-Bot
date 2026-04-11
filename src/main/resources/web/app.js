import { createAppRuntime } from '/web/modules/bootstrap.js';
import { bindAppEvents } from '/web/modules/bindings.js';

const app = createAppRuntime();
bindAppEvents(app);
app.init();
