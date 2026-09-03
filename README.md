# NoRule Bot

NoRule Bot 是以 Java 21 開發的 Discord 多功能機器人，整合音樂播放、歌單、伺服器管理、客服單、私人語音包廂、接龍遊戲、短網址、媒體分享、Minecraft 狀態查詢，以及兩套 Nuxt 4 Web UI。

本文件以目前 repository 的 Java 註冊程式、`pom.xml`、預設設定檔與 `web/` workspace 為準。部署前請先閱讀[設定範本](src/main/resources/defaults/config.yml)，不要把 token、OAuth secret、Cookie、資料庫密碼或 HMAC secret 提交到版本控制。

## 版本與技術棧

- 專案版本：`1.7`
- Java：21
- Discord：JDA `6.5.0`
- 音訊：Lavaplayer `2.2.7`、youtube-source `1.18.2`、LavaSrc `4.8.3`、bilibili-common `1.0.4`
- 後端：JDK `HttpServer`、SQLite / MySQL、SnakeYAML、Jackson
- 前端：Nuxt `4.5.2`、Vue `3.5.41`、TypeScript `6.0.3`、Vite `8.x`
- 授權：GPL-3.0，詳見 [LICENSE](LICENSE)

## 主要功能

- YouTube、Bilibili、Spotify 資源解析與播放；可選 Companion 播放後端、YouTube 驗證與 Lavalink 嚴格預檢。
- 音樂控制面板、搜尋選擇、佇列控制、循環、自動推薦、音量、歷史與統計。
- 伺服器歌單的儲存、載入、新增歌曲、刪除、查看、單曲移除，以及六位數代碼匯出／匯入。
- 互動式伺服器設定、歡迎訊息、日誌、私人語音包廂、客服單、警告、訊息刪除、防洗頻與 honeypot 頻道。
- 數字接龍、英文單字接龍、使用者／身分組／伺服器資訊、訊息與語音統計、排行榜、問題回報。
- 獨立 Dashboard 與 NoRule URL 前端；正式環境由 Java 提供 API、OAuth、Session、媒體內容與靜態檔案，不需要 Nuxt server。
- 短網址、自訂短碼、登入後內容管理、媒體分享、密碼保護、去重、配額、速率限制與可信任代理 IP 解析。

## Discord 指令

下表直接對應 `DiscordCommandCatalog`。每個英文頂層指令都有一個獨立註冊的繁中別名；`<...>` 表示必填，`[...]` 表示選填。表中的權限是 Discord 註冊時的預設權限，伺服器管理員仍可在 Discord 內調整指令權限。

### 一般、資訊與服務

| 指令與繁中別名 | 參數 | 功能 | 預設權限 |
|---|---|---|---|
| `/help`、`/說明` | 無 | 開啟互動式說明 | 所有人 |
| `/ping`、`/延遲` | 無 | 顯示 Discord 延遲 | 所有人 |
| `/url`、`/短網址` | `<url>` `[slug]` | 建立 HTTP/HTTPS 短網址；`slug` 是選填自訂短碼 | 所有人 |
| `/mcstatus`、`/mc狀態` | `<address>` `[type:JAVA\|BEDROCK]` | 查詢 Minecraft 伺服器狀態 | 所有人 |
| `/user-info`、`/使用者資訊` | `[user]` | 顯示自己或指定使用者資訊 | 所有人 |
| `/role-info`、`/身分組資訊` | `<role>` | 顯示身分組資訊 | 所有人 |
| `/server-info`、`/伺服器資訊` | 無 | 顯示目前伺服器資訊 | 所有人 |
| `/stats`、`/統計` | `[user]` | 顯示自己或指定使用者的訊息與語音統計 | 所有人 |
| `/top`、`/排行榜` | 無 | 開啟排行榜選單 | 所有人 |
| `/report`、`/回報` | `<type:bug\|feedback>` | 開啟回報表單；送出需設定 `developers.developerChannelId` | 所有人 |

### 音樂

