# NoRule Bot

NoRule Bot 是以 Java 17 + JDA 6 + LavaPlayer 開發的 Discord 多功能機器人，提供音樂播放、通知模板、日誌、私人包廂、數字接龍與 Web UI 管理。

## 功能重點

- 音樂：YouTube 關鍵字/URL、Spotify 連結轉播、互動式音樂面板、音量控制、播放歷史、音樂統計
- 設定：`/settings`（英文）與 `/設定`（中文）
- 日誌：訊息編輯/刪除、指令使用、頻道事件、身分組事件、管理事件
- 私人包廂：進入觸發語音頻道後自動建立房間，無人自動刪除
- 數字接龍：可設定頻道、啟用狀態與重置
- Web UI：Discord OAuth2 登入後管理伺服器設定

## 環境需求

- Java 17+
- Maven 3.9+
- Discord Bot Token

## 快速開始

1. 首次啟動會自動建立：
   - `config.yml`
   - `lang/zh-TW.yml`
   - `lang/zh-CN.yml`
   - `lang/en.yml`
   - `lang/web/web-zh-TW.yml`
   - `lang/web/web-zh-CN.yml`
   - `lang/web/web-en.yml`
2. 在 `config.yml` 填入：

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

5. 執行（建議使用 all jar）：

```bash
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.1.0-all.jar
```

## `/help` 說明（已更新）

`/help` 會開啟互動式說明面板，包含：

- 一般
- 音樂
- 設定
- 管理
- 私人包廂

可透過下拉選單與按鈕快速切換頁籤。

## 主要指令

- `/help`
- `/ping`
- `/延遲`
- `/join`
- `/play query:<關鍵字或URL>`
- `/volume value:<0-200>`
- `/音量 value:<0-200>`
- `/history`
- `/播放歷史`
- `/playlist save/load/delete/list`
- `/歌單 儲存/載入/刪除/列表`
- `/skip`
- `/stop`
- `/leave`
- `/music-panel`
- `/music stats`
- `/音樂 統計`
- `/repeat mode:<off|single|all>`
- `/private-room-settings`
- `/包廂設定`
- `/settings`（英文）
- `/設定`（中文）
- `/delete-messages`（英文）
- `/刪除訊息`（中文）
- `/warnings`
- `/anti-duplicate`
- `/number-chain`

## 設定指令（重點）

### 中文：`/設定`

採單一指令 `action` 選項：

- 資訊
- 重新加載
- 恢復預設
- 訊息模板
- 模組開關
- 紀錄頻道
- 音樂功能
- 語言

補充：選擇「語言」後會開啟語言下拉選單，不再常駐顯示 `code` 欄位。

### 英文：`/settings`

維持子指令模式：

- `info`
- `reload`
- `reset`
- `template`
- `module`
- `logs`
- `music`
- `language`

### `/settings info`（已更新）

- 已移除「總覽」分頁
- 保留下拉選單
- 新增按鈕切換分頁
- 新增「數字接龍」資訊頁籤

## 刪除訊息（已更新）

### 中文：`/刪除訊息`

已改為單一指令選項模式：

- `type: 頻道` 時填 `channel`
- `type: 使用者` 時填 `user`
- `amount` 可選，預設 99

### 英文：`/delete-messages`

維持子指令：

- `channel`
- `user`

## Prefix 指令（保留）

- `!help`
- `!join`
- `!play <關鍵字或URL>`
- `!volume <0-200>`
- `!history`
- `!music`
- `!skip`
- `!stop`
- `!leave`
- `!repeat <off|single|all>`

## 音樂功能（第一階段）

- `/volume` / `/音量`
  - 設定音量範圍 `0-200%`
  - 音量會依伺服器保存
- `/history` / `/播放歷史`
  - 顯示本伺服器最近播放的歌曲
  - 包含來源、時長、點歌者與相對時間
- `/playlist` / `/歌單`
  - 可儲存目前播放歌曲與佇列為歌單
  - 支援載入、刪除與列出已儲存歌單
- `/music stats` / `/音樂 統計`
  - 顯示本伺服器播放次數最多歌曲
  - 顯示最常點歌成員
  - 顯示今日播放時數
- 音樂控制面板強化
  - 顯示封面縮圖
  - 顯示上傳者、來源、時長、播放進度
  - 顯示目前音量與 Autoplay 狀態
 - Web UI 音樂頁面
   - 顯示音樂統計卡片
   - 可快速查看熱門歌曲、熱門點歌成員、今日播放時數與歷史筆數
 - Autoplay 強化
   - 依最近播放歷史避開重複推薦
   - 優先挑選同作者、同風格與較接近的推薦歌曲

## 後續規劃

- Web UI 歌單管理
- 更進一步的 Autoplay 推薦策略與權重調整

## 設定檔

- 全域：`config.yml`
- 伺服器：`guild-configs/<guildId>.yml`
- Bot 語言：`lang/*.yml`
- Web UI 語言：`lang/web/web-*.yml`

## 控制台指令

- `reload`：重新載入設定
- `stop` / `end`：安全關機

## 授權

本專案使用 **GNU GPL v3.0**。

- 授權檔：[LICENSE](./LICENSE)
- 條文：https://www.gnu.org/licenses/gpl-3.0.html
