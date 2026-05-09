package com.norule.musicbot.discord.bot.gateway.command.settings.view;

import com.norule.musicbot.i18n.I18nService;

import java.util.Objects;
import java.util.function.Supplier;

public final class BoolTextHelper {
    private final Supplier<I18nService> i18nSupplier;

    public BoolTextHelper(Supplier<I18nService> i18nSupplier) {
        this.i18nSupplier = Objects.requireNonNull(i18nSupplier, "i18nSupplier");
    }

    public String boolText(String lang, boolean value) {
        return value ? i18nSupplier.get().t(lang, "settings.info_bool_on") : i18nSupplier.get().t(lang, "settings.info_bool_off");
    }
}
