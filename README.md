# Discord 音樂與伺服器管理機器人（Java）

- [重點功能與指令教學](./重點功能與指令教學.md)

這是一個使用 `JDA 6` + `LavaPlayer` 的 Discord Bot，功能包含：
- 音樂播放（YouTube 關鍵字/URL、Spotify 連結轉播）
- 互動式音樂控制面板（按鈕）
- 音樂 Autoplay（自動推薦下一首）
- 成員/語音進出通知（可模板化）
- 訊息編輯/刪除日誌
- 伺服器事件日誌（身分組/頻道/封禁/踢出/指令）
- 刪除訊息指令（含確認按鈕、跨頁搜尋）
- 私人語音包廂（自動建立/清除）
- 多語系（預設英文、繁體中文）
- 每個伺服器獨立設定檔（`guild-configs/<guildId>.yml`）

---

## 1. 環境需求

- Java 17+
- Maven 3.9+
- Discord Bot Token

---

## 2. 啟動方式

```powershell
mvn clean compile exec:java
```

打包（建議正式環境使用）：

```powershell
mvn clean package -DskipTests
```

輸出檔案：
- `target/discord-music-bot-1.0.0.jar`（一般 jar）
- `target/discord-music-bot-1.0.0-all.jar`（含相依套件，建議 Pterodactyl 使用）

啟動範例：

```powershell
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.0.0-all.jar
```

---

## 3. 主要設定檔

### `config.yml`
全域預設設定（新伺服器會繼承）：
- `token`: Discord Bot Token
- `prefix`: 前綴指令（例如 `!`）
- `commandGuildId`: 開發時可指定單一伺服器快速更新 Slash 指令
- `guildSettingsDir`: 各伺服器設定資料夾（預設 `guild-configs`）
- `languageDir`: 語系檔資料夾（預設 `lang`）
- `defaultLanguage`: 預設語言（`en` 或 `zh-TW`）
- `commandDescriptions`: Slash 指令簡介鍵值（可在 `config.yml` 直接覆寫）

### `guild-configs/<guildId>.yml`
每個伺服器自己的設定，包含：
- `language`
- `notifications`
- `messageLogs`
- `music`
- `privateRoom`

`messageLogs` 主要欄位（功能開關預設 `true`，頻道 ID 預設空）：
- `channelId`（預設日誌頻道）
- `commandUsageChannelId`（指令使用日誌獨立頻道，可不設）
- `channelLifecycleChannelId`（頻道建立/刪除日誌獨立頻道，可不設）
- `roleLogChannelId`（身分組變更日誌獨立頻道，可不設）
- `moderationLogChannelId`（封禁/踢出日誌獨立頻道，可不設）
- `roleLogEnabled`
- `channelLifecycleLogEnabled`
- `moderationLogEnabled`
- `commandUsageLogEnabled`

`privateRoom` 主要欄位：
- `enabled`
- `triggerVoiceChannelId`
- `userLimit`
- 包廂分類會自動跟隨「觸發語音頻道」的父分類，不需要 `categoryId` 設定

預設行為：
- module 相關功能預設為啟用
- 若未設定對應通知/日誌頻道，Bot 會自動忽略該功能，不會報錯

---

## 4. 指令總覽

## 4.1 一般/音樂
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

## 4.2 刪除訊息
- `/delete-messages channel channel:<頻道> amount:<1-99(可省略)>`
- `/delete-messages user user:<使用者> amount:<1-99(可省略)>`
- `/刪除訊息 ...`（中文別名）

特性：
- 送出後會先顯示「確認/取消」按鈕，避免誤刪
- 支援跨多頁歷史訊息搜尋（不只最近 100 則）
- 只會刪除 14 天內訊息（Discord API 限制）
- 若未輸入 `amount`，預設使用系統上限 `99`，並提示使用者

## 4.3 設定指令 `/settings`
- `/settings info`：查看當前伺服器設定摘要
- `/settings reload`
- `/settings language code:<en|zh-TW>`

`template` 子指令：
- `/settings template`（開啟下拉選單）
- 在下拉選單選擇：`member-join / member-leave / voice-join / voice-leave / voice-move`
- 選擇後會彈出表單編輯模板

`logs` 子指令：
- `/settings logs`（先開選單，再選要設定的日誌類型，接著用頻道選擇器挑文字頻道）
- 可設定項目：`default-channel（預設日誌頻道） / messages-channel（訊息編輯/刪除日誌） / member-channel / voice-channel / command-usage-channel / channel-events-channel / role-events-channel / moderation-channel`
- `member-channel` 會先出現子選單：
  - `進入/離開 在同一個頻道`：設定同一個文字頻道
  - `進入/離開 不在同一個頻道`：再選擇要設定「加入」或「離開」通知頻道，各自指定文字頻道
- 身分組變更、頻道建立/刪除、封禁/踢出、指令使用日誌都可獨立設定頻道（可選）
- `default-channel` 僅作為預設 fallback 頻道，不綁定特定日誌類型
- 若獨立頻道未設定，會自動回退到 `default-channel`（預設日誌頻道）
- 若成員進出通知頻道/語音通知頻道未設定，也會回退到 `default-channel`
- 啟用日誌模組前，需先設定 `default-channel`

