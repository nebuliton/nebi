package io.nebuliton.ai;

import io.nebuliton.config.Config;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class Commands extends ListenerAdapter {
    private final ContextStore contextStore;
    private final Config config;
    private final AIManager aiManager;

    public Commands(ContextStore contextStore, Config config, AIManager aiManager) {
        this.contextStore = contextStore;
        this.config = config;
        this.aiManager = aiManager;
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
                new SubcommandData("add", "➕ Wissenszeile hinzufügen")
                        .addOptions(
                                new OptionData(OptionType.STRING, "text", "📌 Wissen", true)
                                        .setMaxLength(maxKnowledge)
                        ),
                new SubcommandData("list", "📜 Letzte Wissenseinträge anzeigen")
                        .addOptions(new OptionData(OptionType.INTEGER, "limit", "🔢 Anzahl (1-20)", false)
                                .setMinValue(1)
                                .setMaxValue(20)),
                new SubcommandData("remove", "🗑️ Wissenseintrag löschen")
                        .addOptions(new OptionData(OptionType.INTEGER, "id", "🪪 ID aus /knowledge list", true)),
                new SubcommandData("review", "🕵️ Gelerntes Wissen mit niedriger Confidence prüfen")
                        .addOptions(
                                new OptionData(OptionType.NUMBER, "max_confidence", "0.0 bis 1.0", false)
                                        .setMinValue(0)
                                        .setMaxValue(1),
                                new OptionData(OptionType.INTEGER, "limit", "🔢 Anzahl (1-20)", false)
                                        .setMinValue(1)
                                        .setMaxValue(20)
                        )
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

        CommandData forget = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "forget",
                "🧹 Lösche meine Konversationshistorie"
        );

        CommandData stats = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "stats",
                "📊 Server-Statistiken anzeigen"
        );

        CommandData why = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "why",
                "🧪 Zeigt, wie meine letzte Antwort gebaut wurde"
        );

        CommandData sources = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "sources",
                "📚 Zeigt Wissensquellen aus der letzten Antwort"
        );

        CommandData rate = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "rate",
                "⭐ Bewerte die letzte AI-Antwort"
        ).addOptions(
                new OptionData(OptionType.STRING, "rating", "✅ oder ❌", true)
                        .addChoice("good", "good")
                        .addChoice("bad", "bad"),
                new OptionData(OptionType.STRING, "reason", "Optionaler Grund", false)
                        .setMaxLength(280)
        );

        CommandData summarize = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "summarize",
                "📝 Fasse die letzten Nachrichten zusammen"
        ).addOptions(
                new OptionData(OptionType.INTEGER, "limit", "Anzahl Nachrichten (10-100)", false)
                        .setMinValue(10)
                        .setMaxValue(100),
                new OptionData(OptionType.STRING, "style", "z.B. kurz, technisch, action-items", false)
                        .setMaxLength(40)
        );

        CommandData aiHealth = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "ai-health",
                "🩺 AI-Systemzustand inkl. Top-Chatter"
        );

        CommandData topChatters = net.dv8tion.jda.api.interactions.commands.build.Commands.slash(
                "top-chatters",
                "🏆 Zeigt die aktivsten Chatter"
        ).addOptions(
                new OptionData(OptionType.INTEGER, "limit", "Anzahl (1-20)", false)
                        .setMinValue(1)
                        .setMaxValue(20)
        );

        List<CommandData> commands = List.of(
                context,
                knowledge,
                blacklist,
                forget,
                stats,
                why,
                sources,
                rate,
                summarize,
                aiHealth,
                topChatters
        );

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
            replyError(event, "Nur im Server verfügbar", "Dieser Command funktioniert nicht in DMs.");
            return;
        }

        switch (event.getName()) {
            case "context" -> handleContext(event);
            case "knowledge" -> handleKnowledge(event);
            case "ai-blacklist" -> handleBlacklist(event);
            case "forget" -> handleForget(event);
            case "stats" -> handleStats(event);
            case "why" -> handleWhy(event);
            case "sources" -> handleSources(event);
            case "rate" -> handleRate(event);
            case "summarize" -> handleSummarize(event);
            case "ai-health" -> handleAIHealth(event);
            case "top-chatters" -> handleTopChatters(event);
            default -> {
            }
        }
    }

    private void handleContext(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        if (sub == null) {
            replyWarning(event, "Subcommand fehlt", "Nutze `add`, `clear` oder `view`.");
            return;
        }

        switch (sub) {
            case "add" -> handleContextAdd(event);
            case "clear" -> handleContextClear(event);
            case "view" -> handleContextView(event);
            default -> replyError(event, "Unbekannter Subcommand", "Bitte überprüfe den Command-Aufruf.");
        }
    }

    private void handleContextAdd(SlashCommandInteractionEvent event) {
        User target = getUserOrSelf(event);
        if (!isSelfOrAllowed(event, target)) {
            return;
        }

        String context = getRequiredString(event, "context");
        if (context.length() > config.ux.maxContextLength) {
            replyWarning(event, "Kontext zu lang", "Maximal **" + config.ux.maxContextLength + "** Zeichen erlaubt.");
            return;
        }

        contextStore.setUserContext(event.getGuild().getIdLong(), target.getIdLong(), context);
        replySuccess(event, "Kontext gespeichert", "Für **" + safeName(target) + "** wurde der Kontext aktualisiert.");
    }

    private void handleContextClear(SlashCommandInteractionEvent event) {
        User target = getUserOrSelf(event);
        if (!isSelfOrAllowed(event, target)) {
            return;
        }

        contextStore.clearUserContext(event.getGuild().getIdLong(), target.getIdLong());
        replySuccess(event, "Kontext gelöscht", "Für **" + safeName(target) + "** wurde der Kontext entfernt.");
    }

    private void handleContextView(SlashCommandInteractionEvent event) {
        User target = getUserOrSelf(event);
        if (!isSelfOrAllowed(event, target)) {
            return;
        }

        Optional<String> context = contextStore.getUserContext(event.getGuild().getIdLong(), target.getIdLong());
        if (context.isEmpty()) {
            replyInfo(event, "Kein Kontext vorhanden", "Für **" + safeName(target) + "** ist nichts gespeichert.");
            return;
        }

        String preview = truncate(context.get(), 1800);
        replyInfo(event, "Kontext für " + safeName(target), "```" + preview + "```");
    }

    private void handleKnowledge(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            replyError(event, "Keine Berechtigung", "Dafür brauchst du **Manage Server**.");
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            replyWarning(event, "Subcommand fehlt", "Nutze `add`, `list`, `review` oder `remove`.");
            return;
        }

        switch (sub) {
            case "add" -> handleKnowledgeAdd(event);
            case "list" -> handleKnowledgeList(event);
            case "review" -> handleKnowledgeReview(event);
            case "remove" -> handleKnowledgeRemove(event);
            default -> replyError(event, "Unbekannter Subcommand", "Bitte überprüfe den Command-Aufruf.");
        }
    }

    private void handleKnowledgeAdd(SlashCommandInteractionEvent event) {
        String text = getRequiredString(event, "text");
        if (text.length() > config.ux.maxKnowledgeLength) {
            replyWarning(event, "Wissen zu lang", "Maximal **" + config.ux.maxKnowledgeLength + "** Zeichen erlaubt.");
            return;
        }

        contextStore.addKnowledge(event.getGuild().getIdLong(), event.getUser().getIdLong(), text);
        replySuccess(event, "Wissen gespeichert", "Der Eintrag wurde zur Wissensbasis hinzugefügt.");
    }

    private void handleKnowledgeList(SlashCommandInteractionEvent event) {
        int limit = getOptionalInt(event, "limit", 10);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<ContextStore.KnowledgeEntry> entries =
                contextStore.listKnowledge(event.getGuild().getIdLong(), safeLimit);
        if (entries.isEmpty()) {
            replyInfo(event, "Keine Wissenseinträge", "Aktuell ist noch nichts gespeichert.");
            return;
        }

        StringBuilder builder = new StringBuilder("📚 Wissenseinträge:\n");
        for (ContextStore.KnowledgeEntry entry : entries) {
            builder.append(entry.id())
                    .append(") [")
                    .append(entry.source())
                    .append(", conf=")
                    .append(String.format("%.2f", entry.confidence()))
                    .append("] ")
                    .append(truncate(entry.text(), 130))
                    .append('\n');
        }

        replyInfo(event, "Wissenseinträge", "```" + truncate(builder.toString(), 1700) + "```");
    }

    private void handleKnowledgeReview(SlashCommandInteractionEvent event) {
        double maxConfidence = getOptionalDouble(event, "max_confidence", 0.65);
        int limit = Math.max(1, Math.min(getOptionalInt(event, "limit", 10), 20));

        List<ContextStore.KnowledgeEntry> entries =
                contextStore.listKnowledgeForReview(event.getGuild().getIdLong(), limit, maxConfidence);
        if (entries.isEmpty()) {
            replyInfo(event, "Nichts zu reviewen", "Keine `learned`-Einträge unter der gewählten Confidence.");
            return;
        }

        StringBuilder builder = new StringBuilder("🕵️ Review-Liste (learned, low confidence):\n");
        for (ContextStore.KnowledgeEntry entry : entries) {
            builder.append(entry.id())
                    .append(") conf=")
                    .append(String.format("%.2f", entry.confidence()))
                    .append(" | ")
                    .append(truncate(entry.text(), 130))
                    .append('\n');
        }

        replyInfo(event, "Knowledge Review", "```" + truncate(builder.toString(), 1700) + "```");
    }

    private void handleKnowledgeRemove(SlashCommandInteractionEvent event) {
        long id = getRequiredLong(event, "id");
        contextStore.removeKnowledge(event.getGuild().getIdLong(), id);
        replySuccess(event, "Wissenseintrag gelöscht", "Der Eintrag wurde entfernt.");
    }

    private void handleBlacklist(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            replyError(event, "Keine Berechtigung", "Dafür brauchst du **Manage Server**.");
            return;
        }

        String sub = event.getSubcommandName();
        if (sub == null) {
            replyWarning(event, "Subcommand fehlt", "Nutze `add`, `remove` oder `list`.");
            return;
        }

        switch (sub) {
            case "add" -> handleBlacklistAdd(event);
            case "remove" -> handleBlacklistRemove(event);
            case "list" -> handleBlacklistList(event);
            default -> replyError(event, "Unbekannter Subcommand", "Bitte überprüfe den Command-Aufruf.");
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
        replySuccess(event, "Blacklist aktualisiert", "**" + safeName(target) + "** wurde auf die Blacklist gesetzt.");
    }

    private void handleBlacklistRemove(SlashCommandInteractionEvent event) {
        User target = event.getOption("user", event.getUser(), OptionMapping::getAsUser);
        contextStore.removeBlacklist(event.getGuild().getIdLong(), target.getIdLong());
        replySuccess(event, "Blacklist aktualisiert", "**" + safeName(target) + "** wurde von der Blacklist entfernt.");
    }

    private void handleBlacklistList(SlashCommandInteractionEvent event) {
        int limit = getOptionalInt(event, "limit", 10);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        List<ContextStore.BlacklistEntry> entries =
                contextStore.listBlacklist(event.getGuild().getIdLong(), safeLimit);
        if (entries.isEmpty()) {
            replyInfo(event, "Blacklist", "Keine Einträge vorhanden.");
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

        replyInfo(event, "Blacklist", "```" + truncate(builder.toString(), 1700) + "```");
    }

    private void handleForget(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();
        contextStore.clearConversation(guildId, userId);
        replySuccess(event, "Konversation gelöscht", "Alles zurückgesetzt. Frischer Start.");
    }

    private void handleStats(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        int knowledgeCount = contextStore.countKnowledge(guildId);
        int blacklistCount = contextStore.countBlacklist(guildId);
        int contextCount = contextStore.countContexts(guildId);
        int conversationCount = contextStore.countConversations(guildId);
        ContextStore.FeedbackStats feedback = contextStore.getFeedbackStats(
                guildId,
                Instant.now().minus(Duration.ofDays(7)).toEpochMilli()
        );

        String stats = String.format("""
                📊 **Server-Statistiken**
                
                🧠 Wissenseinträge: **%d**
                🧭 User-Kontexte: **%d**
                💬 Aktive Konversationen: **%d**
                🚫 Blacklist-Einträge: **%d**
                ⭐ Feedback 7 Tage: **%d good / %d bad**
                """, knowledgeCount, contextCount, conversationCount, blacklistCount, feedback.goodCount(), feedback.badCount());

        replyInfo(event, "Server-Statistiken", stats);
    }

    private void handleWhy(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();
        Optional<ContextStore.ReplyAudit> audit = contextStore.getLatestReplyAudit(guildId, userId);
        if (audit.isEmpty()) {
            replyInfo(event, "Noch keine Daten", "Schreib mir zuerst eine Nachricht per Mention.");
            return;
        }

        ContextStore.ReplyAudit a = audit.get();
        String text = String.format("""
                🧪 **Warum diese Antwort?**
                
                🤖 Modell: `%s`
                🧭 User-Kontext genutzt: **%s**
                🗂️ History-Nachrichten genutzt: **%d**
                📚 Knowledge-Quellen: **%s**
                ⚡ Latenz: **%dms**
                🕒 Zeit: <t:%d:R>
                """,
                a.model(),
                a.usedUserContext() ? "ja" : "nein",
                a.historyCount(),
                (a.knowledgeIds() == null || a.knowledgeIds().isBlank()) ? "0" : a.knowledgeIds(),
                a.latencyMs(),
                a.createdAt() / 1000
        );

        replyInfo(event, "Warum diese Antwort?", truncate(text, 1700));
    }

    private void handleSources(SlashCommandInteractionEvent event) {
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();
        Optional<ContextStore.ReplyAudit> audit = contextStore.getLatestReplyAudit(guildId, userId);
        if (audit.isEmpty()) {
            replyInfo(event, "Keine Quellen", "Frag mich erst etwas per Mention.");
            return;
        }

        String preview = audit.get().knowledgePreview();
        if (preview == null || preview.isBlank()) {
            replyInfo(event, "Keine Knowledge-Quellen", "Bei der letzten Antwort wurde kein gespeichertes Wissen genutzt.");
            return;
        }

        replyInfo(event, "Verwendete Quellen", "```" + truncate(preview, 1700) + "```");
    }

    private void handleRate(SlashCommandInteractionEvent event) {
        String ratingRaw = event.getOption("rating", "", OptionMapping::getAsString).trim().toLowerCase();
        String rating = switch (ratingRaw) {
            case "good", "bad" -> ratingRaw;
            default -> "";
        };

        if (rating.isBlank()) {
            replyWarning(event, "Ungültiges Rating", "Nutze `good` oder `bad`.");
            return;
        }

        Optional<ContextStore.ReplyAudit> audit = contextStore.getLatestReplyAudit(
                event.getGuild().getIdLong(),
                event.getUser().getIdLong()
        );
        if (audit.isEmpty()) {
            replyInfo(event, "Noch keine letzte Antwort", "Bewerte zuerst nach einer Antwort per Mention.");
            return;
        }

        String reason = event.getOption("reason", "", OptionMapping::getAsString);
        contextStore.addFeedback(event.getGuild().getIdLong(), event.getUser().getIdLong(), rating, reason);

        String response = "good".equals(rating)
                ? "Danke fürs positive Feedback."
                : "Danke, ich nutze das Feedback zur Verbesserung.";
        replySuccess(event, "Feedback gespeichert", response);
    }

    private void handleSummarize(SlashCommandInteractionEvent event) {
        int limit = Math.max(10, Math.min(getOptionalInt(event, "limit", 30), 100));
        String style = event.getOption("style", "neutral", OptionMapping::getAsString);

        event.deferReply(true).queue(hook -> event.getChannel().getHistory().retrievePast(limit).queue(history -> {
            List<Message> sorted = new ArrayList<>(history);
            sorted.sort(Comparator.comparing(Message::getTimeCreated));

            List<String> lines = new ArrayList<>();
            for (Message msg : sorted) {
                if (msg.getAuthor().isSystem()) {
                    continue;
                }
                String content = msg.getContentDisplay();
                if (content == null || content.isBlank()) {
                    continue;
                }
                lines.add(msg.getAuthor().getName() + ": " + truncate(content, 280));
            }

            if (lines.isEmpty()) {
                hook.editOriginal(buildComponentEdit("⚠️", "Keine Daten", "Keine verwertbaren Nachrichten gefunden.")).queue();
                return;
            }

            aiManager.summarizeMessages(
                    event.getGuild().getIdLong(),
                    event.getUser().getIdLong(),
                    style,
                    lines
            ).thenAccept(summary -> hook.editOriginal(
                            buildComponentEdit("ℹ️", "Zusammenfassung", truncate(summary, 3800))
                    ).queue())
                    .exceptionally(error -> {
                        hook.editOriginal(buildComponentEdit("❌", "Fehler", config.ux.errorReply)).queue();
                        return null;
                    });
        }, error -> hook.editOriginal(
                buildComponentEdit("❌", "Fehler", "Konnte Channel-Historie nicht laden.")
        ).queue()));
    }

    private void handleAIHealth(SlashCommandInteractionEvent event) {
        if (!hasManageServer(event)) {
            replyError(event, "Keine Berechtigung", "Dafür brauchst du **Manage Server**.");
            return;
        }

        long guildId = event.getGuild().getIdLong();
        AIManager.HealthStats health = aiManager.healthStats();
        int lowConfidence = contextStore.countLowConfidenceKnowledge(guildId, 0.65);
        String topLine = buildTopChattersText(guildId, 5);

        String text = String.format("""
                🩺 **AI Health**
                
                📨 Requests gesamt: **%d**
                ❌ Fehler gesamt: **%d**
                ⚡ Ø Latenz: **%dms**
                📥 Queue: **%d** | 🔧 Aktiv: **%d** | ✅ Fertig: **%d**
                🧪 Low-Confidence Knowledge (<=0.65): **%d**
                
                🏆 **Top-Chatter**
                %s
                """,
                health.totalRequests(),
                health.totalErrors(),
                health.avgLatencyMs(),
                health.queueDepth(),
                health.activeWorkers(),
                health.completedTasks(),
                lowConfidence,
                topLine
        );

        replyInfo(event, "AI Health", truncate(text, 1700));
    }

    private void handleTopChatters(SlashCommandInteractionEvent event) {
        int limit = Math.max(1, Math.min(getOptionalInt(event, "limit", 10), 20));
        String top = buildTopChattersText(event.getGuild().getIdLong(), limit);
        replyInfo(event, "Top Chatters", top);
    }

    private String buildTopChattersText(long guildId, int limit) {
        List<ContextStore.UserMessageCount> top = contextStore.listTopChatters(guildId, limit);
        if (top.isEmpty()) {
            return "- keine Daten -";
        }
        StringBuilder builder = new StringBuilder();
        int rank = 1;
        for (ContextStore.UserMessageCount entry : top) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(rank)
                    .append(". <@")
                    .append(entry.userId())
                    .append(">: ")
                    .append(entry.totalMessages())
                    .append(" Nachrichten");
            rank++;
        }
        return builder.toString();
    }

    private boolean isSelfOrAllowed(SlashCommandInteractionEvent event, User target) {
        if (target.getIdLong() == event.getUser().getIdLong()) {
            return true;
        }
        if (hasManageServer(event)) {
            return true;
        }
        replyError(event, "Keine Berechtigung", "Du darfst nur deinen eigenen Kontext bearbeiten.");
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

    private double getOptionalDouble(SlashCommandInteractionEvent event, String option, double fallback) {
        return event.getOption(option, fallback, OptionMapping::getAsDouble);
    }

    private void replyInfo(SlashCommandInteractionEvent event, String title, String description) {
        event.reply(buildComponentMessage("ℹ️", title, description))
                .setEphemeral(true)
                .queue();
    }

    private void replySuccess(SlashCommandInteractionEvent event, String title, String description) {
        event.reply(buildComponentMessage("✅", title, description))
                .setEphemeral(true)
                .queue();
    }

    private void replyWarning(SlashCommandInteractionEvent event, String title, String description) {
        event.reply(buildComponentMessage("⚠️", title, description))
                .setEphemeral(true)
                .queue();
    }

    private void replyError(SlashCommandInteractionEvent event, String title, String description) {
        event.reply(buildComponentMessage("❌", title, description))
                .setEphemeral(true)
                .queue();
    }

    private MessageCreateData buildComponentMessage(String icon, String title, String description) {
        MessageCreateBuilder builder = new MessageCreateBuilder();
        builder.addComponents(Container.of(TextDisplay.of(formatComponentText(icon, title, description))));
        return builder.build();
    }

    private MessageEditData buildComponentEdit(String icon, String title, String description) {
        MessageEditBuilder builder = new MessageEditBuilder();
        builder.setComponents(List.of(Container.of(TextDisplay.of(formatComponentText(icon, title, description)))));
        return builder.build();
    }

    private String formatComponentText(String icon, String title, String description) {
        return "## " + icon + " " + title + "\n"
                + truncate(description, 3600)
                + "\n\n-# Nebi Commands • <t:" + (Instant.now().toEpochMilli() / 1000) + ":R>";
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
