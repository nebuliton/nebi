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

public final class AIManager {
    private static final Logger LOG = LoggerFactory.getLogger(AIManager.class);
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
            messages.add(new OpenAIClient.ChatMessage("system", config.openai.systemPrompt));
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
                return response;
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && msg.contains("401")) {
                    LOG.error("OpenAI API-Key ungültig! Check deinen Key in der config.yml");
                } else if (msg != null && msg.contains("429")) {
                    LOG.warn("OpenAI Rate-Limit erreicht, chill mal kurz");
                } else if (msg != null && msg.contains("timeout")) {
                    LOG.warn("OpenAI Timeout - Anfrage hat zu lange gedauert");
                } else {
                    LOG.error("OpenAI Fehler: {}", msg != null ? msg.split("\n")[0] : "Unbekannt");
                }
                return config.ux.errorReply;
            }
        }, executor);
    }
}
