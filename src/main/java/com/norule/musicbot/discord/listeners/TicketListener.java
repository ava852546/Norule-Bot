package com.norule.musicbot.discord.listeners;

import com.norule.musicbot.config.*;
import com.norule.musicbot.domain.music.*;
import com.norule.musicbot.i18n.*;
import com.norule.musicbot.web.*;

import com.norule.musicbot.*;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class TicketListener extends ListenerAdapter {
    private static final String CMD_TICKET_ZH = "\u5ba2\u670d\u55ae";
    private static final String OPEN_BUTTON_ID = "ticket:open";
    private static final String OPEN_BUTTON_OPTION_PREFIX = "ticket:open:option:";
    private static final String OPEN_PANEL_SELECT_ID = "ticket:open:panel-select";
    private static final String CLOSE_BUTTON_ID = "ticket:close";
    private static final String REOPEN_BUTTON_PREFIX = "ticket:reopen:";
    private static final String DELETE_BUTTON_PREFIX = "ticket:delete:";
    private static final String OPEN_SELECT_PREFIX = "ticket:open:select:";
    private static final String OPEN_MODAL_PREFIX = "ticket:open:modal:";
    private static final String CLOSE_MODAL_PREFIX = "ticket:close:modal:";
    private static final String PANEL_CHANNEL_SELECT_PREFIX = "ticket:panel:channel:";
    private static final String BLACKLIST_ADD_SELECT_PREFIX = "ticket:blacklist:add:";
    private static final String BLACKLIST_REMOVE_SELECT_PREFIX = "ticket:blacklist:remove:";
    private static final String LIMIT_MODAL_ID = "ticket:limit:modal";
    private static final Set<String> LEGACY_DEFAULT_TICKET_LABELS = new HashSet<>(Arrays.asList(
            "general",
            "open ticket",
            "ticket",
            "開單",
            "開啟客服單",
            "开启工单"
    ));

    private record OpenRequest(long userId, long guildId, String optionId, long expiresAtMillis) {}
    private record ManageRequest(long userId, long guildId, long expiresAtMillis) {}
    private record TicketCategoryPair(Category openCategory, Category closedCategory) {}

    private final GuildSettingsService settingsService;
    private final TicketService ticketService;
    private final I18nService i18n;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, OpenRequest> openRequests = new ConcurrentHashMap<>();
    private final Map<String, ManageRequest> manageRequests = new ConcurrentHashMap<>();
    private volatile net.dv8tion.jda.api.JDA jda;

    public TicketListener(GuildSettingsService settingsService,
                          TicketService ticketService,
                          I18nService i18n) {
        this.settingsService = settingsService;
        this.ticketService = ticketService;
        this.i18n = i18n;
        this.worker.scheduleAtFixedRate(this::cleanupExpiredRequests, 1, 1, TimeUnit.MINUTES);
        this.worker.scheduleAtFixedRate(this::autoCloseStaleTickets, 2, 10, TimeUnit.MINUTES);
    }

    @Override
    public void onReady(ReadyEvent event) {
        this.jda = event.getJDA();
        worker.execute(this::ensureCategoriesForEnabledGuilds);
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) {
            return;
        }
        long guildId = event.getGuild().getIdLong();
        long channelId = event.getChannel().getIdLong();
        if (!ticketService.isTicketChannel(guildId, channelId)) {
            return;
        }
        ticketService.touchTicketMessage(guildId, channelId, event.getAuthor().getIdLong());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String name = canonicalCommandName(event.getName());
        if (!"ticket".equals(name)) {
            return;
        }
        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        String sub = event.getSubcommandName();
        if ((sub == null || sub.isBlank()) && event.getOption("action") != null) {
            sub = event.getOption("action").getAsString();
        }
        if (sub == null) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        if ("enable".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            boolean enabled = !settingsService.getTicket(event.getGuild().getIdLong()).isEnabled();
            settingsService.updateSettings(event.getGuild().getIdLong(), s -> s.withTicket(s.getTicket().withEnabled(enabled)));
            event.reply(i18n.t(lang, "ticket.result_set_enabled", Map.of("status", boolText(lang, enabled))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if ("status".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
            String panelChannel = cfg.getPanelChannelId() == null
                    ? i18n.t(lang, "settings.info_channels_none")
                    : "<#" + cfg.getPanelChannelId() + ">";
            event.reply(i18n.t(lang, "ticket.result_status", Map.of(
                            "status", boolText(lang, cfg.isEnabled()),
                            "channel", panelChannel,
                            "limit", String.valueOf(cfg.getMaxOpenPerUser()),
                            "blacklist", String.valueOf(cfg.getBlacklistedUserIds().size())
                    )))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if ("panel".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
            if (!cfg.isEnabled()) {
                event.reply(i18n.t(lang, "ticket.disabled")).setEphemeral(true).queue();
                return;
            }
            openPanelChannelPicker(event, lang);
            return;
        }
        if ("close".equals(sub)) {
            if (event.getChannelType() != ChannelType.TEXT) {
                event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                return;
            }
            TextChannel channel = event.getChannel().asTextChannel();
            TicketService.TicketRecord record = ticketService.getTicket(event.getGuild().getIdLong(), channel.getIdLong());
            if (record == null || record.isClosed()) {
                event.reply(i18n.t(lang, "ticket.not_ticket_channel")).setEphemeral(true).queue();
                return;
            }
            if (!canCloseTicket(event.getMember(), record)) {
                event.reply(i18n.t(lang, "ticket.close_no_permission")).setEphemeral(true).queue();
                return;
            }
            TextInput reasonInput = TextInput.create("reason", TextInputStyle.PARAGRAPH)
                    .setRequired(false)
                    .setPlaceholder(i18n.t(lang, "ticket.close_reason_placeholder"))
                    .setMaxLength(500)
                    .build();
            Modal modal = Modal.create(CLOSE_MODAL_PREFIX + channel.getId(), i18n.t(lang, "ticket.close_modal_title"))
                    .addComponents(Label.of(i18n.t(lang, "ticket.close_modal_label"), reasonInput))
                    .build();
            event.replyModal(modal).queue();
            return;
        }
        if ("limit".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            event.replyModal(buildLimitModal(lang, settingsService.getTicket(event.getGuild().getIdLong()).getMaxOpenPerUser())).queue();
            return;
        }
        if ("blacklist-add".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            openBlacklistUserPicker(event, BLACKLIST_ADD_SELECT_PREFIX, lang, "ticket.blacklist_add_prompt");
            return;
        }
        if ("blacklist-remove".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            openBlacklistUserPicker(event, BLACKLIST_REMOVE_SELECT_PREFIX, lang, "ticket.blacklist_remove_prompt");
            return;
        }
        if ("blacklist-list".equals(sub)) {
            if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            List<Long> blacklist = settingsService.getTicket(event.getGuild().getIdLong()).getBlacklistedUserIds();
            if (blacklist.isEmpty()) {
                event.reply(i18n.t(lang, "ticket.blacklist_empty")).setEphemeral(true).queue();
                return;
            }
            String users = blacklist.stream().map(id -> "<@" + id + ">").reduce((a, b) -> a + ", " + b).orElse("-");
            event.reply(i18n.t(lang, "ticket.blacklist_list", Map.of("users", users)))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        String id = event.getComponentId();
        if (OPEN_BUTTON_ID.equals(id) || id.startsWith(OPEN_BUTTON_OPTION_PREFIX)) {
            BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
            if (!cfg.isEnabled()) {
                event.reply(i18n.t(lang, "ticket.disabled")).setEphemeral(true).queue();
                return;
            }
            if (isBlacklisted(cfg, event.getUser().getIdLong())) {
                event.reply(i18n.t(lang, "ticket.blacklist_denied")).setEphemeral(true).queue();
                return;
            }
            List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(cfg, lang);
            if (id.startsWith(OPEN_BUTTON_OPTION_PREFIX)) {
                String optionId = id.substring(OPEN_BUTTON_OPTION_PREFIX.length());
                BotConfig.Ticket.TicketOption option = findTicketOption(cfg, optionId, lang);
                if (option == null) {
                    event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
                    return;
                }
                beginOpenFlow(event, option, lang);
                return;
            }
            if (options.size() == 1) {
                beginOpenFlow(event, options.get(0), lang);
                return;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            openRequests.put(token, new OpenRequest(
                    event.getUser().getIdLong(),
                    event.getGuild().getIdLong(),
                    "",
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
            ));
            StringSelectMenu.Builder menu = StringSelectMenu.create(OPEN_SELECT_PREFIX + token)
                    .setPlaceholder(i18n.t(lang, "ticket.select_placeholder"));
            for (BotConfig.Ticket.TicketOption option : options) {
                menu.addOptions(SelectOption.of(option.getLabel(), option.getId()));
            }
            event.reply(i18n.t(lang, "ticket.select_prompt"))
                    .addComponents(ActionRow.of(menu.build()))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (CLOSE_BUTTON_ID.equals(id)) {
            if (event.getChannelType() != ChannelType.TEXT) {
                event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                return;
            }
            TextChannel channel = event.getChannel().asTextChannel();
            String missing = formatMissingPermissions(event.getGuild().getSelfMember(), channel,
                    Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS, Permission.MESSAGE_SEND);
            if (!"-".equals(missing)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            TicketService.TicketRecord record = ticketService.getTicket(event.getGuild().getIdLong(), channel.getIdLong());
            if (record == null || record.isClosed()) {
                event.reply(i18n.t(lang, "ticket.not_ticket_channel")).setEphemeral(true).queue();
                return;
            }
            if (!canCloseTicket(event.getMember(), record)) {
                event.reply(i18n.t(lang, "ticket.close_no_permission")).setEphemeral(true).queue();
                return;
            }
            TextInput reasonInput = TextInput.create("reason", TextInputStyle.PARAGRAPH)
                    .setRequired(false)
                    .setPlaceholder(i18n.t(lang, "ticket.close_reason_placeholder"))
                    .setMaxLength(500)
                    .build();
            Modal modal = Modal.create(CLOSE_MODAL_PREFIX + channel.getId(), i18n.t(lang, "ticket.close_modal_title"))
                    .addComponents(Label.of(i18n.t(lang, "ticket.close_modal_label"), reasonInput))
                    .build();
            event.replyModal(modal).queue();
            return;
        }
        if (id.startsWith(REOPEN_BUTTON_PREFIX)) {
            if (event.getChannelType() != ChannelType.TEXT) {
                event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                return;
            }
            TextChannel channel = event.getChannel().asTextChannel();
            String missing = formatMissingPermissions(event.getGuild().getSelfMember(), channel,
                    Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS, Permission.MESSAGE_SEND);
            if (!"-".equals(missing)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            long channelId = parseChannelIdFromButton(id, REOPEN_BUTTON_PREFIX, channel.getIdLong());
            TicketService.TicketRecord record = ticketService.getTicket(event.getGuild().getIdLong(), channelId);
            if (record == null) {
                event.reply(i18n.t(lang, "ticket.not_ticket_channel")).setEphemeral(true).queue();
                return;
            }
            if (!canManageClosedActions(event.getMember(), event.getGuild().getIdLong())) {
                event.reply(i18n.t(lang, "ticket.close_no_permission")).setEphemeral(true).queue();
                return;
            }
            event.reply(i18n.t(lang, "ticket.reopening")).setEphemeral(true).queue(
                    success -> worker.execute(() -> reopenTicketChannel(event.getGuild(), channel, record)),
                    error -> {
                    }
            );
            return;
        }
        if (id.startsWith(DELETE_BUTTON_PREFIX)) {
            if (event.getChannelType() != ChannelType.TEXT) {
                event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                return;
            }
            TextChannel channel = event.getChannel().asTextChannel();
            String missing = formatMissingPermissions(event.getGuild().getSelfMember(), channel, Permission.MANAGE_CHANNEL);
            if (!"-".equals(missing)) {
                event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", missing)))
                        .setEphemeral(true)
                        .queue();
                return;
            }
            long channelId = parseChannelIdFromButton(id, DELETE_BUTTON_PREFIX, channel.getIdLong());
            TicketService.TicketRecord record = ticketService.getTicket(event.getGuild().getIdLong(), channelId);
            if (record == null) {
                event.reply(i18n.t(lang, "ticket.not_ticket_channel")).setEphemeral(true).queue();
                return;
            }
            if (!canManageClosedActions(event.getMember(), event.getGuild().getIdLong())) {
                event.reply(i18n.t(lang, "ticket.close_no_permission")).setEphemeral(true).queue();
                return;
            }
            event.reply(i18n.t(lang, "ticket.deleting")).setEphemeral(true).queue(
                    success -> worker.execute(() -> deleteTicketChannel(event.getGuild(), channel, channelId)),
                    error -> {
                    }
            );
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        String id = event.getComponentId();
        if (OPEN_PANEL_SELECT_ID.equals(id)) {
            BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
            if (!cfg.isEnabled()) {
                event.reply(i18n.t(lang, "ticket.disabled")).setEphemeral(true).queue();
                return;
            }
            if (isBlacklisted(cfg, event.getUser().getIdLong())) {
                event.reply(i18n.t(lang, "ticket.blacklist_denied")).setEphemeral(true).queue();
                return;
            }
            String optionId = event.getValues().isEmpty() ? "" : event.getValues().get(0);
            BotConfig.Ticket.TicketOption option = findTicketOption(cfg, optionId, lang);
            if (option == null) {
                event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
                return;
            }
            beginOpenFlow(event, option, lang);
            return;
        }
        if (!id.startsWith(OPEN_SELECT_PREFIX)) {
            return;
        }
        String token = id.substring(OPEN_SELECT_PREFIX.length());
        OpenRequest request = openRequests.get(token);
        if (request == null || request.guildId != event.getGuild().getIdLong() || request.userId != event.getUser().getIdLong()) {
            event.reply(i18n.t(lang, "ticket.request_expired")).setEphemeral(true).queue();
            return;
        }
        if (request.expiresAtMillis < System.currentTimeMillis()) {
            openRequests.remove(token);
            event.reply(i18n.t(lang, "ticket.request_expired")).setEphemeral(true).queue();
            return;
        }
        BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
        String optionId = event.getValues().isEmpty() ? "" : event.getValues().get(0);
        BotConfig.Ticket.TicketOption option = findTicketOption(cfg, optionId, lang);
        if (option == null) {
            event.reply(i18n.t(lang, "general.unknown_command")).setEphemeral(true).queue();
            return;
        }
        openRequests.put(token, new OpenRequest(request.userId, request.guildId, option.getId(), request.expiresAtMillis));
        beginOpenFlow(event, option, lang);
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        String id = event.getComponentId();
        if (id.startsWith(PANEL_CHANNEL_SELECT_PREFIX)) {
            handlePanelChannelSelect(event, lang);
            return;
        }
        if (id.startsWith(BLACKLIST_ADD_SELECT_PREFIX) || id.startsWith(BLACKLIST_REMOVE_SELECT_PREFIX)) {
            handleBlacklistUserSelect(event, lang);
        }
    }

    private List<BotConfig.Ticket.TicketOption> resolveTicketOptions(BotConfig.Ticket cfg, String lang) {
        List<BotConfig.Ticket.TicketOption> options = new ArrayList<>(cfg.getOptions());
        if (options.isEmpty()) {
            options = List.of(new BotConfig.Ticket.TicketOption(
                    "general",
                    i18n.t(lang, "ticket.default_type_label"),
                    cfg.getPanelTitle(),
                    cfg.getPanelDescription(),
                    cfg.getPanelButtonStyle(),
                    cfg.getWelcomeMessage(),
                    cfg.isPreOpenFormEnabled(),
                    cfg.getPreOpenFormTitle(),
                    cfg.getPreOpenFormLabel(),
                    cfg.getPreOpenFormPlaceholder()
            ));
        }
        int limit = Math.max(1, Math.min(25, cfg.getPanelButtonLimit()));
        List<BotConfig.Ticket.TicketOption> normalized = new ArrayList<>();
        for (BotConfig.Ticket.TicketOption option : options) {
            if (option == null || option.getLabel().isBlank()) {
                continue;
            }
            normalized.add(option);
            if (normalized.size() >= limit) {
                break;
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(new BotConfig.Ticket.TicketOption(
                    "general",
                    i18n.t(lang, "ticket.default_type_label"),
                    cfg.getPanelTitle(),
                    cfg.getPanelDescription(),
                    cfg.getPanelButtonStyle(),
                    cfg.getWelcomeMessage(),
                    cfg.isPreOpenFormEnabled(),
                    cfg.getPreOpenFormTitle(),
                    cfg.getPreOpenFormLabel(),
                    cfg.getPreOpenFormPlaceholder()
            ));
        }
        return normalized;
    }

    private BotConfig.Ticket.TicketOption findTicketOption(BotConfig.Ticket cfg, String optionId, String lang) {
        for (BotConfig.Ticket.TicketOption option : resolveTicketOptions(cfg, lang)) {
            if (option.getId().equalsIgnoreCase(optionId)) {
                return option;
            }
        }
        return null;
    }

    private List<ActionRow> buildOpenPanelComponents(BotConfig.Ticket cfg, String lang) {
        List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(cfg, lang);
        if (cfg.getOpenUiMode() == BotConfig.Ticket.OpenUiMode.SELECT) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(OPEN_PANEL_SELECT_ID)
                    .setPlaceholder(i18n.t(lang, "ticket.select_placeholder"));
            for (BotConfig.Ticket.TicketOption option : options) {
                menu.addOptions(SelectOption.of(resolveTicketOptionLabel(option, lang, false), option.getId()));
            }
            return List.of(ActionRow.of(menu.build()));
        }

        List<Button> buttons = new ArrayList<>();
        for (BotConfig.Ticket.TicketOption option : options) {
            if (options.size() == 1) {
                buttons.add(createOpenButton(option.getPanelButtonStyle(), OPEN_BUTTON_ID, resolveTicketOptionLabel(option, lang, true)));
            } else {
                buttons.add(createOpenButton(option.getPanelButtonStyle(), OPEN_BUTTON_OPTION_PREFIX + option.getId(), resolveTicketOptionLabel(option, lang, false)));
            }
            if (buttons.size() >= 25) {
                break;
            }
        }
        if (buttons.isEmpty()) {
            buttons.add(createOpenButton(cfg.getPanelButtonStyle(), OPEN_BUTTON_ID, i18n.t(lang, "ticket.panel_open_button")));
        }
        List<ActionRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 5) {
            rows.add(ActionRow.of(buttons.subList(i, Math.min(i + 5, buttons.size()))));
        }
        return rows;
    }

    private String resolvePublicPanelTitle(BotConfig.Ticket cfg, String lang) {
        String custom = cfg.getPanelTitle() == null ? "" : cfg.getPanelTitle().trim();
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_title") : custom;
    }

    private String resolvePublicPanelDescription(BotConfig.Ticket cfg, String lang) {
        String custom = cfg.getPanelDescription() == null ? "" : cfg.getPanelDescription().trim();
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_desc") : custom;
    }

    private String resolvePanelTitle(BotConfig.Ticket cfg, BotConfig.Ticket.TicketOption option, String lang) {
        String custom = option == null ? "" : option.getPanelTitle().trim();
        if (custom.isBlank()) {
            custom = cfg.getPanelTitle() == null ? "" : cfg.getPanelTitle().trim();
        }
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_title") : custom;
    }

    private String resolvePanelDescription(BotConfig.Ticket cfg, BotConfig.Ticket.TicketOption option, String lang) {
        String custom = option == null ? "" : option.getPanelDescription().trim();
        if (custom.isBlank()) {
            custom = cfg.getPanelDescription() == null ? "" : cfg.getPanelDescription().trim();
        }
        return custom.isBlank() ? i18n.t(lang, "ticket.panel_desc") : custom;
    }

    private Button createOpenButton(String style, String id, String label) {
        String normalized = style == null ? "PRIMARY" : style.trim().toUpperCase();
        return switch (normalized) {
            case "SECONDARY" -> Button.secondary(id, label);
            case "SUCCESS" -> Button.success(id, label);
            case "DANGER" -> Button.danger(id, label);
            default -> Button.primary(id, label);
        };
    }

    private String resolveTicketOptionLabel(BotConfig.Ticket.TicketOption option, String lang, boolean singleButton) {
        String raw = option == null ? "" : option.getLabel();
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank()) {
            return singleButton ? i18n.t(lang, "ticket.panel_open_button") : i18n.t(lang, "ticket.default_type_label");
        }
        if (LEGACY_DEFAULT_TICKET_LABELS.contains(normalized.toLowerCase())) {
            return singleButton ? i18n.t(lang, "ticket.panel_open_button") : i18n.t(lang, "ticket.default_type_label");
        }
        return normalized;
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) {
            return;
        }
        String lang = settingsService.getLanguage(event.getGuild().getIdLong());
        String modalId = event.getModalId();
        if (modalId.startsWith(OPEN_MODAL_PREFIX)) {
            String token = modalId.substring(OPEN_MODAL_PREFIX.length());
            OpenRequest request = openRequests.remove(token);
            if (request == null || request.guildId != event.getGuild().getIdLong() || request.userId != event.getUser().getIdLong()) {
                event.reply(i18n.t(lang, "ticket.request_expired")).setEphemeral(true).queue();
                return;
            }
            String summary = event.getValue("summary") == null ? "" : event.getValue("summary").getAsString().trim();
            event.deferReply(true).queue();
            worker.execute(() -> createTicket(
                    event.getGuild(),
                    event.getMember(),
                    request.optionId,
                    summary,
                    msg -> event.getHook().sendMessage(msg).queue()
            ));
            return;
        }
        if (modalId.startsWith(CLOSE_MODAL_PREFIX)) {
            if (event.getChannelType() != ChannelType.TEXT) {
                event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
                return;
            }
            TextChannel channel = event.getChannel().asTextChannel();
            TicketService.TicketRecord record = ticketService.getTicket(event.getGuild().getIdLong(), channel.getIdLong());
            if (record == null || record.isClosed()) {
                event.reply(i18n.t(lang, "ticket.not_ticket_channel")).setEphemeral(true).queue();
                return;
            }
            if (!canCloseTicket(event.getMember(), record)) {
                event.reply(i18n.t(lang, "ticket.close_no_permission")).setEphemeral(true).queue();
                return;
            }
            String reason = event.getValue("reason") == null ? "" : event.getValue("reason").getAsString().trim();
            event.reply(i18n.t(lang, "ticket.closing")).setEphemeral(true).queue();
            worker.execute(() -> closeTicketChannel(event.getGuild(), channel, record, event.getUser().getAsTag(), reason, false));
            return;
        }
        if (LIMIT_MODAL_ID.equals(modalId)) {
            handleLimitModal(event, lang);
        }
    }

    private void openPanelChannelPicker(SlashCommandInteractionEvent event, String lang) {
        String token = UUID.randomUUID().toString().replace("-", "");
        manageRequests.put(token, new ManageRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
        ));
        EntitySelectMenu channelMenu = EntitySelectMenu.create(PANEL_CHANNEL_SELECT_PREFIX + token, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(i18n.t(lang, "ticket.panel_channel_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(52, 152, 219))
                        .setTitle(i18n.t(lang, "ticket.panel_channel_title"))
                        .setDescription(i18n.t(lang, "ticket.panel_channel_desc"))
                        .build())
                .addComponents(ActionRow.of(channelMenu))
                .setEphemeral(true)
                .queue();
    }

    private void openBlacklistUserPicker(SlashCommandInteractionEvent event, String prefix, String lang, String promptKey) {
        String token = UUID.randomUUID().toString().replace("-", "");
        manageRequests.put(token, new ManageRequest(
                event.getUser().getIdLong(),
                event.getGuild().getIdLong(),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
        ));
        EntitySelectMenu userMenu = EntitySelectMenu.create(prefix + token, EntitySelectMenu.SelectTarget.USER)
                .setPlaceholder(i18n.t(lang, "ticket.blacklist_user_placeholder"))
                .setRequiredRange(1, 1)
                .build();
        event.replyEmbeds(new EmbedBuilder()
                        .setColor(new Color(230, 126, 34))
                        .setTitle(i18n.t(lang, promptKey))
                        .setDescription(i18n.t(lang, "ticket.blacklist_select_desc"))
                        .build())
                .addComponents(ActionRow.of(userMenu))
                .setEphemeral(true)
                .queue();
    }

    private void handlePanelChannelSelect(EntitySelectInteractionEvent event, String lang) {
        String token = event.getComponentId().substring(PANEL_CHANNEL_SELECT_PREFIX.length());
        ManageRequest request = validateManageRequest(event, token, lang);
        if (request == null) {
            return;
        }
        List<IMentionable> values = event.getValues();
        if (values.isEmpty() || !(values.get(0) instanceof TextChannel target)) {
            event.reply(i18n.t(lang, "settings.validation_expected_text_channel")).setEphemeral(true).queue();
            return;
        }
        String panelMissing = formatMissingPermissions(event.getGuild().getSelfMember(), target,
                Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS);
        if (!"-".equals(panelMissing)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", panelMissing)))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
        worker.execute(() -> {
            ensureTicketCategories(event.getGuild(), lang);
            List<BotConfig.Ticket.TicketOption> options = resolveTicketOptions(cfg, lang);
            EmbedBuilder panel = new EmbedBuilder()
                    .setColor(new Color(cfg.getPanelColor()))
                    .setTitle(resolvePublicPanelTitle(cfg, lang))
                    .setDescription(resolvePublicPanelDescription(cfg, lang))
                    .setTimestamp(Instant.now());
            if (options.size() > 1) {
                for (BotConfig.Ticket.TicketOption option : options) {
                    panel.addField(option.getLabel(), resolvePanelDescription(cfg, option, lang), false);
                }
            }
            target.sendMessageEmbeds(panel.build())
                    .setComponents(buildOpenPanelComponents(cfg, lang))
                    .queue(
                            ok -> event.editMessage(i18n.t(lang, "ticket.panel_sent", Map.of("channel", target.getAsMention())))
                                    .setEmbeds()
                                    .setComponents()
                                    .queue(),
                            err -> event.editMessage(i18n.t(lang, "ticket.panel_send_failed"))
                                    .setEmbeds()
                                    .setComponents()
                                    .queue()
                    );
        });
    }

    private void handleBlacklistUserSelect(EntitySelectInteractionEvent event, String lang) {
        boolean addAction = event.getComponentId().startsWith(BLACKLIST_ADD_SELECT_PREFIX);
        String prefix = addAction ? BLACKLIST_ADD_SELECT_PREFIX : BLACKLIST_REMOVE_SELECT_PREFIX;
        String token = event.getComponentId().substring(prefix.length());
        ManageRequest request = validateManageRequest(event, token, lang);
        if (request == null) {
            return;
        }
        List<IMentionable> values = event.getValues();
        long userId;
        if (!values.isEmpty()) {
            IMentionable selected = values.get(0);
            if (selected instanceof User user) {
                userId = user.getIdLong();
            } else if (selected instanceof Member member) {
                userId = member.getIdLong();
            } else {
                event.reply(i18n.t(lang, "general.invalid_user")).setEphemeral(true).queue();
                return;
            }
        } else if (!event.getMentions().getUsers().isEmpty()) {
            userId = event.getMentions().getUsers().get(0).getIdLong();
        } else {
            event.reply(i18n.t(lang, "general.invalid_user")).setEphemeral(true).queue();
            return;
        }
        BotConfig.Ticket ticket = settingsService.getTicket(event.getGuild().getIdLong());
        List<Long> blacklist = new ArrayList<>(ticket.getBlacklistedUserIds());
        String result;
        if (addAction) {
            if (blacklist.contains(userId)) {
                result = i18n.t(lang, "ticket.blacklist_already", Map.of("user", "<@" + userId + ">"));
            } else {
                blacklist.add(userId);
                settingsService.updateSettings(event.getGuild().getIdLong(),
                        s -> s.withTicket(s.getTicket().withBlacklistedUserIds(blacklist)));
                result = i18n.t(lang, "ticket.blacklist_added", Map.of("user", "<@" + userId + ">"));
            }
        } else {
            if (!blacklist.remove(userId)) {
                result = i18n.t(lang, "ticket.blacklist_not_found", Map.of("user", "<@" + userId + ">"));
            } else {
                settingsService.updateSettings(event.getGuild().getIdLong(),
                        s -> s.withTicket(s.getTicket().withBlacklistedUserIds(blacklist)));
                result = i18n.t(lang, "ticket.blacklist_removed", Map.of("user", "<@" + userId + ">"));
            }
        }
        event.editMessage(result)
                .setEmbeds()
                .setComponents()
                .queue();
    }

    private ManageRequest validateManageRequest(EntitySelectInteractionEvent event, String token, String lang) {
        ManageRequest request = manageRequests.remove(token);
        if (request == null || request.guildId != event.getGuild().getIdLong() || request.userId != event.getUser().getIdLong()) {
            event.reply(i18n.t(lang, "ticket.request_expired")).setEphemeral(true).queue();
            return null;
        }
        if (request.expiresAtMillis < System.currentTimeMillis()) {
            event.reply(i18n.t(lang, "ticket.request_expired")).setEphemeral(true).queue();
            return null;
        }
        return request;
    }

    private Modal buildLimitModal(String lang, int currentValue) {
        TextInput valueInput = TextInput.create("value", TextInputStyle.SHORT)
                .setRequired(true)
                .setPlaceholder(i18n.t(lang, "ticket.limit_modal_placeholder"))
                .setValue(String.valueOf(Math.max(1, currentValue)))
                .setMaxLength(2)
                .build();
        return Modal.create(LIMIT_MODAL_ID, i18n.t(lang, "ticket.limit_modal_title"))
                .addComponents(Label.of(i18n.t(lang, "ticket.limit_modal_label"), valueInput))
                .build();
    }

    private void handleLimitModal(ModalInteractionEvent event, String lang) {
        if (!has(event.getMember(), Permission.MANAGE_SERVER)) {
            event.reply(i18n.t(lang, "general.missing_permissions", Map.of("permissions", Permission.MANAGE_SERVER.getName())))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String raw = event.getValue("value") == null ? "" : event.getValue("value").getAsString().trim();
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            event.reply(i18n.t(lang, "ticket.limit_invalid")).setEphemeral(true).queue();
            return;
        }
        if (value < 1 || value > 20) {
            event.reply(i18n.t(lang, "ticket.limit_invalid")).setEphemeral(true).queue();
            return;
        }
        settingsService.updateSettings(event.getGuild().getIdLong(),
                s -> s.withTicket(s.getTicket().withMaxOpenPerUser(value)));
        event.reply(i18n.t(lang, "general.settings_saved",
                        Map.of("key", i18n.t(lang, "settings.key_ticket_maxOpenPerUser"),
                                "value", String.valueOf(value))))
                .setEphemeral(true)
                .queue();
    }

    private void beginOpenFlow(ButtonInteractionEvent event, BotConfig.Ticket.TicketOption option, String lang) {
        BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
        if (isBlacklisted(cfg, event.getUser().getIdLong())) {
            event.reply(i18n.t(lang, "ticket.blacklist_denied"))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        int openCount = ticketService.countOpenTicketsByOwner(event.getGuild().getIdLong(), event.getUser().getIdLong());
        if (openCount >= cfg.getMaxOpenPerUser()) {
            event.reply(i18n.t(lang, "ticket.max_open_reached",
                            Map.of("max", String.valueOf(cfg.getMaxOpenPerUser()))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (option.isPreOpenFormEnabled()) {
            String token = UUID.randomUUID().toString().replace("-", "");
            openRequests.put(token, new OpenRequest(
                    event.getUser().getIdLong(),
                    event.getGuild().getIdLong(),
                    option.getId(),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
            ));
            Modal modal = buildOpenTicketModal(cfg, option, token, lang);
            event.replyModal(modal).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> createTicket(
                event.getGuild(),
                event.getMember(),
                option.getId(),
                "",
                msg -> event.getHook().sendMessage(msg).queue()
        ));
    }

    private void beginOpenFlow(StringSelectInteractionEvent event, BotConfig.Ticket.TicketOption option, String lang) {
        BotConfig.Ticket cfg = settingsService.getTicket(event.getGuild().getIdLong());
        if (isBlacklisted(cfg, event.getUser().getIdLong())) {
            event.reply(i18n.t(lang, "ticket.blacklist_denied"))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        int openCount = ticketService.countOpenTicketsByOwner(event.getGuild().getIdLong(), event.getUser().getIdLong());
        if (openCount >= cfg.getMaxOpenPerUser()) {
            event.reply(i18n.t(lang, "ticket.max_open_reached",
                            Map.of("max", String.valueOf(cfg.getMaxOpenPerUser()))))
                    .setEphemeral(true)
                    .queue();
            return;
        }
        if (option.isPreOpenFormEnabled()) {
            String token = UUID.randomUUID().toString().replace("-", "");
            openRequests.put(token, new OpenRequest(
                    event.getUser().getIdLong(),
                    event.getGuild().getIdLong(),
                    option.getId(),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3)
            ));
            Modal modal = buildOpenTicketModal(cfg, option, token, lang);
            event.replyModal(modal).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> createTicket(
                event.getGuild(),
                event.getMember(),
                option.getId(),
                "",
                msg -> event.getHook().sendMessage(msg).queue()
        ));
    }

    private void createTicket(Guild guild,
                              Member member,
                              String optionId,
                              String summary,
                              Consumer<String> reply) {
        if (guild == null || member == null) {
            return;
        }
        String lang = settingsService.getLanguage(guild.getIdLong());
        BotConfig.Ticket cfg = settingsService.getTicket(guild.getIdLong());
        if (!cfg.isEnabled()) {
            reply.accept(i18n.t(lang, "ticket.disabled"));
            return;
        }
        if (isBlacklisted(cfg, member.getIdLong())) {
            reply.accept(i18n.t(lang, "ticket.blacklist_denied"));
            return;
        }
        int openCount = ticketService.countOpenTicketsByOwner(guild.getIdLong(), member.getIdLong());
        if (openCount >= cfg.getMaxOpenPerUser()) {
            reply.accept(i18n.t(lang, "ticket.max_open_reached",
                    Map.of("max", String.valueOf(cfg.getMaxOpenPerUser()))));
            return;
        }
        BotConfig.Ticket.TicketOption option = findTicketOption(cfg, optionId, lang);
        if (option == null) {
            reply.accept(i18n.t(lang, "general.unknown_command"));
            return;
        }
        String guildMissing = formatMissingPermissions(guild.getSelfMember(), Permission.MANAGE_CHANNEL);
        if (!"-".equals(guildMissing)) {
            reply.accept(i18n.t(lang, "general.missing_permissions", Map.of("permissions", guildMissing)));
            return;
        }
        TicketCategoryPair pair = ensureTicketCategories(guild, lang);
        Category openCategory = pair.openCategory;
        if (openCategory == null) {
            reply.accept(i18n.t(lang, "ticket.category_create_failed"));
            return;
        }

        String channelName = ("ticket-" + sanitize(member.getEffectiveName())).toLowerCase();
        if (channelName.length() > 90) {
            channelName = channelName.substring(0, 90);
        }

        var createAction = guild.createTextChannel(channelName).setParent(openCategory);
        createAction = createAction.addRolePermissionOverride(
                guild.getPublicRole().getIdLong(),
                0L,
                Permission.getRaw(Permission.VIEW_CHANNEL)
        ).addMemberPermissionOverride(
                member.getIdLong(),
                Permission.getRaw(
                        Permission.VIEW_CHANNEL,
                        Permission.MESSAGE_SEND,
                        Permission.MESSAGE_HISTORY,
                        Permission.MESSAGE_ATTACH_FILES,
                        Permission.MESSAGE_EMBED_LINKS,
                        Permission.USE_APPLICATION_COMMANDS
                ),
                0L
        ).addMemberPermissionOverride(
                guild.getSelfMember().getIdLong(),
                Permission.getRaw(
                        Permission.VIEW_CHANNEL,
                        Permission.MESSAGE_SEND,
                        Permission.MESSAGE_HISTORY,
                        Permission.MESSAGE_ATTACH_FILES,
                        Permission.MESSAGE_EMBED_LINKS,
                        Permission.MANAGE_CHANNEL
                ),
                0L
        );

        for (Long roleId : cfg.getSupportRoleIds()) {
            Role supportRole = guild.getRoleById(roleId);
            if (supportRole == null || supportRole.isPublicRole()) {
                continue;
            }
            createAction = createAction.addRolePermissionOverride(
                    supportRole.getIdLong(),
                    Permission.getRaw(
                            Permission.VIEW_CHANNEL,
                            Permission.MESSAGE_SEND,
                            Permission.MESSAGE_HISTORY,
                            Permission.MESSAGE_ATTACH_FILES,
                            Permission.MESSAGE_EMBED_LINKS,
                            Permission.MESSAGE_MANAGE,
                            Permission.USE_APPLICATION_COMMANDS
                    ),
                    0L
            );
        }

        for (Role role : guild.getRoles()) {
            if (role.isPublicRole()) {
                continue;
            }
            if (role.hasPermission(Permission.MANAGE_SERVER)) {
                createAction = createAction.addRolePermissionOverride(
                        role.getIdLong(),
                        Permission.getRaw(
                                Permission.VIEW_CHANNEL,
                                Permission.MESSAGE_SEND,
                                Permission.MESSAGE_HISTORY
                        ),
                        0L
                );
            }
        }

        TextChannel channel = createAction.complete();
        if (channel == null) {
            reply.accept(i18n.t(lang, "ticket.create_failed"));
            return;
        }

        String finalType = resolveTicketOptionLabel(option, lang, false);
        ticketService.createTicket(
                guild.getIdLong(),
                channel.getIdLong(),
                member.getIdLong(),
                finalType,
                summary
        );
        String welcome = applyWelcomeTemplate(resolveWelcomeMessage(cfg, option, lang), member.getAsMention(), finalType, summary, lang);
        EmbedBuilder welcomeEmbed = new EmbedBuilder()
                .setColor(new Color(88, 101, 242))
                .setTitle(resolvePanelTitle(cfg, option, lang))
                .setDescription(welcome)
                .addField(i18n.t(lang, "ticket.field_type"), finalType, true)
                .setTimestamp(Instant.now());
        if (summary != null && !summary.isBlank()) {
            welcomeEmbed.addField(i18n.t(lang, "ticket.field_summary"), summary, false);
        }
        channel.sendMessage(member.getAsMention())
                .setEmbeds(welcomeEmbed.build())
                .addComponents(ActionRow.of(Button.danger(CLOSE_BUTTON_ID, i18n.t(lang, "ticket.close_button"))))
                .queue(success -> {
                }, error -> {
                });

        reply.accept(i18n.t(lang, "ticket.opened_reply", Map.of("channel", channel.getAsMention())));
        ticketService.touchTicketMessage(guild.getIdLong(), channel.getIdLong(), member.getIdLong());
    }

    private void closeTicketChannel(Guild guild,
                                    TextChannel channel,
                                    TicketService.TicketRecord record,
                                    String closedBy,
                                    String reason,
                                    boolean autoClosed) {
        if (guild == null || channel == null || record == null) {
            return;
        }
        String lang = settingsService.getLanguage(guild.getIdLong());
        TicketCategoryPair categories = ensureTicketCategories(guild, lang);
        if (categories.closedCategory != null) {
            channel.getManager().setParent(categories.closedCategory).queue(success -> {
            }, error -> {
            });
        }

        channel.upsertPermissionOverride(guild.getPublicRole())
                .deny(Permission.VIEW_CHANNEL)
                .queue(success -> {
                }, error -> {
                });
        Member owner = guild.getMemberById(record.getOwnerId());
        if (owner != null) {
            channel.upsertPermissionOverride(owner)
                    .deny(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY)
                    .queue(success -> {
                    }, error -> {
                    });
        }

        String finalReason = reason == null || reason.isBlank()
                ? (autoClosed ? i18n.t(lang, "ticket.auto_close_reason") : i18n.t(lang, "ticket.close_reason_default"))
                : reason;
        TicketService.TicketRecord closedRecord = ticketService.closeTicket(guild.getIdLong(), channel.getIdLong(), finalReason);
        Path transcriptFile = ticketService.writeTranscriptHtml(guild.getIdLong(), guild.getName(), channel, closedRecord == null ? record : closedRecord, closedBy);

        EmbedBuilder closedEmbed = new EmbedBuilder()
                .setColor(new Color(231, 76, 60))
                .setTitle(i18n.t(lang, "ticket.closed_title"))
                .setDescription(i18n.t(lang, autoClosed ? "ticket.closed_desc_auto" : "ticket.closed_desc_manual", Map.of("user", closedBy)))
                .addField(i18n.t(lang, "ticket.field_reason"), finalReason, false)
                .setTimestamp(Instant.now());

        if (transcriptFile != null && Files.exists(transcriptFile)) {
            channel.sendMessageEmbeds(closedEmbed.build())
                    .addFiles(FileUpload.fromData(transcriptFile.toFile()))
                    .queue(msg -> {
                        String transcriptUrl = msg.getAttachments().isEmpty() ? "" : msg.getAttachments().get(0).getUrl();
                        notifyParticipantsByDm(guild, closedRecord == null ? record : closedRecord, transcriptFile, transcriptUrl, lang);
                        sendClosedManageActions(guild, channel, lang);
                    }, error -> channel.sendMessageEmbeds(closedEmbed.build()).queue(s -> sendClosedManageActions(guild, channel, lang)));
        } else {
            channel.sendMessageEmbeds(closedEmbed.build()).queue(s -> {
                notifyParticipantsByDm(guild, closedRecord == null ? record : closedRecord, null, "", lang);
                sendClosedManageActions(guild, channel, lang);
            });
        }
    }

    private void sendClosedManageActions(Guild guild, TextChannel channel, String lang) {
        if (guild == null || channel == null) {
            return;
        }
        channel.sendMessage(i18n.t(lang, "ticket.closed_manage_prompt"))
                .addComponents(ActionRow.of(
                        Button.primary(REOPEN_BUTTON_PREFIX + channel.getId(), i18n.t(lang, "ticket.reopen_button")),
                        Button.danger(DELETE_BUTTON_PREFIX + channel.getId(), i18n.t(lang, "ticket.delete_button"))
                ))
                .queue(success -> {
                }, error -> {
                });
    }

    private void reopenTicketChannel(Guild guild, TextChannel channel, TicketService.TicketRecord record) {
        if (guild == null || channel == null || record == null) {
            return;
        }
        String lang = settingsService.getLanguage(guild.getIdLong());
        TicketCategoryPair categories = ensureTicketCategories(guild, lang);
        if (categories.openCategory != null) {
            channel.getManager().setParent(categories.openCategory).queue(success -> {
            }, error -> {
            });
        }
        channel.upsertPermissionOverride(guild.getPublicRole())
                .deny(Permission.VIEW_CHANNEL)
                .queue(success -> {
                }, error -> {
                });
        Member owner = guild.getMemberById(record.getOwnerId());
        if (owner != null) {
            channel.upsertPermissionOverride(owner)
                    .grant(
                            Permission.VIEW_CHANNEL,
                            Permission.MESSAGE_SEND,
                            Permission.MESSAGE_HISTORY,
                            Permission.MESSAGE_ATTACH_FILES,
                            Permission.MESSAGE_EMBED_LINKS,
                            Permission.USE_APPLICATION_COMMANDS
                    )
                    .queue(success -> {
                    }, error -> {
                    });
        }
        for (Long roleId : settingsService.getTicket(guild.getIdLong()).getSupportRoleIds()) {
            Role supportRole = guild.getRoleById(roleId);
            if (supportRole == null || supportRole.isPublicRole()) {
                continue;
            }
            channel.upsertPermissionOverride(supportRole)
                    .grant(
                            Permission.VIEW_CHANNEL,
                            Permission.MESSAGE_SEND,
                            Permission.MESSAGE_HISTORY,
                            Permission.MESSAGE_ATTACH_FILES,
                            Permission.MESSAGE_EMBED_LINKS,
                            Permission.MESSAGE_MANAGE,
                            Permission.USE_APPLICATION_COMMANDS
                    )
                    .queue(success -> {
                    }, error -> {
                    });
        }
        ticketService.reopenTicket(guild.getIdLong(), record.getChannelId());
        channel.sendMessage(i18n.t(lang, "ticket.reopened")).queue(success -> {
        }, error -> {
        });
    }

    private void deleteTicketChannel(Guild guild, TextChannel channel, long channelId) {
        if (guild == null || channel == null) {
            return;
        }
        String lang = settingsService.getLanguage(guild.getIdLong());
        ticketService.deleteTicket(guild.getIdLong(), channelId);
        channel.delete().reason(i18n.t(lang, "ticket.delete_reason")).queue(success -> {
        }, error -> {
        });
    }

    private void notifyParticipantsByDm(Guild guild,
                                        TicketService.TicketRecord record,
                                        Path transcriptFile,
                                        String transcriptUrl,
                                        String lang) {
        if (record == null || jda == null) {
            return;
        }
        Set<Long> targets = new HashSet<>(record.getParticipants());
        targets.add(record.getOwnerId());
        boolean hasFile = transcriptFile != null && Files.exists(transcriptFile);
        for (Long userId : targets) {
            if (userId == null || userId <= 0) {
                continue;
            }
            jda.retrieveUserById(userId).queue(user -> {
                        if (hasFile) {
                            user.openPrivateChannel()
                                    .flatMap(ch -> ch.sendMessage(i18n.t(lang, "ticket.transcript_dm_attached",
                                                    Map.of("guild", guild.getName())))
                                            .addFiles(FileUpload.fromData(transcriptFile.toFile())))
                                    .queue(success -> {
                                    }, error -> {
                                    });
                            return;
                        }
                        if (transcriptUrl != null && !transcriptUrl.isBlank()) {
                            user.openPrivateChannel()
                                    .flatMap(ch -> ch.sendMessage(i18n.t(lang, "ticket.transcript_dm",
                                            Map.of("guild", guild.getName(), "url", transcriptUrl))))
                                    .queue(success -> {
                                    }, error -> {
                                    });
                        }
                    },
                    error -> {
                    });
        }
    }

    private boolean canCloseTicket(Member member, TicketService.TicketRecord record) {
        if (member == null || record == null) {
            return false;
        }
        return member.getIdLong() == record.getOwnerId()
                || member.hasPermission(Permission.MANAGE_CHANNEL)
                || member.hasPermission(Permission.MANAGE_SERVER)
                || isSupportMember(member, member.getGuild().getIdLong());
    }

    private boolean canManageClosedActions(Member member, long guildId) {
        if (member == null) {
            return false;
        }
        return member.hasPermission(Permission.MANAGE_CHANNEL)
                || member.hasPermission(Permission.MANAGE_SERVER)
                || isSupportMember(member, guildId);
    }

    private boolean isSupportMember(Member member, long guildId) {
        if (member == null) {
            return false;
        }
        List<Long> supportRoleIds = settingsService.getTicket(guildId).getSupportRoleIds();
        if (supportRoleIds.isEmpty()) {
            return false;
        }
        Set<Long> roleIds = new HashSet<>();
        for (Role role : member.getRoles()) {
            roleIds.add(role.getIdLong());
        }
        for (Long roleId : supportRoleIds) {
            if (roleIds.contains(roleId)) {
                return true;
            }
        }
        return false;
    }

    private long parseChannelIdFromButton(String componentId, String prefix, long fallback) {
        if (componentId == null || !componentId.startsWith(prefix)) {
            return fallback;
        }
        String raw = componentId.substring(prefix.length()).trim();
        if (raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private TicketCategoryPair ensureTicketCategories(Guild guild, String lang) {
        BotConfig.Ticket cfg = settingsService.getTicket(guild.getIdLong());
        Long openCategoryId = cfg.getOpenCategoryId();
        Long closedCategoryId = cfg.getClosedCategoryId();
        Category open = openCategoryId == null ? null : guild.getCategoryById(openCategoryId);
        Category closed = closedCategoryId == null ? null : guild.getCategoryById(closedCategoryId);
        boolean changed = false;

        if (open == null) {
            open = guild.createCategory(i18n.t(lang, "ticket.default_open_category_name")).complete();
            if (open != null) {
                openCategoryId = open.getIdLong();
                changed = true;
            }
        }
        if (closed == null) {
            closed = guild.createCategory(i18n.t(lang, "ticket.default_closed_category_name")).complete();
            if (closed != null) {
                closedCategoryId = closed.getIdLong();
                changed = true;
            }
        }

        if (changed) {
            Long finalOpenCategoryId = openCategoryId;
            Long finalClosedCategoryId = closedCategoryId;
            settingsService.updateSettings(guild.getIdLong(), s -> s.withTicket(s.getTicket()
                    .withOpenCategoryId(finalOpenCategoryId)
                    .withClosedCategoryId(finalClosedCategoryId)));
        }

        return new TicketCategoryPair(open, closed);
    }

    private void ensureCategoriesForEnabledGuilds() {
        if (jda == null) {
            return;
        }
        for (Guild guild : jda.getGuilds()) {
            try {
                BotConfig.Ticket cfg = settingsService.getTicket(guild.getIdLong());
                if (cfg.isEnabled()) {
                    ensureTicketCategories(guild, settingsService.getLanguage(guild.getIdLong()));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void autoCloseStaleTickets() {
        if (jda == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Guild guild : jda.getGuilds()) {
            BotConfig.Ticket cfg = settingsService.getTicket(guild.getIdLong());
            if (!cfg.isEnabled()) {
                continue;
            }
            long cutoff = now - TimeUnit.DAYS.toMillis(Math.max(1, cfg.getAutoCloseDays()));
            for (TicketService.TicketRecord record : ticketService.getOpenTickets(guild.getIdLong())) {
                if (record.getLastInteractionAt() > cutoff) {
                    continue;
                }
                TextChannel channel = guild.getTextChannelById(record.getChannelId());
                if (channel == null) {
                    ticketService.closeTicket(guild.getIdLong(), record.getChannelId(), "Auto closed (channel missing)");
                    continue;
                }
                closeTicketChannel(guild, channel, record, "AutoClose", "", true);
            }
        }
    }

    private String applyWelcomeTemplate(String template, String userMention, String typeLabel, String summary, String lang) {
        String text = template == null ? "" : template;
        if (text.isBlank()) {
            text = i18n.t(lang, "ticket.default_welcome_message");
        }
        return text.replace("{user}", userMention == null ? "-" : userMention)
                .replace("{type}", typeLabel == null ? "-" : typeLabel)
                .replace("{summary}", summary == null || summary.isBlank() ? "-" : summary);
    }

    private String resolveWelcomeMessage(BotConfig.Ticket cfg, BotConfig.Ticket.TicketOption option, String lang) {
        String configured = option == null ? "" : option.getWelcomeMessage();
        if (configured == null || configured.isBlank()) {
            configured = cfg.getWelcomeMessage();
        }
        return configured == null || configured.isBlank()
                ? i18n.t(lang, "ticket.default_welcome_message")
                : configured;
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "user";
        }
        String value = raw.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5_-]", "-");
        value = value.replaceAll("-{2,}", "-");
        if (value.startsWith("-")) {
            value = value.substring(1);
        }
        if (value.endsWith("-")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isBlank() ? "user" : value;
    }

    private boolean isBlacklisted(BotConfig.Ticket cfg, long userId) {
        if (cfg == null || userId <= 0L) {
            return false;
        }
        return cfg.getBlacklistedUserIds().contains(userId);
    }

    private void cleanupExpiredRequests() {
        long now = System.currentTimeMillis();
        openRequests.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
        manageRequests.entrySet().removeIf(e -> e.getValue().expiresAtMillis < now);
    }

    private boolean has(Member member, Permission permission) {
        return member != null && member.hasPermission(permission);
    }

    private String formatMissingPermissions(Member member, Permission... permissions) {
        if (member == null) {
            return "-";
        }
        Set<String> names = new HashSet<>();
        for (Permission permission : permissions) {
            if (!member.hasPermission(permission)) {
                names.add(permission.getName());
            }
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private String formatMissingPermissions(Member member, TextChannel channel, Permission... permissions) {
        if (member == null || channel == null) {
            return "-";
        }
        Set<String> names = new HashSet<>();
        for (Permission permission : permissions) {
            if (!member.hasPermission(channel, permission)) {
                names.add(permission.getName());
            }
        }
        return names.isEmpty() ? "-" : String.join(", ", names);
    }

    private Modal buildOpenTicketModal(BotConfig.Ticket cfg, BotConfig.Ticket.TicketOption option, String token, String lang) {
        String modalTitle = firstNonBlank(option.getPreOpenFormTitle(), firstNonBlank(cfg.getPreOpenFormTitle(), i18n.t(lang, "ticket.open_modal_title")));
        String modalLabel = firstNonBlank(option.getPreOpenFormLabel(), firstNonBlank(cfg.getPreOpenFormLabel(), i18n.t(lang, "ticket.open_modal_label")));
        String modalPlaceholder = firstNonBlank(option.getPreOpenFormPlaceholder(), firstNonBlank(cfg.getPreOpenFormPlaceholder(), i18n.t(lang, "ticket.summary_placeholder")));
        TextInput summaryInput = TextInput.create("summary", TextInputStyle.PARAGRAPH)
                .setRequired(false)
                .setPlaceholder(modalPlaceholder)
                .setMaxLength(500)
                .build();
        return Modal.create(OPEN_MODAL_PREFIX + token, modalTitle)
                .addComponents(Label.of(modalLabel, summaryInput))
                .build();
    }

    private String firstNonBlank(String configured, String fallback) {
        if (configured != null && !configured.trim().isBlank()) {
            return configured.trim();
        }
        return fallback == null ? "" : fallback;
    }

    private String canonicalCommandName(String name) {
        return CMD_TICKET_ZH.equals(name) ? "ticket" : name;
    }

    private String boolText(String lang, boolean value) {
        return i18n.t(lang, value ? "settings.info_bool_on" : "settings.info_bool_off");
    }
}




