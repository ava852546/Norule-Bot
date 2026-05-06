package com.norule.musicbot.service.minecraft;

import com.norule.musicbot.config.domain.MinecraftStatusConfig;
import com.norule.musicbot.domain.minecraft.MinecraftServerAddress;
import com.norule.musicbot.domain.minecraft.MinecraftServerStatus;
import com.norule.musicbot.domain.minecraft.MinecraftServerType;
import com.norule.musicbot.gateway.minecraft.MinecraftStatusGateway;
import com.norule.musicbot.gateway.minecraft.dto.McSrvStatResponse;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class MinecraftStatusService {
    private static final String DEFAULT_USER_AGENT = "NoRuleBot/1.0 contact: admin@norule.me";
    private static final int DEFAULT_REQUEST_TIMEOUT_MILLIS = 15_000;
    private static final int DEFAULT_INTERNAL_CACHE_SECONDS = 60;

    public record QueryResult(boolean success,
                              MinecraftServerStatus status,
                              String errorCode,
                              String errorMessage,
                              int statusCode) {
        public static QueryResult success(MinecraftServerStatus status) {
            return new QueryResult(true, status, "", "", 200);
        }

        public static QueryResult failure(String errorCode, String errorMessage, int statusCode) {
            return new QueryResult(false, null, errorCode, errorMessage, statusCode);
        }
    }

    private record CacheKey(String address, MinecraftServerType type) {}

    private record CacheEntry(MinecraftServerStatus status, long expiresAtMillis) {}

    private final MinecraftStatusGateway gateway;
    private final Supplier<MinecraftStatusConfig> configSupplier;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public MinecraftStatusService(MinecraftStatusGateway gateway, MinecraftStatusConfig config) {
        this(gateway, () -> config);
    }

    public MinecraftStatusService(MinecraftStatusGateway gateway, Supplier<MinecraftStatusConfig> configSupplier) {
        if (gateway == null) {
            throw new IllegalArgumentException("gateway cannot be null");
        }
        if (configSupplier == null) {
            throw new IllegalArgumentException("configSupplier cannot be null");
        }
        this.gateway = gateway;
        this.configSupplier = configSupplier;
    }

    public QueryResult query(String rawAddress, String rawType) {
        MinecraftStatusConfig config = effectiveConfig();
        MinecraftServerType serverType = MinecraftServerType.parseOrNull(rawType);
        if (serverType == null) {
            return QueryResult.failure("INVALID_TYPE", "type must be JAVA or BEDROCK", 400);
        }

        MinecraftServerAddress address = MinecraftServerAddress.of(rawAddress);
        if (!address.isValid()) {
            return QueryResult.failure("INVALID_ADDRESS", "Address is required and cannot contain http:// or https://", 400);
        }

        CacheKey cacheKey = new CacheKey(address.value(), serverType);
        long now = System.currentTimeMillis();
        CacheEntry cachedEntry = cache.get(cacheKey);
        if (cachedEntry != null && cachedEntry.expiresAtMillis > now) {
            return QueryResult.success(cachedEntry.status.withCached(true));
        }

        try {
            McSrvStatResponse upstream = gateway.fetchStatus(address.value(), serverType, config);
            MinecraftServerStatus status = mapToDomainStatus(address.value(), serverType, upstream);
            int cacheSeconds = Math.max(0, config.getInternalCacheSeconds());
            if (cacheSeconds > 0) {
                cache.put(cacheKey, new CacheEntry(status.withCached(false), now + cacheSeconds * 1000L));
            }
            return QueryResult.success(status);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return QueryResult.failure("UPSTREAM_INTERRUPTED", "Minecraft status request was interrupted", 502);
        } catch (IOException exception) {
            return QueryResult.failure("UPSTREAM_ERROR", "Failed to query Minecraft server status", 502);
        } catch (Exception exception) {
            return QueryResult.failure("INTERNAL_ERROR", "Failed to process Minecraft server status response", 500);
        }
    }

    private MinecraftServerStatus mapToDomainStatus(String address,
                                                    MinecraftServerType serverType,
                                                    McSrvStatResponse upstream) {
        String motd = "";
        if (upstream.motdLines() != null && !upstream.motdLines().isEmpty()) {
            motd = String.join("\n", upstream.motdLines().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .toList());
        }
        return new MinecraftServerStatus(
                upstream.online(),
                address,
                preferIpv4(address, upstream.ip()),
                Math.max(0, upstream.port()),
                emptyIfNull(upstream.version()),
                Math.max(0, upstream.playersOnline()),
                Math.max(0, upstream.playersMax()),
                motd,
                gateway.buildIconUrl(address),
                serverType,
                upstream.cacheHit()
        );
    }

    private MinecraftStatusConfig effectiveConfig() {
        MinecraftStatusConfig loaded = configSupplier.get();
        if (loaded != null) {
            return loaded;
        }
        return new MinecraftStatusConfig(DEFAULT_USER_AGENT, DEFAULT_REQUEST_TIMEOUT_MILLIS, DEFAULT_INTERNAL_CACHE_SECONDS);
    }

    private String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private String preferIpv4(String address, String upstreamIp) {
        String normalizedIp = emptyIfNull(upstreamIp).trim();
        if (normalizedIp.isBlank() || isIpv4Literal(normalizedIp)) {
            return normalizedIp;
        }

        String host = extractLookupHost(address);
        if (host.isBlank()) {
            return normalizedIp;
        }

        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            for (InetAddress one : resolved) {
                if (one instanceof Inet4Address ipv4) {
                    return ipv4.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return normalizedIp;
    }

    private String extractLookupHost(String address) {
        String value = emptyIfNull(address).trim();
        if (value.isBlank()) {
            return "";
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end > 1) {
                return value.substring(1, end);
            }
        }
        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return value.substring(0, firstColon);
        }
        return value;
    }

    private boolean isIpv4Literal(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String[] parts = value.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return false;
            }
            try {
                int one = Integer.parseInt(part);
                if (one < 0 || one > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }
}