| 指令與繁中別名 | 參數 | 功能 |
|---|---|---|
| `/join`、`/加入` | 無 | 加入使用者所在語音頻道 |
| `/play`、`/播放` | `<query>` | 播放關鍵字、支援的 URL 或 Spotify URL |
| `/skip`、`/跳過` | 無 | 跳過目前歌曲 |
| `/stop`、`/停止` | 無 | 停止播放並清空佇列 |
| `/leave`、`/離開` | 無 | 離開語音頻道 |
| `/music-panel`、`/音樂面板` | 無 | 建立或移動互動式音樂控制面板 |
| `/repeat`、`/循環` | `<mode:OFF\|SINGLE\|ALL>` | 設定循環模式 |
| `/volume`、`/音量` | `<value:1-100>` | 設定播放音量 |
| `/history`、`/播放歷史` | 無 | 顯示最近播放記錄 |
| `/music stats`、`/音樂 統計` | 無 | 顯示伺服器音樂統計 |

以上指令的 Discord 預設權限皆為所有人；實際播放仍會檢查語音頻道與音樂指令頻道等執行條件。

### 歌單

英文入口為 `/playlist`，繁中入口為 `/歌單`；繁中子指令依序為 `儲存`、`載入`、`新增歌曲`、`刪除`、`列表`、`查看`、`刪除歌曲`、`匯出`、`匯入`。

| 子指令 | 參數 | 功能 |
|---|---|---|
| `/playlist save` | `<name>` | 將目前佇列儲存為歌單 |
| `/playlist load` | `<name>` | 載入歌單到播放佇列 |
| `/playlist add` | `<name>` `<url>` | 將 URL 加入既有歌單 |
| `/playlist delete` | `<name>` | 刪除自己的歌單 |
| `/playlist list` | `[scope:mine\|all]` | 列出自己的或全部歌單；預設為全部 |
| `/playlist view` | `<name>` | 查看歌單內容 |
| `/playlist remove-track` | `<name>` `<index:1-10000>` | 依索引移除歌單內歌曲 |
| `/playlist export` | `<name>` | 產生六位數跨伺服器匯入代碼 |
| `/playlist import` | `<code>` `[name]` | 以六位數代碼匯入，可另指定新名稱 |

### 設定、社群與遊戲

| 指令與繁中別名 | 參數 | 功能 | 預設權限 |
|---|---|---|---|
| `/settings`、`/設定` | 無 | 開啟互動式設定選單；包含詳細資訊、重載、重設、模板、模組、日誌、音樂、數字接龍、英文接龍與語言 | 管理伺服器 |
| `/welcome`、`/歡迎訊息` | `[action:enable\|status]` `[channel]` | 編輯、啟用或查看歡迎訊息與頻道 | 管理伺服器 |
| `/private-room-settings`、`/包廂設定` | 無 | 管理使用者自己的私人語音包廂 | 所有人 |
| `/number-chain`、`/數字接龍` | 無 | 顯示數字接龍狀態；管理設定請使用 `/settings` | 所有人 |
| `/wordchain`、`/英文接龍` | 無 | 顯示英文單字接龍狀態；管理設定請使用 `/settings` | 所有人 |
| `/ticket`、`/客服單` | 無 | 開啟客服單操作選單 | 所有人 |
| `/honeypot-channel`、`/密罐頻道` | 無 | 建立 honeypot 文字頻道 | 管理伺服器 |

### 管理

| 指令與繁中別名 | 參數 | 功能 | 預設權限 |
|---|---|---|---|
| `/delete-messages`、`/刪除訊息` | `<type:channel\|user>` `[channel]` `[user]` `[time]` `[amount:1-99]` | 依頻道或使用者刪除訊息；`time` 最長 14 天、預設 24 小時 | 管理訊息 |
| `/warnings`、`/警告` | `<action:add\|remove\|view\|clear>` `[user]` `[amount:1-50]` | 新增、移除、查看或清除警告 | 管理成員 |
| `/anti-duplicate`、`/防洗頻` | `<action:enable\|status>` `[value]` | 啟用或查看重複訊息偵測 | 管理伺服器 |