`music` 子指令：
- `/settings music`（開啟下拉選單）
- 選單項目：
  - `自動離開啟用`（切換開關）
  - `自動離開分鐘`（表單輸入 1-60）
  - `Autoplay 推薦啟用`（切換開關）
  - `音樂指令頻道`（選擇文字頻道）
  - `私人包廂觸發頻道`（選擇語音/Stage 頻道）
- 設定 `私人包廂觸發頻道` 時，系統會自動啟用私人包廂模組（避免已設頻道但功能關閉）
- `/private-room-settings` 或 `/包廂設定`（下拉選單：上鎖頻道 / 設定人數 / 更改頻道名稱）
- `設定人數`、`更改頻道名稱` 送出後，會直接刷新同一個「包廂設定面板」與選單（可連續操作）
- `/settings reset`（下拉選單：重設語言 / 通知 / 日誌 / 音樂 / 私人包廂 / 全部）

補充：
- 若未設定 `command-channel`，音樂指令預設可在所有頻道使用。
- 私人包廂會自動使用「觸發語音頻道」的類別建立，不需另外設定分類。
- `/settings info` 已美化成分頁面板（含圖示與目前值/預設值對照）。
- `日誌` 分頁會完整顯示所有擴充日誌開關狀態。
- `Autoplay` 會優先嘗試 YouTube 相關播放清單（避免重複同曲），失敗時會 fallback 關鍵字推薦。
- 若 `Autoplay` 找不到或載入失敗，會在音樂控制面板顯示提示訊息。

`/settings info` 日誌分頁會顯示：
- 基礎日誌總開關與日誌頻道
- 身分組變更日誌頻道（可獨立於一般日誌頻道）
- 封禁/踢出管理日誌頻道（可獨立於一般日誌頻道）
- 指令使用日誌頻道（可獨立於一般日誌頻道）
- 頻道事件日誌頻道（可獨立於一般日誌頻道）
- 身分組變更日誌
- 頻道建立/刪除日誌
- 封禁/踢出管理日誌
- 指令使用日誌

`module` 子指令：
- `/settings module`（開啟下拉選單）
- 可切換項目（對應 config 主要開關）：
  - `通知啟用 (notifications.enabled)`
  - `訊息日誌啟用 (messageLogs.enabled)`
  - `成員加入通知`
  - `成員離開通知`
  - `語音日誌`
  - `指令使用日誌`
  - `頻道建立/刪除日誌`
  - `身分組變更日誌`
  - `封禁/踢出管理日誌`
  - `音樂自動離開啟用 (music.autoLeaveEnabled)`
  - `音樂 Autoplay 啟用 (music.autoplayEnabled)`
  - `私人包廂啟用 (privateRoom.enabled)`

補充：
- `/settings info` 已改為下拉式分頁面板，可切換 `總覽 / 通知 / 模板 / 日誌 / 音樂 / 私人包廂 / 模組`。

---

## 5. 音樂搜尋下拉選單

當 `/play` 使用「關鍵字」而非 URL 時：
- Bot 會列出前 10 項搜尋結果（下拉選單）
- 選單顯示：來源、歌曲長度、作者
- 選單 30 秒後自動失效
- 失效後會提示使用者重新執行 `/play`

## 5.1 音樂控制面板顯示

- 面板上方摘要現在使用：
  - `頻道`：會顯示機器人目前所在語音頻道的「頻道標記」（例如 `<#123...>`）
  - `列隊`：顯示當前待播數量
- 面板下方 `列隊` 欄位會顯示前幾首待播歌曲清單

---

## 6. 模板表單與預覽

通知模板改為「表單輸入」流程（非直接參數輸入），提交後會回覆：
- 儲存結果
- 套用站位符後的預覽內容（Embed）

成員加入/離開模板可在表單中一併設定 Embed 顏色（Hex）：
- `#RRGGBB`
- `RRGGBB`

常用站位符：
- 成員模板：`{user}` `{username}` `{guild}` `{id}` `{tag}` `{isBot}` `{createdAt}` `{accountAgeDays}`
- 語音模板：`{user}` `{channel}` `{from}` `{to}`

---

## 7. 私人包廂行為

啟用後，使用者加入觸發語音頻道時會：
- 自動建立「(使用者名稱) 的包廂」語音頻道
- 給該使用者管理該頻道所需權限
- 可透過 `/private-room-settings` 或 `/包廂設定` 即時上鎖、改人數、改名稱
- 該包廂無人時自動刪除

---

## 8. 權限需求（重點）

敏感操作都已加上權限檢查：
- `/settings ...` 需要 `MANAGE_SERVER`
- `/delete-messages` / `/刪除訊息` 需要 `MESSAGE_MANAGE`

建議 Bot 本身至少具備：
- `View Channels`
- `Send Messages`
- `Manage Messages`（若要刪訊息）
- `Connect` / `Speak`（音樂）
- `Manage Channels`（私人包廂）
- `Move Members`（私人包廂移動）

---

## 9. Pterodactyl 部署建議

建議用打包後執行：
1. `mvn clean package`
2. 於面板啟動 `java -jar target/<你的jar檔名>.jar`

如果你是用 `exec:java` 也可開發測試，但正式環境通常建議用 `jar` 啟動較穩定。

---

## 10. 法務文件

- [隱私權政策](./隱私權政策.md)
- [服務條款](./服務條款.md)
