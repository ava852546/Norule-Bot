# NoRule Bot

NoRule Bot 是以 Java 21 LTS + JDA 製作的 Discord 多功能社群機器人，整合音樂播放、歌單管理、伺服器設定、管理工具、客服單、私人包廂、日誌、Web UI、短網址服務與 Minecraft 伺服器狀態查詢。

本專案採用單一 Java 後端為核心。NoRule URL 首頁與 Dashboard 都使用 Nuxt 4、Vue 3 與 TypeScript；正式部署仍由 Java Web Server 提供 API、OAuth、Session 與靜態資源，不需要 Node runtime。

## 目前版本

- 專案版本：`1.7`
- Java：`21 LTS`
- Discord 函式庫：`JDA 6.5.0`
- 音樂核心：`Lavaplayer 2.2.7`、`youtube-source 1.18.2`、`lavasrc 4.8.3`、`bilibili-common 1.0.4`
- Web 前端：`Nuxt 4 + Vue 3 + TypeScript`（NoRule URL 與 Dashboard）
- 資料儲存：檔案、SQLite、MySQL / HikariCP，依模組設定使用
- 授權：GPL-3.0

## 功能介紹

### 音樂播放

- 支援 YouTube 關鍵字 / URL、Bilibili 影片與 `b23.tv` 短網址、Spotify 連結轉搜尋、SoundCloud 與一般 URL。
- 支援加入語音、播放、跳過、停止、離開、音量、循環模式與播放佇列。
- 支援播放歷史、熱門歌曲、熱門點歌者、今日播放時間與統計資料。
- 支援互動式音樂控制面板。
- 支援佇列結束後自動推薦歌曲，並避開最近播放過的歌曲。

### 歌單管理

- 可儲存目前歌曲與佇列。
- 可載入、刪除、列出、查看歌單。
- 可移除歌單中的指定歌曲。
- 可產生 6 位數匯出代碼，支援跨伺服器匯入歌單。

### 伺服器設定

- 可透過 Discord 指令與 Web UI 管理伺服器設定。
- 支援語言切換、模組開關、日誌設定、音樂設定、數字接龍與訊息模板。
- 支援全域設定重載與伺服器設定重設。

### 管理與安全

- 刪除指定頻道或指定使用者的訊息。
- 使用者警告新增、減少、查看與清除。
- 防重複訊息偵測。
- 數字接龍遊戲。
- 密罐頻道：建立「請勿發送訊息」頻道，使用者誤發後可自動刪除訊息、清理 24 小時內發言並踢出伺服器。

### 客服單

- 支援客服單開關、狀態查看與開單面板。
- 支援開單前表單、分類選項、關閉、重開、刪除。
- 支援 HTML Transcript 紀錄。
- 支援黑名單與每人同時開單上限。

### 私人包廂

- 使用者進入指定語音頻道後，自動建立私人語音房。
- 支援改名、限制人數、轉移擁有者與自動刪除。

### Web UI

- 支援 Discord OAuth2 登入。
- 可管理伺服器設定、語言、歡迎訊息、音樂、日誌、客服單等模組。
- 支援 HTTP 或 HTTPS。
- Java 後端負責 `/api/**`、Session、OAuth Callback 與靜態資源。
- `web/` 是共用前端 workspace；NoRule URL 與 Dashboard 都使用獨立的 Nuxt SPA/static build 設定。

### 短網址服務

- 可使用獨立短網址網域，例如 `https://s.norule.me`。
- 首頁 `/` 提供長網址輸入與短網址建立頁面。
- `POST /api/short` 可建立短網址。
- `/{code}` 會轉址到原始網址。
- 支援自訂代碼、隨機代碼、重複網址去重、過期時間與過期清理。
- 會阻擋無效網址、保留路徑、短網址自我指向與私有 / 本機目標，除非設定允許。
- 短網址不存在或過期時會顯示統一風格的 404 頁面。

### Minecraft 伺服器狀態

- Web 後端整合 Minecraft 伺服器狀態查詢流程。
- 可設定查詢 User-Agent、請求逾時與內部快取時間。
- 適合用於 Web UI 或 API 顯示 Minecraft Server 狀態。

