package com.norule.musicbot.service.shorturl;

import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class TurnstileVerifier {
    public record Options(boolean enabled, String siteKey, String secret, String hostname) {
        public Options {
            siteKey = siteKey == null ? "" : siteKey.trim();
            secret = secret == null ? "" : secret.trim();
            hostname = hostname == null ? "" : hostname.trim();
            if (enabled && (siteKey.isBlank() || secret.isBlank() || hostname.isBlank())) {
                throw new IllegalArgumentException("Enabled Turnstile requires siteKey, TURNSTILE_SECRET and public hostname");
            }
        }
        public static Options disabled() { return new Options(false, "", "", ""); }
        @Override public String toString() { return "TurnstileOptions[enabled=" + enabled + "]"; }
    }

    public enum Result { ALLOWED, REJECTED, UNAVAILABLE }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final HttpClient client;
    private final URI endpoint;
    private volatile Options options = Options.disabled();

    public TurnstileVerifier() {
        this(CLIENT, URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify"));
    }

    TurnstileVerifier(HttpClient client, URI endpoint) {
        this.client = client;
        this.endpoint = endpoint;
    }

    public void updateOptions(Options options) { this.options = options; }
    public Options options() { return options; }

    public Result verify(String token, String address) {
        Options current = options;
        if (!current.enabled()) return Result.ALLOWED;
        if (token == null || token.isBlank() || token.length() > 2048) return Result.REJECTED;
        DataObject body = DataObject.empty().put("secret", current.secret()).put("response", token);
        if (address != null && !"unknown".equals(address)) body.put("remoteip", address);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Result.UNAVAILABLE;
            DataObject result = DataObject.fromJson(response.body());
            return result.getBoolean("success", false)
                    && current.hostname().equalsIgnoreCase(result.getString("hostname", ""))
                    ? Result.ALLOWED : Result.REJECTED;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return Result.UNAVAILABLE;
        } catch (IOException | RuntimeException ignored) {
            // Fail closed without logging secrets, challenge tokens or the provider response.
            return Result.UNAVAILABLE;
        }
    }
}
