package com.norule.musicbot.gateway.youtube;

import com.norule.musicbot.domain.music.CipherPolicy;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import dev.lavalink.youtube.cipher.CipherManager;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

/** Lazy boundary guard: disabled mode never constructs or invokes a real cipher delegate. */
final class PolicyCipherManager implements CipherManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyCipherManager.class);
    private final CipherPolicy policy;
    private final Supplier<CipherManager> factory;
    private final CipherDiagnosticTrace diagnostic;
    private CipherManager delegate;
    private static final ThreadLocal<Scope> CURRENT_SCOPE = new ThreadLocal<>();

    static Scope metadataScope() {
        return scope("METADATA_DISCOVERY");
    }

    static Scope streamScope() {
        return scope("STREAM_EXTRACTION");
    }

    static boolean isStreamScope() {
        Scope current = CURRENT_SCOPE.get();
        return current != null && "STREAM_EXTRACTION".equals(current.stage);
    }

    boolean isAllowed() {
        return policy.isAllowed();
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
        this(policy, factory, null);
    }

    PolicyCipherManager(CipherPolicy policy, Supplier<CipherManager> factory, CipherDiagnosticTrace diagnostic) {
        this.policy = Objects.requireNonNull(policy);
        this.factory = Objects.requireNonNull(factory);
        this.diagnostic = diagnostic;
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
            format = withUrlNParameter(format);
            // A direct URL without signature/n transformation needs no cipher work.
            if (empty(format.getSignature()) && empty(format.getNParameter())) {
                if (diagnostic != null) diagnostic.notRequired();
                return format.getUrl();
            }
            CipherManager active = delegate();
            if (diagnostic != null) diagnostic.resolving(format);
            try {
                URI result = active.resolveFormatUrl(http, script, format);
                if (diagnostic != null) diagnostic.resolved(format, result);
                return result;
            } catch (IOException | RuntimeException failure) {
                if (diagnostic != null) diagnostic.resolveFailed();
                throw failure;
            }
        }
    }

    @Override
    public CachedPlayerScript getCachedPlayerScript(HttpInterface http) {
        synchronized (policy) {
            CipherManager active = delegate();
            if (diagnostic != null) diagnostic.scriptRequested();
            try {
                CachedPlayerScript result = active.getCachedPlayerScript(http);
                if (diagnostic != null) diagnostic.scriptMetadata(result);
                return result;
            } catch (RuntimeException failure) {
                if (diagnostic != null) diagnostic.scriptFailed();
                throw failure;
            }
        }
    }

    @Override
    public CachedPlayerScript getPlayerScript(HttpInterface http) {
        synchronized (policy) {
            CipherManager active = delegate();
            if (diagnostic != null) diagnostic.scriptRequested();
            try {
                CachedPlayerScript result = active.getPlayerScript(http);
                if (diagnostic != null) diagnostic.scriptMetadata(result);
                return result;
            } catch (RuntimeException failure) {
                if (diagnostic != null) diagnostic.scriptFailed();
                throw failure;
            }
        }
    }

    @Override
    public String getTimestamp(HttpInterface http, String script) throws IOException {
        synchronized (policy) {
            CipherManager active = delegate();
            if (diagnostic != null) diagnostic.scriptRequested();
            try {
                String result = active.getTimestamp(http, script);
                if (diagnostic != null) diagnostic.timestamp(result);
                return result;
            } catch (IOException | RuntimeException failure) {
                if (diagnostic != null) diagnostic.scriptFailed();
                throw failure;
            }
        }
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static StreamFormat withUrlNParameter(StreamFormat format) {
        if (!empty(format.getNParameter())) return format;
        // 1.18.2 parses a whole URL as form data and can miss n when it is the first query item.
        String n = queryParameter(format.getUrl(), "n");
        if (empty(n)) return format;
        return new StreamFormat(format.getType(), format.getItag(), format.getBitrate(), format.getContentLength(),
                format.getAudioChannels(), format.getUrl().toString(), n, format.getSignature(),
                format.getSignatureKey(), format.isDefaultAudioTrack(), format.isDrc());
    }

    static String queryParameter(URI uri, String name) {
        if (uri == null || name == null) return null;
        return org.apache.http.client.utils.URLEncodedUtils.parse(uri, StandardCharsets.UTF_8).stream()
                .filter(pair -> name.equals(pair.getName())).map(pair -> pair.getValue())
                .filter(Objects::nonNull).findFirst().orElse(null);
    }
}