## 指令

### 一般

- `/help`、`/說明`：顯示互動式說明。
- `/ping`、`/延遲`：查看 Bot 延遲。
- `/welcome`、`/歡迎訊息`：設定成員加入歡迎訊息與頻道，需要管理伺服器權限。

### 音樂

- `/join`、`/加入`：加入你的語音頻道。
- `/play query:<關鍵字或URL>`、`/播放 query:<關鍵字或URL>`：播放歌曲。
- `/skip`、`/跳過`：跳過目前歌曲。
- `/stop`、`/停止`：停止播放並清空佇列。
- `/leave`、`/離開`：離開語音頻道。
- `/repeat mode:<OFF|SINGLE|ALL>`、`/循環 mode:<OFF|SINGLE|ALL>`：設定循環模式。
- `/volume value:<1-100>`、`/音量 音量:<1-100>`：設定播放音量。
- `/history`、`/播放歷史`：查看最近播放紀錄。
- `/music stats`、`/音樂 統計`：查看熱門歌曲、熱門點歌者、今日播放時間與歷史筆數。
- `/music-panel`、`/音樂面板`：建立互動式音樂控制面板。

### 歌單

- `/playlist save name:<名稱>`、`/歌單 儲存 name:<名稱>`：儲存目前歌曲與佇列。
- `/playlist load name:<名稱>`、`/歌單 載入 name:<名稱>`：載入歌單。
- `/playlist delete name:<名稱>`、`/歌單 刪除 name:<名稱>`：刪除歌單。
- `/playlist list scope:<mine|all>`、`/歌單 列表 scope:<mine|all>`：列出歌單。
- `/playlist view name:<名稱>`、`/歌單 查看 name:<名稱>`：查看歌單內容。
- `/playlist remove-track name:<名稱> index:<編號>`、`/歌單 刪除歌曲 name:<名稱> index:<編號>`：移除歌單內指定歌曲。
- `/playlist export name:<名稱>`、`/歌單 匯出 name:<名稱>`：產生 6 位數匯出代碼。
- `/playlist import code:<代碼> name:<名稱>`、`/歌單 匯入 code:<代碼> name:<名稱>`：匯入歌單。

### 設定

- `/settings action:info`、`/設定 選項:詳細資訊`：查看伺服器設定。
- `/settings action:reload`、`/設定 選項:重載設定`：重新載入設定。
- `/settings action:reset`、`/設定 選項:恢復預設`：重設指定設定區塊。
- `/settings action:template`、`/設定 選項:模板編輯`：編輯訊息模板。
- `/settings action:module`、`/設定 選項:模組開關`：管理模組啟用狀態。
- `/settings action:logs`、`/設定 選項:日誌頻道`：設定日誌頻道。
- `/settings action:log-settings`、`/設定 選項:日誌忽略`：設定日誌忽略成員、頻道或前綴。
- `/settings action:music`、`/設定 選項:音樂設定`：設定音樂模組。
- `/settings action:number-chain`、`/設定 選項:接龍遊戲`：開啟數字接龍設定面板。
- `/settings action:language`、`/設定 選項:語言設置`：切換伺服器語言。

### 管理

- `/delete-messages type:<channel|user> channel:<頻道> amount:<1-99>`、`/刪除訊息 type:<頻道|使用者> channel:<頻道> amount:<1-99>`：刪除指定頻道訊息。
- `/delete-messages type:user user:<使用者> amount:<1-99>`、`/刪除訊息 type:使用者 user:<使用者> amount:<1-99>`：刪除指定使用者訊息；未指定頻道時會掃描全部文字頻道。
- `/warnings action:add user:<使用者> amount:<數量>`、`/警告 action:增加 user:<使用者> amount:<數量>`：增加警告。
- `/warnings action:remove user:<使用者> amount:<數量>`、`/警告 action:減少 user:<使用者> amount:<數量>`：減少警告。
- `/warnings action:view user:<使用者>`、`/警告 action:查看 user:<使用者>`：查看警告。
- `/warnings action:clear user:<使用者>`、`/警告 action:清除 user:<使用者>`：清除警告。
- `/anti-duplicate action:enable value:<true|false>`、`/防洗頻 action:啟用 value:<true|false>`：開關重複訊息偵測。
- `/anti-duplicate action:status`、`/防洗頻 action:狀態`：查看重複訊息偵測狀態。
- `/honeypot-channel`、`/密罐頻道`：建立密罐文字頻道，需要管理伺服器權限。

