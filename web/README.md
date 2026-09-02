# NoRule Web Workspace

`web/` 是 NoRule 的共用前端 workspace。NoRule URL 首頁與 Dashboard 都使用 Nuxt 4、Vue 3 與 TypeScript，並各自產生可由 Java classpath 提供的靜態頁面。

目前前端主要版本：

- Nuxt：`4.5.2`
- Vue：`3.5.41`
- Vue Router：`5.2.0`
- TypeScript：`6.0.3`
- Vite：`8.2.x`
- Vitest：`4.1.10`

正式環境不需要 Node.js 或 Nuxt server。瀏覽器資產會在 Maven package 階段寫入 Java classpath，啟動方式仍是：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.7.jar
```

## 目錄

```text
web/
├─ app/                         # Nuxt 4 / NoRule URL
│  ├─ assets/css/               # --nr-* design tokens 與 base styles
│  ├─ components/
│  │  ├─ ui/                    # Dashboard 可重用的基礎元件
│  │  ├─ layout/
│  │  ├─ short-url/
│  │  └─ media/
│  ├─ composables/              # API、upload 與 clipboard 狀態
│  ├─ pages/index.vue
│  ├─ types/
│  └─ utils/
├─ dashboard/                   # Dashboard Nuxt root
│  └─ nuxt.config.ts
├─ scripts/                     # 兩個 Nuxt 靜態輸出的同步腳本
│  ├─ sync-dashboard-output.mjs
│  └─ sync-static-output.mjs
├─ src/
│  ├─ dashboard/               # Nuxt Dashboard：Vue 元件、composables、型別與樣式
│  └─ templates/               # Java 特殊頁模板
├─ nuxt.config.ts
└─ package.json
```

Java 仍負責 `/api/**`、OAuth、Session、Redirect、Rate Limit、資料庫、媒體內容與靜態檔案服務。Nuxt 不提供 server API。

### 原始碼與生成產物

開發時請依下列 ownership 修改：

```text
NoRule URL 原始碼
├─ web/app/**
└─ web/nuxt.config.ts

Dashboard 原始碼
├─ web/src/dashboard/**
└─ web/dashboard/nuxt.config.ts

Java 特殊頁模板
└─ web/src/templates/**
```

下列目錄／檔案屬於 Nuxt 或同步腳本產生的輸出，不應直接修改：

```text
web/.nuxt/
web/.output/
web/.output-dashboard/
web/dashboard/.nuxt/
src/main/resources/web/dashboard/_nuxt/
src/main/resources/web/short-url/_nuxt/
target/classes/web/
```

若需要改 UI，請修改 Nuxt / template 原始碼後重新執行 build，而不是直接編輯 hashed assets 或 Maven build output。

## 開發

需求：Node.js 22.19+ 或 24.11+。

先安裝依賴：

```bash
cd web
npm ci
```

### NoRule URL

```bash
npm run dev
```

NoRule URL dev server 預設只監聽 `127.0.0.1`，並把 `/api` 代理至：

```text
http://127.0.0.1:60001
```

需要指定其他短網址後端時，可設定：

```powershell
$env:NUXT_DEV_API_TARGET='http://127.0.0.1:60001'
npm run dev
```

### Dashboard

```bash
npm run dev:dashboard
```

Dashboard dev server 預設使用 `127.0.0.1:5173`，並將 `/api` 與 `/auth` 代理至：

```text
http://127.0.0.1:60000
```

需要指定其他 Dashboard Java 後端時，可設定：

```powershell
$env:NUXT_DASHBOARD_API_TARGET='http://127.0.0.1:60000'
npm run dev:dashboard
```

## 檢查與建置

提交前建議執行：

```bash
npm run typecheck
npm test
npm run build
```

目前 `package.json` 的完整 build 流程為：

```text
npm run build
├─ npm run generate:dashboard
├─ npm run sync:dashboard
├─ npm run generate:short-url
└─ npm run sync:short-url
```

依序會：

1. 以 `nuxt generate dashboard` 產生 Dashboard 靜態頁面。
2. 將 Dashboard hashed assets 同步至 `/web/dashboard/_nuxt/`。
3. 將 Dashboard HTML 同步成 `dashboard.html`。
4. 將 Java 特殊頁需要的 legacy CSS 合併成 `app.css`，並同步特殊頁模板。
5. 以 `nuxt generate` 產生 NoRule URL 靜態頁面。
6. 將 NoRule URL HTML 同步成 `short-url.html`。
7. 將 NoRule URL hashed assets 同步至 `/web/short-url/_nuxt/`。

同步腳本也會清理已淘汰的輸出，例如舊 `app.js`、`chunks/` 與 `short-url-stats.html`，避免舊資產殘留。

未設定 `NORULE_WEB_OUTPUT_DIR` 時，產物同步至：

```text
src/main/resources/web
```

Maven package 時則會設定：

```text
NORULE_WEB_OUTPUT_DIR=target/classes/web
```

因此 production JAR 只包含最終 HTML、CSS、JavaScript 與 hashed assets，不包含 `node_modules`、Vue SFC、TypeScript 原始碼或 Nuxt server runtime。

## Maven production build

在專案根目錄執行：

```bash
mvn clean package
```

Maven 的 `prepare-package` 會自動：

1. 執行 `npm ci`。
2. 執行 `npm run build`。
3. 將前端 production 產物同步到 `target/classes/web`。
4. 將 Java runtime dependencies 寫入 `runtime-libs/` 並產生 dependency manifest。

因此 production 只需要執行 Java JAR：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.7.jar
```

不需要 `npm run start`、Nuxt SSR server 或獨立前端 container。

## Java 後端設定對應

### Dashboard

Dashboard 對應根目錄 `config.yml`：

```yml
web:
  # 是否啟用 Java Dashboard Web Server。
  enabled: true
  bind:
    # Java Dashboard HTTP 監聽連接埠。
    port: 60000
  public:
    # 對外公開的 Dashboard origin；OAuth / redirect 會依此部署。
    baseUrl: "https://dash.norule.me"

  # Web Session 有效時間（分鐘）。
  sessionExpireMinutes: 720

  # Discord OAuth2 應用資訊。
  discordClientId: ""
  discordClientSecret: ""
  discordRedirectUri: "https://dash.norule.me/auth/callback"
```

本機 Nuxt Dashboard 開發時，`NUXT_DASHBOARD_API_TARGET` 應指向上面的 Java `web.bind.port`。

### NoRule URL

NoRule URL 對應：

```yml
shortUrl:
  # 是否啟用短網址 Java Web Server。
  enabled: true

  # Java 短網址 HTTP 監聽連接埠。
  bindPort: 60001

  # 對外公開的短網址 origin。
  publicBaseUrl: "https://s.norule.me"
```

本機 Nuxt NoRule URL 開發時，`NUXT_DEV_API_TARGET` 應指向上面的 `shortUrl.bindPort`。

若 Java 位於 Nginx、Cloudflare Tunnel 或其他反向代理後方，真實 Client IP 是否可信由後端 `shortUrl.abuseProtection.rateLimit.trustedProxyCidrs` 控制；前端不負責解析或信任 `X-Forwarded-For`。

## Java 特殊頁

圖片／影片 viewer、媒體密碼驗證、分享過期頁與部分 Java 404 / fallback 頁面仍由 Java 模板處理。

`sync-dashboard-output.mjs` 會把 Dashboard / legacy 樣式來源（包含 `media-share-pages.css`）整理並合併成：

```text
src/main/resources/web/app.css
```

或 Maven build 時的：

```text
target/classes/web/app.css
```

因此不要直接編輯生成後的 `app.css`；應修改對應的 CSS 原始碼，再重新執行同步／build。
