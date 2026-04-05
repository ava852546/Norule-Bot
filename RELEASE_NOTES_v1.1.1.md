## NoRule Bot v1.1.1

這個版本主要聚焦在音樂控制面板體驗、中文語系修復，以及執行期檔案管理整理。

### 重點更新

- 音樂控制面板新增封面顯示
- 面板新增音量增減按鈕，並支援即時刷新
- 音量按鈕會依 `0%` / `200%` 自動停用
- 面板互動按鈕加入冷卻提示
- 補回完整繁中與簡中主語言檔
- 修復多個語言鍵顯示成鍵名或亂碼的問題
- `.gitignore` 補齊執行期資料與本機 Maven 目錄

### 音樂面板

- 顯示歌曲封面大圖
- 如果來源本身沒有提供封面，YouTube 會自動推導縮圖
- 新增音量按鈕：
  - `🔉 降低音量`
  - `🔊 增加音量`
- 點擊音量按鈕後會立即刷新面板
- 音量達邊界時會自動停用按鈕：
  - `0%` 停用降低音量
  - `200%` 停用增加音量
- 面板按鈕若碰到冷卻，會提示使用者冷卻中

### 語言與本地化

- 重建完整 `zh-TW.yml` 與 `zh-CN.yml`
- 補齊主要區塊翻譯：
  - `music`
  - `help`
  - `settings`
  - `room_settings`
  - `delete`
  - `logs`
  - `notifications`
  - `message_logs`
  - `warnings`
  - `anti_duplicate`
  - `moderation`
  - `number_chain`
  - `ticket`
  - `ping`
- 修復以下常見問題：
  - `settings.language_menu_title`
  - `settings.language_menu_desc`
  - `ping.title`
  - `music.queue_added`
  - 音樂面板欄位與按鈕未正確中文化

### 專案整理

- 版本號更新為 `1.1.1`
- README 執行範例更新為 `discord-music-bot-1.1.1-all.jar`
- `.gitignore` 新增：
  - `.m2/`
  - `cache/`
  - `guild-moderation/`
  - `guild-music/`
  - `guild-tickets/`
  - `tmp-settings.xml`
  - `config.backup-*.yml`

### 建議更新後操作

1. 重啟 Bot
2. 重新建立新的音樂控制面板
3. 測試 `/設定 -> 語言`、`/延遲`、`/歌單`、音量按鈕與封面顯示