### 數字接龍

- `/number-chain action:enable channel:<頻道>`、`/數字接龍 action:啟用 channel:<頻道>`：開關數字接龍，可同時設定頻道。
- `/number-chain action:status`、`/數字接龍 action:狀態`：查看接龍狀態。
- `/number-chain reset:true`、`/數字接龍 reset:true`：重置接龍進度。

### 客服單

- `/ticket action:enable`、`/客服單 action:啟用`：開關客服單。
- `/ticket action:status`、`/客服單 action:狀態`：查看客服單狀態。
- `/ticket action:panel`、`/客服單 action:面板`：發送客服單面板。
- `/ticket action:close`、`/客服單 action:關閉`：關閉目前客服單。
- `/ticket action:limit`、`/客服單 action:上限`：設定每位使用者可同時開啟的客服單數量。
- `/ticket action:blacklist-add`、`/客服單 action:黑名單新增`：加入客服單黑名單。
- `/ticket action:blacklist-remove`、`/客服單 action:黑名單移除`：移出客服單黑名單。
- `/ticket action:blacklist-list`、`/客服單 action:黑名單列表`：查看客服單黑名單。

### 私人包廂

- `/private-room-settings`、`/包廂設定`：管理目前所在私人包廂。

### Prefix 指令

預設 prefix 是 `!`，可在 `config.yml` 修改。

- `!help`
- `!join`
- `!play <關鍵字或URL>`
- `$p <Bilibili URL>`：快速播放 Bilibili 網址，不受 `prefix` 設定影響；也可使用 `<prefix>p <URL>`。
- `!volume <1-100>`
- `!history`
- `!music`
- `!playlist <save|load|delete|list|view|export> [name]`
- `!playlist import <code> [name]`
- `!skip`
- `!stop`
- `!leave`
- `!repeat <off|single|all>`

### 控制台指令

- `reload`：重新載入全域設定、伺服器設定、音樂資料、管理資料、客服單與密罐資料。
- `stop`、`end`：安全關閉 Bot。

## 專案結構

```text
src/main/java/com/norule/musicbot
├─ bootstrap/             # 啟動入口
├─ config/                # 全域設定、伺服器設定、DomainConfig
├─ discord/               # Discord 指令與事件處理
├─ domain/                # 純邏輯與領域模型
├─ i18n/                  # 語言與翻譯服務
├─ service/               # 業務服務
├─ shorturl/              # 短網址資料儲存介面與實作
├─ web/                   # Web Controller / Service / Ops / Session / Infra
├─ HoneypotService.java
├─ ModerationService.java
├─ ShortUrlService.java
└─ TicketService.java

web/
├─ app/                   # Nuxt 4 / Vue / TypeScript 與共用設計系統
├─ dashboard/             # Dashboard Nuxt root 與設定
├─ scripts/               # 兩個 Nuxt static output 安全同步
├─ src/dashboard/         # Dashboard Vue 元件、composables、型別與樣式
├─ src/templates/         # Java 特殊頁模板
├─ nuxt.config.ts
└─ package.json
```

## 架構設計

NoRule Bot 採用分層式 Discord gateway 架構，各層職責明確分離：

- **`MusicCommandService`** 作為 Discord runtime / composition root，整合事件處理所需服務與 handler；實際事件由 gateway listener、flow 與 ops / command handler 分層處理。
- **Discord 指令 handler**（slash、button、select、modal）統一放在 `discord.bot.gateway.command.*`，依功能分為 music、settings、moderation、privateroom 等子套件。
- **Settings menu handler** 放在 `discord.bot.gateway.command.settings.menu`，各 menu 互相獨立。
- **音樂控制面板**（panel runtime、renderer、state store、refresh service）放在 `discord.bot.gateway.panel`。
- **指令名稱、Component ID、Route mapping** 集中管理：
  - 指令與選項名稱 → `CommandNames` / `CommandOptions`
  - Component ID → `ComponentIds`
  - Slash 指令結構 → `DiscordCommandCatalog`
  - ZH↔EN 名稱對應與 route 解析 → `DiscordCommandRouteMapper`
