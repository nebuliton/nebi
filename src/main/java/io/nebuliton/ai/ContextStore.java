package io.nebuliton.ai;

import io.nebuliton.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ContextStore {
    private final Database database;

    public ContextStore(Database database) {
        this.database = database;
    }

    public void setUserContext(long guildId, long userId, String context) {
        String sql = """
                INSERT INTO user_contexts (guild_id, user_id, context, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(guild_id, user_id)
                DO UPDATE SET context = excluded.context, updated_at = excluded.updated_at;
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setString(3, context);
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store user context", e);
        }
    }

    public Optional<String> getUserContext(long guildId, long userId) {
        String sql = "SELECT context FROM user_contexts WHERE guild_id = ? AND user_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.ofNullable(resultSet.getString("context"));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to fetch user context", e);
        }
        return Optional.empty();
    }

    public void clearUserContext(long guildId, long userId) {
        String sql = "DELETE FROM user_contexts WHERE guild_id = ? AND user_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear user context", e);
        }
    }

    public void addKnowledge(long guildId, long addedBy, String text) {
        upsertKnowledge(guildId, addedBy, text, 1.0, "manual");
    }

    public void addLearnedKnowledge(long guildId, long addedBy, String text, double confidence) {
        upsertKnowledge(guildId, addedBy, text, confidence, "learned");
    }

    private void upsertKnowledge(long guildId, long addedBy, String text, double confidence, String source) {
        String findSql = """
                SELECT id, confidence, source
                FROM knowledge_entries
                WHERE guild_id = ? AND lower(trim(text)) = lower(trim(?))
                LIMIT 1;
                """;
        String insertSql = """
                INSERT INTO knowledge_entries (guild_id, text, confidence, source, added_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?);
                """;
        String updateSql = """
                UPDATE knowledge_entries
                SET confidence = ?, source = ?, added_by = ?, created_at = ?
                WHERE id = ? AND guild_id = ?;
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement find = connection.prepareStatement(findSql)) {
            find.setLong(1, guildId);
            find.setString(2, text);
            long now = Instant.now().toEpochMilli();
            try (ResultSet resultSet = find.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    double mergedConfidence = Math.max(resultSet.getDouble("confidence"), confidence);
                    String existingSource = resultSet.getString("source");
                    String mergedSource = "manual".equalsIgnoreCase(existingSource) || "manual".equalsIgnoreCase(source)
                            ? "manual"
                            : "learned";
                    try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                        update.setDouble(1, mergedConfidence);
                        update.setString(2, mergedSource);
                        update.setLong(3, addedBy);
                        update.setLong(4, now);
                        update.setLong(5, id);
                        update.setLong(6, guildId);
                        update.executeUpdate();
                    }
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setLong(1, guildId);
                insert.setString(2, text);
                insert.setDouble(3, confidence);
                insert.setString(4, source);
                insert.setLong(5, addedBy);
                insert.setLong(6, now);
                insert.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add knowledge entry", e);
        }
    }

    public List<KnowledgeEntry> listKnowledge(long guildId, int limit) {
        String sql = """
                SELECT id, text, confidence, source, added_by, created_at
                FROM knowledge_entries
                WHERE guild_id = ?
                ORDER BY id DESC
                LIMIT ?;
                """;
        List<KnowledgeEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new KnowledgeEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("text"),
                            resultSet.getDouble("confidence"),
                            resultSet.getString("source"),
                            resultSet.getLong("added_by"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list knowledge", e);
        }
        return entries;
    }

    public void removeKnowledge(long guildId, long entryId) {
        String sql = "DELETE FROM knowledge_entries WHERE guild_id = ? AND id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, entryId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove knowledge entry", e);
        }
    }

    public List<KnowledgeEntry> listKnowledgeForReview(long guildId, int limit, double maxConfidence) {
        String sql = """
                SELECT id, text, confidence, source, added_by, created_at
                FROM knowledge_entries
                WHERE guild_id = ? AND source = 'learned' AND confidence <= ?
                ORDER BY confidence ASC, id DESC
                LIMIT ?;
                """;
        List<KnowledgeEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setDouble(2, maxConfidence);
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new KnowledgeEntry(
                            resultSet.getLong("id"),
                            resultSet.getString("text"),
                            resultSet.getDouble("confidence"),
                            resultSet.getString("source"),
                            resultSet.getLong("added_by"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list review knowledge", e);
        }
        return entries;
    }

    public boolean isBlacklisted(long guildId, long userId) {
        String sql = "SELECT 1 FROM ai_blacklist WHERE guild_id = ? AND user_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to check blacklist", e);
        }
    }

    public void addBlacklist(long guildId, long userId, long addedBy, String reason) {
        String sql = """
                INSERT INTO ai_blacklist (guild_id, user_id, reason, added_by, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(guild_id, user_id)
                DO UPDATE SET reason = excluded.reason, added_by = excluded.added_by, created_at = excluded.created_at;
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setString(3, reason);
            statement.setLong(4, addedBy);
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add blacklist entry", e);
        }
    }

    public void removeBlacklist(long guildId, long userId) {
        String sql = "DELETE FROM ai_blacklist WHERE guild_id = ? AND user_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to remove blacklist entry", e);
        }
    }

    public void addConversationMessage(long guildId, long userId, String role, String content) {
        String sql = """
                INSERT INTO conversation_messages (guild_id, user_id, role, content, created_at)
                VALUES (?, ?, ?, ?, ?);
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setString(3, role);
            statement.setString(4, content);
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add conversation message", e);
        }
    }

    public List<ConversationMessage> listConversationMessages(long guildId, long userId, int limit) {
        String sql = """
                SELECT role, content, created_at
                FROM conversation_messages
                WHERE guild_id = ? AND user_id = ?
                ORDER BY id DESC
                LIMIT ?;
                """;
        List<ConversationMessage> messages = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(new ConversationMessage(
                            resultSet.getString("role"),
                            resultSet.getString("content"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list conversation messages", e);
        }
        Collections.reverse(messages);
        return messages;
    }

    public void trimConversation(long guildId, long userId, int keepLimit) {
        if (keepLimit <= 0) {
            clearConversation(guildId, userId);
            return;
        }
        String sql = """
                DELETE FROM conversation_messages
                WHERE guild_id = ? AND user_id = ?
                AND id NOT IN (
                    SELECT id FROM conversation_messages
                    WHERE guild_id = ? AND user_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                );
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setLong(3, guildId);
            statement.setLong(4, userId);
            statement.setInt(5, keepLimit);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to trim conversation", e);
        }
    }

    public void clearConversation(long guildId, long userId) {
        String sql = "DELETE FROM conversation_messages WHERE guild_id = ? AND user_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear conversation", e);
        }
    }

    public List<BlacklistEntry> listBlacklist(long guildId, int limit) {
        String sql = """
                SELECT user_id, reason, added_by, created_at
                FROM ai_blacklist
                WHERE guild_id = ?
                ORDER BY created_at DESC
                LIMIT ?;
                """;
        List<BlacklistEntry> entries = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    entries.add(new BlacklistEntry(
                            resultSet.getLong("user_id"),
                            resultSet.getString("reason"),
                            resultSet.getLong("added_by"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list blacklist", e);
        }
        return entries;
    }

    public record KnowledgeEntry(long id, String text, double confidence, String source, long addedBy, long createdAt) {
    }

    public record BlacklistEntry(long userId, String reason, long addedBy, long createdAt) {
    }

    public record ConversationMessage(String role, String content, long createdAt) {
    }

    public int countKnowledge(long guildId) {
        String sql = "SELECT COUNT(*) FROM knowledge_entries WHERE guild_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count knowledge", e);
        }
        return 0;
    }

    public int countBlacklist(long guildId) {
        String sql = "SELECT COUNT(*) FROM ai_blacklist WHERE guild_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count blacklist", e);
        }
        return 0;
    }

    public int countContexts(long guildId) {
        String sql = "SELECT COUNT(*) FROM user_contexts WHERE guild_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count contexts", e);
        }
        return 0;
    }

    public int countConversations(long guildId) {
        String sql = "SELECT COUNT(DISTINCT user_id) FROM conversation_messages WHERE guild_id = ?;";
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count conversations", e);
        }
        return 0;
    }

    public int countLowConfidenceKnowledge(long guildId, double maxConfidence) {
        String sql = """
                SELECT COUNT(*)
                FROM knowledge_entries
                WHERE guild_id = ? AND source = 'learned' AND confidence <= ?;
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setDouble(2, maxConfidence);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count low-confidence knowledge", e);
        }
        return 0;
    }

    public void saveReplyAudit(
            long guildId,
            long userId,
            String model,
            boolean usedUserContext,
            int historyCount,
            List<Long> knowledgeIds,
            String knowledgePreview,
            String promptExcerpt,
            String responseExcerpt,
            long latencyMs
    ) {
        String sql = """
                INSERT INTO ai_reply_audit (
                    guild_id, user_id, model, used_user_context, history_count, knowledge_ids, knowledge_preview,
                    prompt_excerpt, response_excerpt, latency_ms, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setString(3, model);
            statement.setInt(4, usedUserContext ? 1 : 0);
            statement.setInt(5, historyCount);
            statement.setString(6, joinKnowledgeIds(knowledgeIds));
            statement.setString(7, knowledgePreview);
            statement.setString(8, promptExcerpt);
            statement.setString(9, responseExcerpt);
            statement.setLong(10, latencyMs);
            statement.setLong(11, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save reply audit", e);
        }
    }

    public Optional<ReplyAudit> getLatestReplyAudit(long guildId, long userId) {
        String sql = """
                SELECT model, used_user_context, history_count, knowledge_ids, knowledge_preview, prompt_excerpt,
                       response_excerpt, latency_ms, created_at
                FROM ai_reply_audit
                WHERE guild_id = ? AND user_id = ?
                ORDER BY id DESC
                LIMIT 1;
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(new ReplyAudit(
                            resultSet.getString("model"),
                            resultSet.getInt("used_user_context") == 1,
                            resultSet.getInt("history_count"),
                            resultSet.getString("knowledge_ids"),
                            resultSet.getString("knowledge_preview"),
                            resultSet.getString("prompt_excerpt"),
                            resultSet.getString("response_excerpt"),
                            resultSet.getLong("latency_ms"),
                            resultSet.getLong("created_at")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load reply audit", e);
        }
        return Optional.empty();
    }

    public void addFeedback(long guildId, long userId, String rating, String reason) {
        String sql = """
                INSERT INTO response_feedback (guild_id, user_id, rating, reason, created_at)
                VALUES (?, ?, ?, ?, ?);
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, userId);
            statement.setString(3, rating);
            statement.setString(4, reason);
            statement.setLong(5, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store feedback", e);
        }
    }

    public FeedbackStats getFeedbackStats(long guildId, long sinceEpochMs) {
        String sql = """
                SELECT
                    SUM(CASE WHEN rating = 'good' THEN 1 ELSE 0 END) AS good_count,
                    SUM(CASE WHEN rating = 'bad' THEN 1 ELSE 0 END) AS bad_count
                FROM response_feedback
                WHERE guild_id = ? AND created_at >= ?;
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setLong(2, sinceEpochMs);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new FeedbackStats(
                            resultSet.getInt("good_count"),
                            resultSet.getInt("bad_count")
                    );
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get feedback stats", e);
        }
        return new FeedbackStats(0, 0);
    }

    public List<UserMessageCount> listTopChatters(long guildId, int limit) {
        String sql = """
                SELECT user_id, COUNT(*) AS total_messages
                FROM conversation_messages
                WHERE guild_id = ? AND role = 'user'
                GROUP BY user_id
                ORDER BY total_messages DESC
                LIMIT ?;
                """;
        List<UserMessageCount> results = new ArrayList<>();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(new UserMessageCount(
                            resultSet.getLong("user_id"),
                            resultSet.getInt("total_messages")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list top chatters", e);
        }
        return results;
    }

    private String joinKnowledgeIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(id);
        }
        return builder.toString();
    }

    public record ReplyAudit(
            String model,
            boolean usedUserContext,
            int historyCount,
            String knowledgeIds,
            String knowledgePreview,
            String promptExcerpt,
            String responseExcerpt,
            long latencyMs,
            long createdAt
    ) {
    }

    public record FeedbackStats(int goodCount, int badCount) {
        public int total() {
            return goodCount + badCount;
        }
    }

    public record UserMessageCount(long userId, int totalMessages) {
    }
}
