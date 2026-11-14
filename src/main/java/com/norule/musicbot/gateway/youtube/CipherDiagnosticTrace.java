package com.norule.musicbot.gateway.youtube;

import dev.lavalink.youtube.cipher.CipherManager;
import dev.lavalink.youtube.track.format.StreamFormat;

import java.net.URI;

/** Opt-in, per-manager evidence. No URLs, scripts, signatures or tokens are retained. */
final class CipherDiagnosticTrace {
    boolean invoked;
    boolean resolveInvoked;
    String playerScript = "NOT_REQUESTED";
    String scriptMetadata = "NOT_REQUESTED";
    String functionParse = "NOT_INVOKED";
    String signature = "NOT_INVOKED";
    String n = "NOT_INVOKED";

    void scriptRequested() { invoked = true; scriptMetadata = "REQUESTED"; }
    void scriptMetadata(CipherManager.CachedPlayerScript script) {
        scriptMetadata = script != null && script.url != null && script.signatureTimestamp != null
                ? "SUCCESS" : "UNKNOWN";
    }
    void timestamp(String value) { scriptMetadata = value == null || value.isBlank() ? "UNKNOWN" : "SUCCESS"; }
    void scriptFailed() { scriptMetadata = "FAILED"; }
    void scriptFetched() { playerScript = "SUCCESS"; }
    void scriptFetchFailed() { playerScript = "FAILED"; }
    void notRequired() { signature = "NOT_REQUIRED"; n = "NOT_REQUIRED"; }
    void resolving(StreamFormat format) {
        invoked = true;
        resolveInvoked = true;
        functionParse = "UNKNOWN";
        signature = required(format.getSignature()) ? "UNKNOWN" : "NOT_REQUIRED";
        n = required(format.getNParameter()) ? "UNKNOWN" : "NOT_REQUIRED";
    }
    void resolved(StreamFormat format, URI result) {
        if (!validUrl(result)) {
            if (required(format.getSignature())) signature = "INVALID_RESULT";
            if (required(format.getNParameter())) n = "INVALID_RESULT";
            return;
        }
        if (required(format.getSignature())) {
            String value = parameter(result, format.getSignatureKey());
            signature = required(value) && !value.equals(format.getSignature()) ? "SUCCESS" : "INVALID_RESULT";
        }
        if (required(format.getNParameter())) {
            String value = parameter(result, "n");
            n = required(value) && !value.equals(format.getNParameter()) && !value.startsWith("enhanced_except_")
                    && !value.endsWith("_w8_" + format.getNParameter())
                    ? "SUCCESS" : "INVALID_RESULT";
        }
        // The combined upstream API does not expose independent parser callbacks.
        if ((!required(format.getSignature()) || "SUCCESS".equals(signature))
                && (!required(format.getNParameter()) || "SUCCESS".equals(n))) functionParse = "SUCCESS_INFERRED";
    }
    void resolveFailed() {
        // A combined call may throw before either transform; do not invent an individual failure.
        functionParse = "UNKNOWN";
    }
    static boolean validUrl(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && uri.getUserInfo() == null;
    }
    static String parameter(URI uri, String name) {
        return PolicyCipherManager.queryParameter(uri, name);
    }
    private static boolean required(String value) { return value != null && !value.isEmpty(); }
}
