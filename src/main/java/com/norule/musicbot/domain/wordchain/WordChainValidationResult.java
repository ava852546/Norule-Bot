package com.norule.musicbot.domain.wordchain;

public enum WordChainValidationResult {
    OK,
    EMPTY,
    NOT_SINGLE_WORD,
    NOT_ENGLISH,
    WORD_USED,
    WRONG_START_LETTER,
    WORD_NOT_FOUND,
    DICTIONARY_API_ERROR
}