### Prefix 指令

Prefix 由 `prefix` 設定，預設為 `!`。Prefix 指令只支援英文命令字；`p` 是 `play` 的別名，快速播放使用目前設定的 prefix（例如 `<prefix>p`）。

```text
<prefix>help
<prefix>join
<prefix>play <關鍵字或 URL>
<prefix>p <關鍵字或 URL>
<prefix>p <Bilibili URL>       # 快速播放範例
<prefix>volume <1-100>
<prefix>history
<prefix>music
<prefix>playlist [list] [mine|all]
<prefix>playlist save <名稱>
<prefix>playlist load <名稱>
<prefix>playlist add <名稱> <URL>
<prefix>playlist delete <名稱>
<prefix>playlist view <名稱>
<prefix>playlist export <名稱>
<prefix>playlist import <六位數代碼> [新名稱]
<prefix>skip
<prefix>stop
<prefix>leave
<prefix>repeat [off|single|all]
```

Prefix 歌單目前沒有 `remove-track` 動作；請使用 Slash 指令移除單曲。

### 控制台指令

| 指令 | 功能 |
|---|---|
| `help`、`?`、`h` | 顯示控制台指令說明 |
| `reload` | 重新載入主要設定與語言檔 |
| `stop`、`end` | 正常關閉 Bot |
| `clear message_log [t:12d34m56s]` | 清除訊息日誌快取；未提供時間時預設 7 天 |
| `clear message_log_cache [t:12d34m56s]` | 上一指令的相容別名 |
| `clear play_history [t:12d34m56s]` | 清除播放歷史；未提供時間時預設 7 天 |
| `clear guild_commands` | 清除目前註冊的 guild commands |

## 專案結構與架構

主要 Java 原始碼位於 `src/main/java/com/norule/musicbot/`：

```text
bootstrap/                    啟動、生命週期、runtime dependency bootstrap
config/                       設定載入與各功能 DomainConfig
discord/bot/app/              Discord composition root、handler registry、prefix router
discord/bot/gateway/          JDA 入口、指令 handler、route、catalog、component、panel
discord/bot/flow/             多步驟互動流程
domain/                       不依賴 framework 或 I/O 的純領域邏輯
gateway/                      外部音樂、字典等 adapter
i18n/                         語言載入與翻譯服務
ops/                          路由、協調與用例編排
service/                      業務服務與 repository 協調
shorturl/                     短網址 HTTP gateway 與 persistence adapter
storage/                      共用儲存實作
web/                          Dashboard HTTP controller、web service、session 與 infra
```

依賴方向遵循：

```text
gateway → flow → ops → service → domain
web → service → ops → domain
```

`MusicCommandService` 位於 `discord.bot.app`，是 JDA listener／組合根，不承載大型指令業務邏輯。Slash schema 由 `DiscordCommandCatalog` 管理，canonical route 由 `DiscordCommandRouteMapper` 管理，各類 handler 放在 `discord.bot.gateway.command.*`。

前端來源與輸出責任：

```text
web/app/                       NoRule URL Nuxt 來源
web/nuxt.config.ts             NoRule URL 設定
web/src/dashboard/             Dashboard Vue 來源
web/dashboard/nuxt.config.ts   Dashboard Nuxt 設定
web/src/templates/             Java render 的特殊頁面模板
web/scripts/                   static output 同步腳本
src/main/resources/web/        建置同步產物，不是主要來源
target/classes/web/            Maven 建置產物，不可直接修改
```

完整前端工作方式另見 [web/README.md](web/README.md)，設計變更需遵循 [DESIGN_GUIDELINES.md](docs/DESIGN_GUIDELINES.md)。

## 建置與部署

### 需求

