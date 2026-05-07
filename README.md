# NoRule Bot

NoRule Bot 是以 Java 17 + JDA 製作的 Discord 多功能社群機器人，整合音樂播放、歌單管理、伺服器設定、管理工具、客服單、私人包廂、日誌、Web UI、短網址服務與 Minecraft 伺服器狀態查詢。

本專案採用單一 Java 後端為核心，Web UI 前端可使用 Vite 開發與打包，正式部署時仍可由 Java Web Server 提供 API、OAuth、Session 與靜態資源。

## 目前版本

- 專案版本：`1.6`
- Java：`17+`
- Discord 函式庫：`JDA 6.3.1`
- 音樂核心：`Lavaplayer 2.2.6`、`youtube-source 1.18.0`、`lavasrc 4.8.1`
- Web 前端：`Vite`
- 資料儲存：檔案、SQLite、MySQL / HikariCP，依模組設定使用
- 授權：GPL-3.0

## 功能介紹

### 音樂播放

- 支援 YouTube 關鍵字 / URL、Spotify 連結轉搜尋、SoundCloud 與一般 URL。
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
- `web/` 目錄使用 Vite 作為前端開發與打包 workspace。

### 短網址服務

- 可使用獨立短網址網域，例如 `https://s.norule.me`。
- 首頁 `/` 提供長網址輸入與短網址建立頁面。
- `POST /api/short-url` 可建立短網址。
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
├─ src/                   # Web UI 前端原始碼
├─ public/                # 前端公開資源
├─ package.json           # Vite 指令與依賴
└─ vite.config.js
```

## 部署教學

### 需求

- Java 17 或更新版本。
- Maven 3.9 或更新版本。
- Discord Bot Token。
- 若要編譯 Web UI，需安裝 Node.js 與 npm。
- Bot 邀請到伺服器時需勾選 `bot` 與 `applications.commands` scope。
- 常用權限：查看頻道、發送訊息、嵌入連結、管理訊息、讀取訊息歷史、連接語音、語音發話、管理頻道、踢出成員、管理伺服器。依功能啟用狀態可再縮減。

### 建置

一般 Java 建置：

```bash
mvn clean package -DskipTests
```

包含 Web UI 前端建置：

```bash
mvn clean package -DskipTests -Pweb-build
```

建置完成後會產生：

```text
target/discord-music-bot-1.6.jar
lib/
```

目前專案採用「主程式 jar + 外部 lib 依賴」模式，Maven 會在打包時將 runtime 依賴複製到 `lib/`。

### 首次啟動

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.6.jar
```

首次啟動會自動建立 `config.yml`、語言檔與必要資料夾。停止程式後，編輯 `config.yml`：

```yml
token: "YOUR_DISCORD_BOT_TOKEN"
defaultLanguage: "zh-TW"
```

再重新啟動：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.6.jar
```

## 常用設定

```yml
prefix: "!"
debug: false
commandGuildId: ""
defaultLanguage: "zh-TW"
commandCooldownSeconds: 3
numberChainReactionDelayMillis: 500

data:
  guildSettingsDir: "guild/configs"
  languageDir: "lang"
  cacheDir: "cache"
  musicDir: "guild/music"
  moderationDir: "guild/moderation"
  ticketDir: "guild/tickets"
  ticketTranscriptDir: "ticket/transcripts"
  honeypotDir: "guild/honeypot"
  logDir: "logs"

music:
  autoLeaveEnabled: true
  autoLeaveMinutes: 5
  autoplayEnabled: true
  defaultRepeatMode: "OFF"
  commandChannelId: ""
  historyLimit: 50
  statsRetentionDays: 0
```

`commandGuildId` 留空會註冊全域 Slash 指令；開發測試時可填單一伺服器 ID，加快指令更新速度。

## Web UI 設定

在 Discord Developer Portal 建立 OAuth2 應用，Redirect URI 填入：

```text
https://dash.norule.me/auth/callback
```

設定 `config.yml`：

```yml
web:
  enabled: true
  host: "0.0.0.0"
  port: 60000
  baseUrl: "https://dash.norule.me"
  discordClientId: "YOUR_CLIENT_ID"
  discordClientSecret: "YOUR_CLIENT_SECRET"
  discordRedirectUri: "https://dash.norule.me/auth/callback"
```

啟動後開啟：

```text
https://dash.norule.me
```

## 短網址設定

短網址服務可使用獨立網域，例如 `s.norule.me`。建議由 Nginx / Cloudflare 將該網域反向代理到短網址服務或同一個 Java Web Server 對應的連接埠。

```yml
shortUrl:
  enabled: true
  host: "0.0.0.0"
  port: 60001
  domain: "s.norule.me"
  publicBaseUrl: "https://s.norule.me"
  codeLength: 7
  dedupeEnabled: true
  ttlDays: 7
  cleanupIntervalMinutes: 10
  allowPrivateTargets: false
```

使用方式：

```text
https://s.norule.me/
https://s.norule.me/abc1234
```

API 範例：

```bash
curl -X POST "https://s.norule.me/api/short-url" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com","customCode":"example"}'
```

## Minecraft 狀態查詢設定

```yml
minecraftStatus:
  userAgent: "NoRuleBot/1.0 contact: admin@norule.me"
  requestTimeoutMillis: 15000
  internalCacheSeconds: 60
```

## 統計資料儲存設定

統計資料可依需求使用 SQLite 或 MySQL。

SQLite 範例：

```yml
stats:
  storage: "sqlite"
  sqlite:
    path: "data/message-stats.db"
```

MySQL 範例：

```yml
stats:
  storage: "mysql"
  mysql:
    jdbcUrl: "jdbc:mysql://localhost:3306/discord_bot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
    username: "root"
    password: ""
    poolSize: 8
```

## HTTPS 設定

PEM 憑證放在 `certs/`：

```text
certs/privkey.pem
certs/fullchain.pem
```

設定：

```yml
web:
  ssl:
    enabled: true
    certDir: "certs"
    privateKeyFile: "privkey.pem"
    fullChainFile: "fullchain.pem"
```

若前面已經使用 Nginx 或 Cloudflare 終止 HTTPS，Java Web Server 可維持 HTTP，讓反向代理負責 TLS。

## Web UI 前端開發

安裝依賴：

```bash
cd web
npm install
```

建置一次：

```bash
npm run build
```

監看模式：

```bash
npm run dev
```

獨立 Vite dev server：

```bash
npm run dev:server
```

建議本地開發流程：

1. 啟動 Java 後端。
2. 在 `web/` 執行 `npm run dev`。
3. 開啟 Java Web UI 網址，例如 `http://localhost:60000`。
4. Vite watcher 會自動更新 `src/main/resources/web/app.js` 與 `app.css`。

## 更新

```bash
git pull
mvn clean package -DskipTests
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.6.jar
```

若有更新 Web UI 前端：

```bash
git pull
mvn clean package -DskipTests -Pweb-build
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.6.jar
```

## 注意事項

- 請勿將 `config.yml`、Token、OAuth Secret、資料庫密碼提交到 Git。
- 若使用短網址服務，建議只開放 HTTPS 對外入口，並由 Nginx 或 Cloudflare 代理。
- 若使用 MySQL，請先建立資料庫並確認 Bot 主機可連線。
- 若 Slash 指令更新較慢，可在測試階段設定 `commandGuildId` 為單一伺服器 ID。
- 若 Discord 顯示亂碼，請確認啟動參數包含 `-Dfile.encoding=UTF-8`。
