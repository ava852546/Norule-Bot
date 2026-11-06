# Gateway 與 JVM 監控

監控在 JDA 建立前註冊，使用獨立排程器，不依賴音樂指令排程。Gateway 告警與真正的監控／語音操作錯誤保留 console／`data.logDir` 檔案日誌；JVM 心跳與 VoiceAutoLeave 例行事件改為指定 Discord 頻道。所有時間門檻使用單調時鐘，系統校時不會改變離線時長。這些設定於啟動時讀取，修改後需重新啟動 Bot。

## 設定

`config.yml` 的 `discord.monitoring`（舊設定會使用預設值）：

```yaml
discord:
  monitoring:
    resourceIntervalSeconds: 60
    offlineWarningSeconds: 300
    warningRepeatSeconds: 300
    recoveryEnabled: false
    restartAfterSeconds: 1800
    stalledReconnectSeconds: 1800
    maxRecoveryRestarts: 3
    restartCooldownSeconds: 60
  logging:
    jvmHeartbeat:
      channelId: "0"
      deliveryMode: UPDATE_LATEST
    voiceAutoLeave:
      channelId: "0"
```

每 10 秒檢查離線狀態，因此 5 分鐘告警最遲於下一次檢查發出。持續離線每 5 分鐘再告警，成功連線後重設。告警寫入本機 WARN 日誌，請由日誌平台或程序管理器轉送給維運人員；Gateway 離線時不依賴 Discord 訊息通知。此變更不會自動安裝或啟動服務。

## 日誌欄位

- `[Gateway] event=CONNECTED/DISCONNECTED/RECONNECTING/IDENTIFY`：保留 JDA 原始 `status`、上一狀態與重連次數。`IDENTIFY` 表示 JDA 的 `IDENTIFYING_SESSION` 階段，可能是新登入或恢復流程，實際 Session 結果以下列事件為準。
- `RECONNECT_RESULT`：逐次記錄 `attempt`、`SUCCESS/FAILED`、斷線原因與 server/client WebSocket 關閉代碼。無 close frame 時代碼為 `null`；無原因時明確標示 transport／unknown，不能據此推斷是 Discord 或本機網路故障。底層網路錯誤細節仍以 JDA 自身日誌為準。
- `SESSION_CONNECTED`：`INITIAL_READY` 為首次登入；`RESUMED`／`resumed=true` 表示原 Session 恢復；`RECREATED`／`resumed=false` 表示新 Session。記錄整段離線秒數與嘗試總數，不輸出 Token、Session ID 或 Gateway payload。
- `OFFLINE_WARNING`：包含離線秒數、多久無進展、原始狀態、關閉代碼及是否允許自動恢復。
- `JVM Resource Monitor` Embed：程序／主機 CPU 百分比、heap 使用／配置／上限、non-heap 使用量（MiB）、GC 次數與收集耗時差值、live／daemon／peak 執行緒數與各執行緒狀態計數。無法取得的數值顯示 `N/A`；時間戳為取樣時間。
- GC 停頓使用 JDK JFR `jdk.GCPhasePause`：每個紀錄區間的停頓次數、總毫秒與最大毫秒。JFR 事件非同步送達，會有短暫延遲；GC collection time 與 stop-the-world pause 是分開的欄位。JFR 無法使用時，停頓值為 `N/A`，MXBean GC 計數仍保留。JFR 只開啟 GC pause 事件，不收集 stack trace，保留上限 2 分鐘／32 MiB。

## 運行日誌頻道

兩個 `channelId` 預設為字串 `"0"`，空白、未設定或無效 ID 也不發送；停用時不輸出例行 INFO。請填入文字頻道 ID，並給 Bot `VIEW_CHANNEL`、`MESSAGE_SEND`、`MESSAGE_EMBED_LINKS` 權限。更新舊心跳另需能讀取該訊息（Discord 的 `MESSAGE_HISTORY` 權限）。所有發送皆禁止 Mention；頻道失效、權限不足或離線不會中止監控與語音退出。

`UPDATE_LATEST` 將心跳訊息 ID 保存在設定檔目錄下的 `data/monitoring/jvm-heartbeat-<channelId>.id`，重新啟動後驗證作者、標題及 footer，才更新本 Bot 的心跳。訊息刪除後於後續取樣重建；不掃描或修改頻道中的其他訊息。請持久化此狀態檔，遺失時會建立新訊息。`NEW_MESSAGE` 每次新增，不保存訊息 ID。

