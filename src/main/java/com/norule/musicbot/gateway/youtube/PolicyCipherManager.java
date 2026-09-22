package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.CipherPolicy;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.cipher.CipherManager;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;

/** Lazy boundary guard: disabled mode never constructs or invokes a real cipher delegate. */
final class PolicyCipherManager implements CipherManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyCipherManager.class);
    private final CipherPolicy policy;
    private final Supplier<CipherManager> factory;
    private CipherManager delegate;
    private static final ThreadLocal<Scope> CURRENT_SCOPE = new ThreadLocal<>();

    static Scope metadataScope() {
        return scope("METADATA_DISCOVERY");
    }

    static Scope streamScope() {
        return scope("STREAM_EXTRACTION");
    }

    private static Scope scope(String stage) {
        Scope scope = new Scope(CURRENT_SCOPE.get(), stage);
        CURRENT_SCOPE.set(scope);
        return scope;
    }

    static final class Scope implements AutoCloseable {
        private final Scope previous;
        private final String stage;
        private boolean blocked;

        private Scope(Scope previous, String stage) {
            this.previous = previous;
            this.stage = stage;
        }

        @Override
        public void close() {
            if (previous == null) CURRENT_SCOPE.remove();
            else CURRENT_SCOPE.set(previous);
        }
    }

    PolicyCipherManager(CipherPolicy policy, Supplier<CipherManager> factory) {
        this.policy = Objects.requireNonNull(policy);
        this.factory = Objects.requireNonNull(factory);
    }

    void requirePlayerScriptAllowed() {
        Scope scope = CURRENT_SCOPE.get();
        if (!policy.isAllowed() && (scope == null || !scope.blocked)) {
            LOGGER.warn("[NoRule] Cipher request blocked: reason=CIPHER_DISABLED backend=YOUTUBE_SOURCE "
                    + "stage={} cipherAllowed=false cipherAttempted=false cipherBlocked=true",
                    scope == null ? "STREAM_EXTRACTION" : scope.stage);
            if (scope != null) scope.blocked = true;
        }
        policy.requireAllowed();
    }

    private CipherManager delegate() {
        requirePlayerScriptAllowed();
        if (delegate == null) {
            delegate = factory.get();
        }
        return delegate;
    }

    @Override
    public URI resolveFormatUrl(HttpInterface http, String script, StreamFormat format) throws IOException {
        synchronized (policy) {
            // A direct URL without signature/n transformation needs no cipher work.
            if (!policy.isAllowed() && empty(format.getSignature()) && empty(format.getNParameter())) {
                return format.getUrl();
            }
            return delegate().resolveFormatUrl(http, script, format);
        }
    }

    @Override
    public CachedPlayerScript getCachedPlayerScript(HttpInterface http) {
        synchronized (policy) {
            return delegate().getCachedPlayerScript(http);
        }
    }

    @Override
    public CachedPlayerScript getPlayerScript(HttpInterface http) {
        synchronized (policy) {
            return delegate().getPlayerScript(http);
        }
    }

    @Override
    public String getTimestamp(HttpInterface http, String script) throws IOException {
        synchronized (policy) {
            return delegate().getTimestamp(http, script);
        }
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }
}
