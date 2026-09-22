package com.norule.musicbot.domain.music;

/** Runtime authority for standalone youtube-source Cipher, excluding Companion's internal decipher. */
public final class CipherPolicy {
    private volatile boolean allowed;

    public CipherPolicy(boolean enabled) {
        update(enabled);
    }

    public boolean isAllowed() {
        return allowed;
    }

    public synchronized void update(boolean enabled) {
        allowed = enabled;
    }

    public void requireAllowed() {
        if (!allowed) {
            throw new CipherDisabledException();
        }
    }
}
