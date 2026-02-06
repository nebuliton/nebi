package io.nebuliton.ai;

import io.nebuliton.config.Config;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.List;
import java.util.Optional;

public final class Commands extends ListenerAdapter {
    private final ContextStore contextStore;
    private final Config config;

    public Commands(ContextStore contextStore, Config config) {
        this.contextStore = contextStore;
        this.config = config;
    }

    public static void registerCommands(JDA jda, Config config) {
        int maxContext = clamp(config.ux.maxContextLength, 1, 4000);
        int maxKnowledge = clamp(config.ux.maxKnowledgeLength, 1, 4000);

        CommandData context = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "context",
                "🧭 User-Kontext verwalten"
        ).addSubcommands(
                new SubcommandData("add", "➕ Kontext setzen oder ersetzen")
                        .addOptions(
                                new OptionData(OptionType.STRING, "context", "📝 Kontexttext", true)
                                        .setMaxLength(maxContext),
                                new OptionData(OptionType.USER, "user", "🧑 Optionaler Ziel-User", false)
                        ),
                new SubcommandData("clear", "🧹 Kontext entfernen")
                        .addOptions(new OptionData(OptionType.USER, "user", "🧑 Optionaler Ziel-User", false)),
                new SubcommandData("view", "👀 Kontext anzeigen")
                        .addOptions(new OptionData(OptionType.USER, "user", "🧑 Optionaler Ziel-User", false))
        );

