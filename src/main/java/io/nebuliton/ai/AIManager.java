package io.nebuliton.ai;

import io.nebuliton.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AIManager {
    private static final Logger LOG = LoggerFactory.getLogger(AIManager.class);
    private static final Pattern LEARN_PATTERN = Pattern.compile(
            "\\[LEARN:([^\\]]+)\\]",
            Pattern.CASE_INSENSITIVE
    );
    private static final String FACT_CHECK_PROMPT = """
            Du bist ein Fact-Checker. Analysiere die folgende Aussage und antworte NUR mit einem JSON-Objekt.
            
            Regeln:
            - "valid": true wenn die Aussage faktisch korrekt und speichernswert ist
            - "valid": false wenn die Aussage falsch, politisch, kontrovers, beleidigend, rassistisch, sexistisch oder Meinung ist
            - "reason": kurze Begründung (max 50 Zeichen)
            
            IMMER ablehnen bei:
            - Politische Themen (Parteien, Politiker, Wahlen, Gesetze)
            - Kontroverse Themen (Religion, Abtreibung, Gender-Debatten)
            - Verschwörungstheorien (Flache Erde, Chemtrails, etc.)
            - Beleidigungen oder Diskriminierung
            - Subjektive Meinungen als Fakten getarnt
            - Falsche wissenschaftliche Behauptungen
            
            Aussage: "%s"
            
            Antwort (nur JSON, kein anderer Text):
            """;

    private final OpenAIClient client;
    private final ContextStore contextStore;
    private final Config config;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-worker");
        thread.setDaemon(true);
        return thread;
    });

    public AIManager(OpenAIClient client, ContextStore contextStore, Config config) {
        this.client = client;
        this.contextStore = contextStore;
        this.config = config;
    }

    public CompletableFuture<String> generateReply(long guildId, long userId, String displayName, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            List<OpenAIClient.ChatMessage> messages = new ArrayList<>();
            messages.add(new OpenAIClient.ChatMessage("system", buildSystemPrompt()));
            messages.add(new OpenAIClient.ChatMessage(
                    "system",
                    "User: " + displayName + " (" + userId + "). Sprich den User gelegentlich mit dem Namen an."
            ));

            List<ContextStore.KnowledgeEntry> knowledgeEntries =
                    contextStore.listKnowledge(guildId, config.ux.maxKnowledgeEntries);
            if (!knowledgeEntries.isEmpty()) {
                StringBuilder knowledge = new StringBuilder("Server-Wissen:\n");
                for (ContextStore.KnowledgeEntry entry : knowledgeEntries) {
                    knowledge.append("- ").append(entry.text()).append('\n');
                }
                messages.add(new OpenAIClient.ChatMessage("system", knowledge.toString().trim()));
            }

            Optional<String> userContext = contextStore.getUserContext(guildId, userId);
            userContext.ifPresent(context -> messages.add(new OpenAIClient.ChatMessage(
                    "system",
                    "User-Kontext (nur nutzen, wenn relevant): " + context
            )));

            if (config.ux.maxConversationMessages > 0) {
                List<ContextStore.ConversationMessage> history =
                        contextStore.listConversationMessages(guildId, userId, config.ux.maxConversationMessages);
                for (ContextStore.ConversationMessage message : history) {
                    if (message.content() == null || message.content().isBlank()) {
                        continue;
                    }
                    String role = message.role();
                    if (!"user".equals(role) && !"assistant".equals(role)) {
                        continue;
                    }
                    messages.add(new OpenAIClient.ChatMessage(role, message.content()));
                }
            }

            messages.add(new OpenAIClient.ChatMessage("user", prompt));
            try {
                String response = client.createChatCompletion(
                        config.openai.model,
                        messages,
                        config.openai.temperature,
                        config.openai.maxTokens
                );
                if (response == null || response.isBlank()) {
                    return config.ux.errorReply;
                }

                // Prüfe ob der Bot etwas lernen will
                String cleanResponse = processLearning(guildId, userId, response);
                return cleanResponse;
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null) {
                    LOG.error("Unbekannter Fehler aufgetreten");
                } else if (msg.contains("401")) {
                    LOG.error("API-Key ist ungültig! Hol dir einen neuen von platform.openai.com");
                } else if (msg.contains("403")) {
                    LOG.error("Zugriff verweigert - dein API-Key hat keine Berechtigung für dieses Modell");
                } else if (msg.contains("429")) {
                    LOG.warn("Rate-Limit erreicht - zu viele Anfragen, warte kurz");
                } else if (msg.contains("500") || msg.contains("502") || msg.contains("503")) {
                    LOG.warn("OpenAI Server hat Probleme - versuch's später nochmal");
                } else if (msg.contains("timeout") || msg.contains("timed out")) {
                    LOG.warn("Timeout - Anfrage hat zu lange gedauert");
                } else if (msg.contains("Connection") || msg.contains("connect")) {
                    LOG.error("Keine Verbindung zu OpenAI möglich - check dein Internet");
                } else if (msg.contains("context_length") || msg.contains("maximum context")) {
                    LOG.warn("Nachricht zu lang - Konversation wird gekürzt");
                } else if (msg.contains("insufficient_quota")) {
                    LOG.error("Kein Guthaben mehr auf deinem OpenAI Account!");
                } else {
                    LOG.error("OpenAI Fehler: {}", msg.split("\n")[0]);
                }
                return config.ux.errorReply;
            }
        }, executor);
    }

    private String buildSystemPrompt() {
        return config.openai.systemPrompt + """
            
            WISSEN SPEICHERN:
            Wenn dir jemand etwas Interessantes über den Server, die Community oder allgemein nützliche Fakten erzählt,
            kannst du das speichern indem du [LEARN:dein text hier] in deine Antwort einbaust.
            Das Tag wird automatisch entfernt und der Inhalt wird fact-gecheckt bevor er gespeichert wird.
            
            Beispiel: "Ah cool, merk ich mir! [LEARN:Der Server wurde 2020 gegründet]"
            
            NIEMALS speichern:
            - Politische Aussagen
            - Kontroverse Meinungen  
            - Beleidigungen oder Diskriminierung
            - Offensichtlich falsche Fakten
            - Persönliche Meinungen als Fakten
            """;
    }

    private String processLearning(long guildId, long userId, String response) {
        Matcher matcher = LEARN_PATTERN.matcher(response);
        StringBuffer cleanResponse = new StringBuffer();

        while (matcher.find()) {
            String learnContent = matcher.group(1).trim();
            if (!learnContent.isBlank() && learnContent.length() <= config.ux.maxKnowledgeLength) {
                // Async fact-check und speichern
                CompletableFuture.runAsync(() -> {
                    try {
                        if (factCheck(learnContent)) {
                            contextStore.addKnowledge(guildId, userId, learnContent);
                            LOG.info("Neues Wissen gelernt: {}", learnContent);
                        } else {
                            LOG.info("Wissen abgelehnt (Fact-Check failed): {}", learnContent);
                        }
                    } catch (Exception e) {
                        LOG.warn("Fact-Check fehlgeschlagen: {}", e.getMessage());
                    }
                }, executor);
            }
            matcher.appendReplacement(cleanResponse, "");
        }
        matcher.appendTail(cleanResponse);

        return cleanResponse.toString().trim().replaceAll("\\s{2,}", " ");
    }

    private boolean factCheck(String statement) {
        try {
            List<OpenAIClient.ChatMessage> messages = List.of(
                    new OpenAIClient.ChatMessage("system", "Du bist ein strenger Fact-Checker. Antworte nur mit JSON."),
                    new OpenAIClient.ChatMessage("user", String.format(FACT_CHECK_PROMPT, statement))
            );

            String response = client.createChatCompletion(
                    config.openai.model,
                    messages,
                    0.1, // Niedrige Temperature für konsistente Ergebnisse
                    100
            );

            if (response == null) {
                return false;
            }

            // Einfaches Parsing - suche nach "valid": true
            String lower = response.toLowerCase();
            boolean hasValidTrue = lower.contains("\"valid\"") && lower.contains("true");
            boolean hasValidFalse = lower.contains("\"valid\"") && lower.contains("false");

            if (hasValidFalse) {
                return false;
            }

            return hasValidTrue;
        } catch (Exception e) {
            LOG.warn("Fact-Check API Fehler: {}", e.getMessage());
            return false; // Im Zweifel ablehnen
        }
    }
}