發送器每 5 秒處理一次，最多一項 REST 操作進行中；待送心跳合併為最新一份，語音佇列上限 64 筆，超量淘汰最舊事件，超過 5 分鐘的事件略過。離線時捨棄待送例行日誌，恢復後發送新取樣／事件，避免補送洗版。429 交由 JDA 原生機制處理；發送器不自行重試。發送錯誤只保留節流後的本機 WARN，不回送 Discord。

VoiceAutoLeave 保留目前事件名稱 `TIMER_STARTED`、`TIMER_CANCELLED`、`DISCONNECTED`，嚴重斷線操作失敗可另送紅色 `ERROR`，本機 WARN 仍保留。事件只包含實際取得的伺服器、語音頻道、人數、原因及適用時間欄位；空房 10 秒、Bot 不計入人數、閒置或暫停 600 秒、返回取消與各伺服器獨立倒數皆不變。MODERN／LEGACY 面板原有通知與清理行為不變。

## Discord Token 與設定升級

憑證來源順序為 `DISCORD_TOKEN` → 實際設定檔同目錄的 `token`。檔名沒有副檔名，內容為單行 UTF-8 純文字，不加 `token:`、引號或等號；可有 BOM、首尾空白及最後 CRLF／LF，但拒絕內部空白與多行內容。不會輸出憑證原文。缺少時顯示：

```text
[NoRule] Discord Token not found. Please create a 'token' file next to config.yml or set DISCORD_TOKEN.
```

舊版根層 `token:` 會在設定初始化時遷移：先以不覆寫方式建立同目錄 `token`，重新讀取確認一致，才移除 YAML 欄位。Unix 檔案系統支援時，新檔使用 `0600`。既有 `token` 不同、不可讀或建立失敗時停止設定更新，保留舊 YAML，需人工核對。即使環境變數已設定，也不會直接丟棄舊 YAML 憑證。

手動遷移時先停止 Bot，把 YAML 裡的 Bot Token 值放入同目錄 `token`，確認可讀後刪除根層 `token:`，再啟動。不要將檔案內容貼到日誌、API 或版本控制。Config Parser 不再以 YAML 為長期憑證來源；沒有 `BOT_CONFIG_PATH` 時，依序選擇工作目錄、以及從 `target` 啟動時的父目錄中存在的 `config.yml`，不再依 Token 內容挑選設定檔。

設定自動合併保留使用者值，補入預設停用的 `discord.logging`，不重建 `token:`。新產生的自動備份移除根層 Token，並遮蔽已辨識的秘密欄位（Token、密碼、Secret、Cookie、API Key、spDc、JDBC URL），不保存原始註解。備份中的遮蔽值不能直接當作有效憑證還原。舊版本已產生的備份不會自動修改，請自行檢查並安全處理。

Docker 建議掛載整個設定目錄，例如將持久化目錄掛到 `/app/config` 並設定 `BOT_CONFIG_PATH=/app/config/config.yml`，憑證位於 `/app/config/token`；亦可單獨將 Token 唯讀掛載至該位置，事先完成手動遷移。需要自動升級 YAML 時，設定目錄須可寫。Pterodactyl 通常使用 `/home/container/config.yml` 與 `/home/container/token`，可透過 File Manager 建立無副檔名檔案；若自訂 `BOT_CONFIG_PATH`，Token 必須跟著設定檔目錄移動。環境變數部署仍可沿用 `DISCORD_TOKEN`，不要把值寫入 Startup Command。

## 恢復策略與保護條件

JDA 自動重連保持啟用，不因離線 5 分鐘就重啟。`recoveryEnabled` 預設為 `false`，應先確認 Pterodactyl／Wings 的 crash detection 可接手程序重啟，再明確開啟。

只有曾成功登入、連續離線超過 `restartAfterSeconds`，且符合以下任一證據，才提出一次 `RECOVERY_REQUEST`：

1. 收到 JDA `ShutdownEvent`，關閉代碼明確允許重連，且非正常關閉；JDA 已停止運作。
2. 至少曾進入一次重連，JDA 實際狀態與事件狀態一致，重連相關狀態持續沒有進展。等待門檻為 `max(stalledReconnectSeconds, 2 × JDA max reconnect delay + 60)` 秒。這是「重連流程停滯」的操作判定，並非宣稱 Discord 或網路一定健康。