        CommandData knowledge = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "knowledge",
                "🧠 Server-Wissen verwalten"
        ).addSubcommands(
                new SubcommandData("add", "➕ Wissenszeile hinzufuegen")
                        .addOptions(
                                new OptionData(OptionType.STRING, "text", "📌 Wissen", true)
                                        .setMaxLength(maxKnowledge)
                        ),
                new SubcommandData("list", "📜 Letzte Wissenseintraege anzeigen")
                        .addOptions(new OptionData(OptionType.INTEGER, "limit", "🔢 Anzahl (1-20)", false)
                                .setMinValue(1)
                                .setMaxValue(20)),
                new SubcommandData("remove", "🗑️ Wissenseintrag loeschen")
                        .addOptions(new OptionData(OptionType.INTEGER, "id", "🪪 ID aus /knowledge list", true))
        );

        CommandData blacklist = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "ai-blacklist",
                "🚫 AI-Blacklist verwalten"
        ).addSubcommands(
                new SubcommandData("add", "⛔ User auf Blacklist setzen")
                        .addOptions(
                                new OptionData(OptionType.USER, "user", "🧑 User", true),
                                new OptionData(OptionType.STRING, "reason", "📝 Optionaler Grund", false)
                                        .setMaxLength(200)
                        ),
                new SubcommandData("remove", "✅ User von Blacklist entfernen")
                        .addOptions(new OptionData(OptionType.USER, "user", "🧑 User", true)),
                new SubcommandData("list", "📋 Blacklist anzeigen")
                        .addOptions(new OptionData(OptionType.INTEGER, "limit", "🔢 Anzahl (1-20)", false)
                                .setMinValue(1)
                                .setMaxValue(20))
        );

        List<CommandData> commands = List.of(context, knowledge, blacklist);

        if (config.discord.guildId != null && !config.discord.guildId.isBlank()) {
            Guild guild = jda.getGuildById(config.discord.guildId);
            if (guild != null) {
                guild.updateCommands().addCommands(commands).queue();
                return;
            }
        }

        jda.updateCommands().addCommands(commands).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.isFromGuild()) {
            event.reply("Dieser Command geht nur auf einem Server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (event.getName()) {
            case "context" -> handleContext(event);
            case "knowledge" -> handleKnowledge(event);
            case "ai-blacklist" -> handleBlacklist(event);
            default -> {
            }
        }
    }

    private void handleContext(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("Bitte einen Subcommand verwenden: add, clear oder view.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (sub) {
            case "add" -> handleContextAdd(event);
            case "clear" -> handleContextClear(event);
            case "view" -> handleContextView(event);
            default -> event.reply("Unbekannter Subcommand.").setEphemeral(true).queue();
        }
    }

    private void handleContextAdd(SlashCommandInteractionEvent event) {
        User target = getUserOrSelf(event);
        if (!isSelfOrAllowed(event, target)) {
            return;
        }

        String context = getRequiredString(event, "context");
        if (context.length() > config.ux.maxContextLength) {
            event.reply("Kontext ist zu lang. Max " + config.ux.maxContextLength + " Zeichen.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        contextStore.setUserContext(event.getGuild().getIdLong(), target.getIdLong(), context);
        event.reply("Kontext gespeichert fuer **" + safeName(target) + "**.")
                .setEphemeral(true)
                .queue();
    }

    private void handleContextClear(SlashCommandInteractionEvent event) {
        User target = getUserOrSelf(event);
        if (!isSelfOrAllowed(event, target)) {
            return;
        }

        contextStore.clearUserContext(event.getGuild().getIdLong(), target.getIdLong());
        event.reply("Kontext geloescht fuer **" + safeName(target) + "**.")
                .setEphemeral(true)
                .queue();
    }

    private void handleContextView(SlashCommandInteractionEvent event) {
        User target = getUserOrSelf(event);
        if (!isSelfOrAllowed(event, target)) {
            return;
        }

        Optional<String> context = contextStore.getUserContext(event.getGuild().getIdLong(), target.getIdLong());
        if (context.isEmpty()) {
            event.reply("Kein Kontext gespeichert fuer **" + safeName(target) + "**.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String preview = truncate(context.get(), 1800);
        event.reply("Kontext fuer **" + safeName(target) + "**:\n" + preview)
                .setEphemeral(true)
                .queue();
    }

    private void handleKnowledge(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            event.reply("Dafuer brauchst du Manage Server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("Bitte einen Subcommand verwenden: add, list oder remove.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (sub) {
            case "add" -> handleKnowledgeAdd(event);
            case "list" -> handleKnowledgeList(event);
            case "remove" -> handleKnowledgeRemove(event);
            default -> event.reply("Unbekannter Subcommand.").setEphemeral(true).queue();
        }
    }

    private void handleKnowledgeAdd(SlashCommandInteractionEvent event) {
        String text = getRequiredString(event, "text");
        if (text.length() > config.ux.maxKnowledgeLength) {
            event.reply("Wissen ist zu lang. Max " + config.ux.maxKnowledgeLength + " Zeichen.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        contextStore.addKnowledge(event.getGuild().getIdLong(), event.getUser().getIdLong(), text);
        event.reply("Wissen gespeichert. Mein Kopf ist jetzt etwas groesser.")
                .setEphemeral(true)
                .queue();
    }

    private void handleKnowledgeList(SlashCommandInteractionEvent event) {
        int limit = getOptionalInt(event, "limit", 10);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<ContextStore.KnowledgeEntry> entries =
                contextStore.listKnowledge(event.getGuild().getIdLong(), safeLimit);
        if (entries.isEmpty()) {
            event.reply("Noch kein Wissen gespeichert.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        StringBuilder builder = new StringBuilder("Wissenseintraege:\n");
        for (ContextStore.KnowledgeEntry entry : entries) {
            builder.append(entry.id())
                    .append(") ")
                    .append(truncate(entry.text(), 160))
                    .append('\n');
        }

        event.reply(truncate(builder.toString(), 1900))
                .setEphemeral(true)
                .queue();
    }

    private void handleKnowledgeRemove(SlashCommandInteractionEvent event) {
        long id = getRequiredLong(event, "id");
        contextStore.removeKnowledge(event.getGuild().getIdLong(), id);
        event.reply("Wissenseintrag entfernt.")
                .setEphemeral(true)
                .queue();
    }

    private void handleBlacklist(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            event.reply("Dafuer brauchst du Manage Server.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            event.reply("Bitte einen Subcommand verwenden: add, remove oder list.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        switch (sub) {
            case "add" -> handleBlacklistAdd(event);
            case "remove" -> handleBlacklistRemove(event);
            case "list" -> handleBlacklistList(event);
            default -> event.reply("Unbekannter Subcommand.").setEphemeral(true).queue();
        }
    }

    private void handleBlacklistAdd(SlashCommandInteractionEvent event) {
        User target = event.getOption("user", event.getUser(), OptionMapping::getAsUser);
        String reason = event.getOption("reason", "Kein Grund angegeben.", OptionMapping::getAsString);
        contextStore.addBlacklist(
                event.getGuild().getIdLong(),
                target.getIdLong(),
                event.getUser().getIdLong(),
                reason
        );
        event.reply("**" + safeName(target) + "** ist jetzt auf der Blacklist. Ich antworte nicht mehr.")
                .setEphemeral(true)
                .queue();
    }

    private void handleBlacklistRemove(SlashCommandInteractionEvent event) {
        User target = event.getOption("user", event.getUser(), OptionMapping::getAsUser);
        contextStore.removeBlacklist(event.getGuild().getIdLong(), target.getIdLong());
        event.reply("Blacklist fuer **" + safeName(target) + "** entfernt.")
                .setEphemeral(true)
                .queue();
    }

    private void handleBlacklistList(SlashCommandInteractionEvent event) {
        int limit = getOptionalInt(event, "limit", 10);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<ContextStore.BlacklistEntry> entries =
                contextStore.listBlacklist(event.getGuild().getIdLong(), safeLimit);
        if (entries.isEmpty()) {
            event.reply("Blacklist ist leer.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        StringBuilder builder = new StringBuilder("Blacklist:\n");
        for (ContextStore.BlacklistEntry entry : entries) {
            builder.append("<@")
                    .append(entry.userId())
                    .append(">");
            if (entry.reason() != null && !entry.reason().isBlank()) {
                builder.append(" - ").append(truncate(entry.reason(), 120));
            }
            builder.append('\n');
        }

        event.reply(truncate(builder.toString(), 1900))
                .setEphemeral(true)
                .queue();
    }

    private boolean isSelfOrAllowed(SlashCommandInteractionEvent event, User target) {
        if (target.getIdLong() == event.getUser().getIdLong()) {
            return true;
        }
        if (hasManageServer(event)) {
            return true;
        }
        event.reply("Du darfst nur deinen eigenen Kontext bearbeiten.")
                .setEphemeral(true)
                .queue();
        return false;
    }

    private boolean hasManageServer(SlashCommandInteractionEvent event) {
        return event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER, Permission.ADMINISTRATOR);
    }

    private User getUserOrSelf(SlashCommandInteractionEvent event) {
        return event.getOption("user", event.getUser(), OptionMapping::getAsUser);
    }

    private String getRequiredString(SlashCommandInteractionEvent event, String option) {
        return event.getOption(option, "", OptionMapping::getAsString);
    }

    private long getRequiredLong(SlashCommandInteractionEvent event, String option) {
        return event.getOption(option, 0L, OptionMapping::getAsLong);
    }

    private int getOptionalInt(SlashCommandInteractionEvent event, String option, int fallback) {
        long value = event.getOption(option, (long) fallback, OptionMapping::getAsLong);
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }

    private String safeName(User user) {
        return user.getName();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
