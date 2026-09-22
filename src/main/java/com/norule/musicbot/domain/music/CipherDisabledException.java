package com.norule.musicbot.domain.music;

public final class CipherDisabledException extends RuntimeException {
    public CipherDisabledException() {
        super("CIPHER_REQUIRED_BUT_DISABLED: music.cipher.enabled=false");
    }
}
