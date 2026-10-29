package com.norule.musicbot.discord.bot.gateway;

import com.norule.musicbot.discord.bot.gateway.panel.MusicPanelStateStore;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import okhttp3.Interceptor;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Observes actual HTTP 429 responses, including requests JDA will automatically retry. */
public final class DiscordRateLimitInterceptor implements Interceptor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordRateLimitInterceptor.class);
    private static final Pattern CHANNEL_ROUTE = Pattern.compile("/channels/([0-9]+)(?:/|$)");
    private static final Pattern GUILD_ROUTE = Pattern.compile("/guilds/([0-9]+)(?:/|$)");

    private final Supplier<JDA> jdaSupplier;
    private final Supplier<Map<Long, MusicPanelStateStore.PanelRef>> panelRefs;

    public DiscordRateLimitInterceptor(Supplier<JDA> jdaSupplier,
                                       Supplier<Map<Long, MusicPanelStateStore.PanelRef>> panelRefs) {
        this.jdaSupplier = jdaSupplier;
        this.panelRefs = panelRefs;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        if (response.code() == 429) {
            logRateLimit(response);
        }
        return response;
    }

    private void logRateLimit(Response response) {
        String path = response.request().url().encodedPath();
        String channelId = routeId(CHANNEL_ROUTE, path);
        String guildId = routeId(GUILD_ROUTE, path);
        String feature = "DISCORD_REST";
        for (Map.Entry<Long, MusicPanelStateStore.PanelRef> entry : panelRefs.get().entrySet()) {
            MusicPanelStateStore.PanelRef panel = entry.getValue();
            if (Long.toUnsignedString(panel.channelId).equals(channelId)) {
                guildId = Long.toUnsignedString(entry.getKey());
                if (path.endsWith("/messages/" + Long.toUnsignedString(panel.messageId))) {
                    feature = "MUSIC_PANEL";
                }
                break;
            }
        }

        JDA jda = jdaSupplier.get();
        GuildChannel channel = jda == null || channelId == null ? null : jda.getGuildChannelById(channelId);
        Guild guild = channel == null
                ? (jda == null || guildId == null ? null : jda.getGuildById(guildId))
                : channel.getGuild();
        if (guild != null) {
            guildId = guild.getId();
        }

        LOGGER.warn("[RateLimit]\nguildId={}\nguildName={}\nchannelId={}\nchannelName={}\nfeature={}\nhttpStatus=429\nretryAfterMs={}",
                logValue(guildId),
                logValue(guild == null ? null : guild.getName()),
                logValue(channelId),
                logValue(channel == null ? null : channel.getName()),
                feature,
                retryAfterMillis(response));
    }

    private static String routeId(Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static long retryAfterMillis(Response response) {
        for (String header : new String[]{"Retry-After", "X-RateLimit-Reset-After"}) {
            String value = response.header(header);
            if (value == null) {
                continue;
            }
            try {
                long millis = new BigDecimal(value).movePointRight(3)
                        .setScale(0, RoundingMode.CEILING).longValueExact();
                if (millis >= 0L) {
                    return millis;
                }
            } catch (NumberFormatException | ArithmeticException ignored) {
                // Try the next Discord rate-limit header without affecting the response or retry.
            }
        }
        return -1L;
    }

    private static String logValue(String value) {
        return value == null ? "unknown" : value.replace('\r', ' ').replace('\n', ' ');
    }
}
