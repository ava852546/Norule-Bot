# 1.8 單 JVM 啟動與驗收

## 啟動邊界

`java -jar discord-music-bot-1.8.jar` 先進入只依賴 JDK 的 `bootstrap.Main`。
`RuntimeDependencyBootstrap` 讀取內嵌七欄依賴 manifest，取得工作目錄下
`runtime-libs/.bootstrap.lock` 的獨占鎖，驗證 SHA-256，下載缺少或損壞的依賴，
再建立 `URLClassLoader("NoRuleRuntime", ..., ClassLoader.getPlatformClassLoader())`。
主 JAR 在 URL 清單首位，依賴順序使用 manifest 的固定順序；不掃描額外 JAR。
Manifest 在建置時依座標排序，保留既有固定 repository 與 checksum 格式。

設定主執行緒的 Context ClassLoader 後，反射呼叫 `BotMain.main(args)`，由它啟動
`RuntimeBootstrap.run()`。應用類別與第三方依賴位於同一 runtime loader；JDK
平台類別仍由 platform/bootstrap loader 提供。不反射修改 JDK 內部載入器。
IDE / Maven `exec:java` 的 classes 目錄入口沿用開發工具提供的 classpath。

BotMain 返回不代表 Bot 停止；JDA 與其他非 daemon 執行緒仍會運作。runtime loader
與快取鎖保留至 JVM 結束，不在 main 返回或獨立 shutdown hook 中提前關閉。
OS 在程序退出後釋放鎖；保留的 `.bootstrap.lock` 檔案本身不表示鎖仍被持有。
新建執行緒繼承 Context ClassLoader；第三方自行選擇 loader 或 common pool 的路徑
仍須透過目標環境功能驗證。未新增全域 uncaught exception handler，沿用既有執行緒錯誤處理。

## 快取與下載

- 必要依賴全部通過驗證時不發出下載請求，可離線啟動。
- SHA-256 驗證不可由 JVM property 停用；`verify-checksums=false` 會拒絕啟動。
- 不再刪除舊版本、額外或衝突 logging JAR；它們不在 manifest URL 清單時不會載入。
  `norule.bootstrap.cleanup-obsolete` 不再執行清理。
- 保留現有 timeout、stall detection、HTTP 狀態檢查、有限重試與 `.part` 清理。
- 僅在 checksum 正確後原子替換；檔案系統不支援原子替換時明確失敗，避免降級為
  可能中斷的覆寫。請在與正式環境相同的 volume 驗收。
- 座標拒絕路徑分隔符、URL query／fragment；快取目標拒絕越界與 symbolic link。
- 下載來源仍由 repository enum 固定。重新導向最多五次，只允許同 scheme／host／port，
  拒絕 credential-bearing URL 與 HTTPS 降級。不自動信任新的 CDN host。
- 同一快取只允許一個 1.8 程序持有鎖。1.7 不認得這個鎖，因此新舊版本不得同時
  使用相同快取；正式切換與回滾都先正常停止現有程序。

## 建置

使用 JDK 21 編譯，不變更正式環境的 Java 版本、Heap、GC 或 JDA 快取參數：

```powershell
mvn -q -DskipTests compile
mvn clean package
```

Maven 完整 package 仍包含 npm ci、兩個 Nuxt 靜態應用的建置與同步。
依賴建置輸出已改為 `target/runtime-libs/`，不覆寫專案工作目錄的既有 `runtime-libs/`。
JAR 是 thin JAR，不內嵌第三方依賴。正式執行時仍使用工作目錄的 `runtime-libs/`。

若 IDE 會重寫 `target/classes`，可隔離 Maven 的建置產物：

```powershell
mvn "-Dnorule.build.directory=cache/build-1.8" -DskipTests package
mvn "-Dnorule.build.directory=cache/build-1.8" test
```

此時 JAR、依賴、classes 與 surefire-reports 都在 `cache/build-1.8/`。
部分 HTTP 測試需要 Nuxt 靜態產物，乾淨目錄應先 package 產生資源，再執行完整 test。
這不代表略過測試：正式驗收必須檢查第二個命令的結果。

## 已執行與待執行的驗證

本次本地環境：Windows x86-64、Eclipse Adoptium JDK 21。
最終完整 Java 測試：696 個案例，0 failures、0 errors、2 skipped。
兩個 skipped 為未開啟 `bilibili.live` 的 Bilibili 真實播放測試，不算播放驗證通過。
建置與測試日誌位於 git-ignored `cache/bootstrap-verified-package.log`；
個別 XML 報告位於 `cache/build-1.8/surefire-reports/`。
最初缺少前端靜態產物時一個既有 HTTP 測試出現 EOF，完整產生靜態資源後已通過；
新增程序測試的 Windows 日誌解碼問題亦已修正並重新執行通過。