- **Service / Domain 層不依賴 JDA event**，保持純業務邏輯與可測試性。


---

## 部署教學

### 需求

- Java 21 LTS 或更新版本。
- Maven 3.9 或更新版本。
- Discord Bot Token。
- 建置 Web UI 需 Node.js 22.19+（或 24.11+）與 npm；正式執行不需要 Node.js。
- Bot 邀請到伺服器時需勾選 `bot` 與 `applications.commands` scope。
- 常用權限：查看頻道、發送訊息、嵌入連結、管理訊息、讀取訊息歷史、連接語音、語音發話、管理頻道、踢出成員、管理伺服器。依功能啟用狀態可再縮減。

### 建置

一般 production 建置：

```bash
mvn clean package
```

普通 `mvn package` 會在 `prepare-package` 自動執行 `npm ci`、兩個 Nuxt 應用的 `nuxt generate` 與 static output 同步。若只需要快速檢查 Java 編譯，可使用 `mvn -q -DskipTests compile`，不會重建前端。

建置完成後會產生：

```text
target/discord-music-bot-1.7.jar
runtime-libs/
```

目前專案採用「主程式 jar + 專用 runtime 依賴目錄」模式，Maven 會在打包時將 runtime 依賴複製到 `runtime-libs/`。啟動器會先依內建清單與 SHA-256 校驗碼同步這個目錄，完成後才建立包含 runtime 依賴的子 JVM classpath；其他用途的檔案請勿放入此目錄。

可用 JVM property 調整同步行為：

- `-Dnorule.bootstrap.cleanup-obsolete=true|false`：是否刪除不在目前依賴清單內的 JAR，預設 `true`。
- `-Dnorule.bootstrap.verify-checksums=true|false`：是否驗證 SHA-256，預設 `true`。
- `-Dnorule.bootstrap.force-redownload=true|false`：是否強制重新下載所有 runtime JAR，預設 `false`。

衝突的 SLF4J Provider 與非目前版本的 Logback JAR 屬於啟動安全限制，即使關閉一般 obsolete 清理仍會移除；`runtime-libs/` 內的非 JAR 檔案不會被清理。

### 首次啟動

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.7.jar
```

首次啟動會自動建立 `config.yml`、語言檔與必要資料夾。停止程式後，編輯 `config.yml`：

```yml
token: "YOUR_DISCORD_BOT_TOKEN"
defaultLanguage: "zh-TW"
```

再重新啟動：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.7.jar
```

## 常用設定

```yml
prefix: "!"
debug: false
commandGuildId: ""

data:
  guildSettingsDir: "guild/configs"
  languageDir: "lang"
  musicDir: "guild/music"
  moderationDir: "guild/moderation"
  ticketDir: "guild/tickets"
  ticketTranscriptDir: "ticket-transcripts"
  honeypotDir: "guild/honeypot"
  logDir: "logs"

defaultLanguage: "zh-TW"
commandCooldownSeconds: 3
numberChainReactionDelayMillis: 500

music:
  bilibili:
    enabled: true
    cookie: ""
    metadataCache:
      enabled: true
      ttlHours: 12
      maxEntries: 1000
    rateLimit:
      enabled: true
      requestsPerSecond: 1
      burst: 3
    circuitBreaker:
      enabled: true
      failureThreshold: 3
      windowSeconds: 60
      cooldownSeconds: 300
  youtube:
    # 啟動時選擇播放後端：YOUTUBE_SOURCE 或 COMPANION。
    playbackBackend: YOUTUBE_SOURCE
    companion:
      # 是否允許使用 Companion API；也可用 YOUTUBE_COMPANION_ENABLED 覆寫。
      enabled: false
      # Companion origin；未帶 path 時使用官方預設 /companion。
      url: "http://127.0.0.1:8282"
      # 對應 Companion SERVER_SECRET_KEY，必須是 16 位英數字元。
      secret: ""
      # Companion timeout、離線或 5xx 時，只 fallback youtube-source 一次。
      fallbackToSource: true
      # Companion TCP 連線逾時（毫秒）。
      connectTimeoutMillis: 5000
      # Companion player API 與 playback proxy 讀取逾時（毫秒）。
      requestTimeoutMillis: 10000
    strictPrecheck:
      # 可選的嚴格播放預檢；需搭配安裝 youtube-plugin 的 Lavalink。
      enabled: false
      # 舊版相容的整體 cache TTL。
      cacheTtlHours: 24
      cache:
        # 可播放結果快取時間。
        playableTtlHours: 24
        # 暫時性失敗（例如上游短暫異常）快取時間。
        temporaryFailureTtlMinutes: 10
        # 永久性失敗／不可播放結果快取時間。
        permanentFailureTtlHours: 24
      # 呼叫 Lavalink 預檢 API 的逾時時間。
      timeoutMillis: 5000
      lavalinkBaseUrl: ""
      lavalinkPassword: ""
  oauth:
    enabled: false
    refreshToken: ""
  cipher:
    enabled: false
    server: "http://localhost:8001"
    password: ""
    userAgent: "norule-music-bot"
```

