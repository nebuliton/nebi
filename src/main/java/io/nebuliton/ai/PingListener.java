package io.nebuliton.ai;

import io.nebuliton.config.Config;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.Collections;

public final class PingListener extends ListenerAdapter {
    private final AIManager aiManager;
    private final ContextStore contextStore;
    private final Config config;
    private final RateLimiter rateLimiter;

    public PingListener(AIManager aiManager, ContextStore contextStore, Config config, RateLimiter rateLimiter) {
        this.aiManager = aiManager;
        this.contextStore = contextStore;
        this.config = config;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild()) {
            return;
        }
        if (event.getAuthor().isBot() || event.getAuthor().isSystem()) {
            return;
        }

        if (!event.getMessage().getMentions().isMentioned(event.getJDA().getSelfUser())) {
            return;
        }

        long guildId = event.getGuild().getIdLong();
        long userId = event.getAuthor().getIdLong();
        if (contextStore.isBlacklisted(guildId, userId)) {
            return;
        }

        if (!rateLimiter.allow(guildId, userId)) {
            if (config.ux.cooldownReply != null && !config.ux.cooldownReply.isBlank()) {
                event.getMessage().reply(config.ux.cooldownReply)
                        .setAllowedMentions(Collections.emptyList())
                        .queue();
            }
            return;
        }

        String content = event.getMessage().getContentRaw();
        String cleaned = stripMention(content, event.getJDA().getSelfUser().getId());
        String userMessage = cleaned;
        boolean hasUserContent = !userMessage.isBlank();
        String prompt = hasUserContent ? userMessage : "Sag hallo und frag, was los ist.";
        if (prompt.length() > config.ux.maxUserMessageLength) {
            prompt = prompt.substring(0, config.ux.maxUserMessageLength);
        }

        if (config.ux.typingIndicator) {
            event.getChannel().sendTyping().queue();
        }

        String displayName = event.getMember() != null
                ? event.getMember().getEffectiveName()
                : event.getAuthor().getName();

        aiManager.generateReply(guildId, userId, displayName, prompt)
                .thenAccept(reply -> {
                    event.getMessage().reply(reply)
                            .setAllowedMentions(Collections.emptyList())
                            .queue();
                    storeConversation(guildId, userId, hasUserContent ? userMessage : null, reply);
                })
                .exceptionally(error -> {
                    event.getMessage().reply(config.ux.errorReply)
                            .setAllowedMentions(Collections.emptyList())
                            .queue();
                    return null;
                });
    }

    private String stripMention(String content, String botId) {
        String mention = "<@" + botId + ">";
        String mentionNick = "<@!" + botId + ">";
        String without = content.replace(mention, "").replace(mentionNick, "");
        return without.trim();
    }

    private void storeConversation(long guildId, long userId, String userMessage, String assistantMessage) {
        if (!contextStore.isStorageAllowed(guildId, userId)) {
            return;
        }
        if (config.ux.maxConversationMessages <= 0) {
            return;
        }
        if (assistantMessage == null) {
            return;
        }
        if (assistantMessage.equals(config.ux.errorReply)) {
            return;
        }
        try {
            int maxLen = Math.max(1, config.ux.maxConversationMessageLength);
            if (userMessage != null && !userMessage.isBlank()) {
                contextStore.addConversationMessage(
                        guildId,
                        userId,
                        "user",
                        truncate(userMessage, maxLen)
                );
            }
            contextStore.addConversationMessage(
                    guildId,
                    userId,
                    "assistant",
                    truncate(assistantMessage, maxLen)
            );
            contextStore.trimConversation(guildId, userId, config.ux.maxConversationMessages);
        } catch (Exception ignored) {
        }
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }
}
