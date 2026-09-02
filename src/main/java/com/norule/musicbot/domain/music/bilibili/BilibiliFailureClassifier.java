package com.norule.musicbot.domain.music.bilibili;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BilibiliFailureClassifier {
    private static final Pattern HTTP_STATUS = Pattern.compile("(?<!\\d)([45]\\d{2})(?!\\d)");

    public BilibiliFailureReport classify(Throwable failure, BilibiliFailureStage defaultStage) {
        BilibiliFailureStage stage = defaultStage == null ? BilibiliFailureStage.METADATA : defaultStage;
        if (failure == null) {
            return fallback(stage, false);
        }

        int httpStatus = 0;
        boolean retryableNetworkFailure = false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof BilibiliRequestException requestFailure) {
                int status = requestFailure.httpStatus();
                return report(requestFailure.category(), requestFailure.stage(), status);
            }
            String message = normalizedMessage(current);
            stage = inferStage(message, stage);
            int status = extractHttpStatus(message);
            if (status != 0) {
                httpStatus = status;
            }
            retryableNetworkFailure |= current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || message.contains("timed out")
                    || message.contains("connection reset")
                    || message.contains("broken pipe")
                    || message.contains("stream closed");
        }

        if (httpStatus == 412) {
            return report(BilibiliFailureCategory.BILIBILI_RISK_CONTROL, stage, httpStatus);
        }
        if (httpStatus == 403) {
            return report(BilibiliFailureCategory.BILIBILI_ACCESS_DENIED, stage, httpStatus);
        }
        if (httpStatus == 429) {
            return report(BilibiliFailureCategory.BILIBILI_RATE_LIMITED, stage, httpStatus);
        }
        BilibiliFailureCategory category = stage == BilibiliFailureStage.METADATA
                ? BilibiliFailureCategory.BILIBILI_METADATA_FAILED
                : BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED;
        boolean retryable = retryableNetworkFailure || httpStatus >= 500;
        return new BilibiliFailureReport(category, stage, httpStatus, retryable, false);
    }

    public boolean isBilibiliSourceFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof BilibiliRequestException) {
                return true;
            }
            if (normalizedMessage(current).contains("bilibili")) {
                return true;
            }
        }
        return false;
    }

    private BilibiliFailureReport fallback(BilibiliFailureStage stage, boolean retryable) {
        BilibiliFailureCategory category = stage == BilibiliFailureStage.METADATA
                ? BilibiliFailureCategory.BILIBILI_METADATA_FAILED
                : BilibiliFailureCategory.BILIBILI_PLAYBACK_FAILED;
        return new BilibiliFailureReport(category, stage, 0, retryable, false);
    }

    private BilibiliFailureReport report(BilibiliFailureCategory category,
                                         BilibiliFailureStage stage,
                                         int httpStatus) {
        boolean breakerFailure = httpStatus == 412 || httpStatus == 429;
        return new BilibiliFailureReport(category, stage, httpStatus, false, breakerFailure);
    }

    private BilibiliFailureStage inferStage(String message, BilibiliFailureStage fallback) {
        if (message.contains("metadata")) {
            return BilibiliFailureStage.METADATA;
        }
        if (message.contains("playback") || message.contains("audio stream")) {
            return BilibiliFailureStage.PLAYBACK;
        }
        return fallback;
    }

    private int extractHttpStatus(String message) {
        Matcher matcher = HTTP_STATUS.matcher(message);
        int status = 0;
        while (matcher.find()) {
            status = Integer.parseInt(matcher.group(1));
        }
        return status;
    }

    private String normalizedMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