`commandGuildId` 留空會註冊全域 Slash 指令；開發測試時可填單一伺服器 ID，加快指令更新速度。

### Bilibili 風控保護

Bilibili metadata 使用 12 小時、最多 1000 筆的 bounded cache；實際播放 CDN URL 不會放入長期 cache。同一 BVID 的同時請求會共用一次 metadata resolution。全域 token bucket 預設每秒補充 1 個 token、burst 3；HTTP `412` 會分類為 Bilibili risk control，`429` 會分類為 rate limited。60 秒內累積 3 次 412 或 429 時，circuit breaker 會開啟 5 分鐘，之後以單次 half-open probe 判斷是否恢復。

以下環境變數會覆寫 YAML：

```text
BILIBILI_ENABLED=true
BILIBILI_COOKIE=
BILIBILI_METADATA_CACHE_ENABLED=true
BILIBILI_METADATA_CACHE_TTL_HOURS=12
BILIBILI_METADATA_CACHE_MAX_ENTRIES=1000
BILIBILI_RATE_LIMIT_ENABLED=true
BILIBILI_RATE_LIMIT_RPS=1
BILIBILI_RATE_LIMIT_BURST=3
BILIBILI_CIRCUIT_BREAKER_ENABLED=true
BILIBILI_CIRCUIT_BREAKER_FAILURE_THRESHOLD=3
BILIBILI_CIRCUIT_BREAKER_WINDOW_SECONDS=60
BILIBILI_CIRCUIT_BREAKER_COOLDOWN_SECONDS=300
```

`BILIBILI_COOKIE` 是可選設定，只供需要正常 session 的請求使用；請勿將真實 Cookie 寫入 `config.yml`、測試或提交到 repository。Cookie 無法保證解除 Bilibili 風控，Bot 也不會嘗試繞過安全驗證。

### YouTube 播放後端

預設 `playbackBackend: YOUTUBE_SOURCE`，完整保留 youtube-source、既有 clients 與 Remote Cipher。也可在啟動環境設定：

```text
YOUTUBE_PLAYBACK_BACKEND=YOUTUBE_SOURCE
```

若要讓 Invidious Companion 只負責實際 YouTube 音訊播放，請先部署 Companion，讓 `SERVER_SECRET_KEY` 使用 16 位英數字元，再設定：

```text
YOUTUBE_PLAYBACK_BACKEND=COMPANION
YOUTUBE_COMPANION_ENABLED=true
YOUTUBE_COMPANION_URL=http://127.0.0.1:8282
YOUTUBE_COMPANION_SECRET=ChangeMe12345678
YOUTUBE_COMPANION_FALLBACK_TO_SOURCE=true
```

`YOUTUBE_COMPANION_SECRET` 範例僅示意；實際值必須符合 Companion 的 16 位英數限制。Bot 仍以 youtube-source 處理 URL、搜尋、playlist 與 metadata；播放時才呼叫 `POST /companion/youtubei/v1/player` 並透過 Companion `/videoplayback` proxy 讀取音訊。Bot 不會因此開放任意 HTTP 音訊來源。

