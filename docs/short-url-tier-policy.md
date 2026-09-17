# 短網址匿名／登入分級政策

## 原有架構與問題

- 獨立 `ShortUrlGatewayServer` 與 Dashboard `ShortUrlWebService` 均接受 `POST /api/short`；兩者共用核心 `ShortUrlService`、SQLite/MySQL repository、domain validation。
- Discord OAuth 在 Web 層取得帳號 ID，`WebSessionManager` 從 `norule_session` 找有效 Session，gateway 透過 bootstrap 注入的 resolver 取同一個使用者身分。失效 Session 在建立時視為匿名，在私人 API 則回 401。
- `owner_user_id` 已存在，匿名沿用空字串，IP 從未作為 Owner。`created_at`、`expires_at` 與不分大小寫的唯一性保護已存在。
- `/{code}?stats` 與 `/api/short/{code}/stats` 原本就檢查 Session＋Owner；`/api/short/mine` 只列出自己擁有的內容。未找到既有修改／刪除 HTTP API。
- 原本 `RateLimitService` 為短網址 IP 30/min、User 60/min，沒有匿名 hour/day 與登入 API daily quota。Web 入口還呼叫舊 `ShortUrlCreationGuard.checkRequest`，獨立 gateway 卻沒有，造成入口政策不一致。
- 舊 creation guard 預設匿名 10/min、50/10min、200 成功建立/UTC day；登入 30/min、150/10min、500 成功建立/UTC day。兩個限流器都在記憶體，沒有 Redis 或持久化計數。
- 匿名原本可指定 custom code；JSON 的空 customCode 也會蓋掉 code/slug 別名。本次統一選擇第一個非空別名。

## 最終政策與 API

| 操作 | 匿名 | 有效 Session |
| --- | --- | --- |
| GET /{code} | 公開 redirect，不檢查登入 | 相同 |
| POST /api/short | 自動短碼；Owner 空字串 | 自動／自訂短碼；Owner 取 Session |
| GET /api/short/session | 200，登入狀態與公開 Turnstile 設定 | 相同 |
| GET /api/short/mine | 401 | 只列出自己的內容 |
| GET /api/short/{code}/stats | 401 | Owner 200；其他使用者 403；不存在 404 |
| GET /{code}?stats | 導向既有登入流程 | Owner 頁面；其他使用者 403 |
| PATCH /api/short/{code} | 401 | 只修改自己的短網址目標 |
| DELETE /api/short/{code} | 401 | 只刪除自己的短網址 |
| 媒體 config／metadata／內容 | 沿用公開／密碼規則 | 沿用原規則 |

POST 接受原本 JSON／form 格式。`url` 必填，HTTP/HTTPS only，最多 8192 字元；body 上限 16 KiB。customCode／code／slug 非空值需登入，自訂短碼沿用 3–32 字元、大小寫正規化、保留路徑與 DB 唯一性。Client 傳入的 Owner 不生效。碰撞 409、body 過大 413、無效 URL 400。兩個建立入口共用 `ShortUrlCreationWebService`，沒有複製核心服務。

PATCH 接受 `application/json`，body `{"url":"https://example.com/new"}`；DELETE 無需 body。兩者可由獨立 gateway 與 Dashboard 的 `/api/short/{code}` 使用。管理操作不作用於媒體，亦未加入 Admin 豁免。SQL 同時核對 code、Owner、created_at，避免權限檢查後資源被替換時錯誤修改。修改不變更 code、Owner、統計、建立／到期時間。成功回傳 `{"success":true}`。

## Rate Limit 與設定

沿用 `shortUrl.abuseProtection.rateLimit`：

```yaml
shortUrl:
  abuseProtection:
    rateLimit:
      enabled: true
      shortUrlRequestsPerMinutePerIp: 10
      shortUrlRequestsPerHourPerIp: 30
      shortUrlRequestsPerDayPerIp: 100
      shortUrlRequestsPerMinutePerUser: 20
      shortUrlAuthenticatedRequestsPerMinutePerIp: 60
      shortUrlApiRequestsPerDayPerUser: 200
      mediaConcurrencyPerIp: 2
      mediaConcurrencyPerUser: 3
      trustedProxyCidrs:
        - "127.0.0.1/32"
        - "::1/128"
  anonymous:
    expirationEnabled: false
    expirationDays: 30
    turnstile:
      enabled: false
      siteKey: ""
```

