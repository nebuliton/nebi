package io.nebuliton.ai;

import io.nebuliton.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AIManager {
    private static final Logger LOG = LoggerFactory.getLogger(AIManager.class);
    private static final Pattern LEARN_PATTERN = Pattern.compile(
            "\\[LEARN:([^\\]]+)\\]",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VALID_PATTERN = Pattern.compile("\\\"valid\\\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile("\\\"confidence\\\"\\s*:\\s*(0(?:\\.\\d+)?|1(?:\\.0+)?)");
    private static final Pattern REASON_PATTERN = Pattern.compile("\\\"reason\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");

    private static final String FACT_CHECK_PROMPT = """
            Du bist ein Fact-Checker. Analysiere die folgende Aussage und antworte NUR mit einem JSON-Objekt.
            
            Regeln:
            - \"valid\": true wenn die Aussage faktisch korrekt und speichernswert ist
            - \"valid\": false wenn die Aussage falsch, politisch, kontrovers, beleidigend, rassistisch, sexistisch oder Meinung ist
            - \"confidence\": Zahl von 0.0 bis 1.0
            - \"reason\": kurze Begründung (max 50 Zeichen)
            
            IMMER ablehnen bei:
            - Politische Themen (Parteien, Politiker, Wahlen, Gesetze)
            - Kontroverse Themen (Religion, Abtreibung, Gender-Debatten)
            - Verschwörungstheorien (Flache Erde, Chemtrails, etc.)
            - Beleidigungen oder Diskriminierung
            - Subjektive Meinungen als Fakten getarnt
            - Falsche wissenschaftliche Behauptungen
            
            Aussage: \"%s\"
            
            Antwort (nur JSON, kein anderer Text):
            """;

    private final OpenAIClient client;
    private final ContextStore contextStore;
    private final Config config;
    private final ThreadPoolExecutor executor;

    private final AtomicLong totalRequests = new AtomicLong();
    private final AtomicLong totalErrors = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    public AIManager(OpenAIClient client, ContextStore contextStore, Config config) {
        this.client = client;
        this.contextStore = contextStore;
        this.config = config;
        this.executor = new ThreadPoolExecutor(
                2,
                2,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                runnable -> {
                    Thread thread = new Thread(runnable, "ai-worker");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    public CompletableFuture<String> generateReply(long guildId, long userId, String displayName, String prompt) {
        totalRequests.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = System.currentTimeMillis();
            boolean storageAllowed = contextStore.isStorageAllowed(guildId, userId);

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

            Optional<String> userContext = storageAllowed
                    ? contextStore.getUserContext(guildId, userId)
                    : Optional.empty();
            userContext.ifPresent(context -> messages.add(new OpenAIClient.ChatMessage(
                    "system",
                    "User-Kontext (nur nutzen, wenn relevant): " + context
            )));

            int includedHistory = 0;
            if (storageAllowed && config.ux.maxConversationMessages > 0) {
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
                    includedHistory++;
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
                long latency = Math.max(0L, System.currentTimeMillis() - startedAt);
                totalLatencyMs.addAndGet(latency);

                if (response == null || response.isBlank()) {
                    totalErrors.incrementAndGet();
                    return config.ux.errorReply;
                }

                String cleanResponse = processLearning(guildId, userId, response, storageAllowed);
                if (storageAllowed) {
                    contextStore.saveReplyAudit(
                            guildId,
                            userId,
                            config.openai.model,
                            userContext.isPresent(),
                            includedHistory,
                            knowledgeEntries.stream().map(ContextStore.KnowledgeEntry::id).toList(),
                            previewKnowledge(knowledgeEntries),
                            truncate(prompt, 400),
                            truncate(cleanResponse, 600),
                            latency
                    );
                }
                return cleanResponse;
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                logOpenAIError(e.getMessage());
                return config.ux.errorReply;
            }
        }, executor);
    }

    public CompletableFuture<String> summarizeMessages(long guildId, long userId, String style, List<String> messages) {
        totalRequests.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = System.currentTimeMillis();
            boolean storageAllowed = contextStore.isStorageAllowed(guildId, userId);
            try {
                List<OpenAIClient.ChatMessage> prompt = new ArrayList<>();
                prompt.add(new OpenAIClient.ChatMessage("system", """
                        Du bist ein Discord-Assistant.
                        Erstelle eine praezise Zusammenfassung auf Deutsch.
                        Gib zuerst 4-8 Stichpunkte und dann den Block 'Action Items' mit klaren TODOs.
                        Wenn etwas unklar ist, schreibe 'Unklar' statt zu raten.
                        """));
                prompt.add(new OpenAIClient.ChatMessage("user", buildSummaryPrompt(style, messages)));

                String response = client.createChatCompletion(
                        config.openai.model,
                        prompt,
                        0.3,
                        Math.max(220, config.openai.maxTokens)
                );
                long latency = Math.max(0L, System.currentTimeMillis() - startedAt);
                totalLatencyMs.addAndGet(latency);
                if (storageAllowed) {
                    contextStore.saveReplyAudit(
                            guildId,
                            userId,
                            config.openai.model,
                            false,
                            0,
                            List.of(),
                            "",
                            truncate("summarize:" + style, 200),
                            truncate(response, 600),
                            latency
                    );
                }
                if (response == null || response.isBlank()) {
                    totalErrors.incrementAndGet();
                    return "Konnte keine Zusammenfassung erzeugen.";
                }
                return response;
            } catch (Exception e) {
                totalErrors.incrementAndGet();
                logOpenAIError(e.getMessage());
                return config.ux.errorReply;
            }
        }, executor);
    }

    public HealthStats healthStats() {
        long requests = totalRequests.get();
        long errors = totalErrors.get();
        long latencyTotal = totalLatencyMs.get();
        long avgLatency = requests <= 0 ? 0 : latencyTotal / requests;
        return new HealthStats(
                requests,
                errors,
                avgLatency,
                executor.getQueue().size(),
                executor.getActiveCount(),
                executor.getCompletedTaskCount(),
                Instant.now().toEpochMilli()
        );
    }

    private String buildSummaryPrompt(String style, List<String> messages) {
        String tone = style == null || style.isBlank() ? "neutral" : style;
        StringBuilder builder = new StringBuilder();
        builder.append("Stil: ").append(tone).append("\\n")
                .append("Analysiere die folgenden Discord-Nachrichten (alt -> neu):\\n\\n");
        for (String message : messages) {
            if (message == null || message.isBlank()) {
                continue;
            }
            builder.append("- ").append(message).append('\n');
        }
        return builder.toString();
    }

    private String previewKnowledge(List<ContextStore.KnowledgeEntry> entries) {
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int max = Math.min(entries.size(), 8);
        for (int i = 0; i < max; i++) {
            ContextStore.KnowledgeEntry entry = entries.get(i);
            if (!builder.isEmpty()) {
                builder.append(" | ");
            }
            builder.append('#').append(entry.id()).append(' ').append(truncate(entry.text(), 80));
        }
        return truncate(builder.toString(), 700);
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

    private String processLearning(long guildId, long userId, String response, boolean storageAllowed) {
        Matcher matcher = LEARN_PATTERN.matcher(response);
        StringBuffer cleanResponse = new StringBuffer();

        while (matcher.find()) {
            String learnContent = matcher.group(1).trim();
            if (storageAllowed && !learnContent.isBlank() && learnContent.length() <= config.ux.maxKnowledgeLength) {
                CompletableFuture.runAsync(() -> {
                    try {
                        FactCheckResult result = factCheck(learnContent);
                        if (result.valid()) {
                            contextStore.addLearnedKnowledge(guildId, userId, learnContent, result.confidence());
                            LOG.info("Neues Wissen gelernt (confidence={}): {}", result.confidence(), learnContent);
                        } else {
                            LOG.info("Wissen abgelehnt: {} ({})", learnContent, result.reason());
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

    private FactCheckResult factCheck(String statement) {
        try {
            List<OpenAIClient.ChatMessage> messages = List.of(
                    new OpenAIClient.ChatMessage("system", "Du bist ein strenger Fact-Checker. Antworte nur mit JSON."),
                    new OpenAIClient.ChatMessage("user", String.format(FACT_CHECK_PROMPT, statement))
            );

            String response = client.createChatCompletion(
                    config.openai.model,
                    messages,
                    0.1,
                    120
            );

            if (response == null || response.isBlank()) {
                return new FactCheckResult(false, 0.0, "empty response");
            }

            Matcher validMatcher = VALID_PATTERN.matcher(response);
            boolean valid = validMatcher.find() && "true".equalsIgnoreCase(validMatcher.group(1));
            double confidence = extractConfidence(response, valid ? 0.55 : 0.0);
            String reason = extractReason(response);
            return new FactCheckResult(valid, confidence, reason);
        } catch (Exception e) {
            LOG.warn("Fact-Check API Fehler: {}", e.getMessage());
            return new FactCheckResult(false, 0.0, "api error");
        }
    }

    private double extractConfidence(String response, double fallback) {
        Matcher matcher = CONFIDENCE_PATTERN.matcher(response);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            double value = Double.parseDouble(matcher.group(1));
            return Math.max(0.0, Math.min(1.0, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String extractReason(String response) {
        Matcher matcher = REASON_PATTERN.matcher(response);
        if (!matcher.find()) {
            return "no reason";
        }
        return matcher.group(1);
    }

    private void logOpenAIError(String msg) {
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
            LOG.error("Keine Verbindung zu OpenAI moeglich - check dein Internet");
        } else if (msg.contains("context_length") || msg.contains("maximum context")) {
            LOG.warn("Nachricht zu lang - Konversation wird gekuerzt");
        } else if (msg.contains("insufficient_quota")) {
            LOG.error("Kein Guthaben mehr auf deinem OpenAI Account!");
        } else {
            LOG.error("OpenAI Fehler: {}", msg.split("\\n")[0]);
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private record FactCheckResult(boolean valid, double confidence, String reason) {
    }

    public record HealthStats(
            long totalRequests,
            long totalErrors,
            long avgLatencyMs,
            int queueDepth,
            int activeWorkers,
            long completedTasks,
            long measuredAt
    ) {
    }
}