### YouTube 嚴格播放預檢

`youtube-source` 已更新到 `1.18.2`。如果另外部署 Lavalink 並安裝 `dev.lavalink.youtube:youtube-plugin:1.18.2`，可啟用：

```yml
music:
  youtube:
    strictPrecheck:
      # 啟用前需確認 Lavalink 已安裝 youtube-plugin。
      enabled: true
      # 舊版相容的整體 cache TTL。
      cacheTtlHours: 24
      cache:
        playableTtlHours: 24
        temporaryFailureTtlMinutes: 10
        permanentFailureTtlHours: 24
      # 單次預檢逾時（毫秒）。
      timeoutMillis: 5000
      lavalinkBaseUrl: "http://localhost:2333"
      lavalinkPassword: "youshallnotpass"
```

啟用後，單一 YouTube 影片加入佇列前會呼叫 `GET /youtube/stream/{videoId}`，並快取 OK / BLOCKED 結果 24 小時。此功能只能提高入隊前判斷準確率，不能保證 100% 避免播放階段失敗。若使用 Lavalink `application.yml`，請保持內建 YouTube source 關閉：

```yml
lavalink:
  server:
    sources:
      youtube: false
  plugins:
    - dependency: "dev.lavalink.youtube:youtube-plugin:1.18.2"
      snapshot: false
```

## Web UI 設定

在 Discord Developer Portal 建立 OAuth2 應用，Redirect URI 填入：

```text
https://dash.norule.me/auth/callback
```

設定 `config.yml`：

```yml
web:
  enabled: true
  bind:
    port: 60000
  public:
    baseUrl: "https://dash.norule.me"
  discordClientId: "YOUR_CLIENT_ID"
  discordClientSecret: "YOUR_CLIENT_SECRET"
  discordRedirectUri: "https://dash.norule.me/auth/callback"
```

Web Server 固定監聽 `0.0.0.0`；請使用防火牆限制來源，並由反向代理對外提供 HTTPS。

啟動後開啟：

```text
https://dash.norule.me
```

## 短網址設定

短網址服務可使用獨立網域，例如 `s.norule.me`。建議由 Nginx / Cloudflare 將該網域反向代理到短網址服務或同一個 Java Web Server 對應的連接埠。

```yml
shortUrl:
  enabled: true
  bindPort: 60001
  publicBaseUrl: "https://s.norule.me"

  # 隨機短碼預設長度；自訂短碼不使用此值。
  codeLength: 7

  # false 時阻擋 localhost、私有網段與其他非公開目標。
  allowPrivateTargets: false

  # 相同目標網址可重用既有短網址。
  dedupe: true

  # 短網址預設有效天數與背景清理週期。
  ttlDays: 7
  cleanupIntervalMinutes: 10

  abuseProtection:
    rateLimit:
      enabled: true

      # 匿名媒體上傳：每個來源 IP 每分鐘最多 10 個 HTTP 請求。
      mediaRequestsPerMinutePerIp: 10

      # 已登入媒體上傳仍受共用 IP abuse ceiling 保護。
      mediaAuthenticatedRequestsPerMinutePerIp: 60

      # 已登入使用者媒體上傳：每位使用者每分鐘最多 20 個 HTTP 請求。
      mediaRequestsPerMinutePerUser: 20

      # 每位登入使用者每日最多 200 個媒體上傳 HTTP 請求；
      # 包含去重命中與既有媒體分享重用。
      mediaRequestsPerDayPerUser: 200

      # 建立短網址的 IP / 使用者每分鐘限制。
      shortUrlRequestsPerMinutePerIp: 30
      shortUrlRequestsPerMinutePerUser: 60

      # 同時進行中的媒體上傳數量限制。
      mediaConcurrencyPerIp: 2
      mediaConcurrencyPerUser: 3

      # 只有從這些可信任代理進入的請求，才會採信 X-Forwarded-For。
      # 若 Nginx / Cloudflare Tunnel 與 Java 不在 loopback 上，
      # 請加入實際反向代理的來源 CIDR；不要直接信任所有來源。
      trustedProxyCidrs:
        - "127.0.0.1/32"
        - "::1/128"

    creation:
      enabled: true

      # 舊有的短網址建立防濫用限制，與上方 API rate limit 共同生效。
      anonymous:
        maxRequestsPerMinute: 10
        maxRequestsPer10Minutes: 50
        maxCreatesPerDay: 200

      authenticated:
        maxRequestsPerMinute: 30
        maxRequestsPer10Minutes: 150
        maxCreatesPerDay: 500
```