- 匿名建立：IP 10/min＋30/hour＋100/day，三層滾動窗口同時存在。
- 登入建立：User 20/min＋獨立 shared-IP 60/min，不消耗匿名 IP bucket。
- 短網址私人 API 共用 User 200/day：create、mine、stats、PATCH、DELETE；不計 Session polling、公開頁面、redirect，也不套到其他 Dashboard API。
- 計數是依序通過各層時扣除；無效 body／URL 與去重重用也計入已通過的 admission quota，較早被拒的請求不再扣後續窗口。
- 媒體每分鐘／每日計數保持獨立，避免改變現有上傳配額。只有匿名短網址建立加入原本 IP 並行池；兩個建立／上傳操作會佔滿預設 IP 2。
- 舊 `abuseProtection.creation` 保留額外 request／成功建立防護。登入預設 10 分鐘額度由 150 調整為 200，避免壓低新的 20/min；其他舊預設保持。明確配置較低的 legacy limits 仍生效。
- 所有限值至少 1，沿用既有 clamp 慣例；reload 更新設定並保留既有計數。
- 429 一律含 Retry-After、error/errorCode=RATE_LIMITED、message、retryAfter、retryAfterSeconds，不回傳內部 bucket／exception。RateLimit-Limit/Remaining/Reset 未加入，現有 store 沒有該 metadata。
- 計數與並行 lease 在單一程序記憶體內，重啟重設，無跨副本共享保證。

## Session、Proxy 與前端相容性

Cookie 維持 HttpOnly、SameSite=Lax；外部 Web base URL 為 HTTPS 時設定 Secure；沒有新增跨子網域 Domain 或 SameSite=None。OAuth 與 Session handoff 不變。

兩個入口均使用 `ClientAddressResolver` 與相同 trustedProxyCidrs 配置。只有直接 peer 可信時才由右到左遍歷 XFF，遇到第一個不可信 hop 即停止。CF-Connecting-IP、X-Real-IP 不作為 client identity；Nginx 必須安全地整理 XFF 並設定實際可信 hop，不能直接信任外部傳入值。新增 CF header 測試；原有可信代理、偽造 XFF、多 hop 測試持續通過。

匿名首頁自動呼叫 `/api/short/session` 與媒體 `/api/short/image/config`，仍為公開；分享頁使用公開 metadata、password access、媒體 content。私人 stats 頁呼叫 owner stats，my-content 先查 Session 再讀 mine。Dashboard 仍使用 `/api/me`、guild、settings、i18n 等既有 API，沒有全域 Authentication middleware 改動。

前端共用 Session 狀態；匿名顯示登入說明，登入後顯示自訂短碼。前端隱藏不構成授權，後端仍強制檢查。保留 Java 提供 API／OAuth／Session／靜態內容，不需要 Nuxt production server。修改／刪除本次提供 API，未新增管理頁按鈕。

## 到期、資料庫與認領

不需 DB migration，也不重建資料庫。既有 URL、Owner、unique constraints 與到期時間不改寫。

新 HTTP URL 預設無自動到期；DB 使用 `Long.MAX_VALUE` 表示無期限，以相容現有 active/cleanup SQL；對外清單與 stats 映射成 `expiresAt:0`，UI 顯示無期限。啟用匿名到期後只影響新匿名建立：created_at＋expirationDays。登入 HTTP URL 不套匿名到期。Discord／內部原本的 ttlDays 契約保留；去重重用既有 URL 時也保留其原到期時間。

匿名認領本次未加入：目前沒有可證明建立者身分的 token hash 欄位，僅憑 code／IP 不能安全認領。後續應增加 nullable claim_token_hash，建立時用 SecureRandom 產生 256-bit token，只回傳一次並保存 SHA-256/HMAC；認領需登入、比較 hash，且在同一條件式 UPDATE／transaction 中檢查匿名 Owner、寫入 Session userId、清除 hash。舊匿名 URL 沒有 token，不得推測或自動指派 Owner。

## Turnstile 與 Logging

Turnstile 預設 false；啟用需 siteKey 與環境變數 TURNSTILE_SECRET，secret 不從 YAML 讀取也不寫入 Git。缺少必要配置時 fail fast；關閉時不需要 secret、不發驗證請求。流程為 rate limit／concurrency → bounded body → 匿名 custom-code 權限 → Siteverify → URL validation → persistence。

Siteverify 只呼叫固定 Cloudflare HTTPS endpoint，不追蹤 redirect；connect timeout 5 秒、request timeout 10 秒。token 長度限制 2048，核對 success 與配置公開 hostname；失敗 403，服務失效 503，皆不建立 URL。前端按需載入 widget，重新送出需新 token。[Cloudflare 官方驗證契約](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/)。測試使用本地 stub，沒有呼叫正式 Cloudflare。

新建立日誌只有 actor、Session userId、code；限流日誌只有政策維度。既有 Discord access log 的目標網址改為 scheme＋host，避免 user-info、路徑、query、fragment 秘密洩漏，且無期限顯示正確。不記錄 Session／OAuth／claim／Turnstile token、Cookie 或 Authorization。

URL 服務不主動抓目標網址、OpenGraph 或 metadata，本次也未新增 server-side target fetch，因此沒有新增目的網址 SSRF 路徑。

## 驗證與限制

