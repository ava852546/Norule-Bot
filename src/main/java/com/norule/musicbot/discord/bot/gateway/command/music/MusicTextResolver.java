package com.norule.musicbot.discord.bot.gateway.command.music;

import com.norule.musicbot.i18n.I18nService;

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

    private boolean isMissingTranslation(String value, String key) {
        return value == null || value.isBlank() || value.equals(key);
    }

    private String musicUx(String lang, String key, Map<String, String> placeholders) {
        boolean zhCn = lang != null && lang.startsWith("zh-CN");
        boolean zh = lang != null && lang.startsWith("zh");
        String value = switch (key) {
            case "volume_usage" -> zhCn ? "请使用 `!volume <1-100>`。" : (zh ? "請使用 `!volume <1-100>`。" : "Use `!volume <1-100>`.");
            case "volume_set" -> zhCn ? "音量已设置为 `{value}%`。" : (zh ? "音量已設定為 `{value}%`。" : "Volume set to `{value}%`.");
            case "playlist_usage" -> zhCn ? "请使用 `!playlist <save|load|add|delete|list|view|export> [name]`、`!playlist list <mine|all>` 或 `!playlist import <code> [name]`。" : (zh ? "請使用 `!playlist <save|load|add|delete|list|view|export> [name]`、`!playlist list <mine|all>` 或 `!playlist import <code> [name]`。" : "Use `!playlist <save|load|add|delete|list|view|export> [name]`, `!playlist list <mine|all>`, or `!playlist import <code> [name]`.");
            case "playlist_name_required" -> zhCn ? "请提供歌单名称。" : (zh ? "請提供歌單名稱。" : "Please provide a playlist name.");
            case "playlist_save_empty" -> zhCn ? "目前没有可保存的歌曲或队列。" : (zh ? "目前沒有可儲存的歌曲或佇列。" : "There is no current track or queue to save.");
            case "playlist_save_success" -> zhCn ? "歌单 `{name}` 已保存，共 `{count}` 首歌曲。" : (zh ? "歌單 `{name}` 已儲存，共 `{count}` 首歌曲。" : "Playlist `{name}` saved with `{count}` tracks.");
            case "playlist_save_duplicate" -> zhCn ? "歌单 `{name}` 已包含目前要新增的歌曲，未重复新增。" : (zh ? "歌單 `{name}` 已包含目前要新增的歌曲，未重複新增。" : "Playlist `{name}` already contains the current tracks. Nothing was added.");
            case "playlist_load_missing" -> zhCn ? "找不到歌单 `{name}`。" : (zh ? "找不到歌單 `{name}`。" : "Playlist `{name}` was not found.");
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
