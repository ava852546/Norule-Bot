package com.norule.musicbot.config.domain;

public record OperationalLoggingConfig(String jvmChannelId, DeliveryMode deliveryMode, String voiceChannelId) {
    public enum DeliveryMode { UPDATE_LATEST, NEW_MESSAGE }

    public OperationalLoggingConfig {
        jvmChannelId = normalize(jvmChannelId);
        voiceChannelId = normalize(voiceChannelId);
        deliveryMode = deliveryMode == null ? DeliveryMode.UPDATE_LATEST : deliveryMode;
    }

    private static String normalize(String value) {
        if (value == null || !value.matches("[0-9]{1,20}")) return "0";
        try {
            return Long.parseUnsignedLong(value) == 0 ? "0" : value;
        } catch (NumberFormatException invalid) {
            return "0";
        }
    }

    public static OperationalLoggingConfig disabled() {
        return new OperationalLoggingConfig("0", DeliveryMode.UPDATE_LATEST, "0");
    }
}
