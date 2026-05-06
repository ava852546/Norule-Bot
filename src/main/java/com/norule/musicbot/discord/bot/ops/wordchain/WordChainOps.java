package com.norule.musicbot.discord.bot.ops.wordchain;

import com.norule.musicbot.discord.bot.service.wordchain.WordChainService;
import com.norule.musicbot.domain.wordchain.WordChainLeaderboardEntry;
import com.norule.musicbot.domain.wordchain.WordChainPlayerStatsSnapshot;
import com.norule.musicbot.domain.wordchain.WordChainProcessResult;
import com.norule.musicbot.domain.wordchain.WordChainStatusSnapshot;
import com.norule.musicbot.domain.wordchain.WordChainValidationResult;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WordChainOps {
    private static final String EMOJI_ACCEPTED = "\u2705";
    private static final String EMOJI_REJECTED = "\u274C";

    private static final String RESET_CONFIRM_PREFIX = "wordchain:reset:confirm:";
    private static final String RESET_CANCEL_PREFIX = "wordchain:reset:cancel:";
    private static final long RESET_CONFIRM_TTL_MILLIS = 120_000L;

    private record ResetRequest(long userId, long guildId, long expiresAtMillis) {
    }

    private final WordChainService wordChainService;
    private final Map<String, ResetRequest> resetRequests = new ConcurrentHashMap<>();

    public WordChainOps(WordChainService wordChainService) {
        this.wordChainService = wordChainService;
    }

    public boolean handleSlash(String commandName, SlashCommandInteractionEvent event, String lang) {
        if (!"wordchain".equals(commandName) || event.getGuild() == null) {
            return false;
        }
        cleanupExpiredResetRequests();
        String sub = event.getSubcommandName();
        if (sub == null || sub.isBlank()) {
            event.reply("請使用子指令：set-channel、disable、reset、status、rules、stats、leaderboard")
                    .setEphemeral(true)
                    .queue();
            return true;
        }
        switch (sub) {
            case "set-channel" -> handleSetChannel(event);
            case "disable" -> handleDisable(event);
            case "reset" -> handleResetConfirmPrompt(event);
            case "status" -> handleStatus(event);
            case "rules" -> handleRules(event);
            case "stats" -> handleStats(event);
            case "leaderboard" -> handleLeaderboard(event);
            default -> event.reply("未知的 wordchain 子指令。").setEphemeral(true).queue();
        }
        return true;
    }

    public boolean handleButton(ButtonInteractionEvent event, String lang) {
        if (event.getGuild() == null) {
            return false;
        }
        String id = event.getComponentId();
        if (!id.startsWith(RESET_CONFIRM_PREFIX) && !id.startsWith(RESET_CANCEL_PREFIX)) {
            return false;
        }
        cleanupExpiredResetRequests();

        boolean confirm = id.startsWith(RESET_CONFIRM_PREFIX);
        String token = confirm
                ? id.substring(RESET_CONFIRM_PREFIX.length())
                : id.substring(RESET_CANCEL_PREFIX.length());
        ResetRequest request = resetRequests.get(token);
        if (request == null || System.currentTimeMillis() > request.expiresAtMillis()) {
            resetRequests.remove(token);
            event.reply("重置確認已過期，請重新執行 `/wordchain reset`。")
                    .setEphemeral(true)
                    .queue();
            return true;
        }
        if (request.guildId() != event.getGuild().getIdLong() || request.userId() != event.getUser().getIdLong()) {
            event.reply("只有發起重置的人可以操作這個按鈕。")
                    .setEphemeral(true)
                    .queue();
            return true;
        }
        resetRequests.remove(token);

        if (!confirm) {
            event.editMessageEmbeds(new EmbedBuilder()
                            .setColor(new Color(149, 165, 166))
                            .setDescription("已取消重置。")
                            .setTimestamp(Instant.now())
                            .build())
                    .setComponents()
                    .queue();
            return true;
        }

        event.deferEdit().queue(success -> wordChainService.reset(event.getGuild().getIdLong())
                .thenAccept(status -> event.getHook().editOriginalEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setDescription("已重置接龍狀態與已使用單字。")
                                .setTimestamp(Instant.now())
                                .build())
                        .setComponents()
                        .queue())
                .exceptionally(error -> {
                    event.getHook().editOriginal("重置失敗，請稍後再試。").queue();
                    return null;
                }));
        return true;
    }

    public void handleMessage(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getGuild() == null || event.getAuthor().isBot()) {
            return;
        }
        long guildId = event.getGuild().getIdLong();
        long channelId = event.getChannel().getIdLong();
        long userId = event.getAuthor().getIdLong();
        String raw = event.getMessage().getContentRaw();
        wordChainService.processMessage(guildId, channelId, userId, raw)
                .thenAccept(result -> {
                    if (!result.handled() || result.result() == null) {
                        return;
                    }
                    if (result.result() == WordChainValidationResult.OK) {
                        event.getMessage().addReaction(Emoji.fromUnicode(EMOJI_ACCEPTED)).queue(ignored -> {
                        }, error -> {
                        });
                        String next = result.nextRequiredStartLetter() == null
                                ? "-"
                                : String.valueOf(result.nextRequiredStartLetter());
                        event.getMessage().replyEmbeds(new EmbedBuilder()
                                .setColor(new Color(46, 204, 113))
                                .setDescription("下一個字母：`" + next + "`")
                                .setTimestamp(Instant.now())
                                .build()).queue();
                        return;
                    }
                    event.getMessage().addReaction(Emoji.fromUnicode(EMOJI_REJECTED)).queue(ignored -> {
                    }, error -> {
                    });
                    event.getMessage().replyEmbeds(new EmbedBuilder()
                            .setColor(new Color(231, 76, 60))
                            .setDescription(errorText(result))
                            .setTimestamp(Instant.now())
                            .build()).queue();
                })
                .exceptionally(error -> null);
    }

    private void handleSetChannel(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            event.reply("需要 `Manage Server` 權限。").setEphemeral(true).queue();
            return;
        }
        if (event.getOption("channel") == null) {
            event.reply("請指定文字頻道。").setEphemeral(true).queue();
            return;
        }
        if (event.getOption("channel").getAsChannel().getType() != ChannelType.TEXT) {
            event.reply("只支援文字頻道。").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = event.getOption("channel").getAsChannel().asTextChannel();
        event.deferReply(true).queue(hook -> wordChainService.setChannel(event.getGuild().getIdLong(), channel.getIdLong())
                .thenAccept(status -> hook.editOriginal("英文單字接龍頻道已設定為 " + channel.getAsMention() + "，已重置本局進度。").queue())
                .exceptionally(error -> {
                    hook.editOriginal("設定失敗，請稍後再試。").queue();
                    return null;
                }));
    }

    private void handleDisable(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            event.reply("需要 `Manage Server` 權限。").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue(hook -> wordChainService.disable(event.getGuild().getIdLong())
                .thenAccept(status -> hook.editOriginal("已關閉英文單字接龍。").queue())
                .exceptionally(error -> {
                    hook.editOriginal("關閉失敗，請稍後再試。").queue();
                    return null;
                }));
    }

    private void handleResetConfirmPrompt(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            event.reply("需要 `Manage Server` 權限。").setEphemeral(true).queue();
            return;
        }
        String token = Long.toHexString(System.nanoTime());
        resetRequests.put(token, new ResetRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                System.currentTimeMillis() + RESET_CONFIRM_TTL_MILLIS
        ));
        Button confirm = Button.danger(RESET_CONFIRM_PREFIX + token, "確認重置");
        Button cancel = Button.secondary(RESET_CANCEL_PREFIX + token, "取消");
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(241, 196, 15))
                        .setTitle("確認重置")
                        .setDescription("這會清空目前接龍進度與已使用單字，確定要繼續嗎？")
                        .setTimestamp(Instant.now())
                        .build())
                .addComponents(ActionRow.of(confirm, cancel))
                .setEphemeral(true)
                .queue();
    }

    private void handleStatus(SlashCommandInteractionEvent event) {
        event.deferReply(true).queue(hook -> wordChainService.status(event.getGuild().getIdLong())
                .thenAccept(status -> hook.editOriginalEmbeds(statusEmbed(status).build()).queue())
                .exceptionally(error -> {
                    hook.editOriginal("讀取狀態失敗，請稍後再試。").queue();
                    return null;
                }));
    }

    private void handleRules(SlashCommandInteractionEvent event) {
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle("英文單字接龍規則")
                        .setDescription("""
                                1. 只能輸入一個英文單字（a-z）。
                                2. 單字需以上一個單字最後字母開頭。
                                3. 同一局不可重複使用相同單字。
                                4. 單字會用 dictionaryapi.dev 驗證。
                                5. 字典服務錯誤時不會計入無效次數，稍後再試即可。
                                """)
                        .setTimestamp(Instant.now())
                        .build())
                .setEphemeral(true)
                .queue();
    }

    private void handleStats(SlashCommandInteractionEvent event) {
        User target = event.getOption("user", event.getUser(), option -> option.getAsUser());
        event.deferReply(true).queue(hook -> wordChainService.stats(event.getGuild().getIdLong(), target.getIdLong())
                .thenAccept(stats -> hook.editOriginalEmbeds(statsEmbed(target, stats).build()).queue())
                .exceptionally(error -> {
                    hook.editOriginal("讀取統計失敗，請稍後再試。").queue();
                    return null;
                }));
    }

    private void handleLeaderboard(SlashCommandInteractionEvent event) {
        final int safeLimit = Math.max(1, Math.min(20, event.getOption("limit", 10, option -> option.getAsInt())));
        event.deferReply(true).queue(hook -> wordChainService.leaderboard(event.getGuild().getIdLong(), safeLimit)
                .thenAccept(ranking -> hook.editOriginalEmbeds(leaderboardEmbed(ranking, safeLimit).build()).queue())
                .exceptionally(error -> {
                    hook.editOriginal("讀取排行榜失敗，請稍後再試。").queue();
                    return null;
                }));
    }

    private EmbedBuilder statusEmbed(WordChainStatusSnapshot status) {
        String channel = status.channelId() == null ? "未設定" : "<#" + status.channelId() + ">";
        String lastWord = status.lastWord() == null || status.lastWord().isBlank() ? "-" : status.lastWord();
        String next = status.nextRequiredStartLetter() == null ? "-" : String.valueOf(status.nextRequiredStartLetter());
        return new EmbedBuilder()
                .setColor(status.enabled() ? new Color(46, 204, 113) : new Color(149, 165, 166))
                .setTitle("英文單字接龍狀態")
                .addField("啟用", status.enabled() ? "是" : "否", true)
                .addField("頻道", channel, true)
                .addField("上一個單字", "`" + lastWord + "`", true)
                .addField("下一個開頭字母", "`" + next + "`", true)
                .addField("已接龍數量", "`" + status.chainCount() + "`", true)
                .setTimestamp(Instant.now());
    }

    private EmbedBuilder statsEmbed(User user, WordChainPlayerStatsSnapshot stats) {
        return new EmbedBuilder()
                .setColor(new Color(52, 152, 219))
                .setTitle("WordChain 統計")
                .setDescription(user == null ? "-" : user.getAsMention())
                .addField("總訊息數", String.valueOf(stats.totalMessages()), true)
                .addField("成功次數", String.valueOf(stats.successCount()), true)
                .addField("錯誤(無效)次數", String.valueOf(stats.invalidCount()), true)
                .addField("成功率", formatRate(stats.successRate()), true)
                .setTimestamp(Instant.now());
    }

    private EmbedBuilder leaderboardEmbed(List<WordChainLeaderboardEntry> ranking, int limit) {
        StringBuilder content = new StringBuilder();
        if (ranking.isEmpty()) {
            content.append("目前尚無有效統計資料。");
        } else {
            int rank = 1;
            for (WordChainLeaderboardEntry one : ranking) {
                content.append(rank)
                        .append(". <@")
                        .append(one.userId())
                        .append("> - 成功 ")
                        .append(one.successCount())
                        .append(" / 總訊息 ")
                        .append(one.totalMessages())
                        .append(" / 成功率 ")
                        .append(formatRate(one.successRate()))
                        .append('\n');
                rank++;
            }
        }
        return new EmbedBuilder()
                .setColor(new Color(241, 196, 15))
                .setTitle("WordChain 排行榜（成功次數優先，成功率次之）")
                .setDescription(content.toString())
                .setFooter("顯示前 " + Math.max(1, Math.min(20, limit)) + " 名")
                .setTimestamp(Instant.now());
    }

    private String errorText(WordChainProcessResult result) {
        return switch (result.result()) {
            case EMPTY -> "請輸入單字。";
            case NOT_SINGLE_WORD -> "一次只允許一個英文單字。";
            case NOT_ENGLISH -> "只允許 a-z 英文字母。";
            case WORD_USED -> {
                Character expected = result.expectedStartLetter();
                String word = result.word() == null ? "" : result.word();
                boolean wrongStart = expected != null && !word.isBlank() && word.charAt(0) != expected;
                if (wrongStart) {
                    yield "開頭字母錯誤，應為 `" + expected + "`，且這個單字本局已使用過。";
                }
                yield "這個單字本局已使用過。";
            }
            case WRONG_START_LETTER -> "開頭字母錯誤，請用 `"
                    + (result.expectedStartLetter() == null ? "-" : result.expectedStartLetter()) + "` 開頭。";
            case WORD_NOT_FOUND -> "字典查無此單字。";
            case DICTIONARY_API_ERROR -> "字典服務暫時無法確認，請稍後再試";
            default -> "處理失敗，請稍後再試。";
        };
    }

    private String formatRate(double rate) {
        double safe = Math.max(0D, Math.min(1D, rate));
        return String.format("%.2f%%", safe * 100D);
    }

    private boolean hasManageServer(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        return member != null && member.hasPermission(Permission.MANAGE_SERVER);
    }

    private void cleanupExpiredResetRequests() {
        long now = System.currentTimeMillis();
        resetRequests.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().expiresAtMillis() < now);
    }
}
