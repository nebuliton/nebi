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
        String sql = """
                INSERT INTO knowledge_entries (guild_id, text, added_by, created_at)
                VALUES (?, ?, ?, ?);
                """;
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, guildId);
            statement.setString(2, text);
            statement.setLong(3, addedBy);
            statement.setLong(4, Instant.now().toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to add knowledge entry", e);
        }
    }

    public List<KnowledgeEntry> listKnowledge(long guildId, int limit) {
        String sql = """
                SELECT id, text, added_by, created_at
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

    public record KnowledgeEntry(long id, String text, long addedBy, long createdAt) {
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
}
