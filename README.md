# NoRule Bot

NoRule Bot 是 Java + JDA 製作的 Discord 多功能機器人，提供音樂播放、伺服器設定、管理工具、客服單、私人包廂、日誌、Web UI 與防廣告密罐頻道。

## 功能介紹

- 音樂播放：支援 YouTube 關鍵字/URL、Spotify 連結轉搜尋、SoundCloud/一般 URL、播放佇列、音量、循環、歷史紀錄、統計與互動式音樂面板。
- 歌單管理：可儲存目前播放與佇列、載入、刪除、查看、移除單曲，並可用 6 位數代碼跨伺服器匯入。
- 自動推薦：佇列結束後可依目前歌曲推薦下一首，並避開最近播放過的歌曲。
- 伺服器設定：可透過 Discord 指令或 Web UI 管理語言、模組開關、日誌、音樂、數字接龍與模板。
- 日誌系統：支援訊息、指令使用、頻道生命週期、身分組與管理事件紀錄。
- 管理工具：刪除訊息、使用者警告、防重複訊息、數字接龍。
- 密罐頻道：建立「請勿發送訊息」文字頻道，使用者在該頻道發言後會刪除訊息、清理 24 小時內發言並踢出伺服器。
- 客服單：支援開單面板、開單前表單、分類選項、關閉/重開/刪除、HTML 紀錄、黑名單與每人開單上限。
- 私人包廂：進入指定語音頻道後自動建立私人語音房，支援改名、限制人數、轉移擁有者與自動刪除。
- Web UI：Discord OAuth2 登入後管理伺服器設定，可選擇 HTTP 或 HTTPS。

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
- `/volume value:<0-200>`、`/音量 音量:<0-200>`：設定播放音量。
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
- `!volume <0-200>`
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

## 部署教學

### 需求

- Java 17 或更新版本。
- Maven 3.9 或更新版本。
- Discord Bot Token。
- Bot 邀請到伺服器時需勾選 `bot` 與 `applications.commands` scope。
- 常用權限：查看頻道、發送訊息、嵌入連結、管理訊息、讀取訊息歷史、連接語音、語音發話、管理頻道、踢出成員、管理伺服器。依功能啟用狀態可再縮減。

### 建置

```bash
mvn clean package -DskipTests
```

建置完成後會產生：

```text
target/discord-music-bot-1.2.3.jar
```

### 首次啟動

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.2.3.jar
```

首次啟動會自動建立 `config.yml`、語言檔與必要資料夾。停止程式後，編輯 `config.yml`：

```yml
token: "YOUR_DISCORD_BOT_TOKEN"
defaultLanguage: "zh-TW"
```

再重新啟動：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.2.3.jar
```

### 常用設定

```yml
prefix: "!"
commandGuildId: ""

data:
  guildSettingsDir: "guild-configs"
  languageDir: "lang"
  cacheDir: "cache"
  musicDir: "guild-music"
  moderationDir: "guild-moderation"
  ticketDir: "guild-tickets"
  ticketTranscriptDir: "ticket-transcripts"
  honeypotDir: "guild-honeypot"
  logDir: "LOG"

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

### Web UI

在 Discord Developer Portal 建立 OAuth2 應用，Redirect URI 填入：

```text
http://localhost:60000/auth/callback
```

設定 `config.yml`：

```yml
web:
  enabled: true
  host: "0.0.0.0"
  port: 60000
  baseUrl: "http://localhost:60000"
  discordClientId: "YOUR_CLIENT_ID"
  discordClientSecret: "YOUR_CLIENT_SECRET"
  discordRedirectUri: "http://localhost:60000/auth/callback"
```

啟動後開啟：

```text
http://localhost:60000
```

### HTTPS

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

### 更新

```bash
git pull
mvn clean package -DskipTests
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.2.3.jar
```

