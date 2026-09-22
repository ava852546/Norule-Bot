package com.norule.musicbot.domain.music;

import java.util.regex.Pattern;

/** Text-only share input handling. Extracted URLs still require normal playback validation. */
public final class AudioInputNormalizer {
    private static final Pattern HTTP_START = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final String OPEN_BRACKETS = "([{\uFF08\u3010";
    private static final String CLOSE_BRACKETS = ")]}\uFF09\u3011";
    private static final String SHARE_DELIMITERS = "<>\"`\u201C\u201D\u2018\u2019\u300C\u300D\u300E\u300F"
            + "\u3002\uFF0C\uFF01\uFF1F\uFF1B\uFF1A\u3001\u2026";

    private AudioInputNormalizer() {
    }

    public static String extractFirstHttpUrlOrQuery(String input) {
        String text = input == null ? "" : input.strip();
        var matcher = HTTP_START.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        int start = matcher.start();
        int end = matcher.end();
        int[] bracketDepth = new int[OPEN_BRACKETS.length()];
        while (end < text.length()) {
            char ch = text.charAt(end);
            if (Character.isWhitespace(ch) || Character.isSpaceChar(ch) || SHARE_DELIMITERS.indexOf(ch) >= 0) {
                break;
            }
            int opening = OPEN_BRACKETS.indexOf(ch);
            int closing = CLOSE_BRACKETS.indexOf(ch);
            if (opening >= 0) {
                bracketDepth[opening]++;
            } else if (closing >= 0) {
                if (bracketDepth[closing] == 0) {
                    break;
                }
                bracketDepth[closing]--;
            }
            end++;
        }

        // Only remove paired outer markup; apostrophes, underscores and tildes can be URL data.
        String prefix = text.substring(0, start);
        int beforePunctuation = end;
        while (beforePunctuation > matcher.end() && ".,!?;:".indexOf(text.charAt(beforePunctuation - 1)) >= 0) {
            beforePunctuation--;
        }
        for (String wrapper : new String[] {"'", "**", "__", "~~", "*", "_"}) {
            if (prefix.endsWith(wrapper)) {
                if (text.substring(start, end).endsWith(wrapper)) {
                    end -= wrapper.length();
                    break;
                }
                if (text.substring(start, beforePunctuation).endsWith(wrapper)) {
                    end = beforePunctuation - wrapper.length();
                    break;
                }
            }
        }
        // ASCII punctuation is ambiguous in URLs. Trim prose-ending periods/commas only
        // in surrounding text and outside query/fragment data; keep bare URLs unchanged.
        String url = text.substring(start, end);
        if (start > 0 && end == text.length() && url.indexOf('?') < 0 && url.indexOf('#') < 0) {
            while (end > matcher.end() && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == ',')) {
                end--;
            }
        }
        return text.substring(start, end);
    }
}
