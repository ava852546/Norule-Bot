package com.norule.musicbot.discord.bot.flow;

import com.norule.musicbot.discord.bot.app.MusicCommandService;
import com.norule.musicbot.discord.gateway.FlowContext;
import com.norule.musicbot.discord.gateway.FlowStep;
import com.norule.musicbot.discord.gateway.Intent;
import com.norule.musicbot.discord.gateway.Signals;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

import java.util.List;

public final class SlashFlow {
    private final MusicCommandService owner;
    private final Signals signals;
    private final List<FlowStep<SlashCommandInteractionEvent>> steps;

    public SlashFlow(MusicCommandService owner, Signals signals) {
        this.owner = owner;
        this.signals = signals;
        this.steps = List.of(
                new SlashIdentityGate(),
                this::localeGate,
                this::intentResolver,
                new SlashPolicyGate(owner),
                this::dispatch
        );
    }

    public void run(SlashCommandInteractionEvent event) {
        FlowContext context = new FlowContext();
        for (FlowStep<SlashCommandInteractionEvent> step : steps) {
            if (!step.apply(event, context)) {
                return;
            }
        }
    }

    private boolean localeGate(SlashCommandInteractionEvent event, FlowContext context) {
        context.put("lang", owner.lang(event.getGuild().getIdLong()));
        return true;
    }

    private boolean intentResolver(SlashCommandInteractionEvent event, FlowContext context) {
        context.put("intent", new Intent(owner.canonicalSlashName(event.getName())));
        return true;
    }

    private boolean dispatch(SlashCommandInteractionEvent event, FlowContext context) {
        owner.dispatchSlashFromGateway(event);
        Intent intent = context.get("intent", Intent.class);
        signals.publish("slash:" + (intent == null ? event.getName() : intent.key()));
        return true;
    }
}

