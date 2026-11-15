package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;
import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelModeResolver;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class MusicTextResolver {
    private final Supplier<I18nService> i18nSupplier;

    public MusicTextResolver(Supplier<I18nService> i18nSupplier) {
        this.i18nSupplier = Objects.requireNonNull(i18nSupplier, "i18nSupplier");
    }

    public String musicText(String lang, String key) {
        return musicText(lang, key, Map.of());
    }

    public String musicText(String lang, String key, Map<String, String> placeholders) {
        String fullKey = "music." + key;
        String value = i18nService().t(lang, fullKey, placeholders);
        return isMissingTranslation(value, fullKey) ? musicUx(lang, key, placeholders) : value;
    }

    public String panelStatus(String lang, MusicPanelModeResolver.Selection mode) {
        String reasonKey = panelReasonKey(mode.reason());
        String permissions = mode.missingPermissions().isEmpty()
                ? musicText(lang, mode.modern() ? "panel_permissions_none" : "panel_permissions_unknown")
                : mode.missingPermissions().stream().map(permission -> musicText(lang, "panel_permission_" + permission))
                        .collect(java.util.stream.Collectors.joining(", "));
        return "⚠️ " + musicText(lang, mode.missingPermissions().isEmpty() ? "panel_fallback_title" : "panel_permission_title")
                + "\n" + musicText(lang, "panel_status", Map.of("channel", mode.channelId() == null ? "—" : mode.channelId().toString(),
                        "configured", mode.configured().name(), "effective", mode.effective().name(),
                        "reason", musicText(lang, reasonKey), "permissions", permissions))
                + "\n" + musicText(lang, "panel_repair");
    }

    public static String panelReasonKey(String reason) {
        return switch (reason) {
            case "AVAILABLE", "LEGACY_MODE" -> "panel_permissions_none";
            case "NOT_CONFIGURED", "NO_INTERACTION_CHANNEL" -> "panel_reason_not_configured";
            case "UNKNOWN_CHANNEL" -> "panel_reason_deleted";
            case "MISSING_VIEW_PERMISSION", "MISSING_SEND_PERMISSION", "MISSING_EMBED_PERMISSION" -> "panel_reason_permissions";
            case "MISSING_ACCESS", "MISSING_PERMISSIONS" -> "panel_reason_access";
            case "UNSUPPORTED_CHANNEL_TYPE" -> "panel_reason_unsupported";
            case "THREAD_LOCKED" -> "panel_reason_locked";
            case "THREAD_ARCHIVED" -> "panel_reason_archived";
            case "CHANNEL_NOT_WRITABLE" -> "panel_reason_not_writable";
            default -> "panel_reason_verification";
        };
    }

    private boolean isMissingTranslation(String value, String key) {
        return value == null || value.isBlank() || value.equals(key);
    }

    private String musicUx(String lang, String key, Map<String, String> placeholders) {
        boolean zhCn = lang != null && lang.startsWith("zh-CN");
        boolean zh = lang != null && lang.startsWith("zh");
        String value = switch (key) {
            case "panel_permission_title" -> zhCn ? "音乐面板权限不足" : (zh ? "音樂面板權限不足" : "Music panel permissions missing");
            case "panel_status" -> zhCn ? "频道：{channel} · 设置模式：{configured} · 实际模式：{effective}\n原因：{reason}\n缺少权限：{permissions}" : (zh ? "頻道：{channel} · 設定模式：{configured} · 實際模式：{effective}\n原因：{reason}\n缺少權限：{permissions}" : "Channel: {channel} · Configured mode: {configured} · Effective mode: {effective}\nReason: {reason}\nMissing permissions: {permissions}");
            case "panel_repair" -> zhCn ? "请管理员在指定频道允许 NoRule Bot 查看频道、发送消息（讨论串需对应权限）及嵌入链接，并解除讨论串归档／锁定。修正后自动恢复 MODERN，保留原设置，不必重新设置。频道已删除或尚未设置时，请用 /settings 选择有效频道；暂时无法验证时请稍后重试。" : (zh ? "請管理員在指定頻道允許 NoRule Bot 檢視頻道、傳送訊息（討論串需對應權限）及嵌入連結，並解除討論串封存／鎖定。修正後自動恢復 MODERN，保留原設定，不必重新設定。頻道已刪除或尚未設定時，請用 /settings 選擇有效頻道；暫時無法驗證時請稍後再試。" : "Ask an administrator to allow NoRule Bot to View Channel, Send Messages (in Threads for threads), and Embed Links, and unlock/unarchive the thread. MODERN resumes automatically after repair; your channel setting is retained. For a deleted or unset channel, choose a valid channel in /settings. If verification is temporarily unavailable, try again later.");
            case "panel_reason_permissions" -> zhCn ? "缺少必要的频道权限，部分面板功能可能无法工作。" : (zh ? "缺少必要的頻道權限，部分面板功能可能無法工作。" : "Required channel permissions are missing; some panel features may not work.");
            case "panel_reason_not_configured" -> zhCn ? "尚未设置面板频道" : (zh ? "尚未設定面板頻道" : "No panel channel configured");
            case "panel_reason_deleted" -> zhCn ? "Discord 已确认频道不存在" : (zh ? "Discord 已確認頻道不存在" : "Discord confirmed the channel does not exist");
            case "panel_reason_verification" -> zhCn ? "暂时无法验证频道；不代表频道已删除" : (zh ? "暫時無法驗證頻道；不代表頻道已刪除" : "Channel verification is temporarily unavailable; this does not mean it was deleted");
            case "panel_reason_access" -> zhCn ? "无法访问频道；请检查 Bot 角色及频道权限" : (zh ? "無法存取頻道；請檢查 Bot 角色及頻道權限" : "Cannot access the channel; check the bot role and channel permissions");
            case "panel_reason_unsupported" -> zhCn ? "不支持的频道类型" : (zh ? "不支援的頻道類型" : "Unsupported channel type");
            case "panel_reason_locked" -> zhCn ? "讨论串已锁定" : (zh ? "討論串已鎖定" : "Thread is locked");
            case "panel_reason_archived" -> zhCn ? "讨论串已归档" : (zh ? "討論串已封存" : "Thread is archived");
            case "panel_reason_not_writable" -> zhCn ? "讨论串目前无法写入" : (zh ? "討論串目前無法寫入" : "Thread is not writable");
            case "panel_permission_VIEW_CHANNEL" -> zhCn ? "查看频道（View Channel）" : (zh ? "檢視頻道（View Channel）" : "View Channel");
            case "panel_permission_MESSAGE_SEND" -> zhCn ? "发送消息（Send Messages）" : (zh ? "傳送訊息（Send Messages）" : "Send Messages");
            case "panel_permission_MESSAGE_SEND_IN_THREADS" -> zhCn ? "在讨论串发送消息（Send Messages in Threads）" : (zh ? "在討論串傳送訊息（Send Messages in Threads）" : "Send Messages in Threads");
            case "panel_permission_MESSAGE_EMBED_LINKS" -> zhCn ? "嵌入链接（Embed Links）" : (zh ? "嵌入連結（Embed Links）" : "Embed Links");
            case "panel_permissions_unknown" -> zhCn ? "无法确认" : (zh ? "無法確認" : "Not confirmed");
            case "panel_permissions_none" -> zhCn ? "无" : (zh ? "無" : "None");
            case "panel_mode_label" -> zhCn ? "音乐面板模式" : (zh ? "音樂面板模式" : "Music panel mode");
            case "panel_mode_modern" -> zhCn ? "新版模式（默认）" : (zh ? "新版模式（預設）" : "Modern mode (default)");
            case "panel_mode_legacy" -> zhCn ? "传统模式" : (zh ? "傳統模式" : "Legacy mode");
            case "panel_mode_modern_description" -> zhCn ? "常驻音乐控制面板，自动清理指定音乐频道消息。" : (zh ? "常駐音樂控制面板，自動清理指定音樂頻道訊息。" : "Persistent music controls; automatically cleans messages in the configured music channel.");
            case "panel_mode_legacy_description" -> zhCn ? "每次成功点歌后，移除旧音乐面板并在频道底部建立新面板；不清理一般消息。" : (zh ? "每次成功點歌後，移除舊音樂面板並在頻道底部建立新面板；不清理一般訊息。" : "After a successful play request, replaces the old music panel at the bottom of the channel. General messages are not cleaned.");
            case "panel_fallback_legacy" -> zhCn ? "默认音乐面板频道不存在、无法访问或尚未设置，目前暂时使用传统模式，仍可正常点歌。请通过 /settings 设置有效的默认音乐面板频道。如果不希望每次点歌都收到提示，可以在设置中切换为 LEGACY 传统模式。在频道恢复有效或手动切换为 LEGACY 前，每次 /play 都会显示此私人提示。" : (zh ? "預設音樂面板頻道不存在、無法存取或尚未設定，目前暫時使用傳統模式，仍可正常點歌。請透過 /settings 設定有效的預設音樂面板頻道。如果不希望每次點歌都收到提示，可以在設定中切換為 LEGACY 傳統模式。在頻道恢復有效或手動切換為 LEGACY 前，每次 /play 都會顯示此私人提示。" : "The default music panel channel is missing, inaccessible, or not configured. Temporarily using Legacy mode; you can still request music. Use /settings to set a valid default music panel channel. To stop this notice on every /play, switch to Legacy mode in settings. This notice appears on every /play until the channel is valid or Legacy mode is selected.");
            case "panel_fallback_title" -> zhCn ? "音乐面板设置异常" : (zh ? "音樂面板設定異常" : "Music panel configuration issue");
            case "panel_fallback_description" -> zhCn ? "目前暂时使用传统模式，请通过 `/settings` 设置有效的默认音乐面板频道，或切换为传统模式以停止提醒。" : (zh ? "目前暫時使用傳統模式，請透過 `/settings` 設定有效的預設音樂面板頻道，或切換為傳統模式以停止提醒。" : "Temporarily using Legacy mode. Use `/settings` to set a valid default music panel channel, or switch to Legacy mode to stop these notices.");
            case "panel_fallback_footer" -> zhCn ? "仅执行指令者可见 · 保留提示" : (zh ? "僅執行指令者可見 · 保留提示" : "Only visible to you · Notice retained");
            case "volume_usage" -> zhCn ? "请使用 `!volume <1-100>`。" : (zh ? "請使用 `!volume <1-100>`。" : "Use `!volume <1-100>`.");
            case "volume_set" -> zhCn ? "音量已设置为 `{value}%`。" : (zh ? "音量已設定為 `{value}%`。" : "Volume set to `{value}%`.");
            case "playlist_usage" -> zhCn ? "请使用 `!playlist <save|load|add|delete|list|view|export> [name]`、`!playlist list <mine|all>` 或 `!playlist import <code> [name]`。" : (zh ? "請使用 `!playlist <save|load|add|delete|list|view|export> [name]`、`!playlist list <mine|all>` 或 `!playlist import <code> [name]`。" : "Use `!playlist <save|load|add|delete|list|view|export> [name]`, `!playlist list <mine|all>`, or `!playlist import <code> [name]`.");
            case "playlist_operation_failed" -> zhCn ? "\u6b4c\u5355\u64cd\u4f5c\u5931\u8d25\u6216\u7cfb\u7edf\u5fd9\u788c\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002" : (zh ? "\u6b4c\u55ae\u64cd\u4f5c\u5931\u6557\u6216\u7cfb\u7d71\u5fd9\u788c\uff0c\u8acb\u7a0d\u5f8c\u91cd\u8a66\u3002" : "The playlist operation failed or the system is busy. Please try again later.");
            case "playlist_name_required" -> zhCn ? "请提供歌单名称。" : (zh ? "請提供歌單名稱。" : "Please provide a playlist name.");
            case "playlist_save_empty" -> zhCn ? "目前没有可保存的歌曲或队列。" : (zh ? "目前沒有可儲存的歌曲或佇列。" : "There is no current track or queue to save.");
            case "playlist_save_success" -> zhCn ? "歌单 `{name}` 已保存，共 `{count}` 首歌曲。" : (zh ? "歌單 `{name}` 已儲存，共 `{count}` 首歌曲。" : "Playlist `{name}` saved with `{count}` tracks.");
            case "playlist_save_duplicate" -> zhCn ? "歌单 `{name}` 已包含目前要新增的歌曲，未重复新增。" : (zh ? "歌單 `{name}` 已包含目前要新增的歌曲，未重複新增。" : "Playlist `{name}` already contains the current tracks. Nothing was added.");
            case "playlist_load_missing" -> zhCn ? "找不到歌单 `{name}`。" : (zh ? "找不到歌單 `{name}`。" : "Playlist `{name}` was not found.");
            case "playlist_load_processing" -> zhCn ? "🎵 歌单 `{name}` 加载中，共 {count} 首，正在处理。" : (zh ? "🎵 歌單 `{name}` 載入中，共 {count} 首，正在處理。" : "🎵 Loading playlist `{name}` ({count} tracks).");
            case "playlist_load_duplicate" -> zhCn ? "歌单 `{name}` 已在加载中，请等待当前批次完成。" : (zh ? "歌單 `{name}` 已在載入中，請等待目前批次完成。" : "Playlist `{name}` is already loading.");
            case "playlist_load_full" -> zhCn ? "加载队列已满，请稍后再试。" : (zh ? "載入佇列已滿，請稍後再試。" : "The loading queue is full. Try again later.");
            case "playlist_load_complete" -> zhCn ? "歌单 `{name}` 处理完成：成功加入 {queued} 首、失败 {failed} 首、跳过 {skipped} 首、取消 {cancelled} 首。" : (zh ? "歌單 `{name}` 處理完成：成功加入 {queued} 首、失敗 {failed} 首、略過 {skipped} 首、取消 {cancelled} 首。" : "Playlist `{name}` finished: queued {queued}, failed {failed}, skipped {skipped}, cancelled {cancelled}.");
            case "playlist_load_success" -> zhCn ? "歌单 `{name}` 已加入队列，共 `{count}` 首歌曲。" : (zh ? "歌單 `{name}` 已加入佇列，共 `{count}` 首歌曲。" : "Playlist `{name}` queued with `{count}` tracks.");
            case "playlist_add_no_track" -> zhCn ? "无法从提供的 URL 解析可新增的歌曲。" : (zh ? "無法從提供的 URL 解析可新增的歌曲。" : "No addable track could be resolved from the provided URL.");
            case "playlist_add_success" -> zhCn ? "已将 `{title}` 新增到歌单 `{name}`，目前共 `{count}` 首歌曲。" : (zh ? "已將 `{title}` 新增到歌單 `{name}`，目前共 `{count}` 首歌曲。" : "Added `{title}` to playlist `{name}`. It now has `{count}` tracks.");
            case "playlist_add_duplicate" -> zhCn ? "歌单 `{name}` 已有 `{title}`，未重复新增。" : (zh ? "歌單 `{name}` 已有 `{title}`，未重複新增。" : "Playlist `{name}` already contains `{title}`. Nothing was added.");
            case "playlist_add_not_owner" -> zhCn ? "歌单 `{name}` 由 `{owner}` 建立，只有建立者可以新增歌曲。" : (zh ? "歌單 `{name}` 由 `{owner}` 建立，只有建立者可以新增歌曲。" : "Playlist `{name}` was created by `{owner}`. Only the creator can add tracks.");
            case "playlist_add_limit" -> zhCn ? "歌单 `{name}` 已达上限 `{count}` 首歌曲，无法继续新增。" : (zh ? "歌單 `{name}` 已達上限 `{count}` 首歌曲，無法繼續新增。" : "Playlist `{name}` already reached the limit of `{count}` tracks.");
            case "playlist_add_failed" -> zhCn ? "解析 URL 失败：{reason}" : (zh ? "解析 URL 失敗：{reason}" : "Failed to resolve URL: {reason}");
            case "playlist_delete_missing" -> zhCn ? "找不到歌单 `{name}`。" : (zh ? "找不到歌單 `{name}`。" : "Playlist `{name}` was not found.");
            case "playlist_delete_success" -> zhCn ? "歌单 `{name}` 已删除。" : (zh ? "歌單 `{name}` 已刪除。" : "Playlist `{name}` deleted.");
            case "playlist_export_missing" -> zhCn ? "找不到可导出的歌单 `{name}`。" : (zh ? "找不到可匯出的歌單 `{name}`。" : "Playlist `{name}` was not found for export.");
            case "playlist_export_success" -> zhCn ? "歌单 `{name}` 已生成 6 位数导出代码 `{code}`，共 `{count}` 首歌曲。代码 `{minutes}` 分钟内有效。" : (zh ? "歌單 `{name}` 已產生 6 位數匯出代碼 `{code}`，共 `{count}` 首歌曲。代碼 `{minutes}` 分鐘內有效。" : "Playlist `{name}` generated 6-digit export code `{code}` with `{count}` tracks. The code is valid for `{minutes}` minutes.");
            case "playlist_code_required" -> zhCn ? "请提供 6 位数导入代码。" : (zh ? "請提供 6 位數匯入代碼。" : "Please provide a 6-digit import code.");
            case "playlist_import_invalid_code" -> zhCn ? "导入代码 `{code}` 无效、已过期或不存在。" : (zh ? "匯入代碼 `{code}` 無效、已過期或不存在。" : "Import code `{code}` is invalid, expired, or unavailable.");
            case "playlist_import_success" -> zhCn ? "已从代码 `{code}` 导入歌单 `{name}`，共 `{count}` 首歌曲。" : (zh ? "已從代碼 `{code}` 匯入歌單 `{name}`，共 `{count}` 首歌曲。" : "Imported playlist `{name}` from code `{code}` with `{count}` tracks.");
            case "playlist_name_conflict" -> zhCn ? "歌单 `{name}` 已存在，由 `{owner}` 建立。请更改名称。" : (zh ? "歌單 `{name}` 已存在，由 `{owner}` 建立。請更改名稱。" : "Playlist `{name}` already exists and was created by `{owner}`. Please choose a different name.");
            case "playlist_delete_not_owner" -> zhCn ? "歌单 `{name}` 由 `{owner}` 建立，只有建立者可以删除或覆盖。" : (zh ? "歌單 `{name}` 由 `{owner}` 建立，只有建立者可以刪除或覆蓋。" : "Playlist `{name}` was created by `{owner}`. Only the creator can delete or overwrite it.");
            case "playlist_title" -> zhCn ? "已保存歌单" : (zh ? "已儲存歌單" : "Saved Playlists");
            case "playlist_desc" -> zhCn ? "保存目前播放歌曲与队列，之后可随时重新载入。" : (zh ? "儲存目前播放歌曲與佇列，之後可隨時重新載入。" : "Save the current track and queue, then load them again anytime.");
            case "playlist_field" -> zhCn ? "歌单列表" : (zh ? "歌單列表" : "Playlists");
            case "playlist_list_desc" -> zhCn ? "这个服务器已保存的歌单。" : (zh ? "這個伺服器已儲存的歌單。" : "Saved playlists for this server.");
            case "playlist_list_empty" -> zhCn ? "目前还没有保存任何歌单。" : (zh ? "目前還沒有儲存任何歌單。" : "No playlists saved yet.");
            case "playlist_list_desc_all" -> zhCn ? "这个服务器的所有歌单。" : (zh ? "這個伺服器的全部歌單。" : "All playlists saved in this server.");
            case "playlist_list_desc_mine" -> zhCn ? "你在这个服务器建立的歌单。" : (zh ? "你在這個伺服器建立的歌單。" : "Playlists you created in this server.");
            case "playlist_list_empty_all" -> zhCn ? "目前还没有任何歌单。" : (zh ? "目前還沒有任何歌單。" : "There are no playlists in this server yet.");
            case "playlist_list_empty_mine" -> zhCn ? "你还没有在这个服务器建立任何歌单。" : (zh ? "你還沒有在這個伺服器建立任何歌單。" : "You have not created any playlists in this server yet.");
            case "playlist_owner" -> zhCn ? "创建者" : (zh ? "建立者" : "Owner");
            case "playlist_updated" -> zhCn ? "已更新" : (zh ? "已更新" : "Updated");
            case "playlist_view_title" -> zhCn ? "歌单：{name}" : (zh ? "歌單：{name}" : "Playlist: {name}");
            case "playlist_view_desc" -> zhCn ? "以下为歌单 `{name}` 的曲目内容。" : (zh ? "以下為歌單 `{name}` 的曲目內容。" : "Tracks inside playlist `{name}`.");
            case "playlist_view_missing" -> zhCn ? "找不到歌单 `{name}`。" : (zh ? "找不到歌單 `{name}`。" : "Playlist `{name}` was not found.");
            case "playlist_view_empty" -> zhCn ? "这个歌单目前没有曲目。" : (zh ? "這個歌單目前沒有曲目。" : "This playlist is currently empty.");
            case "playlist_view_expired" -> zhCn ? "歌单查看分页已过期，请重新执行指令。" : (zh ? "歌單查看分頁已過期，請重新執行指令。" : "The playlist view pagination has expired. Please run the command again.");
            case "playlist_track_count" -> zhCn ? "曲目数" : (zh ? "曲目數" : "Tracks");
            case "playlist_track_list" -> zhCn ? "曲目内容" : (zh ? "曲目內容" : "Track List");
            case "playlist_view_more" -> zhCn ? "还有 `{count}` 首未显示。" : (zh ? "還有 `{count}` 首未顯示。" : "`{count}` more not shown.");
            case "playlist_prev_page" -> zhCn ? "上一页" : (zh ? "上一頁" : "Previous");
            case "playlist_next_page" -> zhCn ? "下一页" : (zh ? "下一頁" : "Next");
            case "playlist_page_indicator" -> zhCn ? "第 `{current}` / `{total}` 页" : (zh ? "第 `{current}` / `{total}` 頁" : "Page `{current}` / `{total}`");
            case "playlist_source" -> zhCn ? "歌单" : (zh ? "歌單" : "playlist");
            case "queue_added" -> zhCn ? "已加入队列：`{title}`" : (zh ? "已加入佇列：`{title}`" : "Queued: `{title}`");
            case "panel_autoplay" -> zhCn ? "自动推荐" : (zh ? "自動推薦" : "Autoplay");
            case "panel_autoplay_notice" -> zhCn ? "自动推荐提示" : (zh ? "自動推薦提示" : "Autoplay Notice");
            case "btn_autoplay_on" -> zhCn ? "自动推荐：开启" : (zh ? "自動推薦：開啟" : "Autoplay: ON");
            case "btn_autoplay_off" -> zhCn ? "自动推荐：关闭" : (zh ? "自動推薦：關閉" : "Autoplay: OFF");
            case "panel_title" -> zhCn ? "音乐控制面板" : (zh ? "音樂控制面板" : "Music Control Panel");
            case "panel_current" -> zhCn ? "当前播放" : (zh ? "目前播放" : "Now Playing");
            case "panel_channel" -> zhCn ? "频道" : (zh ? "頻道" : "channel");
            case "panel_queue" -> zhCn ? "队列" : (zh ? "佇列" : "Queue");
            case "panel_repeat" -> zhCn ? "循环" : (zh ? "循環" : "repeat");
            case "panel_state" -> zhCn ? "状态" : (zh ? "狀態" : "State");
            case "panel_paused" -> zhCn ? "暂停" : (zh ? "暫停" : "Paused");
            case "panel_playing" -> zhCn ? "播放中" : (zh ? "播放中" : "Playing");
            case "panel_idle" -> zhCn ? "闲置" : (zh ? "閒置" : "Idle");
            case "panel_source" -> zhCn ? "来源" : (zh ? "來源" : "Source");
            case "panel_requester" -> zhCn ? "点歌者" : (zh ? "點歌者" : "Requested By");
            case "panel_progress" -> zhCn ? "播放进度" : (zh ? "播放進度" : "Progress");
            case "panel_none" -> zhCn ? "（无）" : (zh ? "（無）" : "(none)");
            case "autoplay_on" -> zhCn ? "开启" : (zh ? "開啟" : "ON");
            case "autoplay_off" -> zhCn ? "关闭" : (zh ? "關閉" : "OFF");
            case "btn_play_pause" -> zhCn ? "播放/暂停" : (zh ? "播放/暫停" : "Play/Pause");
            case "btn_skip" -> zhCn ? "跳过" : (zh ? "跳過" : "Skip");
            case "btn_stop" -> zhCn ? "停止" : (zh ? "停止" : "Stop");
            case "btn_leave" -> zhCn ? "离开" : (zh ? "離開" : "leave");
            case "btn_volume_down" -> zhCn ? "降低音量" : (zh ? "降低音量" : "Volume Down");
            case "btn_volume_up" -> zhCn ? "增加音量" : (zh ? "增加音量" : "Volume Up");
            case "btn_repeat_single" -> zhCn ? "单曲循环" : (zh ? "單曲循環" : "Repeat One");
            case "btn_repeat_all" -> zhCn ? "队列循环" : (zh ? "佇列循環" : "Repeat Queue");
            case "btn_repeat_off" -> zhCn ? "关闭循环" : (zh ? "關閉循環" : "Disable Repeat");
            case "btn_refresh" -> zhCn ? "刷新" : (zh ? "刷新" : "Refresh");
            case "btn_shuffle" -> zhCn ? "随机打乱" : (zh ? "隨機打亂" : "Shuffle");
            case "panel_volume" -> zhCn ? "音量" : (zh ? "音量" : "volume");
            case "panel_author" -> zhCn ? "上传者" : (zh ? "上傳者" : "Uploader");
            case "panel_duration" -> zhCn ? "时长" : (zh ? "時長" : "Duration");
            case "history_title" -> zhCn ? "播放历史" : (zh ? "播放歷史" : "Playback History");
            case "history_desc" -> zhCn ? "这个服务器最近播放过的歌曲。" : (zh ? "這個伺服器最近播放過的歌曲。" : "Recently played tracks for this server.");
            case "history_field" -> zhCn ? "最近播放" : (zh ? "最近播放" : "Recently Played");
            case "history_empty" -> zhCn ? "目前还没有播放历史。" : (zh ? "目前還沒有播放歷史。" : "No playback history yet.");
            case "history_source" -> zhCn ? "来源" : (zh ? "來源" : "Source");
            case "history_duration" -> zhCn ? "时长" : (zh ? "時長" : "Duration");
            case "history_requester" -> zhCn ? "点歌者" : (zh ? "點歌者" : "Requester");
            case "stats_title" -> zhCn ? "音乐统计" : (zh ? "音樂統計" : "Music Stats");
            case "stats_desc" -> zhCn ? "这个服务器的音乐活动概览。" : (zh ? "這個伺服器的音樂活動概覽。" : "Music activity overview for this server.");
            case "stats_top_song" -> zhCn ? "最多播放歌曲" : (zh ? "最多播放歌曲" : "Most Played Song");
            case "stats_top_user" -> zhCn ? "最常点歌成员" : (zh ? "最常點歌成員" : "Most Active Requester");
            case "stats_today_time" -> zhCn ? "今日播放时数" : (zh ? "今日播放時數" : "Today Playback Time");
            case "stats_history_count" -> zhCn ? "历史纪录数量" : (zh ? "歷史紀錄數量" : "History Entries");
            case "stats_none" -> zhCn ? "暂无资料" : (zh ? "暫無資料" : "No data");
            case "panel_operation_superseded" -> zhCn ? "\u97f3\u4e50\u9762\u677f\u8bbe\u7f6e\u5df2\u5728\u64cd\u4f5c\u671f\u95f4\u66f4\u6539\uff0c\u8bf7\u91cd\u8bd5\u3002" : (zh ? "\u97f3\u6a02\u9762\u677f\u8a2d\u5b9a\u5df2\u5728\u64cd\u4f5c\u671f\u9593\u8b8a\u66f4\uff0c\u8acb\u91cd\u8a66\u3002" : "Music panel settings changed while this operation was running. Please try again.");
            case "panel_playback_unaffected" -> zhCn ? "\u6b4c\u66f2\u5df2\u52a0\u5165\u961f\u5217\uff0c\u64ad\u653e\u4e0d\u53d7\u5f71\u54cd\uff1b\u9762\u677f\u66f4\u65b0\u5931\u8d25\uff1a{error}" : (zh ? "\u6b4c\u66f2\u5df2\u52a0\u5165\u4f47\u5217\uff0c\u64ad\u653e\u4e0d\u53d7\u5f71\u97ff\uff1b\u9762\u677f\u66f4\u65b0\u5931\u6557\uff1a{error}" : "The song was queued successfully. Music playback is unaffected, but the music panel could not be updated: {error}");
            case "panel_target_unavailable" -> zhCn ? "\u97f3\u4e50\u9762\u677f\u76ee\u6807\u9891\u9053\u4e0d\u53ef\u7528\uff0c\u8bf7\u5728\u6709\u6548\u7684\u6587\u5b57\u9891\u9053\u91cd\u8bd5\u6216\u68c0\u67e5 /settings\u3002" : (zh ? "\u97f3\u6a02\u9762\u677f\u76ee\u6a19\u983b\u9053\u7121\u6cd5\u4f7f\u7528\uff0c\u8acb\u5728\u6709\u6548\u7684\u6587\u5b57\u983b\u9053\u91cd\u8a66\u6216\u6aa2\u67e5 /settings\u3002" : "The music panel target channel is unavailable. Retry in a valid text channel or check /settings.");
            default -> key;
        };
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return value;
    }

    private I18nService i18nService() {
        return i18nSupplier.get();
    }
}