| 範圍 | 方法與驗收界線 |
|---|---|
| 依賴同步 | 單元測試涵蓋快取重用、損壞重下載、checksum 不符、下載失敗清除暫存、逾時重試與固定來源；HTTP 下載以本地假 repository 執行。 |
| 載入器 | 測試固定 URL 順序、排除額外 JAR、platform 模組、TCCL 與新執行緒繼承、原始例外還原、main 返回後資源仍可讀取。 |
| 同程序／退出 | `PackagedBootstrapTest` 使用實際 `java -jar` 與假 Bot，比對入口 PID、重啟零下載、shutdown hook 資源讀取與退出碼 75；沒有登入 Discord。 |
| 本機相容性 | `RuntimeCompatibilityTest` 在 platform-parent 隔離 loader 中載入 JDA、LavaPlayer、LavaSrc、libdave adapter、Jackson、MySQL，檢查 Logback／SLF4J、JDBC／Rhino SPI，實際執行 SQLite/Hikari 記憶體資料庫查詢與 JNA、Opus、Lava、DAVE native 載入。 |
| 正式 JAR | 檢查 Maven version=1.8、Main-Class、Enable-Native-Access、內嵌 manifest 與小於 50 MB。 |
| Linux／Temurin 25 Native | **未驗證**；Windows native 載入不能代表 Linux、musl、aarch64 或音訊解碼成功。 |
| Discord Gateway | **未驗證**；IDENTIFY、RESUMED、重連、Slash／Prefix、事件與 REST 需測試 Bot 帳號。 |
| 四路音樂／FFmpeg | **未驗證**；需驗證 YouTube、Bilibili、Companion、四個 Guild 同時播放、切歌／暫停／離開、native 解碼與閒置釋放。FFmpeg 依實際 Companion 部署追蹤。 |
| Wings／Docker | **未驗證**；SIGTERM、console stop、停止逾時、退出碼 75 後容器重啟、持久化額度與記憶體限額需獨立測試環境。 |
| 效能 | **未量測**；不保證節省 58 MiB。需在相同 JVM 參數、Guild 數與四路播放負載下比較容器記憶體、RSS、Heap、GC、CPU 與執行緒。 |

原有 `GatewayMonitoringRuntime`、`GatewayMonitorService`、重啟額度及
`RuntimeBootstrap` shutdown hook 未修改。正常停止與人工停止沿用既有流程；
持續停滯仍經證據確認、冷卻與持久化額度後 `System.exit(75)`，現在直接交給 Wings。
合成退出碼測試不等於 Wings 整合驗證通過。

## 獨立環境正式切換前檢查

1. 保留現有 1.7 JAR、內嵌 manifest、匹配的完整 `runtime-libs/` 與設定／資料備份。
2. 在獨立工作目錄放置 1.8 JAR 與建置產生的依賴副本；不要讓測試程序使用正式 token、
   正式資料庫、媒體目錄或正在播放的 Bot。
3. 使用相同 Ubuntu image、Temurin 25、volume 與 JVM 記憶體參數，先測空快取下載，
   再測完整快取的無下載啟動。下載失敗／checksum 錯誤測試使用拋棄式目錄。
4. 完成上表所有待驗證案例。每個案例記錄環境、結果、時間、日誌、失敗原因與影響。
   確認程序樹只剩一個 Java 程序；非 Java 的 FFmpeg／Companion 依原部署保留。
5. 安排停機窗口，正常停止正式 Bot 並等待清理完成。不要在仍有音樂播放時直接重啟。
6. 由維運切換至 1.8 JAR，保留既有 Heap／GC 與 native-access manifest。
   使用固定 WorkingDirectory，依賴目錄為該目錄的 `runtime-libs/`。
   不用 `|| true` 或無限迴圈吞掉退出碼，不自動重設 Gateway 恢復額度。
7. 驗證停止命令、SIGTERM、Wings crash detection 及重啟額度；參閱 `deploy/README.md`。
   本次沒有更改 Startup 設定或操作正式環境。

## 回滾

1. 正常停止 1.8，確認 Java 程序已退出；不要在活躍 loader 使用依賴時覆寫快取。
2. 切回備份的 1.7 JAR 與匹配依賴工作目錄。若 1.8 曾修復同名損壞依賴，使用備份
   還原完整快取，而不是只更換 JAR。不要自動刪除恢復額度檔。
3. 沿用原本 JVM 參數與 `java -jar discord-music-bot-1.7.jar` 啟動；1.7 會走原有雙 JVM 流程。
4. 檢查登入、指令、音樂與停止流程。若失敗保留日誌，先診斷再人工重試。

本機實作前的回滾副本保存在 git-ignored `cache/rollback-1.7/`：包含原 JAR
及完整 runtime-libs。舊 JAR SHA-256：
`d87808c7dca383f494d3ada1f73ccaeae9b2aafcebcf04dc19cb0cd205f5183b`。
此副本僅是本機保留；正式部署仍須另行保存正式版本與其設定／資料。

## 修改檔案

- `pom.xml`：版本 1.8、依賴建置輸出與可隔離的 Maven build directory。
- `src/main/java/com/norule/musicbot/bootstrap/Main.java`：JDK-only 啟動與致命啟動錯誤退出。
- `src/main/java/com/norule/musicbot/bootstrap/BotMain.java`：新增應用入口。
- `src/main/java/com/norule/musicbot/bootstrap/RuntimeDependencyBootstrap.java`：同 JVM 載入、TCCL、
  原始例外還原、程序生命週期快取鎖、座標／路徑檢查與保留舊快取。
- `src/main/java/com/norule/musicbot/bootstrap/RuntimeDependencyDownloader.java`：受限重新導向。
- `src/test/java/com/norule/musicbot/bootstrap/RuntimeDependencyBootstrapTest.java`：保留快取的預期。
- `src/test/java/com/norule/musicbot/bootstrap/RuntimeDependencyDownloaderTest.java`：重新導向拒絕案例。
- 同測試目錄新增 `RuntimeClassLoaderTest.java`、`PackagedBootstrapTest.java`、
  `RuntimeCompatibilityTest.java`、`RuntimeCompatibilityProbe.java`。
- `README.md`、`deploy/README.md`、本文件：版本、流程、驗收與部署／回滾指引。
