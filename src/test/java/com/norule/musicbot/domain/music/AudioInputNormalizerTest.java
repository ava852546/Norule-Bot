package com.norule.musicbot.domain.music;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioInputNormalizerTest {
    @ParameterizedTest
    @MethodSource("shareInputs")
    void extractsFirstUrlWithoutChangingItsData(String input, String expected) {
        assertEquals(expected, AudioInputNormalizer.extractFirstHttpUrlOrQuery(input));
    }

    private static Stream<Arguments> shareInputs() {
        String shortUrl = "https://b23.tv/1DvRnBr";
        return Stream.of(
                Arguments.of("\u3010\u6B4C\u66F2\u6A19\u984C-\u54D4\u54E9\u54D4\u54E9\u3011 " + shortUrl, shortUrl),
                Arguments.of("[\u6B4C\u66F2\u9023\u7D50](" + shortUrl + ")", shortUrl),
                Arguments.of("[" + shortUrl + "](" + shortUrl + " \"\u5206\u4EAB\u9023\u7D50\")", shortUrl),
                Arguments.of("<" + shortUrl + ">", shortUrl),
                Arguments.of("\u4F86\u807D\u9019\u9996\uFF1A" + shortUrl + "\u3002", shortUrl),
                Arguments.of("title\r\n" + shortUrl + "\nmore text", shortUrl),
                Arguments.of("`" + shortUrl + "`", shortUrl),
                Arguments.of("```\n" + shortUrl + "\n```", shortUrl),
                Arguments.of("\"" + shortUrl + "\"", shortUrl),
                Arguments.of("'" + shortUrl + "'", shortUrl),
                Arguments.of("share '" + shortUrl + "',", shortUrl),
                Arguments.of("**" + shortUrl + "?x=1**!", shortUrl + "?x=1"),
                Arguments.of("\u300C" + shortUrl + "\u300D", shortUrl),
                Arguments.of("**" + shortUrl + "**", shortUrl),
                Arguments.of("__" + shortUrl + "__", shortUrl),
                Arguments.of("~~" + shortUrl + "~~", shortUrl),
                Arguments.of("share " + shortUrl + ".", shortUrl),
                Arguments.of("share " + shortUrl + ",", shortUrl),
                Arguments.of(shortUrl + "\uFF0C\u5FEB\u4F86\u807D", shortUrl),
                Arguments.of(shortUrl + "\u00A0https://example.com/b", shortUrl),
                Arguments.of("https://example.com/song_(live)", "https://example.com/song_(live)"),
                Arguments.of("share (https://example.com/song_(live))", "https://example.com/song_(live)"),
                Arguments.of("[song](https://example.com/song_((live)))", "https://example.com/song_((live))"),
                Arguments.of("https://example.com/a https://example.com/b", "https://example.com/a"),
                Arguments.of("[https://example.com/a](https://example.com/b)", "https://example.com/a"),
                Arguments.of("share HTTP://example.com/a", "HTTP://example.com/a"),
                Arguments.of("share hTtPs://example.com/a", "hTtPs://example.com/a"),
                Arguments.of("  \u5B8B\u51AC\u91CE \u8463\u5C0F\u59D0  ", "\u5B8B\u51AC\u91CE \u8463\u5C0F\u59D0"),
                Arguments.of("  artist  song\nversion \t", "artist  song\nversion"),
                Arguments.of("  ftp://example.com/a  ", "ftp://example.com/a"),
                Arguments.of("spotify:playlist:abc", "spotify:playlist:abc")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/watch?v=123&t=30#part2",
            "https://example.com/a%20b?q=x%26y",
            "https://example.com/a?z=3&a=1&a=2#part%202",
            "https://example.com/a?redirect=https://other.example/b",
            "https://example.com/a?x=(live)&y=1,2!;:#end?",
            "https://example.com/rock'n'roll_~!$&+,;=:@",
            "https://example.com/a?x='value'",
            "http://[2001:db8::1]/song_(live)",
            "https://example.com/file.",
            "https://example.com/a?empty=&last=",
            "https://example.com/a?q=%ZZ"
    })
    void preservesUrlCharactersAndDoesNotDecodeOrValidate(String url) {
        assertEquals(url, AudioInputNormalizer.extractFirstHttpUrlOrQuery(url));
        assertEquals(url, AudioInputNormalizer.extractFirstHttpUrlOrQuery("<" + url + ">"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u3000"})
    void treatsNullAndWhitespaceAsEmpty(String input) {
        assertEquals("", AudioInputNormalizer.extractFirstHttpUrlOrQuery(input));
    }
}
