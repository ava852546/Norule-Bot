# NoRule Bot

NoRule Bot 是一個以 **Java 17 + JDA 6 + LavaPlayer** 開發的 Discord 多功能機器人，整合音樂播放、通知模板、日誌系統、私人包廂與 Web UI 管理。

## 功能摘要

- 音樂播放：YouTube 關鍵字 / URL、Spotify 連結轉播
- 互動式音樂面板：播放/暫停、跳過、停止、離開、循環、Autoplay
- 音樂面板資訊：封面、播放進度、來源、點歌者、隊列、語音頻道
- 搜尋選單：`/play` 關鍵字提供前 10 筆下拉選單（30 秒失效）
- 閒置自動離開：可設定分鐘數，無播放或無人時自動離開
- 私人包廂：進入觸發語音後自動建房、房主可調整設定、無人自動刪除
- 通知模板：成員加入/離開、語音進出/移動模板化與預覽
- 日誌系統：訊息編輯刪除、頻道事件、身分組事件、管理事件、指令使用
- 多語系：`zh-TW`、`zh-CN`、`en`（可擴充）
- 多伺服器獨立設定：`guild-configs/<guildId>.yml`
- Web UI：Discord OAuth2 登入後管理伺服器設定

## 環境需求

- Java 17+
- Maven 3.9+
- Discord Bot Token

## 快速開始

1. 第一次啟動會自動建立：
- `config.yml`
- `lang/zh-TW.yml`
- `lang/zh-CN.yml`
- `lang/en.yml`
- `lang/web/web-zh-TW.yml`
- `lang/web/web-zh-CN.yml`
- `lang/web/web-en.yml`

2. 在 `config.yml` 填入 Token：

```yml
token: "YOUR_DISCORD_BOT_TOKEN"
```

3. 開發模式啟動：

```bash
mvn clean compile exec:java
```

4. 打包：

```bash
mvn clean package -DskipTests
```

5. 使用 fat jar 執行：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.0.0-all.jar
```

## 目前註冊的斜線指令（13）

- `/help`
- `/join`
- `/play query:<關鍵字或 URL>`
- `/skip`
- `/stop`
- `/leave`
- `/music-panel`
- `/repeat mode:<off|single|all>`
- `/private-room-settings`
- `/包廂設定`
- `/settings <info|reload|reset|template|module|logs|music|language>`
- `/delete-messages <channel|user>`
- `/刪除訊息 <channel|user>`

補充：
- 音樂與包廂指令預設一般成員可用
- `/settings` 需要 `Manage Server`
- 刪除訊息指令需要 `Manage Messages`

## Prefix 指令（保留）

- `!help`
- `!join`
- `!play <關鍵字或 URL>`
- `!skip`
- `!stop`
- `!leave`
- `!repeat <off|single|all>`

## 設定檔說明

### 全域設定

- `config.yml`：主設定檔
- `guild-configs/<guildId>.yml`：各伺服器獨立設定

### 語言檔

- Bot 指令語言：`lang/*.yml`（排除 `lang/web/`）
- Web UI 語言：`lang/web/web-*.yml`

### 自動補齊與備份機制

- 首次建立 `config.yml` 會直接複製預設檔，保留完整註解
- 升級時若發現缺少新鍵值，會先備份舊設定後再合併
- 語言檔損壞時會自動備份並重建

## Bot 狀態輪播與佔位符

在 `config.yml > bot.activities` 中可使用：

- `{guildCount}` 或 `{serverCount}`：機器人所在伺服器數量
- `{memberCount}` 或 `{totalMemberCount}`：所有伺服器總成員數

範例：

```yml
bot:
  activityType: "LISTENING"
  rotationSeconds: 20
  activities:
    - "PLAYING|/help | {guildCount} servers"
    - "LISTENING|Serving {memberCount} members"
```

## Web UI（可選）

`config.yml` 內 `web.enabled: true` 後可啟用控制台，支援 Discord OAuth2 登入與權限檢查。

常見重點：
- 若使用 Nginx 反向代理 HTTPS，建議 `web.ssl.enabled: false`，由 Nginx 處理 TLS
- `baseUrl` 與 `discordRedirectUri` 必須和實際網域一致
- 若直接由 Bot 提供 HTTPS，可用 `certs/privkey.pem` + `certs/fullchain.pem`

## 主控台指令

- `reload`：重新讀取設定
- `stop` 或 `end`：安全關機

## 授權

本專案採用 **GNU GPL v3.0**。

- 授權檔：[LICENSE](./LICENSE)
- 官方條文：https://www.gnu.org/licenses/gpl-3.0.html
