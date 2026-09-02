package com.norule.musicbot.domain.shorturl;

public enum ShortUrlCreationError {
    NONE,
    INVALID_TARGET,
    INVALID_CUSTOM_CODE,
    RESERVED_CUSTOM_CODE,
    CUSTOM_CODE_ALREADY_EXISTS
}
