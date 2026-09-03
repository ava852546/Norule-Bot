# NoRule Bot

NoRule Bot 是以 Java 21 開發的 Discord 多功能機器人，整合音樂播放、歌單、伺服器管理、客服單、私人語音包廂、接龍遊戲、短網址、媒體分享、Minecraft 狀態查詢，以及兩套 Nuxt 4 Web UI。


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
| `/report`、`/回報` | `<type:bug\|feedback>` | 開啟回報表單 | 所有人 |

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

Prefix 由 `prefix` 設定，預設為 `!`。Prefix 指令只支援英文命令字；`p` 是 `play` 的別名。

```text
<prefix>help
<prefix>join
<prefix>play <關鍵字或 URL>
<prefix>p <關鍵字或 URL>
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

完整前端工作方式另見 [web/README.md](web/README.md)。

## 建置與部署

### 需求

- 執行環境：JDK 21。
- Java 建置：Maven；建議使用 Maven 3.9 以上。本專案沒有 Maven Wrapper，也沒有在 POM 中硬性鎖定 Maven 版本。
- 前端建置：Node.js `^20.19.0` 或 `>=22.12.0`。此範圍來自目前 `package-lock.json` 內的 Nuxt/Vite 工具鏈；正式執行已完成建置的 JAR 時不需要 Node.js 或 Nuxt server。


### 驗證與打包

```powershell
# 快速 Java 編譯
mvn -q -DskipTests compile

# Java 測試
mvn test

# 正式打包：會執行 Java 測試、npm ci、兩套 Nuxt generate 與靜態輸出同步
mvn clean package
```

略過 Java 測試時使用：

```powershell
mvn clean package -DskipTests
```


## 前端開發

```powershell
cd web
npm ci

# NoRule URL：http://127.0.0.1:3000，/api 預設代理到 http://127.0.0.1:60001
npm run dev

# Dashboard：http://127.0.0.1:5173，/api 與 /auth 預設代理到 http://127.0.0.1:60000
npm run dev:dashboard
```


前端驗證：

```powershell
cd web
npm run typecheck
npm test
npm run build
```


## License

本專案依 [GNU General Public License v3.0](LICENSE) 授權。
