package com.norule.musicbot.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

enum RuntimeRepository {
    MAVEN_CENTRAL("central", "https://repo.maven.apache.org/maven2"),
    LAVALINK_RELEASES("lavalink-releases", "https://maven.lavalink.dev/releases"),
    TOPIWTF_RELEASES("topiwtf-releases", "https://maven.topi.wtf/releases");

    private final String id;
    private final String baseUrl;
    private final String resolverId;

    RuntimeRepository(String id, String baseUrl) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.resolverId = id + "-" + sha1(baseUrl);
    }

    String id() {
        return id;
    }

    String baseUrl() {
        return baseUrl;
    }

    static RuntimeRepository fromId(String id) {
        return Arrays.stream(values())
                .filter(repository -> repository.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown runtime dependency repository ID: " + id));
    }

    static RuntimeRepository fromResolverId(String id) {
        return Arrays.stream(values())
                .filter(repository -> repository.id.equals(id) || repository.resolverId.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Maven resolver repository ID: " + id));
    }

    private static String sha1(String url) {
        try {
            // Resolver nid_hurl hashes the exact URL; trailing slashes are significant.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not supported by this Java runtime", e);
        }
    }
}