使用方式：

```text
https://s.norule.me/
https://s.norule.me/abc1234
```

API 範例：

```bash
curl -X POST "https://s.norule.me/api/short" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com","customCode":"example"}'
```

自訂短碼規則：

- 長度為 `3-32` 個字元。
- 僅允許英文字母、數字、`-` 與 `_`。
- 建立時會正規化為小寫；大小寫視為同一代碼。
- 系統保留路徑不可使用。
- 若自訂代碼已存在，API 會回傳 `409 Conflict`。

## Minecraft 狀態查詢設定

```yml
minecraftStatus:
  userAgent: "NoRuleBot/1.0 contact: admin@norule.me"
  requestTimeoutMillis: 15000
  internalCacheSeconds: 60
```

## 共用資料庫設定

統計與短網址資料共用 `database` 設定，可依需求使用 SQLite 或 MySQL。

SQLite 範例：

```yml
database:
  storage: "sqlite"
  sqlite:
    path: "data/norule.db"
```

MySQL 範例：

```yml
database:
  storage: "mysql"
  mysql:
    jdbcUrl: "jdbc:mysql://localhost:3306/data?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    username: "root"
    password: ""
    poolSize: 8
```

## HTTPS 設定

Java Web Server 僅提供 HTTP。請使用 Nginx、Caddy、Cloudflare Tunnel 或其他反向代理終止 HTTPS，並將請求轉送至 `web.bind.port`。

若反向代理需要傳遞真實用戶端 IP，請同步設定 `shortUrl.abuseProtection.rateLimit.trustedProxyCidrs`。只有來自可信任代理的 `X-Forwarded-For` 才會被接受，避免外部直接偽造來源 IP。

## Web UI 前端開發

安裝依賴：

```bash
cd web
npm ci
```

建置一次：

```bash
npm run build
```

NoRule URL Nuxt dev server：

```bash
npm run dev
```

Dashboard Nuxt dev server：

```bash
npm run dev:dashboard
```

建議本地開發流程：

1. 啟動 Java 短網址後端（預設 `http://127.0.0.1:60001`）。
2. 在 `web/` 執行 `npm run dev`。
3. 開啟 Nuxt 顯示的開發網址；`/api` 會代理至 `NUXT_DEV_API_TARGET`，預設為短網址後端。
4. 提交前執行 `npm run typecheck`、`npm test` 與 `npm run build`。

Production build 流程：

```text
NoRule URL Nuxt source ─┐
                        ├─→ nuxt generate → target/classes/web → Maven JAR
Dashboard Nuxt source ──┘
```

JAR 只包含最終 HTML、CSS、JavaScript 與 hashed assets，不包含 Node modules、Nuxt server、Vue SFC 或 TypeScript 原始碼。完整前端架構請參考 [web/README.md](web/README.md)。

## 更新

```bash
git pull
mvn clean package -DskipTests
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.7.jar
```

更新 Web UI 時不需要額外 Maven profile；普通 `mvn clean package` 已包含前端 build。

## 注意事項

- 請勿將 `config.yml`、Token、OAuth Secret、資料庫密碼提交到 Git。
- 若使用短網址服務，建議只開放 HTTPS 對外入口，並由 Nginx 或 Cloudflare 代理。
- 若使用 MySQL，請先建立資料庫並確認 Bot 主機可連線。
- 若 Slash 指令更新較慢，可在測試階段設定 `commandGuildId` 為單一伺服器 ID。
- 若 Discord 顯示亂碼，請確認啟動參數包含 `-Dfile.encoding=UTF-8`。
