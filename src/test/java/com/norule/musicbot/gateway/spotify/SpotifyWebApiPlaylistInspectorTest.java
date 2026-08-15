package com.norule.musicbot.gateway.spotify;

import com.norule.musicbot.config.domain.MusicConfig;
import com.norule.musicbot.domain.music.SpotifyPlaylistInspector;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyWebApiPlaylistInspectorTest {
    private static final String USER_PLAYLIST_ID = "2C6ZhaaFawulwECyZm0arY";
    private static final String SPOTIFY_PLAYLIST_ID = "37i9dQZF1DWVta2VrIys6y";
    private static final String READABLE_ITEMS = """
            {
              "items": [
                {"item": {"id": "track-1", "type": "track", "is_playable": true}}
              ],
              "total": 1
            }
            """;

    @Test
    void classifiesReadableUserPlaylistFromMetadata() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("user-owner", true, "Public mix", "User description", 1),
                200,
                READABLE_ITEMS
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, USER_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.READABLE, result.outcome());
        assertEquals(SpotifyPlaylistInspector.PlaylistClassification.USER_PLAYLIST, result.classification());
        assertEquals("user-owner", result.metadata().ownerId());
        assertEquals(true, result.metadata().publicPlaylist());
        assertEquals("Public mix", result.metadata().name());
        assertEquals("User description", result.metadata().description());
        assertEquals(1, result.metadata().declaredItemCount());
        assertTrue(transport.requestPaths().stream().anyMatch(path -> path.endsWith("/" + USER_PLAYLIST_ID)));
        assertTrue(transport.requestPaths().stream().anyMatch(path -> path.endsWith("/" + USER_PLAYLIST_ID + "/items")));
    }

    @Test
    void spotifyOwnedPlaylistRemainsReadableWhenItemsContainTrack() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Spotify mix", "Algorithmic", 1),
                200,
                READABLE_ITEMS
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.READABLE, result.outcome());
        assertEquals(SpotifyPlaylistInspector.PlaylistClassification.SPOTIFY_OWNED_PLAYLIST,
                result.classification());
    }

    @Test
    void spotifyOwnedPlaylistWithForbiddenItemsIsRestricted() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Restricted mix", "Personalized", 20),
                403,
                spotifyError(403, "Forbidden")
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_RESTRICTED_OR_PERSONALIZED,
                result.outcome());
        assertEquals(SpotifyPlaylistInspector.PlaylistClassification.SPOTIFY_OWNED_PLAYLIST,
                result.classification());
    }

    @Test
    void missingItemsEndpointAfterMetadataIsRestricted() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Missing mix", "Personalized", 20),
                404,
                spotifyError(404, "Not found")
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_RESTRICTED_OR_PERSONALIZED,
                result.outcome());
        assertEquals(404, result.statusCode());
    }

    @Test
    void preservesMetadata404StatusForLoadFailureContext() {
        ScenarioTransport transport = new ScenarioTransport(
                404,
                spotifyError(404, "Resource not found"),
                200,
                READABLE_ITEMS
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.UNAVAILABLE, result.outcome());
        assertEquals(SpotifyPlaylistInspector.PlaylistClassification.UNKNOWN, result.classification());
        assertEquals(404, result.statusCode());
    }

    @Test
    void unauthorizedItemsAreAuthenticationFailure() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Auth mix", "", 20),
                401,
                spotifyError(401, "The access token expired")
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_AUTH_FAILED, result.outcome());
    }

    @Test
    void rateLimitedItemsAreNotClassifiedAsPersonalized() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Busy mix", "", 20),
                429,
                spotifyError(429, "Too many requests")
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_RATE_LIMITED, result.outcome());
    }

    @Test
    void genuinelyEmptyPlaylistIsEmptyRatherThanRestricted() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("user-owner", true, "Empty mix", "", 0),
                200,
                "{\"items\":[],\"total\":0}"
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, USER_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_PLAYLIST_EMPTY, result.outcome());
    }

    @Test
    void nonEmptyMetadataWithEmptyItemsIsRestricted() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Hidden mix", "", 25),
                200,
                "{\"items\":[],\"total\":0}"
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_RESTRICTED_OR_PERSONALIZED,
                result.outcome());
    }

    @Test
    void missingItemsFieldIsRestrictedWhenMetadataWasRead() {
        ScenarioTransport transport = new ScenarioTransport(
                200,
                metadata("spotify", true, "Hidden mix", "", 25),
                200,
                "{\"total\":25}"
        );

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.SPOTIFY_RESTRICTED_OR_PERSONALIZED,
                result.outcome());
    }

    @Test
    void checksLaterItemPagesBeforeDeclaringPlaylistRestricted() {
        AtomicInteger itemRequests = new AtomicInteger();
        SpotifyWebApiPlaylistInspector.HttpTransport transport = request -> {
            if ("accounts.spotify.com".equals(request.uri().getHost())) {
                return completed(200, "{\"access_token\":\"test-token\",\"expires_in\":3600}");
            }
            if (!request.uri().getPath().endsWith("/items")) {
                return completed(200, metadata("spotify", true, "Paged mix", "", 2));
            }
            itemRequests.incrementAndGet();
            if (request.uri().getQuery().contains("offset=0")) {
                return completed(200, "{\"items\":[{\"item\":null}],\"total\":2}");
            }
            return completed(200, READABLE_ITEMS.replace("\"total\": 1", "\"total\": 2"));
        };

        SpotifyPlaylistInspector.Inspection result = inspect(transport, SPOTIFY_PLAYLIST_ID);

        assertEquals(SpotifyPlaylistInspector.Outcome.READABLE, result.outcome());
        assertEquals(2, itemRequests.get());
    }

    private SpotifyPlaylistInspector.Inspection inspect(SpotifyWebApiPlaylistInspector.HttpTransport transport,
                                                         String playlistId) {
        SpotifyWebApiPlaylistInspector inspector = new SpotifyWebApiPlaylistInspector(transport);
        return inspector.inspect(
                "https://open.spotify.com/playlist/" + playlistId,
                "test/playlist",
                spotifyConfig()
        ).join();
    }

    private MusicConfig.Spotify spotifyConfig() {
        return new MusicConfig.Spotify(true, "client-id", "client-secret", "", "TW", false, 100, 0);
    }

    private static String metadata(String ownerId,
                                   boolean publicPlaylist,
                                   String name,
                                   String description,
                                   int total) {
        return """
                {
                  "owner": {"id": "%s"},
                  "public": %s,
                  "name": "%s",
                  "description": "%s",
                  "items": {"total": %d}
                }
                """.formatted(ownerId, publicPlaylist, name, description, total);
    }

    private static String spotifyError(int status, String message) {
        return "{\"error\":{\"status\":" + status + ",\"message\":\"" + message + "\"}}";
    }

    private static CompletableFuture<SpotifyWebApiPlaylistInspector.ApiResponse> completed(int status,
                                                                                            String body) {
        return CompletableFuture.completedFuture(new SpotifyWebApiPlaylistInspector.ApiResponse(status, body));
    }

    private static final class ScenarioTransport implements SpotifyWebApiPlaylistInspector.HttpTransport {
        private final int metadataStatus;
        private final String metadataBody;
        private final int itemsStatus;
        private final String itemsBody;
        private final List<HttpRequest> requests = new ArrayList<>();

        private ScenarioTransport(int metadataStatus,
                                  String metadataBody,
                                  int itemsStatus,
                                  String itemsBody) {
            this.metadataStatus = metadataStatus;
            this.metadataBody = metadataBody;
            this.itemsStatus = itemsStatus;
            this.itemsBody = itemsBody;
        }

        @Override
        public CompletableFuture<SpotifyWebApiPlaylistInspector.ApiResponse> send(HttpRequest request) {
            requests.add(request);
            if ("accounts.spotify.com".equals(request.uri().getHost())) {
                return completed(200, "{\"access_token\":\"test-token\",\"expires_in\":3600}");
            }
            if (request.uri().getPath().endsWith("/items")) {
                return completed(itemsStatus, itemsBody);
            }
            return completed(metadataStatus, metadataBody);
        }

        private CompletableFuture<SpotifyWebApiPlaylistInspector.ApiResponse> completed(int status, String body) {
            return SpotifyWebApiPlaylistInspectorTest.completed(status, body);
        }

        private List<String> requestPaths() {
            return requests.stream().map(request -> request.uri().getPath()).toList();
        }
    }
}
