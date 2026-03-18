﻿# NoRule Bot (Discord 音樂與伺服器管理機器人)

NoRule Bot 是一個以 **Java 17 + JDA 6 + LavaPlayer** 開發的 Discord Bot，整合音樂播放、通知模板、日誌、私人包廂與多語系管理。

---

## 功能特色

- 音樂播放：YouTube 關鍵字 / URL、Spotify 連結轉播
- 互動式音樂控制面板（按鈕）：播放/暫停、跳過、停止、離開、循環、Autoplay
- 音樂面板資訊：目前歌曲、進度條、點歌者、來源、列隊、頻道（頻道標記）
- 搜尋互動：`/play` 關鍵字會提供前 10 筆下拉選單（30 秒失效）
- 自動離開：閒置時依設定分鐘數自動離開語音
- 私人包廂：進入觸發頻道後自動建房、房主可調整上鎖/人數/名稱、無人自動刪除
- 通知模板：成員加入/離開、語音進出模板化（含預覽與顏色）
- 日誌系統：訊息編輯/刪除、指令使用、頻道事件、身分組、管理事件
- 多伺服器獨立設定：`guild-configs/<guildId>.yml`
- 多語系：繁中 `zh-TW`、英文 `en`

---

## 環境需求

- Java 17+
- Maven 3.9+
- Discord Bot Token

---

## 快速開始

### 1) 設定 Token

首次啟動會自動建立：

- `config.yml`
- `lang/zh-TW.yml`
- `lang/en.yml`

請在 `config.yml` 填入：

```yml
token: "YOUR_DISCORD_BOT_TOKEN"
```

### 2) 本機開發啟動

```bash
mvn clean compile exec:java
```

### 3) 打包（建議正式環境）

```bash
mvn clean package -DskipTests
```

輸出：

- `target/discord-music-bot-1.0.0.jar`
- `target/discord-music-bot-1.0.0-all.jar`（含依賴，建議直接部署）

啟動範例：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.0.0-all.jar
```

---

## 主要指令

### 音樂

- `/help`
- `/join`
- `/play query:<關鍵字或URL>`
- `/skip`
- `/stop`
- `/leave`
- `/music-panel`
- `/repeat mode:<off|single|all>`

前綴指令（保留）：

- `!help`
- `!join`
- `!play <關鍵字或URL>`
- `!skip`
- `!stop`
- `!leave`
- `!repeat <off|single|all>`

### 刪除訊息

- `/delete-messages channel channel:<文字頻道> amount:<1-99>`
- `/delete-messages user user:<使用者> amount:<1-99>`
- `/刪除訊息`（中文別名）

補充：

- 未填 `amount` 預設為 `99`
- 有二次確認按鈕
- Discord API 限制：僅可批量刪除 14 天內訊息

### 私人包廂

- `/private-room-settings`
- `/包廂設定`（中文別名）

### 設定

- `/settings info`
- `/settings reload`
- `/settings reset`
- `/settings language code:<en|zh-TW>`
- `/settings template`
- `/settings module`
- `/settings logs`
- `/settings music`

---

## `/settings` 子系統說明

### `/settings template`

- 透過下拉選單選擇模板類型
- 使用表單編輯內容
- 支援預覽與（成員進出）Embed 顏色設定

### `/settings module`

可切換主要開關（布林值）：

- `notifications.enabled`
- `messageLogs.enabled`
- `memberJoinEnabled`
- `memberLeaveEnabled`
- `voiceLogEnabled`
- `commandUsageLogEnabled`
- `channelLifecycleLogEnabled`
- `roleLogEnabled`
- `moderationLogEnabled`
- `music.autoLeaveEnabled`
- `music.autoplayEnabled`
- `privateRoom.enabled`

### `/settings logs`

- 設定預設日誌頻道（fallback）
- 可獨立設定：訊息、指令、頻道事件、身分組、管理事件頻道
- 成員進出支援同頻道或分開頻道模式

### `/settings music`

- 切換 `自動離開`、`Autoplay`
- 設定自動離開分鐘（1-60）
- 設定音樂指令頻道
- 設定私人包廂觸發語音頻道

補充：設定「私人包廂觸發頻道」時會自動啟用私人包廂模組。

---

## 設定檔與自動更新行為

- 全域設定：`config.yml`
- 伺服器設定：`guild-configs/<guildId>.yml`
- 語言檔：`lang/*.yml`

啟動時會：

- 自動補齊缺少的 `config.yml` / 語言檔
- 檢查新版預設鍵值
- 發現缺鍵時先備份舊 `config.yml`，再合併新預設與舊值

---

## 權限需求

- `/settings ...`：`Manage Server`
- `/delete-messages`：`Manage Messages`

建議 Bot 具備：

- `View Channels`
- `Send Messages`
- `Manage Messages`
- `Connect` / `Speak`
- `Manage Channels`
- `Move Members`

---

## Pterodactyl / Linux 部署

建議直接用 fat jar：

```bash
java -Dfile.encoding=UTF-8 -Xms128M -XX:MaxRAMPercentage=95.0 -jar discord-music-bot-1.0.0-all.jar
```

主控台指令：

- `reload`：重新讀取設定
- `stop` 或 `end`：安全關機

---

## 授權

本專案採用 **GNU General Public License v3.0 (GPL-3.0)**。

- 授權檔：[`LICENSE`](./LICENSE)
- 官方條文：https://www.gnu.org/licenses/gpl-3.0.html

