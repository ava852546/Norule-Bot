package com.norule.musicbot.config.domain;

import com.norule.musicbot.config.BotConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class TicketConfig {
    public enum OpenUiMode {
        BUTTON,
        SELECT;

        public static OpenUiMode parse(String raw, OpenUiMode fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback == null ? BUTTON : fallback;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "SELECT", "MENU" -> SELECT;
                case "BUTTON", "BUTTONS" -> BUTTON;
                default -> fallback == null ? BUTTON : fallback;
            };
        }

        BotConfig.Ticket.OpenUiMode toLegacy() {
            return this == SELECT ? BotConfig.Ticket.OpenUiMode.SELECT : BotConfig.Ticket.OpenUiMode.BUTTONS;
        }
    }

    public static final class TicketOption {
        private final String id;
        private final String label;
        private final String panelTitle;
        private final String panelDescription;
        private final String panelButtonStyle;
        private final String welcomeMessage;
        private final boolean preOpenFormEnabled;
        private final String preOpenFormTitle;
        private final String preOpenFormLabel;
        private final String preOpenFormPlaceholder;

        public TicketOption(String id,
                            String label,
                            String panelTitle,
                            String panelDescription,
                            String panelButtonStyle,
                            String welcomeMessage,
                            boolean preOpenFormEnabled,
                            String preOpenFormTitle,
                            String preOpenFormLabel,
                            String preOpenFormPlaceholder) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
            this.panelTitle = panelTitle == null ? "" : panelTitle;
            this.panelDescription = panelDescription == null ? "" : panelDescription;
            this.panelButtonStyle = panelButtonStyle == null ? "PRIMARY" : panelButtonStyle;
            this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage;
            this.preOpenFormEnabled = preOpenFormEnabled;
            this.preOpenFormTitle = preOpenFormTitle == null ? "" : preOpenFormTitle;
            this.preOpenFormLabel = preOpenFormLabel == null ? "" : preOpenFormLabel;
            this.preOpenFormPlaceholder = preOpenFormPlaceholder == null ? "" : preOpenFormPlaceholder;
        }

        public static TicketOption fromLegacy(BotConfig.Ticket.TicketOption legacy) {
            if (legacy == null) {
                return defaultValues();
            }
            return new TicketOption(
                    legacy.getId(),
                    legacy.getLabel(),
                    legacy.getPanelTitle(),
                    legacy.getPanelDescription(),
                    legacy.getPanelButtonStyle(),
                    legacy.getWelcomeMessage(),
                    legacy.isPreOpenFormEnabled(),
                    legacy.getPreOpenFormTitle(),
                    legacy.getPreOpenFormLabel(),
                    legacy.getPreOpenFormPlaceholder()
            );
        }

        public static TicketOption defaultValues() {
            return fromLegacy(BotConfig.Ticket.TicketOption.defaultValues());
        }

        public BotConfig.Ticket.TicketOption toLegacy() {
            return new BotConfig.Ticket.TicketOption(
                    id,
                    label,
                    panelTitle,
                    panelDescription,
                    panelButtonStyle,
                    welcomeMessage,
                    preOpenFormEnabled,
                    preOpenFormTitle,
                    preOpenFormLabel,
                    preOpenFormPlaceholder
            );
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
        public String getPanelTitle() { return panelTitle; }
        public String getPanelDescription() { return panelDescription; }
        public String getPanelButtonStyle() { return panelButtonStyle; }
        public String getWelcomeMessage() { return welcomeMessage; }
        public boolean isPreOpenFormEnabled() { return preOpenFormEnabled; }
        public String getPreOpenFormTitle() { return preOpenFormTitle; }
        public String getPreOpenFormLabel() { return preOpenFormLabel; }
        public String getPreOpenFormPlaceholder() { return preOpenFormPlaceholder; }
    }

    private final boolean enabled;
    private final Long panelChannelId;
    private final Long openCategoryId;
    private final Long closedCategoryId;
    private final int autoCloseDays;
    private final int maxOpenPerUser;
    private final OpenUiMode openUiMode;
    private final String panelTitle;
    private final String panelDescription;
    private final int panelColor;
    private final String panelButtonStyle;
    private final int panelButtonLimit;
    private final String welcomeMessage;
    private final boolean preOpenFormEnabled;
    private final String preOpenFormTitle;
    private final String preOpenFormLabel;
    private final String preOpenFormPlaceholder;
    private final List<String> optionLabels;
    private final List<TicketOption> options;
    private final List<Long> supportRoleIds;
    private final List<Long> blacklistedUserIds;

    public TicketConfig(boolean enabled,
                        Long panelChannelId,
                        Long openCategoryId,
                        Long closedCategoryId,
                        int autoCloseDays,
                        int maxOpenPerUser,
                        OpenUiMode openUiMode,
                        String panelTitle,
                        String panelDescription,
                        int panelColor,
                        String panelButtonStyle,
                        int panelButtonLimit,
                        String welcomeMessage,
                        boolean preOpenFormEnabled,
                        String preOpenFormTitle,
                        String preOpenFormLabel,
                        String preOpenFormPlaceholder,
                        List<String> optionLabels,
                        List<TicketOption> options,
                        List<Long> supportRoleIds,
                        List<Long> blacklistedUserIds) {
        this.enabled = enabled;
        this.panelChannelId = panelChannelId;
        this.openCategoryId = openCategoryId;
        this.closedCategoryId = closedCategoryId;
        this.autoCloseDays = Math.max(1, autoCloseDays);
        this.maxOpenPerUser = Math.max(1, maxOpenPerUser);
        this.openUiMode = openUiMode == null ? OpenUiMode.BUTTON : openUiMode;
        this.panelTitle = panelTitle == null ? "" : panelTitle;
        this.panelDescription = panelDescription == null ? "" : panelDescription;
        this.panelColor = panelColor;
        this.panelButtonStyle = panelButtonStyle == null ? "PRIMARY" : panelButtonStyle;
        this.panelButtonLimit = Math.max(1, panelButtonLimit);
        this.welcomeMessage = welcomeMessage == null ? "" : welcomeMessage;
        this.preOpenFormEnabled = preOpenFormEnabled;
        this.preOpenFormTitle = preOpenFormTitle == null ? "" : preOpenFormTitle;
        this.preOpenFormLabel = preOpenFormLabel == null ? "" : preOpenFormLabel;
        this.preOpenFormPlaceholder = preOpenFormPlaceholder == null ? "" : preOpenFormPlaceholder;
        this.optionLabels = optionLabels == null ? List.of() : List.copyOf(optionLabels);
        this.options = options == null ? List.of() : List.copyOf(options);
        this.supportRoleIds = supportRoleIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(supportRoleIds));
        this.blacklistedUserIds = blacklistedUserIds == null ? List.of() : List.copyOf(new LinkedHashSet<>(blacklistedUserIds));
    }

    public static TicketConfig defaultValues() {
        return fromLegacy(BotConfig.Ticket.defaultValues());
    }

    public TicketConfig(BotConfig.Ticket legacy) {
        this(fromLegacy(legacy));
    }

    private TicketConfig(TicketConfig value) {
        this(
                value.enabled,
                value.panelChannelId,
                value.openCategoryId,
                value.closedCategoryId,
                value.autoCloseDays,
                value.maxOpenPerUser,
                value.openUiMode,
                value.panelTitle,
                value.panelDescription,
                value.panelColor,
                value.panelButtonStyle,
                value.panelButtonLimit,
                value.welcomeMessage,
                value.preOpenFormEnabled,
                value.preOpenFormTitle,
                value.preOpenFormLabel,
                value.preOpenFormPlaceholder,
                value.optionLabels,
                value.options,
                value.supportRoleIds,
                value.blacklistedUserIds
        );
    }

    public static TicketConfig fromLegacy(BotConfig.Ticket legacy) {
        if (legacy == null) {
            return defaultValues();
        }
        List<TicketOption> optionValues = new ArrayList<>();
        for (BotConfig.Ticket.TicketOption option : legacy.getOptions()) {
            optionValues.add(TicketOption.fromLegacy(option));
        }
        return new TicketConfig(
                legacy.isEnabled(),
                legacy.getPanelChannelId(),
                legacy.getOpenCategoryId(),
                legacy.getClosedCategoryId(),
                legacy.getAutoCloseDays(),
                legacy.getMaxOpenPerUser(),
                OpenUiMode.parse(legacy.getOpenUiMode() == null ? null : legacy.getOpenUiMode().name(), OpenUiMode.BUTTON),
                legacy.getPanelTitle(),
                legacy.getPanelDescription(),
                legacy.getPanelColor(),
                legacy.getPanelButtonStyle(),
                legacy.getPanelButtonLimit(),
                legacy.getWelcomeMessage(),
                legacy.isPreOpenFormEnabled(),
                legacy.getPreOpenFormTitle(),
                legacy.getPreOpenFormLabel(),
                legacy.getPreOpenFormPlaceholder(),
                legacy.getOptionLabels(),
                optionValues,
                legacy.getSupportRoleIds(),
                legacy.getBlacklistedUserIds()
        );
    }

    public BotConfig.Ticket toLegacy() {
        List<BotConfig.Ticket.TicketOption> optionValues = options.stream().map(TicketOption::toLegacy).toList();
        return BotConfig.Ticket.defaultValues()
                .withEnabled(enabled)
                .withPanelChannelId(panelChannelId)
                .withOpenCategoryId(openCategoryId)
                .withClosedCategoryId(closedCategoryId)
                .withAutoCloseDays(autoCloseDays)
                .withMaxOpenPerUser(maxOpenPerUser)
                .withOpenUiMode(openUiMode.toLegacy())
                .withPanelTitle(panelTitle)
                .withPanelDescription(panelDescription)
                .withPanelColor(panelColor)
                .withPanelButtonStyle(panelButtonStyle)
                .withPanelButtonLimit(panelButtonLimit)
                .withWelcomeMessage(welcomeMessage)
                .withPreOpenFormEnabled(preOpenFormEnabled)
                .withPreOpenFormTitle(preOpenFormTitle)
                .withPreOpenFormLabel(preOpenFormLabel)
                .withPreOpenFormPlaceholder(preOpenFormPlaceholder)
                .withOptionLabels(optionLabels)
                .withOptions(optionValues)
                .withSupportRoleIds(supportRoleIds)
                .withBlacklistedUserIds(blacklistedUserIds);
    }

    public boolean isEnabled() { return enabled; }
    public Long getPanelChannelId() { return panelChannelId; }
    public Long getOpenCategoryId() { return openCategoryId; }
    public Long getClosedCategoryId() { return closedCategoryId; }
    public int getAutoCloseDays() { return autoCloseDays; }
    public int getMaxOpenPerUser() { return maxOpenPerUser; }
    public OpenUiMode getOpenUiMode() { return openUiMode; }
    public String getPanelTitle() { return panelTitle; }
    public String getPanelDescription() { return panelDescription; }
    public int getPanelColor() { return panelColor; }
    public String getPanelButtonStyle() { return panelButtonStyle; }
    public int getPanelButtonLimit() { return panelButtonLimit; }
    public String getWelcomeMessage() { return welcomeMessage; }
    public boolean isPreOpenFormEnabled() { return preOpenFormEnabled; }
    public String getPreOpenFormTitle() { return preOpenFormTitle; }
    public String getPreOpenFormLabel() { return preOpenFormLabel; }
    public String getPreOpenFormPlaceholder() { return preOpenFormPlaceholder; }
    public List<String> getOptionLabels() { return optionLabels; }
    public List<TicketOption> getOptions() { return options; }
    public List<Long> getSupportRoleIds() { return supportRoleIds; }
    public List<Long> getBlacklistedUserIds() { return blacklistedUserIds; }

    public TicketConfig withOpenCategoryId(Long value) {
        return new TicketConfig(enabled, panelChannelId, value, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode,
                panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage,
                preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options,
                supportRoleIds, blacklistedUserIds);
    }

    public TicketConfig withClosedCategoryId(Long value) {
        return new TicketConfig(enabled, panelChannelId, openCategoryId, value, autoCloseDays, maxOpenPerUser, openUiMode,
                panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage,
                preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options,
                supportRoleIds, blacklistedUserIds);
    }

    public TicketConfig withEnabled(boolean value) {
        return new TicketConfig(value, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode,
                panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage,
                preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options,
                supportRoleIds, blacklistedUserIds);
    }

    public TicketConfig withMaxOpenPerUser(int value) {
        return new TicketConfig(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, value, openUiMode,
                panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage,
                preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options,
                supportRoleIds, blacklistedUserIds);
    }

    public TicketConfig withBlacklistedUserIds(List<Long> values) {
        return new TicketConfig(enabled, panelChannelId, openCategoryId, closedCategoryId, autoCloseDays, maxOpenPerUser, openUiMode,
                panelTitle, panelDescription, panelColor, panelButtonStyle, panelButtonLimit, welcomeMessage,
                preOpenFormEnabled, preOpenFormTitle, preOpenFormLabel, preOpenFormPlaceholder, optionLabels, options,
                supportRoleIds, values);
    }
}

