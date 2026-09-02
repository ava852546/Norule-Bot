package com.norule.musicbot.domain.shorturl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortUrlReservedRouteCoverageTest {
    private static final Pattern CONTEXT_ROUTE = Pattern.compile("createContext\\(\"/([^/\"]*)");
    private final ShortUrlDomainService domain = new ShortUrlDomainService();

    @Test
    void registeredHttpAndNuxtTopLevelRoutesAreReserved() throws Exception {
        assertContextRoutesReserved(Path.of(
                "src/main/java/com/norule/musicbot/web/infra/WebRouteBinder.java"));
        assertContextRoutesReserved(Path.of(
                "src/main/java/com/norule/musicbot/shorturl/infra/ShortUrlGatewayServer.java"));

        Path pages = Path.of("web/app/pages");
        try (Stream<Path> files = Files.walk(pages)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = pages.relativize(file);
                String topLevel = relative.getName(0).toString().replaceFirst("\\.vue$", "");
                if (!"index".equals(topLevel) && !topLevel.startsWith("[")) {
                    assertReserved(topLevel, file.toString());
                }
            }
        }
    }

    @Test
    void fixedInfrastructureAndStaticNamesAreReserved() {
        for (String route : List.of(
                "api", "auth", "login", "logout", "oauth", "callback", "session",
                "my-content", "web", "short-url", "_nuxt", "dashboard",
                "favicon.ico", "robots.txt", "sitemap.xml", "share-expired", "404", "index")) {
            assertReserved(route, "fixed route");
        }
    }

    private void assertContextRoutesReserved(Path source) throws Exception {
        Matcher matcher = CONTEXT_ROUTE.matcher(Files.readString(source));
        while (matcher.find()) {
            String topLevel = matcher.group(1);
            if (!topLevel.isBlank()) {
                assertReserved(topLevel, source.toString());
            }
        }
    }

    private void assertReserved(String route, String source) {
        assertTrue(domain.isReservedCode(route),
                () -> "Top-level route '" + route + "' from " + source + " must be reserved");
    }
}