- 執行環境：JDK 21。
- Java 建置：Maven；建議使用 Maven 3.9 以上。本專案沒有 Maven Wrapper，也沒有在 POM 中硬性鎖定 Maven 版本。
- 前端建置：Node.js `^20.19.0` 或 `>=22.12.0`。此範圍來自目前 `package-lock.json` 內的 Nuxt/Vite 工具鏈；正式執行已完成建置的 JAR 時不需要 Node.js 或 Nuxt server。

Windows 本機可明確指定 JDK 21：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
mvn -version
```

### 驗證與打包

```powershell
# 快速 Java 編譯
mvn -q -DskipTests compile

# Java 測試
mvn test

# 正式打包：會執行 Java 測試、npm ci、兩套 Nuxt generate 與靜態輸出同步
mvn clean package
```

只有在明確接受略過 Java 測試時才使用：

```powershell
mvn clean package -DskipTests
```

Maven 的 `prepare-package` 階段會執行 `npm ci` 與 `npm run build`，並把兩套 Nuxt 靜態輸出同步至 `target/classes/web`。主要輸出為：

```text
target/discord-music-bot-<version>.jar
runtime-libs/
target/classes/web/
```

目前版本的 JAR 名稱範例是 `target/discord-music-bot-1.7.jar`；自動化腳本應使用版本變數或檔案比對，不要長期硬編碼這個名稱。

### Runtime dependencies

這不是 fat JAR。Maven 會把 runtime dependencies 複製到專案工作目錄的 `runtime-libs/`，並把 dependency URL 與 SHA-256 manifest 寫入 JAR 的 `bootstrap/` resources。直接執行主 JAR 時，bootstrap 會驗證目前工作目錄下的 `runtime-libs`、下載缺少或 checksum 不符的 JAR、清理 obsolete/conflicting logging JAR，然後用主 JAR 加 `runtime-libs/*` 重新啟動 JVM。

```powershell
java -jar target/discord-music-bot-1.7.jar
```

可用 `runtime-dependencies` 設定下載進度、timeout 與重試；JVM system properties 可覆寫：

| Property | 用途 |
|---|---|
| `norule.bootstrap.cleanup-obsolete` | 是否清理 manifest 外的舊 runtime JAR，預設 `true` |
| `norule.bootstrap.verify-checksums` | 是否驗證 SHA-256，預設 `true` |
| `norule.bootstrap.force-redownload` | 是否強制重新下載，預設 `false` |
| `norule.bootstrap.progress-enabled` | 是否顯示下載進度 |
| `norule.bootstrap.progress-interval-ms` | 進度輸出間隔 |
| `norule.bootstrap.connect-timeout-ms` | 連線逾時 |
| `norule.bootstrap.read-timeout-ms` | 讀取逾時 |
| `norule.bootstrap.stall-timeout-ms` | 無資料進度逾時 |
| `norule.bootstrap.max-retries` | 最大重試次數 |

若部署環境不能連外，請連同已驗證的 `runtime-libs/` 一起部署。

### 首次啟動

1. 在預定工作目錄執行 JAR。若尚無設定，程式會建立 `config.yml`、`lang/` 與 `lang/web/` 的預設語言檔。
2. 沒有 token 時程式會在依賴與設定初始化後停止；請在 `config.yml` 設定 `token`。只有 YAML token 空白時，程式才會改用 `DISCORD_TOKEN`。
3. 再次啟動。其他 `data`、guild 設定、日誌、歌單、管理、客服單、honeypot 與 transcript 路徑會由對應服務在啟用／使用時建立。

可用 `BOT_CONFIG_PATH` 指向另一份主要 YAML。相對資料路徑以程序的工作目錄解析，因此服務化部署時應固定 `WorkingDirectory`。

### 更新

```powershell
git pull
mvn clean package
java -jar target/discord-music-bot-1.7.jar
```

更新前先備份 `config.yml`、`lang/`、資料庫、guild 資料與媒體儲存目錄，並先比較新版[預設設定](src/main/resources/defaults/config.yml)。不要直接以新版預設檔覆蓋現有 secret 或站台設定。

## 設定

完整、可解析的設定與所有預設值都在 [`src/main/resources/defaults/config.yml`](src/main/resources/defaults/config.yml)。README 只列啟動最常用的骨架，避免複製一份容易過期的完整 schema：

```yaml
token: ""
prefix: "!"
defaultLanguage: "zh-TW"
commandGuildId: ""       # 空白時註冊 global commands；開發時可填 guild ID

web:
  enabled: false
  bind:
    port: 60000
  public:
    baseUrl: "https://dash.example.com"
  sessionExpireMinutes: 720
  discordClientId: ""
  discordClientSecret: ""
  discordRedirectUri: "https://dash.example.com/auth/callback"

shortUrl:
  enabled: false
  bindPort: 60001
  publicBaseUrl: "https://example.com/"

database:
  storage: "sqlite"       # sqlite 或 mysql
  sqlite:
    path: "data/norule.db"
```

設定檔包含憑證時應限制檔案權限；正式環境優先以 secret manager 或環境變數注入可覆寫的秘密。

### 環境變數

下列名稱是目前 Java 與前端程式實際讀取的環境變數。未列出的 YAML 欄位不代表能以同名環境變數覆寫。

| 類別 | 環境變數 |
|---|---|
| 核心 | `BOT_CONFIG_PATH`、`DISCORD_TOKEN`、`MERRIAM_WEBSTER_API_KEY` |
| MySQL statistics/cache | `MYSQL_JDBC_URL`、`MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_POOL_SIZE` |
| Spotify | `SPOTIFY_ENABLED`、`SPOTIFY_CLIENT_ID`、`SPOTIFY_CLIENT_SECRET`、`SPOTIFY_SP_DC`、`SPOTIFY_COUNTRY_CODE`、`SPOTIFY_PREFER_ANONYMOUS_TOKEN`、`SPOTIFY_CUSTOM_TOKEN_ENDPOINT` |
| Bilibili | `BILIBILI_ENABLED`、`BILIBILI_COOKIE`、`BILIBILI_METADATA_CACHE_ENABLED`、`BILIBILI_METADATA_CACHE_TTL_HOURS`、`BILIBILI_METADATA_CACHE_MAX_ENTRIES`、`BILIBILI_RATE_LIMIT_ENABLED`、`BILIBILI_RATE_LIMIT_RPS`、`BILIBILI_RATE_LIMIT_BURST`、`BILIBILI_CIRCUIT_BREAKER_ENABLED`、`BILIBILI_CIRCUIT_BREAKER_FAILURE_THRESHOLD`、`BILIBILI_CIRCUIT_BREAKER_WINDOW_SECONDS`、`BILIBILI_CIRCUIT_BREAKER_COOLDOWN_SECONDS` |
| YouTube Companion | `YOUTUBE_PLAYBACK_BACKEND`、`YOUTUBE_COMPANION_ENABLED`、`YOUTUBE_COMPANION_FALLBACK_TO_SOURCE`、`YOUTUBE_COMPANION_URL`、`YOUTUBE_COMPANION_SECRET`、`YOUTUBE_COMPANION_CONNECT_TIMEOUT_MILLIS`、`YOUTUBE_COMPANION_REQUEST_TIMEOUT_MILLIS` |
| YouTube cipher/auth | `YOUTUBE_CIPHER_ENABLED`、`YOUTUBE_CIPHER_SERVER`、`YOUTUBE_CIPHER_PASSWORD`、`YOUTUBE_CIPHER_USER_AGENT`、`YOUTUBE_AUTH_MODE`、`YOUTUBE_OAUTH_ENABLED`、`YOUTUBE_STRICT_AUTH_CONFIG`、`YOUTUBE_PO_TOKEN`、`YOUTUBE_VISITOR_DATA`、`YOUTUBE_OAUTH_REFRESH_TOKEN` |
| YouTube 預檢 | `LAVALINK_BASE_URL`、`LAVALINK_PASSWORD` |
| 短網址／媒體 | `SHORT_URL_QUOTA_HMAC_SECRET`、`SHORT_URL_DEVICE_HMAC_SECRET` |
| 前端建置／開發 | `NUXT_DEV_API_TARGET`、`NUXT_DASHBOARD_API_TARGET`、`NORULE_WEB_OUTPUT_DIR` |

YouTube 仍接受舊相容別名 `YOUTUBE_REMOTE_CIPHER_URL`、`YOUTUBE_REMOTE_CIPHER_PASSWORD`、`YOUTUBE_REMOTE_CIPHER_USER_AGENT`、`YOUTUBE_POTOKEN`、`YOUTUBE_VISITORDATA`、`YOUTUBE_REFRESH_TOKEN`；新部署請使用上表的主要名稱。

## 音樂來源與播放後端

### Bilibili

Bilibili URL 由專用 adapter 處理。控制面 API 請求具備：

- 選填 Cookie；只會附加到 `bilibili.com` 子網域與 `b23.tv`。
- metadata TTL/LRU cache、single-flight、token-bucket rate limiter 與 circuit breaker。
- 主 metadata API 遇到 HTTP 412 風控時，改以 `x/player/pagelist` 做降級 metadata 解析；降級成功會寫入 cache 並視為成功，不計入 breaker failure。
- 403、412、429、metadata 與 playback failure 會分類成不同錯誤；Cookie 能降低部分限制，但無法保證避開地區、帳號或上游風控。

核心參數位於 `music.bilibili`；若使用 `BILIBILI_COOKIE`，請視同敏感憑證管理。

### YouTube、Companion 與嚴格預檢

預設 `music.youtube.playbackBackend` 為 `YOUTUBE_SOURCE`。設為 `COMPANION` 且 `music.youtube.companion.enabled: true` 時，Companion 負責取得／代理播放串流，youtube-source 仍負責搜尋與 metadata。Companion secret 必須是 16 位英數字元；若啟用 `fallbackToSource`，Companion timeout、無法連線、5xx 或無串流時只會 fallback 到 youtube-source 一次。

`music.youtube.strictPrecheck.enabled` 是選填的 Lavalink youtube-plugin 預檢。啟用時需提供 base URL 與 password，程式會呼叫 `/youtube/stream/{videoId}`，並依可播放、暫時失敗與永久失敗分別使用設定的 cache TTL；若 Lavalink 未安裝相容 plugin，請保持關閉。

YouTube cipher server、PO token、visitor data 或 OAuth refresh token 都是進階選項，可能受上游政策影響。不要把憑證提交到 repository；修改後應以實際 `/play` 測試，不要只以啟動成功判定可播放。

### Spotify 與直接 HTTP

Spotify 整合預設關閉。啟用並提供需要的 client credentials／`sp_dc` 後，Spotify track、album、playlist 與 artist 資源會解析成可播放搜尋結果；Jam、show 與 episode 不是一般播放入口。Spotify Web API 仍可能因私人、個人化、空歌單或 rate limit 拒絕解析。

任意 HTTP 音訊預設由 `music.audio.direct-http.enabled: false` 關閉。若啟用，請同時維持 HTTPS、host allowlist、DNS rebinding／私有位址阻擋與 timeout 限制；不要用全域 allowlist 繞過 SSRF 防護。

## Web Dashboard

Dashboard 是 `web/src/dashboard/` 的獨立 Nuxt app，由 Java Web Server 在 `web.bind.port`（預設 `60000`）提供：

- `/`：Dashboard shell。
- `/auth/login`、`/auth/callback`、`/auth/logout`：Discord OAuth 與 session。
- `/api/bot`、`/api/me`、`/api/guilds`、`/api/guild/...`：Dashboard 資料與 guild 設定 API。
- `/api/web/i18n`、`/api/short`、`/api/minecraft/status`：前端使用的輔助 API。

啟用前至少設定 `web.public.baseUrl`、`web.discordClientId`、`web.discordClientSecret` 與 `web.discordRedirectUri`。Discord Developer Portal 的 redirect URL 必須與設定完全一致，例如 `https://dash.example.com/auth/callback`。Java server 使用 HTTP；公開 HTTPS 應由 Nginx、Caddy、Cloudflare Tunnel 或同類反向代理終止 TLS。

Dashboard 目前包含一般、通知、日誌、音樂、私人包廂、歡迎訊息、數字接龍與客服單設定。API 會檢查 session、CSRF 與 guild 權限；不要只靠前端隱藏控制項。

## NoRule URL、短網址與媒體分享

獨立 Short URL server 在 `shortUrl.bindPort`（預設 `60001`）提供 NoRule URL 靜態 app 與 API：

- `/`：建立短網址／媒體分享頁面。
- `/{code}`：網址 redirect 或媒體頁面；`/{code}?stats` 只允許登入後的擁有者查看。
- `/my-content`：登入後管理自己的短網址與媒體。
- `/api/short`、`/api/short/{code}`、`/api/short/{code}/stats`：建立、解析與統計。
- `/api/short/session`、`/api/short/session/login`、`/api/short/session/logout`：登入狀態。
- `/api/short/mine`：列出登入者擁有的內容。
- `/api/short/image`、`/api/short/image/config`、`/api/short/image/content/{code}`、`/api/short/image/access/{code}`：媒體上傳、設定與內容存取。

`shortUrl.publicBaseUrl` 是產生公開連結的唯一來源，不要在 handler 內硬編碼 production domain。

### 自訂短碼

- 長度 3–32，允許 `a-z`、`A-Z`、`0-9`、`-`、`_`。
- 比對不分大小寫，儲存與回傳會正規化為小寫。
- API、auth、login、dashboard、stats、privacy、terms、status、assets、media 等保留路徑會被拒絕。
- 已有短碼忽略大小寫後相同時回傳 HTTP `409`。
- 前端 `web/app/utils/shortCode.ts` 與後端 domain 規則必須保持一致；後端保留字集合是最終依據。

### Rate limit 與來源 IP

API admission 預設限制：匿名媒體上傳每 IP 每分鐘 10 次；已登入上傳另受 IP 每分鐘 60 次、每使用者每分鐘 20 次與每日 200 次限制；短網址建立為 IP 每分鐘 30 次、使用者每分鐘 60 次；同時媒體上傳為 IP 2、使用者 3。

短網址建立另有 `shortUrl.abuseProtection.creation` 防護：匿名使用者預設為每分鐘 10 次、每 10 分鐘 50 次、每日建立 200 個；已登入使用者為每分鐘 30 次、每 10 分鐘 150 次、每日建立 500 個。請同時考量兩層限制；被限制時回傳 HTTP `429`、`Retry-After` 與統一 rate-limit payload。

只有直接 peer 位於 `shortUrl.abuseProtection.rateLimit.trustedProxyCidrs` 時，程式才採信 `X-Forwarded-For`。部署在 Nginx、Cloudflare 或 Tunnel 後方時，請填入實際「直接上一跳」的 CIDR，並確認代理正確覆寫／附加 `X-Forwarded-For`；不要信任所有網段，也不要假設程式會讀取其他 vendor-specific IP header。

### 媒體儲存與秘密

媒體上傳先寫入暫存檔，再以 blob-level persistence 做去重與 ownership 管理，不會把大型 request body 全部留在記憶體。`SHORT_URL_QUOTA_HMAC_SECRET` 與 `SHORT_URL_DEVICE_HMAC_SECRET` 在對應功能啟用時必須使用高熵、固定且不公開的值；更換秘密可能影響既有 quota/device identity。

`shortUrl.allowPrivateTargets` 預設為 `false`，不要在公開服務任意開啟。媒體預設上限為圖片 20 MB、影片 100 MB／300 秒；預設保留 1 小時、最長 365 天，過期 archive 保留 30 天。managed storage 預設上限為 50 GB，70% 警告、檔案系統使用率 80% 時停止上傳。

密碼保護目前允許以 `MMdd` 作為空白密碼的預設值；若站台不接受這項取捨，請關閉 `shortUrl.image.abuseProtection.passwordProtection.allowDateDefaultPassword` 並要求明確密碼。匿名身份、密碼嘗試／退避、使用者 quota 與 cleanup 細節以預設設定檔為準。

## 資料庫與資料路徑

`database.storage` 支援 `sqlite` 與 `mysql`：

- SQLite 預設為 `data/norule.db`，供統計、快取與多個 guild repository 共用；短網址／媒體 repository 也會依其設定使用 SQLite。
- MySQL 可用於訊息統計、訊息日誌、重複訊息、私人包廂等 cache/repository，以及設定為 MySQL 的短網址／媒體 repository。
- `MYSQL_*` 環境變數目前覆寫 Java runtime 建立的 statistics/cache MySQL 連線；其他 repository 應以 `config.yml` 的資料庫設定為準。
- 歌單、客服單 transcript、媒體 blob 與部分 guild 資料另有各自的設定路徑；不要只備份單一資料庫就假設涵蓋全部狀態。

切換 storage 前請自行規劃資料遷移；程式不承諾自動把既有 SQLite／檔案資料搬到 MySQL。

## Minecraft 與英文接龍

Minecraft 狀態查詢使用 `minecraftStatus` 的 timeout、cache、rate limit 與 `userAgent`。公開部署請把 User-Agent 聯絡資訊改成自己的有效維運信箱，不要照抄範例。

英文接龍可使用 Free Dictionary API，並選填 Merriam-Webster fallback。若要啟用 Merriam-Webster，請設定 `MERRIAM_WEBSTER_API_KEY` 或對應 YAML 值；環境變數優先。

## 前端開發

```powershell
cd web
npm ci

# NoRule URL：http://127.0.0.1:3000，/api 預設代理到 http://127.0.0.1:60001
npm run dev

# Dashboard：http://127.0.0.1:5173，/api 與 /auth 預設代理到 http://127.0.0.1:60000
npm run dev:dashboard
```

需要其他 Java backend 時可設定：

```powershell
$env:NUXT_DEV_API_TARGET = 'http://127.0.0.1:60001'
$env:NUXT_DASHBOARD_API_TARGET = 'http://127.0.0.1:60000'
```

前端驗證：

```powershell
cd web
npm run typecheck
npm test
npm run build
```

`npm run build` 會 generate Dashboard、同步 Dashboard、generate NoRule URL、再同步 NoRule URL。未設定 `NORULE_WEB_OUTPUT_DIR` 時，腳本同步到 `src/main/resources/web`；Maven 正式建置會把它指定為 `target/classes/web`。不要手動修改 `_nuxt` hashed assets、generated HTML 或 `target/classes/web`。

## 安全與維運注意事項

- 不要提交 Discord token、OAuth secret、Spotify/Bilibili/YouTube 憑證、資料庫密碼、Companion secret 或短網址 HMAC secret。
- 公開 Web／Short URL server 前，先完成 HTTPS、反向代理、可信任代理 CIDR、OAuth redirect、CSRF/session cookie 與檔案權限設定。
- Web 與 Short URL 的 JDK `HttpServer` 目前都監聽 `0.0.0.0`；請用防火牆或容器網路限制來源，不要直接把內部 HTTP port 暴露到 Internet。
- 保留短網址 SSRF 防護、媒體大小／配額／並行限制，以及上傳暫存檔與 blob cleanup 流程。
- `commandGuildId` 適合開發期快速同步單一 guild；正式 global command 更新可能需要 Discord 傳播時間。
- 音樂來源受上游 API、地區、Cookie、OAuth、限流與影音可用性影響；更新後至少實測 `help`、`play`、`playlist`、`settings`、短網址建立／redirect、ticket 與 private room flow。
- 啟用 MySQL、Companion、Lavalink、OAuth 或 Cloudflare/Nginx 前，先用非正式環境驗證 timeout、fallback、header 與權限行為。

## License

本專案依 [GNU General Public License v3.0](LICENSE) 授權。