正常持續重試會更新進展時間，即使長時間斷網也不重啟。Session controller 的 `RECONNECT_QUEUED`、仍在載入 subsystems、首次登入未成功、登入失敗、不可重連的 fatal code、未知 shutdown code，以及人工關機都不觸發自動重啟。持續嘗試但無法連線時，保留 WARN 告警，交由維運檢查網路、Discord 狀態、憑證與限流。

符合條件後先冷卻 `restartCooldownSeconds`（最少 60 秒），再重新檢查：若已連線或重連有進展，取消退出。仍符合條件時，先持久化保留一次重啟額度，再以退出碼 **75** 呼叫現有 JVM shutdown hook，停止監控並清理資源，由 Wings 啟動新容器／程序。Bot 不會自行建立新的 JDA 或遞迴啟動 JVM。若清理卡住，需由程序管理器的停止逾時或維運處理，不使用 `Runtime.halt` 強制略過清理。

## Pterodactyl／Wings 部署

沿用目前的 Java 21 egg 與 Startup Command，不需要在容器內安裝 systemd、Docker daemon 或額外 shell supervisor。範例（依實際 JAR 位置調整，保留既有 JVM 記憶體參數）：

```sh
java -Dfile.encoding=UTF-8 -jar target/discord-music-bot-1.8.jar
```

確認 egg 的停止命令為 `stop`（或使用能送出正常終止訊號的設定），讓人工停止走既有清理流程。1.8 的 dependency bootstrap 與 Bot 使用同一 JVM，退出碼直接交給容器入口；不再啟動或等待子 JVM。不要用 `|| true`、無限 `while` 或額外 restart script 吃掉退出碼，也不要配置固定排程反覆重啟已被額度保護的 Bot。

Wings 的 crash detection 應由節點管理員檢查。官方設定位於節點的 `/etc/pterodactyl/config.yml`，並非 Bot 的 `config.yml`：

```yaml
system:
  crash_detection:
    enabled: true
    detect_clean_exit_as_crash: false
    timeout: 60
```

`enabled` 允許 Wings 接手非正常退出；`detect_clean_exit_as_crash: false` 讓正常退出不算崩潰；`timeout` 是兩次崩潰太接近時的停止保護，**不是**每次重啟的冷卻等待，也不是累積次數上限。上例屬於整個 Wings 節點設定，會影響該節點其他伺服器；應由節點管理員依現有政策決定，不應直接覆寫。沒有節點權限時請交由託管方確認。此變更沒有自動操作 Pterodactyl 或修改節點設定。

## 持久化重啟額度

額度檔案為 **Bot 設定檔所在目錄下**的 `data/monitoring/gateway-recovery.count`。在標準 Pterodactyl 部署中應位於持久化的 `/home/container` 內；如使用 `BOT_CONFIG_PATH`，請確認該設定目錄同樣持久化且可寫。不要放在 `/tmp` 或隨容器銷毀的目錄，也不要在 Startup Command 中刪除它。

預設總共允許 **3 次 Gateway 恢復退出**，容器／JVM 重啟、成功恢復與重新登入均不會自動歸零。`maxRecoveryRestarts` 可設定 0–10，0 表示完全不給額度。額度耗盡或額度檔無法寫入／損毀時，記錄 `RECOVERY_BLOCKED`，Bot 保持現有程序、繼續離線 WARN，**不再退出讓 Wings 重啟**。因此 Panel 仍可能顯示容器 Running；Discord 連線健康應以 Gateway 日誌判斷。此上限只涵蓋 Gateway 主動提出的恢復，不替代 Wings 對 OOM、外部 kill 或其他程序崩潰的管理。

需要重設時先在 Panel 停止 Bot，檢查斷線原因與資源紀錄，再透過 File Manager 把該檔內容改成單一 `0`（或刪除該檔），然後人工啟動。不要自動定期清空額度。若保留額度後剛好恢復或程序被外部停止，已保留的額度仍會計入，採保守方式避免超額重啟。

參考：[JDA SessionDisconnectEvent](https://docs.jda.wiki/net/dv8tion/jda/api/events/session/SessionDisconnectEvent.html)、[Wings 設定](https://docs.pterodactyl.io/v1/wings/configuration)、[egg 啟動與停止設定](https://docs.pterodactyl.io/v1/guides/egg-creation/egg-config-parser)。