- Java 21 compile；相關 JUnit 測試包含 fake-clock minute/hour/day、登入 IP ceiling、User daily、並行釋放、config clamp、Session 過期、Owner A/B 隔離、SQLite 真實資料庫操作、MySQL SQL 參數、URL 長度／scheme、Turnstile stub、proxy spoof、媒體 regression。
- HTTP integration 使用臨時埠、真實 HttpServer／HttpClient 與 SQLite。測試固定 HTTP/1.1 避免 JDK HttpServer 的 h2c upgrade 停滯。
- 前端執行 typecheck、test、build，建置輸出同步至 target/classes/web，未手動修改 generated assets。
- 本地 compile/test 需避開沙箱對 libdave JAR 的 AccessDenied；未修改依賴版本或 pom。
- 沒有連線正式 Discord OAuth、Cloudflare 或正式 MySQL，因此部署端 proxy chain 與實際登入仍需現場驗收。
- 沒有另做全站安全掃描。原有 HTTP request body 讀取未見應用層總時限，慢速 client 可長時間占用 worker；此為既有問題，本次未擴大修改 server timeout／executor 架構。部署代理應維持 request timeout。

## 本次驗證結果

- JDK 21：mvn -q -DskipTests compile 通過。
- 相關 JUnit suite：27 個測試類別、196 個測試，0 failures、0 errors、0 skipped；包含真實 HTTP／SQLite integration。
- npm.cmd run typecheck：NoRule URL 與 Dashboard 通過。
- npm.cmd test：5 個檔案、15 個測試通過。
- npm.cmd run build：兩個 Nuxt 靜態 app 與 sync 通過，NORULE_WEB_OUTPUT_DIR 指向 target/classes/web。
- git diff --check 通過；所有變更檔案為 UTF-8 無 BOM。
- 未執行正式環境部署、正式 Discord OAuth／Cloudflare／MySQL 驗收，亦未產生新的 package JAR。

## 修改檔案
以下列出本次完整變更檔案（含測試），未直接修改 generated frontend resources。

```text
README.md
src/main/java/com/norule/musicbot/bootstrap/RuntimeBootstrap.java
src/main/java/com/norule/musicbot/config/BotConfig.java
src/main/java/com/norule/musicbot/config/domain/ShortUrlConfig.java
src/main/java/com/norule/musicbot/discord/bot/gateway/command/shorturl/DiscordShortUrlAccessPublisher.java
src/main/java/com/norule/musicbot/domain/shorturl/ShortUrlDomainService.java
src/main/java/com/norule/musicbot/service/shorturl/RateLimitService.java
src/main/java/com/norule/musicbot/service/shorturl/ShortUrlCreationGuard.java
src/main/java/com/norule/musicbot/service/shorturl/TurnstileVerifier.java
src/main/java/com/norule/musicbot/shorturl/infra/ShortUrlGatewayServer.java
src/main/java/com/norule/musicbot/shorturl/MySqlShortUrlRepository.java
src/main/java/com/norule/musicbot/shorturl/ShortUrlRepository.java
src/main/java/com/norule/musicbot/shorturl/SqliteShortUrlRepository.java
src/main/java/com/norule/musicbot/ShortUrlService.java
src/main/java/com/norule/musicbot/web/service/ShortUrlCreationWebService.java
src/main/java/com/norule/musicbot/web/service/ShortUrlManagementWebService.java
src/main/java/com/norule/musicbot/web/service/ShortUrlWebService.java
src/main/resources/defaults/config.yml
src/test/java/com/norule/musicbot/config/domain/ShortUrlConfigTest.java
src/test/java/com/norule/musicbot/discord/bot/gateway/command/shorturl/DiscordShortUrlAccessPublisherTest.java
src/test/java/com/norule/musicbot/domain/shorturl/ShortUrlDomainServiceTest.java
src/test/java/com/norule/musicbot/service/shorturl/RateLimitServiceTest.java
src/test/java/com/norule/musicbot/service/shorturl/TurnstileVerifierTest.java
src/test/java/com/norule/musicbot/shorturl/infra/CustomShortUrlGatewayTest.java
src/test/java/com/norule/musicbot/shorturl/infra/ShortUrlGatewayServerTest.java
src/test/java/com/norule/musicbot/shorturl/infra/ShortUrlTierPolicyTest.java
src/test/java/com/norule/musicbot/shorturl/MySqlShortUrlRepositoryTest.java
src/test/java/com/norule/musicbot/shorturl/ShortUrlWebLifetimeTest.java
src/test/java/com/norule/musicbot/web/security/ClientAddressResolverTest.java
src/test/java/com/norule/musicbot/web/service/ShortUrlWebServiceSecurityTest.java
web/app/components/content/MyContentRow.vue
web/app/components/media/MediaStatsSidebar.vue
web/app/components/short-url/ShortUrlForm.vue
web/app/components/short-url/ShortUrlSessionAction.vue
web/app/components/short-url/ShortUrlVerification.vue
web/app/composables/useShortUrl.ts
web/app/composables/useShortUrlSession.ts
web/app/pages/index.vue
web/app/types/api.ts
web/app/utils/media.ts
web/tests/media.test.ts
```
