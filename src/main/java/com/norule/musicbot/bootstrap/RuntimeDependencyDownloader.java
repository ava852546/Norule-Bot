package com.norule.musicbot.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class RuntimeDependencyDownloader implements RuntimeDependencyBootstrap.ProgressArtifactDownloader {
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_RETRY_DELAY_MS = 30_000L;

    private final RuntimeDependencyBootstrap.BootstrapSettings settings;
    private final Map<RuntimeRepository, String> repositoryUrls;
    private final Consumer<String> output;
    private final LongSupplier nanoTime;
    private final RetrySleeper retrySleeper;
    private final ConnectionFactory connectionFactory;

    RuntimeDependencyDownloader(RuntimeDependencyBootstrap.BootstrapSettings settings, Consumer<String> output) {
        this(settings, defaultRepositoryUrls(), output, System::nanoTime, Thread::sleep);
    }

    RuntimeDependencyDownloader(RuntimeDependencyBootstrap.BootstrapSettings settings,
            Map<RuntimeRepository, String> repositoryUrls,
            Consumer<String> output, LongSupplier nanoTime, RetrySleeper retrySleeper) {
        this(settings, repositoryUrls, output, nanoTime, retrySleeper,
                uri -> (HttpURLConnection) uri.toURL().openConnection());
    }

    RuntimeDependencyDownloader(RuntimeDependencyBootstrap.BootstrapSettings settings,
            Map<RuntimeRepository, String> repositoryUrls,
            Consumer<String> output, LongSupplier nanoTime, RetrySleeper retrySleeper,
            ConnectionFactory connectionFactory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.repositoryUrls = Map.copyOf(repositoryUrls);
        this.output = Objects.requireNonNull(output, "output");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.retrySleeper = Objects.requireNonNull(retrySleeper, "retrySleeper");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public void download(RuntimeDependencyBootstrap.DependencyArtifact artifact, Path destination, int index, int total)
            throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= settings.maxRetries(); attempt++) {
            Files.deleteIfExists(destination);
            try {
                downloadFromManifestRepository(artifact, destination, index, total);
                return;
            } catch (IOException e) {
                lastFailure = e;
                Files.deleteIfExists(destination);
                if (attempt >= settings.maxRetries()) {
                    break;
                }
                int retryNumber = attempt + 1;
                long delayMs = retryDelayMs(retryNumber);
                output.accept(String.format(Locale.ROOT,
                        "[NoRule] Download failed %d/%d: %s repository=%s host=%s %s; retry %d/%d in %.1fs",
                        index, total, RuntimeDependencyBootstrap.buildJarFileName(artifact),
                        artifact.repository().id(), failureHost(e), failureKind(e), retryNumber, settings.maxRetries(),
                        delayMs / 1_000.0));
                sleepBeforeRetry(artifact, e, retryNumber, delayMs);
            }
        }
        throw terminalFailure(artifact, lastFailure, settings.maxRetries());
    }

    private void downloadFromManifestRepository(RuntimeDependencyBootstrap.DependencyArtifact artifact,
            Path destination,
            int index, int total) throws IOException {
        String relativePath = RuntimeDependencyBootstrap.toRelativeArtifactPath(artifact);
        String repositoryUrl = repositoryUrls.get(artifact.repository());
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new RepositoryDownloadException("none", "RepositoryNotConfigured");
        }
        URI uri = URI.create(RuntimeDependencyBootstrap.trimTrailingSlash(repositoryUrl) + "/" + relativePath);
        downloadFrom(uri, destination, artifact, index, total);
    }

    private void downloadFrom(URI uri, Path destination, RuntimeDependencyBootstrap.DependencyArtifact artifact,
            int index, int total) throws IOException {
        String host = safeHost(uri);
        HttpURLConnection connection = null;
        try {
            connection = openConnection(uri);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                closeQuietly(connection.getErrorStream());
                throw new HttpStatusException(host, status);
            }

            long contentLength = connection.getContentLengthLong();
            try (InputStream input = connection.getInputStream();
                    OutputStream target = Files.newOutputStream(destination, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                copyResponseBody(input, target, contentLength, artifact, index, total, host);
            }
        } catch (HttpStatusException | StallTimeoutException | IncompleteDownloadException e) {
            throw e;
        } catch (IOException e) {
            throw new RepositoryDownloadException(host, e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection openConnection(URI origin) throws IOException {
        URI current = origin;
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpURLConnection connection = connectionFactory.open(current);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(settings.connectTimeoutMs());
            connection.setReadTimeout(Math.min(settings.readTimeoutMs(), settings.stallTimeoutMs()));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "NoRule-RuntimeDependencyBootstrap/1");
            try {
                int status = connection.getResponseCode();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    return connection;
                }
                String location = connection.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("Missing repository redirect location");
                }
                URI next = current.resolve(location);
                if (!isTrustedRedirect(origin, next)) {
                    throw new IOException("Untrusted repository redirect");
                }
                current = next;
            } catch (IOException | IllegalArgumentException failure) {
                connection.disconnect();
                throw new IOException("Repository redirect failed", failure);
            }
            connection.disconnect();
        }
        throw new IOException("Too many repository redirects");
    }

    static boolean isTrustedRedirect(URI origin, URI target) {
        // Keep the pinned repository origin; never follow a downgrade or credential-bearing URL.
        return origin.getScheme().equalsIgnoreCase(target.getScheme())
                && origin.getHost() != null && origin.getHost().equalsIgnoreCase(target.getHost())
                && origin.getPort() == target.getPort()
                && target.getRawUserInfo() == null && target.getRawFragment() == null;
    }

    private void copyResponseBody(InputStream input, OutputStream target, long contentLength,
            RuntimeDependencyBootstrap.DependencyArtifact artifact, int index, int total, String host)
            throws IOException {
        long startedAt = nanoTime.getAsLong();
        long lastByteAt = startedAt;
        long downloadedBytes = 0L;
        DownloadProgressReporter reporter = new DownloadProgressReporter(settings.progressEnabled(),
                settings.progressIntervalMs(), index, total, RuntimeDependencyBootstrap.buildJarFileName(artifact),
                artifact.repository().id(), contentLength, startedAt, nanoTime, output);
        reporter.onStart();
        byte[] buffer = new byte[BUFFER_SIZE];
        while (true) {
            int read;
            try {
                read = input.read(buffer);
            } catch (SocketTimeoutException e) {
                if (settings.stallTimeoutMs() <= settings.readTimeoutMs()) {
                    throw new StallTimeoutException(host, settings.stallTimeoutMs());
                }
                throw new RepositoryDownloadException(host, SocketTimeoutException.class.getSimpleName());
            }
            if (read < 0) {
                break;
            }
            if (read == 0) {
                if (nanoTime.getAsLong() - lastByteAt >= settings.stallTimeoutMs() * NANOS_PER_MILLISECOND) {
                    throw new StallTimeoutException(host, settings.stallTimeoutMs());
                }
                continue;
            }
            target.write(buffer, 0, read);
            downloadedBytes += read;
            lastByteAt = nanoTime.getAsLong();
            reporter.onBytes(downloadedBytes);
        }
        if (contentLength >= 0L && downloadedBytes != contentLength) {
            throw new IncompleteDownloadException(host, contentLength, downloadedBytes);
        }
    }

    private void sleepBeforeRetry(RuntimeDependencyBootstrap.DependencyArtifact artifact, IOException failure,
            int retryNumber, long delayMs) throws IOException {
        try {
            retrySleeper.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Failed to download artifact "
                    + RuntimeDependencyBootstrap.buildJarFileName(artifact) + " host=" + failureHost(failure)
                    + " exception=InterruptedException retries=" + (retryNumber - 1) + "/" + settings.maxRetries());
        }
    }

    private static IOException terminalFailure(RuntimeDependencyBootstrap.DependencyArtifact artifact,
            IOException failure, int retries) {
        String host = failure == null ? "unknown" : failureHost(failure);
        String kind = failure == null ? "exception=UnknownDownloadFailure" : failureKind(failure);
        return new IOException("Failed to download artifact " + RuntimeDependencyBootstrap.buildJarFileName(artifact)
                + " repository=" + artifact.repository().id() + " host=" + host + " " + kind
                + " retries=" + retries + "/" + retries);
    }

    private static long retryDelayMs(int retryNumber) {
        int shift = Math.min(Math.max(0, retryNumber - 1), 30);
        return Math.min(MAX_RETRY_DELAY_MS, 1_000L << shift);
    }

    private static String failureHost(IOException failure) {
        if (failure instanceof SafeDownloadException safeFailure) {
            return safeFailure.host();
        }
        return "unknown";
    }

    private static String failureKind(IOException failure) {
        if (failure instanceof HttpStatusException statusFailure) {
            return "httpStatus=" + statusFailure.status();
        }
        if (failure instanceof RepositoryDownloadException repositoryFailure) {
            return "exception=" + repositoryFailure.exceptionType();
        }
        return "exception=" + failure.getClass().getSimpleName();
    }

    private static String safeHost(URI uri) {
        String host = uri.getHost();
        return host == null || host.isBlank() ? "unknown" : host;
    }

    private static void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
            // The connection is being discarded after a non-success HTTP response.
        }
    }

    private static Map<RuntimeRepository, String> defaultRepositoryUrls() {
        Map<RuntimeRepository, String> urls = new EnumMap<>(RuntimeRepository.class);
        for (RuntimeRepository repository : RuntimeRepository.values()) {
            urls.put(repository, repository.baseUrl());
        }
        return urls;
    }

    static final class DownloadProgressReporter {
        private final boolean enabled;
        private final long intervalNanos;
        private final int index;
        private final int total;
        private final String fileName;
        private final String repositoryId;
        private final long contentLength;
        private final long startedAt;
        private final LongSupplier nanoTime;
        private final Consumer<String> output;
        private long lastReportedAt;
        private long lastReportedBytes;

        DownloadProgressReporter(boolean enabled, int intervalMs, int index, int total, String fileName,
                String repositoryId, long contentLength, long startedAt, LongSupplier nanoTime,
                Consumer<String> output) {
            this.enabled = enabled;
            this.intervalNanos = intervalMs * NANOS_PER_MILLISECOND;
            this.index = index;
            this.total = total;
            this.fileName = fileName;
            this.repositoryId = repositoryId;
            this.contentLength = contentLength;
            this.startedAt = startedAt;
            this.nanoTime = nanoTime;
            this.output = output;
            this.lastReportedAt = startedAt;
        }

        void onStart() {
            if (enabled) {
                output.accept(formatProgress(index, total, fileName, repositoryId, 0L, contentLength, 0.0, 0.0));
            }
        }

        void onBytes(long downloadedBytes) {
            if (!enabled) {
                return;
            }
            long now = nanoTime.getAsLong();
            long interval = now - lastReportedAt;
            if (interval < intervalNanos) {
                return;
            }
            long intervalBytes = Math.max(0L, downloadedBytes - lastReportedBytes);
            double speed = interval <= 0L ? 0.0 : intervalBytes * (double) NANOS_PER_SECOND / interval;
            double elapsedSeconds = Math.max(0L, now - startedAt) / (double) NANOS_PER_SECOND;
            output.accept(formatProgress(index, total, fileName, repositoryId, downloadedBytes, contentLength, speed,
                    elapsedSeconds));
            lastReportedAt = now;
            lastReportedBytes = downloadedBytes;
        }
    }

    static String formatProgress(int index, int total, String fileName, String repositoryId, long downloadedBytes,
            long contentLength, double bytesPerSecond, double elapsedSeconds) {
        StringBuilder message = new StringBuilder(String.format(Locale.ROOT,
                "[NoRule] Downloading %d/%d: %s repository=%s downloaded=%d bytes",
                index, total, fileName, repositoryId, downloadedBytes));
        if (contentLength >= 0L) {
            double percentage = contentLength == 0L ? 100.0
                    : Math.min(100.0, downloadedBytes * 100.0 / contentLength);
            message.append(String.format(Locale.ROOT, " total=%d bytes progress=%.1f%%", contentLength, percentage));
        }
        message.append(String.format(Locale.ROOT, " speed=%.2f MB/s elapsed=%.1fs",
                bytesPerSecond / (1024.0 * 1024.0), elapsedSeconds));
        if (contentLength >= 0L && bytesPerSecond > 0.0) {
            double etaSeconds = Math.max(0L, contentLength - downloadedBytes) / bytesPerSecond;
            message.append(String.format(Locale.ROOT, " eta=%.1fs", etaSeconds));
        }
        return message.toString();
    }

    private interface SafeDownloadException {
        String host();
    }

    private static final class HttpStatusException extends IOException implements SafeDownloadException {
        private final String host;
        private final int status;

        private HttpStatusException(String host, int status) {
            super("HTTP status " + status + " from host=" + host);
            this.host = host;
            this.status = status;
        }

        @Override
        public String host() {
            return host;
        }

        private int status() {
            return status;
        }
    }

    private static final class StallTimeoutException extends IOException implements SafeDownloadException {
        private final String host;

        private StallTimeoutException(String host, int timeoutMs) {
            super("No new bytes from host=" + host + " for " + timeoutMs + "ms");
            this.host = host;
        }

        @Override
        public String host() {
            return host;
        }
    }

    private static final class IncompleteDownloadException extends IOException implements SafeDownloadException {
        private final String host;

        private IncompleteDownloadException(String host, long expectedBytes, long downloadedBytes) {
            super("Incomplete response from host=" + host + " expectedBytes=" + expectedBytes
                    + " downloadedBytes=" + downloadedBytes);
            this.host = host;
        }

        @Override
        public String host() {
            return host;
        }
    }

    private static final class RepositoryDownloadException extends IOException implements SafeDownloadException {
        private final String host;
        private final String exceptionType;

        private RepositoryDownloadException(String host, String exceptionType) {
            super("Download exception from host=" + host + " type=" + exceptionType);
            this.host = host;
            this.exceptionType = exceptionType;
        }

        @Override
        public String host() {
            return host;
        }

        private String exceptionType() {
            return exceptionType;
        }
    }

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URI uri) throws IOException;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }
}
