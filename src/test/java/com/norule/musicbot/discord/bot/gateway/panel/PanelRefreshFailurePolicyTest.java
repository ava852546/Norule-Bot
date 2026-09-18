package com.norule.musicbot.discord.bot.gateway.panel;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelRefreshFailurePolicyTest {
    private final PanelRefreshFailurePolicy policy = new PanelRefreshFailurePolicy(Duration.ofMinutes(10));

    @Test
    void allRequiredPermissionsAllowRefresh() {
        EnumSet<Permission> granted = EnumSet.of(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS
        );

        assertNull(policy.firstMissingPermission(granted::contains));
    }

    @Test
    void missingSendOrViewPermissionSkipsBeforeEdit() {
        EnumSet<Permission> withoutSend = EnumSet.of(
                Permission.VIEW_CHANNEL,
                Permission.MESSAGE_EMBED_LINKS
        );
        EnumSet<Permission> withoutView = EnumSet.of(
                Permission.MESSAGE_SEND,
                Permission.MESSAGE_EMBED_LINKS
        );

        assertEquals(Permission.MESSAGE_SEND, policy.firstMissingPermission(withoutSend::contains));
        assertEquals(Permission.VIEW_CHANNEL, policy.firstMissingPermission(withoutView::contains));
    }

    @Test
    void unknownMessageIsExpectedAndClearsStaleState() {
        PanelRefreshFailurePolicy.PanelFailure failure = policy.classify(
                discordError(ErrorResponse.UNKNOWN_MESSAGE, 404)
        );

        assertEquals(PanelRefreshFailurePolicy.FailureDisposition.CLEAR_STATE, failure.disposition());
        assertEquals("UNKNOWN_MESSAGE", failure.reason());
    }

    @Test
    void missingAccessClearsStateWithoutAutomaticRetry() {
        PanelRefreshFailurePolicy.PanelFailure failure = policy.classify(
                discordError(ErrorResponse.MISSING_ACCESS, 403)
        );

        assertEquals(PanelRefreshFailurePolicy.FailureDisposition.CLEAR_STATE, failure.disposition());
        assertEquals("MISSING_ACCESS", failure.reason());
    }

    @Test
    void unexpectedRuntimeFailureRemainsUnexpectedForStackTraceLogging() {
        PanelRefreshFailurePolicy.PanelFailure failure = policy.classify(
                new IllegalStateException("renderer bug")
        );

        assertEquals(PanelRefreshFailurePolicy.FailureDisposition.UNEXPECTED, failure.disposition());
    }

    @Test
    void suppressesSameWarningForTenMinutesAndSuccessClearsIt() {
        assertTrue(policy.shouldLog(1L, 2L, 100L, "MISSING_PERMISSION", 1_000L));
        assertFalse(policy.shouldLog(1L, 2L, 100L, "MISSING_PERMISSION", 599_999L));
        assertTrue(policy.shouldLog(1L, 2L, 100L, "MISSING_PERMISSION", 601_000L));
        assertTrue(policy.shouldLog(1L, 2L, 200L, "MISSING_PERMISSION", 601_000L));

        policy.clearChannel(1L, 2L);

        assertTrue(policy.shouldLog(1L, 2L, 100L, "MISSING_PERMISSION", 601_001L));
    }

    private ErrorResponseException discordError(ErrorResponse error, int status) {
        okhttp3.Response rawResponse = new okhttp3.Response.Builder()
                .request(new Request.Builder().url("https://discord.test/api/v10/channels/1/messages/2").build())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("test")
                .body(ResponseBody.create("{\"message\":\"test\",\"code\":" + error.getCode() + "}", null))
                .build();
        return ErrorResponseException.create(error, new Response(rawResponse, 0L, Set.of()));
    }
}
