package com.norule.musicbot.gateway.minecraft;

import com.norule.musicbot.config.domain.MinecraftStatusConfig;
import com.norule.musicbot.domain.minecraft.MinecraftServerType;
import com.norule.musicbot.gateway.minecraft.dto.McSrvStatResponse;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class McSrvStatGateway implements MinecraftStatusGateway {
    private static final String JAVA_ENDPOINT_PREFIX = "https://api.mcsrvstat.us/3/";
    private static final String BEDROCK_ENDPOINT_PREFIX = "https://api.mcsrvstat.us/bedrock/3/";
    private static final String ICON_ENDPOINT_PREFIX = "https://api.mcsrvstat.us/icon/";
    private static final String DEFAULT_USER_AGENT = "NoRuleBot/1.0 contact: admin@norule.me";

    private final HttpClient httpClient;

    public McSrvStatGateway() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public McSrvStatResponse fetchStatus(String address,
                                         MinecraftServerType serverType,
                                         MinecraftStatusConfig config) throws IOException, InterruptedException {
        String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);
        String endpointPrefix = serverType == MinecraftServerType.BEDROCK ? BEDROCK_ENDPOINT_PREFIX : JAVA_ENDPOINT_PREFIX;
        String endpoint = endpointPrefix + encodedAddress;
        String userAgent = config == null || config.getUserAgent() == null || config.getUserAgent().isBlank()
                ? DEFAULT_USER_AGENT
                : config.getUserAgent();
        int timeoutMillis = config == null ? 15_000 : Math.max(1_000, config.getRequestTimeoutMillis());

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMillis))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("mcsrvstat.us returned HTTP " + response.statusCode());
        }

        DataObject json;
        try {
            json = DataObject.fromJson(response.body());
        } catch (Exception exception) {
            throw new IOException("Failed to parse mcsrvstat.us response", exception);
        }

        DataObject players = safeObject(json, "players");
        DataObject motd = safeObject(json, "motd");
        DataObject debug = safeObject(json, "debug");
        DataArray motdClean = safeArray(motd, "clean");

        List<String> motdLines = new ArrayList<>();
        for (int i = 0; i < motdClean.length(); i++) {
            motdLines.add(motdClean.getString(i));
        }

        return new McSrvStatResponse(
                json.getBoolean("online", false),
                json.getString("ip", ""),
                json.getInt("port", 0),
                json.getString("version", ""),
                players.getInt("online", 0),
                players.getInt("max", 0),
                motdLines,
                debug.getBoolean("cachehit", false)
        );
    }

    @Override
    public String buildIconUrl(String address) {
        return ICON_ENDPOINT_PREFIX + URLEncoder.encode(address == null ? "" : address, StandardCharsets.UTF_8);
    }

    private DataObject safeObject(DataObject source, String key) {
        try {
            DataObject object = source.getObject(key);
            return object == null ? DataObject.empty() : object;
        } catch (Exception ignored) {
            return DataObject.empty();
        }
    }

    private DataArray safeArray(DataObject source, String key) {
        try {
            DataArray array = source.getArray(key);
            return array == null ? DataArray.empty() : array;
        } catch (Exception ignored) {
            return DataArray.empty();
        }
    }
}
