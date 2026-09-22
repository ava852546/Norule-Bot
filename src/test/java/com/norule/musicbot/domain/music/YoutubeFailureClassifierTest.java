package com.norule.musicbot.domain.music;

import com.norule.musicbot.config.domain.MusicConfig;
import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import dev.lavalink.youtube.clients.skeleton.Client;
import org.junit.jupiter.api.Test;
import org.apache.http.client.HttpResponseException;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeFailureClassifierTest {
    private final YoutubeFailureClassifier classifier = new YoutubeFailureClassifier();

    @Test
    void classifiesKnownIndividualClientFailures() {
        assertCategory(YoutubeFailureCategory.HTTP_FORBIDDEN, "Not success status code: 403");
        assertCategory(YoutubeFailureCategory.BOT_DETECTED, "Sign in to confirm you’re not a bot");
        assertCategory(YoutubeFailureCategory.LOGIN_REQUIRED, "This video requires login");
        assertCategory(YoutubeFailureCategory.NO_SUPPORTED_AUDIO_STREAM, "No supported audio streams available");
        assertCategory(YoutubeFailureCategory.DECODER_FAILURE, "Expected decoding to halt, got: 5");
        assertCategory(YoutubeFailureCategory.HTTP_BAD_REQUEST,
                "Invalid status code for player api response: 400");
        assertCategory(YoutubeFailureCategory.VIDEO_UNAVAILABLE, "This video is not available");
        assertCategory(YoutubeFailureCategory.UNKNOWN, "unrecognized youtube failure");
    }

    @Test
    void extractsPerClientFailuresFromYoutubeSource1181Api() {
        YoutubeFailureReport report = classifier.classify(aggregate(
                clientFailure("WEB", "No supported audio streams available"),
                clientFailure("MWEB", "Not success status code: 403"),
                clientFailure("WEB_EMBEDDED_PLAYER", "Video player configuration error"),
                clientFailure("TVHTML5_SIMPLY", "Sign in to confirm you’re not a bot"),
                clientFailure("ANDROID_VR", "This video is not available"),
                clientFailure("ANDROID_MUSIC", "This video requires login"),
                clientFailure("IOS", "Invalid status code for player api response: 400")
        ));

        Map<String, YoutubeFailureCategory> clients = report.clientFailures().stream()
                .collect(Collectors.toMap(YoutubeClientFailure::clientName, YoutubeClientFailure::category));

        assertTrue(report.allClientsFailed());
        assertEquals(YoutubeFailureCategory.BOT_DETECTED, report.category());
        assertEquals(YoutubeRecoveryClass.AUTH_MAY_HELP, report.recoveryClass());
        assertEquals(YoutubeFailureCategory.NO_SUPPORTED_AUDIO_STREAM, clients.get("WEB"));
        assertEquals(YoutubeFailureCategory.HTTP_FORBIDDEN, clients.get("MWEB"));
        assertEquals(YoutubeFailureCategory.PLAYER_CONFIGURATION_ERROR, clients.get("WEB_EMBEDDED_PLAYER"));
        assertEquals(YoutubeFailureCategory.BOT_DETECTED, clients.get("TVHTML5_SIMPLY"));
        assertEquals(YoutubeFailureCategory.VIDEO_UNAVAILABLE, clients.get("ANDROID_VR"));
        assertEquals(YoutubeFailureCategory.LOGIN_REQUIRED, clients.get("ANDROID_MUSIC"));
        assertEquals(YoutubeFailureCategory.HTTP_BAD_REQUEST, clients.get("IOS"));
    }

    @Test
    void decoderEvidenceIsNotOverwrittenByOtherClient403Responses() {
        YoutubeFailureReport report = classifier.classify(aggregate(
                clientFailure("WEB", "Expected decoding to halt, got: 5"),
                clientFailure("MWEB", "Not success status code: 403"),
                clientFailure("IOS", "Not success status code: 403")
        ));

        assertEquals(YoutubeFailureCategory.DECODER_FAILURE, report.category());
        assertEquals(YoutubeRecoveryClass.DECODER_FALLBACK_MAY_HELP, report.recoveryClass());
    }

    @Test
    void recognizesPlayerSignatureExtractionFailure() {
        YoutubeFailureReport report = classifier.classify(new IllegalStateException(
                "Must find sig function from script: /s/player/4fd832e7/player_embed.vflset/zh_TW/base.js"
        ));

        assertEquals(YoutubeFailureCategory.SIGNATURE_FAILURE, report.category());
        assertEquals(YoutubeRecoveryClass.CLIENT_FALLBACK_MAY_HELP, report.recoveryClass());
    }

    @Test
    void httpResponseIsNotMisclassifiedAsGenericNetworkIo() {
        for (int status : List.of(400, 403)) {
            YoutubeFailureCategory expected = status == 400
                    ? YoutubeFailureCategory.HTTP_BAD_REQUEST
                    : YoutubeFailureCategory.HTTP_FORBIDDEN;
            for (IOException cause : List.of(
                    new IOException("Invalid status code for player api response: " + status),
                    new HttpResponseException(status, "Player request rejected")
            )) {
                YoutubeFailureReport report = classifier.classify(new IOException("Playback failed", cause));

                assertEquals(expected, report.category());
                assertEquals(status, report.httpStatus());
                assertEquals(YoutubeRecoveryClass.CLIENT_FALLBACK_MAY_HELP, report.recoveryClass());
            }
        }
        YoutubeFailureReport network = classifier.classify(new IOException("Transport failed"));
        assertEquals(YoutubeFailureCategory.NETWORK_IO, network.category());
        assertEquals(YoutubeRecoveryClass.RETRYABLE, network.recoveryClass());
    }

    @Test
    void reportedMixedFailuresRetainSpecificClientDiagnostics() {
        YoutubeFailureReport report = classifier.classify(aggregate(
                clientFailure("WEB", "No supported audio streams available, available types: "),
                clientFailure("MWEB", "Must find sig function from script: /s/player/4fd832e7/base.js"),
                clientFailure("WEB_EMBEDDED_PLAYER", "This video is unavailable"),
                clientFailure("TVHTML5_SIMPLY", "Sign in to confirm you're not a bot"),
                clientFailure("ANDROID_VR", "This video requires login."),
                clientFailure("ANDROID_MUSIC", "This video requires login."),
                new ClientException("Player request failed", client("IOS"),
                        new IOException("Invalid status code for player api response: 400"))
        ));

        assertEquals(YoutubeFailureCategory.BOT_DETECTED, report.category());
        assertEquals(YoutubeRecoveryClass.AUTH_MAY_HELP, report.recoveryClass());
        assertEquals(400, report.httpStatus());
        assertTrue(report.clientsSummary().contains("MWEB:SIGNATURE_FAILURE"));
        assertTrue(report.clientsSummary().contains("IOS:HTTP_BAD_REQUEST"));
        assertFalse(report.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.NONE));
    }

    @Test
    void recoveryPolicyIsFiniteAndAuthAware() {
        YoutubeFailureReport fallback = classifier.classify(new RuntimeException("No supported audio streams available"));
        YoutubeFailureReport auth = classifier.classify(new RuntimeException("This video requires login"));
        YoutubeFailureReport permanent = classifier.classify(new RuntimeException("This is a private video"));

        assertTrue(fallback.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.NONE));
        assertFalse(auth.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.NONE));
        assertTrue(auth.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.POT));
        assertFalse(permanent.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.OAUTH));
    }

    @Test
    void companionRecoveryAndHttpStatusPreserveFailureSemantics() {
        YoutubeFailureReport stream = classifier.classify(new YouTubePlaybackException(
                YoutubeFailureCategory.COMPANION_STREAM_UNAVAILABLE,
                "temporary stream failure"
        ));
        YoutubeFailureReport auth = classifier.classify(new YouTubePlaybackException(
                YoutubeFailureCategory.COMPANION_AUTH_FAILED,
                "authentication failed",
                401,
                null
        ));
        YoutubeFailureReport badRequest = classifier.classify(new YouTubePlaybackException(
                YoutubeFailureCategory.COMPANION_BAD_REQUEST,
                "bad request",
                400,
                null
        ));

        assertEquals(YoutubeRecoveryClass.RETRYABLE, stream.recoveryClass());
        assertTrue(stream.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.NONE));
        assertEquals(YoutubeRecoveryClass.CONFIGURATION_ERROR, auth.recoveryClass());
        assertEquals(401, auth.httpStatus());
        assertFalse(auth.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.POT));
        assertEquals(YoutubeRecoveryClass.CONFIGURATION_ERROR, badRequest.recoveryClass());
        assertEquals(400, badRequest.httpStatus());
    }

    private void assertCategory(YoutubeFailureCategory expected, String message) {
        assertEquals(expected, classifier.classify(new RuntimeException(message)).category());
    }

    @Test
    void genericFriendlyWrapperIsNotDecoderEvidence() {
        var cause = new YouTubePlaybackException(YoutubeFailureCategory.COMPANION_BAD_REQUEST, "rejected", 400, null);
        var wrapper = new com.sedmelluq.discord.lavaplayer.tools.FriendlyException(
                "Something went wrong when decoding the track", com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.FAULT, cause);
        assertEquals(YoutubeFailureCategory.COMPANION_BAD_REQUEST, classifier.classify(wrapper).category());
        assertEquals(400, classifier.classify(wrapper).httpStatus());
        assertFalse(classifier.classify(wrapper).allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.NONE));
        assertEquals(YoutubeFailureCategory.HTTP_BAD_REQUEST, classifier.classify(
                new RuntimeException("Something went wrong when decoding the track", new HttpResponseException(400, "rejected"))).category());
        assertCategory(YoutubeFailureCategory.UNKNOWN, "Something went wrong when decoding the track");
    }

    @Test
    void mixedPolicyLoginAndHttpFailuresAreAllRetainedAndRedacted() {
        var report = classifier.classify(aggregate(
                new ClientException("blocked", client("WEB"), new CipherDisabledException()),
                clientFailure("ANDROID_VR", "This video requires login"),
                new ClientException("request", client("IOS"), new HttpResponseException(400,
                        "https://fixture.googlevideo.com/audio?sig=private Cookie=secret visitorId=private"))));
        assertEquals(3, report.clientFailures().size());
        assertEquals(YoutubeFailureCategory.CIPHER_REQUIRED_BUT_DISABLED, report.category());
        assertTrue(report.clientsSummary().contains("ANDROID_VR:LOGIN_REQUIRED"));
        assertTrue(report.clientsSummary().contains("IOS:HTTP_BAD_REQUEST"));
        assertFalse(report.clientFailures().get(2).safeMessage().contains("private"));
        assertFalse(report.clientFailures().get(2).safeMessage().contains("secret"));
        assertFalse(report.allowsPlaybackRecovery(MusicConfig.Youtube.AuthMode.OAUTH));
    }

    private AllClientsFailedException aggregate(ClientException... failures) {
        return new AllClientsFailedException(List.of(failures));
    }

    private ClientException clientFailure(String clientName, String causeMessage) {
        return new ClientException(
                causeMessage,
                client(clientName),
                new RuntimeException(causeMessage)
        );
    }

    private Client client(String identifier) {
        return (Client) Proxy.newProxyInstance(
                Client.class.getClassLoader(),
                new Class<?>[] {Client.class},
                (ignored, method, args) -> {
                    if ("getIdentifier".equals(method.getName())) {
                        return identifier;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
