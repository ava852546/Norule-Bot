package com.norule.musicbot.domain.minecraft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftServerAddressTest {

    @Test
    void acceptsNormalHostOrHostPort() {
        assertTrue(MinecraftServerAddress.of("play.example.com").isValid());
        assertTrue(MinecraftServerAddress.of("play.example.com:25565").isValid());
        assertTrue(MinecraftServerAddress.of("192.168.0.1:19132").isValid());
    }

    @Test
    void rejectsBlankOrProtocolAddress() {
        assertFalse(MinecraftServerAddress.of("").isValid());
        assertFalse(MinecraftServerAddress.of("   ").isValid());
        assertFalse(MinecraftServerAddress.of("http://play.example.com").isValid());
        assertFalse(MinecraftServerAddress.of("https://play.example.com").isValid());
    }
}
