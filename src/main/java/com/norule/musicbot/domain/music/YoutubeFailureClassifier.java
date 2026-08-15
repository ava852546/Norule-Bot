package com.norule.musicbot.domain.music;

import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import org.apache.http.client.HttpResponseException;

import java.io.IOException;
import java.net.HttpRetryException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeFailureClassifier {
    private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)([45]\\d{2})(?!\\d)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|oauth(?:2)?(?:[_ -]?token)?|refresh[_ -]?token|po[_ -]?token|potoken|"
                    + "visitor[_ -]?data|sp_dc)\\s*[=:]\\s*([^\\s&,;]+)"
    );
    private static final int MAX_SAFE_MESSAGE_LENGTH = 240;

    public YoutubeFailureReport classify(Throwable failure) {
        AllClientsFailedException aggregate = findAllClientsFailure(failure);
        if (aggregate != null) {
            List<YoutubeClientFailure> clientFailures = aggregate.getClientExceptions().stream()
                    .map(this::classifyClientFailure)
                    .toList();
            YoutubeFailureCategory category = aggregateCategory(clientFailures);
            return new YoutubeFailureReport(
                    category,
                    recoveryClass(category),
                    firstHttpStatus(clientFailures),
                    clientFailures,
                    true
            );
        }

        ClassifiedFailure classified = classifyThrowable(failure);
        return new YoutubeFailureReport(
                classified.category(),
                recoveryClass(classified.category()),
                classified.httpStatus(),
                List.of(),
                false
        );
    }

    public boolean isYoutubeSourceFailure(Throwable failure) {
        for (Throwable current : throwableGraph(failure)) {
            Package exceptionPackage = current.getClass().getPackage();
            if (current instanceof AllClientsFailedException
                    || current instanceof ClientException
                    || (exceptionPackage != null
                    && exceptionPackage.getName().startsWith("dev.lavalink.youtube"))) {
                return true;
            }
        }
        return false;
    }

    YoutubeFailureCategory aggregateCategory(List<YoutubeClientFailure> failures) {
        if (failures == null || failures.isEmpty()) {
            return YoutubeFailureCategory.ALL_CLIENTS_FAILED;
        }
        return failures.stream()
                .map(YoutubeClientFailure::category)
                .max((left, right) -> Integer.compare(priority(left), priority(right)))
                .orElse(YoutubeFailureCategory.ALL_CLIENTS_FAILED);
    }

    private YoutubeClientFailure classifyClientFailure(ClientException failure) {
        ClassifiedFailure classified = classifyThrowable(failure);
        String clientName = failure.getClient() == null
                ? "UNKNOWN_CLIENT"
                : failure.getClient().getIdentifier();
        Throwable root = deepestCause(failure);
        return new YoutubeClientFailure(
                clientName,
                classified.category(),
                classified.httpStatus(),
                root == null ? failure.getClass().getSimpleName() : root.getClass().getSimpleName(),
                safeMessage(root == null ? failure : root)
        );
    }

    private ClassifiedFailure classifyThrowable(Throwable failure) {
        YoutubeFailureCategory best = YoutubeFailureCategory.UNKNOWN;
        Integer bestStatus = null;
        for (Throwable current : throwableGraph(failure)) {
            Integer status = structuredHttpStatus(current);
            YoutubeFailureCategory typeCategory = classifyType(current, status);
            if (priority(typeCategory) > priority(best)) {
                best = typeCategory;
                bestStatus = status;
            } else if (bestStatus == null && status != null) {
                bestStatus = status;
            }

            YoutubeFailureCategory messageCategory = classifyMessage(normalizedMessage(current));
            if (priority(messageCategory) > priority(best)) {
                best = messageCategory;
                Integer messageStatus = httpStatusFromMessage(normalizedMessage(current));
                if (messageStatus != null) {
                    bestStatus = messageStatus;
                }
            }
        }
        return new ClassifiedFailure(best, bestStatus);
    }

    private YoutubeFailureCategory classifyType(Throwable failure, Integer httpStatus) {
        if (failure instanceof YouTubePlaybackException playbackException) {
            return playbackException.category();
        }
        if (failure instanceof SocketTimeoutException || failure instanceof HttpTimeoutException) {
            return YoutubeFailureCategory.NETWORK_TIMEOUT;
        }
        if (httpStatus != null) {
            if (httpStatus == 403) {
                return YoutubeFailureCategory.HTTP_FORBIDDEN;
            }
            if (httpStatus == 400) {
                return YoutubeFailureCategory.HTTP_BAD_REQUEST;
            }
        }
        if (failure instanceof IOException) {
            return YoutubeFailureCategory.NETWORK_IO;
        }
        return YoutubeFailureCategory.UNKNOWN;
    }

    private YoutubeFailureCategory classifyMessage(String message) {
        if (message.isBlank()) {
            return YoutubeFailureCategory.UNKNOWN;
        }
        if (containsAny(message,
                "expected decoding to halt",
                "decoding the track",
                "decoder failure",
                "failed to decode",
                "aac decoder")) {
            return YoutubeFailureCategory.DECODER_FAILURE;
        }
        if (containsAny(message,
                "confirm you’re not a bot",
                "confirm you're not a bot",
                "confirm you are not a bot",
                "not a bot")) {
            return YoutubeFailureCategory.BOT_DETECTED;
        }
        if (containsAny(message,
                "requires login",
                "login required",
                "sign in to view",
                "must be signed in")) {
            return YoutubeFailureCategory.LOGIN_REQUIRED;
        }
        if (containsAny(message,
                "no supported audio streams",
                "no supported audio stream",
                "no supported formats")) {
            return YoutubeFailureCategory.NO_SUPPORTED_AUDIO_STREAM;
        }
        if (containsAny(message,
                "video player configuration error",
                "player configuration error")) {
            return YoutubeFailureCategory.PLAYER_CONFIGURATION_ERROR;
        }
        if (containsAny(message, "private video", "video is private")) {
            return YoutubeFailureCategory.VIDEO_PRIVATE;
        }
        if (containsAny(message, "age-restricted", "age restricted", "confirm your age")) {
            return YoutubeFailureCategory.VIDEO_AGE_RESTRICTED;
        }
        if (containsAny(message,
                "not available in your country",
                "not available in this country",
                "not available in your region",
                "region restricted")) {
            return YoutubeFailureCategory.REGION_RESTRICTED;
        }
        if (containsAny(message,
                "signature decipher",
                "signature extraction",
                "signature failure",
                "invalid signature")) {
            return YoutubeFailureCategory.SIGNATURE_FAILURE;
        }
        if (containsAny(message,
                "cipher server",
                "cipher failure",
                "failed to decipher",
                "cipher script")) {
            return YoutubeFailureCategory.CIPHER_FAILURE;
        }
        if (containsAny(message,
                "this video is not available",
                "video is unavailable",
                "video unavailable")) {
            return YoutubeFailureCategory.VIDEO_UNAVAILABLE;
        }
        if (containsAny(message, "timed out", "timeout")) {
            return YoutubeFailureCategory.NETWORK_TIMEOUT;
        }
        Integer status = httpStatusFromMessage(message);
        if (status != null) {
            if (status == 403) {
                return YoutubeFailureCategory.HTTP_FORBIDDEN;
            }
            if (status == 400) {
                return YoutubeFailureCategory.HTTP_BAD_REQUEST;
            }
        }
        if (containsAny(message,
                "connection reset",
                "connection refused",
                "broken pipe",
                "stream closed",
                "network is unreachable")) {
            return YoutubeFailureCategory.NETWORK_IO;
        }
        return YoutubeFailureCategory.UNKNOWN;
    }

    private YoutubeRecoveryClass recoveryClass(YoutubeFailureCategory category) {
        return switch (category) {
            case NETWORK_TIMEOUT, NETWORK_IO, COMPANION_UNAVAILABLE, COMPANION_TIMEOUT ->
                    YoutubeRecoveryClass.RETRYABLE;
            case BOT_DETECTED, LOGIN_REQUIRED -> YoutubeRecoveryClass.AUTH_MAY_HELP;
            case NO_SUPPORTED_AUDIO_STREAM, PLAYER_CONFIGURATION_ERROR,
                    HTTP_FORBIDDEN, HTTP_BAD_REQUEST, SIGNATURE_FAILURE, CIPHER_FAILURE ->
                    YoutubeRecoveryClass.CLIENT_FALLBACK_MAY_HELP;
            case DECODER_FAILURE -> YoutubeRecoveryClass.DECODER_FALLBACK_MAY_HELP;
            case VIDEO_UNAVAILABLE, VIDEO_PRIVATE, VIDEO_AGE_RESTRICTED, REGION_RESTRICTED,
                    COMPANION_STREAM_UNAVAILABLE ->
                    YoutubeRecoveryClass.PERMANENT;
            case ALL_CLIENTS_FAILED, UNKNOWN -> YoutubeRecoveryClass.UNKNOWN;
        };
    }

    private int priority(YoutubeFailureCategory category) {
        return switch (category) {
            case VIDEO_PRIVATE -> 1_000;
            case VIDEO_AGE_RESTRICTED -> 990;
            case REGION_RESTRICTED -> 980;
            case DECODER_FAILURE -> 900;
            case BOT_DETECTED -> 850;
            case LOGIN_REQUIRED -> 840;
            case NO_SUPPORTED_AUDIO_STREAM -> 800;
            case SIGNATURE_FAILURE -> 790;
            case CIPHER_FAILURE -> 780;
            case PLAYER_CONFIGURATION_ERROR -> 770;
            case NETWORK_TIMEOUT -> 700;
            case NETWORK_IO -> 690;
            case COMPANION_TIMEOUT -> 680;
            case COMPANION_UNAVAILABLE -> 670;
            case COMPANION_STREAM_UNAVAILABLE -> 660;
            case HTTP_FORBIDDEN -> 600;
            case HTTP_BAD_REQUEST -> 590;
            case VIDEO_UNAVAILABLE -> 500;
            case ALL_CLIENTS_FAILED -> 100;
            case UNKNOWN -> 0;
        };
    }

    private AllClientsFailedException findAllClientsFailure(Throwable failure) {
        for (Throwable current : throwableGraph(failure)) {
            if (current instanceof AllClientsFailedException allClientsFailed) {
                return allClientsFailed;
            }
        }
        return null;
    }

    private List<Throwable> throwableGraph(Throwable failure) {
        if (failure == null) {
            return List.of();
        }
        List<Throwable> ordered = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectThrowable(failure, ordered, visited);
        return ordered;
    }

    private void collectThrowable(Throwable failure, List<Throwable> ordered, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) {
            return;
        }
        ordered.add(failure);
        collectThrowable(failure.getCause(), ordered, visited);
        for (Throwable suppressed : failure.getSuppressed()) {
            collectThrowable(suppressed, ordered, visited);
        }
    }

    private Throwable deepestCause(Throwable failure) {
        Throwable deepest = failure;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (deepest != null && deepest.getCause() != null && visited.add(deepest)) {
            deepest = deepest.getCause();
        }
        return deepest;
    }

    private Integer structuredHttpStatus(Throwable failure) {
        if (failure instanceof HttpResponseException responseException) {
            return responseException.getStatusCode();
        }
        if (failure instanceof HttpRetryException retryException) {
            return retryException.responseCode();
        }
        return null;
    }

    private Integer firstHttpStatus(List<YoutubeClientFailure> failures) {
        return failures.stream()
                .filter(failure -> failure.httpStatus() != null)
                .map(YoutubeClientFailure::httpStatus)
                .findFirst()
                .orElse(null);
    }

    private Integer httpStatusFromMessage(String message) {
        Matcher matcher = HTTP_STATUS.matcher(message);
        while (matcher.find()) {
            int status = Integer.parseInt(matcher.group(1));
            if (status == 400 || status == 403) {
                return status;
            }
        }
        return null;
    }

    private boolean containsAny(String message, String... fragments) {
        for (String fragment : fragments) {
            if (message.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String normalizedMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private String safeMessage(Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure == null ? "-" : failure.getClass().getSimpleName();
        }
        String sanitized = SECRET_ASSIGNMENT.matcher(message)
                .replaceAll("$1=<redacted>")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.length() <= MAX_SAFE_MESSAGE_LENGTH
                ? sanitized
                : sanitized.substring(0, MAX_SAFE_MESSAGE_LENGTH);
    }

    private record ClassifiedFailure(YoutubeFailureCategory category, Integer httpStatus) {
    }
}
