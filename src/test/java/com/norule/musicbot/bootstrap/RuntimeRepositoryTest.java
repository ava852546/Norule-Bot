package com.norule.musicbot.bootstrap;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeRepositoryTest {
    @ParameterizedTest
    @EnumSource(RuntimeRepository.class)
    void acceptsLegacyIds(RuntimeRepository repository) {
        assertEquals(repository, RuntimeRepository.fromResolverId(repository.id()));
        assertEquals(repository, RuntimeRepository.fromId(repository.id()));
    }

    @ParameterizedTest
    @CsvSource({
            "MAVEN_CENTRAL, central-8fac3ebd6edbaca3c794783fa38088af0aa128e7",
            "LAVALINK_RELEASES, lavalink-releases-30024d3cb07e8f2cc1a12c5ebf2de1a59c4127c4",
            "TOPIWTF_RELEASES, topiwtf-releases-2c759bccfc78c076a7e542c5047c6af37660969c"
    })
    void acceptsExactResolverIdsWithoutChangingManifestIds(RuntimeRepository repository, String resolverId) {
        assertEquals(repository, RuntimeRepository.fromResolverId(resolverId));
        assertThrows(IllegalArgumentException.class, () -> RuntimeRepository.fromId(resolverId));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "enterprise-mirror", "central-", "central-malicious", " central", "CENTRAL",
            "central-0000000000000000000000000000000000000000",
            "central-8FAC3EBD6EDBACA3C794783FA38088AF0AA128E7",
            "central-8fac3ebd6edbaca3c794783fa38088af0aa128e",
            "central-8fac3ebd6edbaca3c794783fa38088af0aa128e7-extra",
            "mirror-8fac3ebd6edbaca3c794783fa38088af0aa128e7",
            "lavalink-releases-8fac3ebd6edbaca3c794783fa38088af0aa128e7",
            "topiwtf-releases-8fac3ebd6edbaca3c794783fa38088af0aa128e7"
    })
    void rejectsUnknownOrMalformedResolverIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> RuntimeRepository.fromResolverId(id));
    }

    @ParameterizedTest
    @EnumSource(RuntimeRepository.class)
    void rejectsHashOfDifferentUrl(RuntimeRepository repository) throws NoSuchAlgorithmException {
        for (String url : new String[] {repository.baseUrl() + "/", "https://untrusted.example/releases"}) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
            assertThrows(IllegalArgumentException.class,
                    () -> RuntimeRepository.fromResolverId(repository.id() + "-" + hash));
        }
    }
}
