package io.nebuliton.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Config {
    public Discord discord = new Discord();
    public OpenAI openai = new OpenAI();
    public Database database = new Database();
    public UX ux = new UX();
    public Presence presence = new Presence();

    public static Config load(Path path) {
        if (!Files.exists(path)) {
            writeDefaultConfig(path);
            throw new IllegalStateException("Config created at " + path + ". Fill it out and restart the bot.");
        }

        LoaderOptions options = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(Config.class, options));
        try (InputStream input = Files.newInputStream(path)) {
            Config config = yaml.load(input);
            if (config == null) {
                config = new Config();
            }
            config.applyDefaults();
            config.validate();

            // Speichere die Config mit allen ergänzten Feldern
            config.save(path);

            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file at " + path, e);
        }
    }

    public void save(Path path) {
        try {
            StringBuilder sb = new StringBuilder();

            // Discord
            sb.append("discord:\n");
            sb.append("  token: \"").append(escapeYaml(discord.token)).append("\"\n");
            sb.append("  guildId: \"").append(discord.guildId != null ? discord.guildId : "").append("\"\n");

            // Nur statische activity zeigen wenn presence nicht aktiv
            if (!presence.enabled) {
                sb.append("  activity: \"").append(escapeYaml(discord.activity)).append("\"\n");
                sb.append("  activityType: \"").append(discord.activityType).append("\"\n");
            }
            sb.append("  insecureSkipHostnameVerification: ").append(discord.insecureSkipHostnameVerification).append("\n");

            // Presence
            sb.append("\n# Wechselnde Status-Nachrichten\n");
            sb.append("presence:\n");
            sb.append("  enabled: ").append(presence.enabled).append("\n");
            sb.append("  intervalSeconds: ").append(presence.intervalSeconds).append("\n");
            sb.append("  activities:\n");
            if (presence.activities != null && !presence.activities.isEmpty()) {
                for (String activity : presence.activities) {
                    sb.append("    - \"").append(escapeYaml(activity)).append("\"\n");
                }
            } else {
                sb.append("    - \"listening:🎧 auf {servers} Servern\"\n");
                sb.append("    - \"watching:👀 {users} User\"\n");
                sb.append("    - \"playing:⏱️ seit {uptime} online\"\n");
                sb.append("    - \"competing:🏓 {ping}ms Ping\"\n");
                sb.append("    - \"playing:💬 @NebiAI zum Chatten\"\n");
            }

            // OpenAI
            sb.append("\nopenai:\n");
            sb.append("  apiKey: \"").append(escapeYaml(openai.apiKey)).append("\"\n");
            sb.append("  baseUrl: \"").append(openai.baseUrl).append("\"\n");
            sb.append("  model: \"").append(openai.model).append("\"\n");
            sb.append("  temperature: ").append(openai.temperature).append("\n");
            sb.append("  maxTokens: ").append(openai.maxTokens).append("\n");
            sb.append("  timeoutSeconds: ").append(openai.timeoutSeconds).append("\n");
            sb.append("  systemPrompt: |\n");
            for (String line : openai.systemPrompt.split("\n")) {
                sb.append("    ").append(line).append("\n");
            }

            // Database
            sb.append("\ndatabase:\n");
            sb.append("  path: \"").append(database.path).append("\"\n");

            // UX
            sb.append("\nux:\n");
            sb.append("  cooldownSeconds: ").append(ux.cooldownSeconds).append("\n");
            sb.append("  cooldownReply: \"").append(escapeYaml(ux.cooldownReply)).append("\"\n");
            sb.append("  errorReply: \"").append(escapeYaml(ux.errorReply)).append("\"\n");
            sb.append("  typingIndicator: ").append(ux.typingIndicator).append("\n");
            sb.append("  maxUserMessageLength: ").append(ux.maxUserMessageLength).append("\n");
            sb.append("  maxContextLength: ").append(ux.maxContextLength).append("\n");
            sb.append("  maxKnowledgeLength: ").append(ux.maxKnowledgeLength).append("\n");
            sb.append("  maxKnowledgeEntries: ").append(ux.maxKnowledgeEntries).append("\n");
            sb.append("  maxConversationMessages: ").append(ux.maxConversationMessages).append("\n");
            sb.append("  maxConversationMessageLength: ").append(ux.maxConversationMessageLength).append("\n");

            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Ignorieren - Config speichern ist nicht kritisch
        }
    }

    private static String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void applyDefaults() {
        if (discord == null) {
            discord = new Discord();
        }
        if (openai == null) {
            openai = new OpenAI();
        }
        if (database == null) {
            database = new Database();
        }
        if (ux == null) {
            ux = new UX();
        }
        if (presence == null) {
            presence = new Presence();
        }
        if (discord.activity == null) {
            discord.activity = "chillt und antwortet auf @mention";
        }
        if (discord.activityType == null || discord.activityType.isBlank()) {
            discord.activityType = "listening";
        }
    }

    private void validate() {
        if (isBlank(discord.token)) {
            throw new IllegalStateException("discord.token is missing in the config file");
        }
        if (isBlank(openai.apiKey)) {
            throw new IllegalStateException("openai.apiKey is missing in the config file");
        }
        if (isBlank(openai.model)) {
            throw new IllegalStateException("openai.model is missing in the config file");
        }
        if (openai.maxTokens <= 0) {
            throw new IllegalStateException("openai.maxTokens must be > 0");
        }
        if (ux.maxContextLength <= 0 || ux.maxKnowledgeLength <= 0 || ux.maxUserMessageLength <= 0) {
            throw new IllegalStateException("UX limits must be > 0");
        }
        if (ux.maxConversationMessages < 0 || ux.maxConversationMessageLength <= 0) {
            throw new IllegalStateException("Conversation memory limits are invalid");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void writeDefaultConfig(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, defaultTemplate(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create default config at " + path, e);
        }
    }

    private static String defaultTemplate() {
        return """
                discord:
                  token: "PUT_DISCORD_BOT_TOKEN_HERE"
                  guildId: ""
                  activity: "chillt und antwortet auf @mention"
                  activityType: "listening"
                  insecureSkipHostnameVerification: false

                # Wechselnde Status-Nachrichten (optional)
                presence:
                  enabled: false
                  intervalSeconds: 30
                  activities:
                    - "listening:🎧 auf {servers} Servern"
                    - "watching:👀 {users} User"
                    - "playing:⏱️ seit {uptime} online"
                    - "competing:🏓 {ping}ms Ping"
                    - "playing:💬 @NebiAI zum Chatten"

                openai:
                  apiKey: "PUT_OPENAI_API_KEY_HERE"
                  baseUrl: "https://api.openai.com/v1"
                  model: "gpt-4o-mini"
                  temperature: 0.7
                  maxTokens: 320
                  timeoutSeconds: 30
                  systemPrompt: |
                    Du bist Nebi, ein sympathischer, lustiger und cooler Discord-Bot.
                    Antworte knapp, locker und hilfsbereit. Verwende Humor, aber bleib freundlich.
                    Nutze Wissen und User-Kontext nur, wenn es wirklich passt und erwaehne den Kontext nicht direkt.
                    Antworte in der Sprache der Nachricht.

                database:
                  path: "data/nebi.db"

                ux:
                  cooldownSeconds: 15
                  cooldownReply: "Ich atme kurz durch. In ein paar Sekunden bin ich wieder da."
                  errorReply: "Uff, mein Kopf raucht gerade. Versuch es gleich nochmal."
                  typingIndicator: true
                  maxUserMessageLength: 1200
                  maxContextLength: 800
                  maxKnowledgeLength: 1500
                  maxKnowledgeEntries: 20
                  maxConversationMessages: 12
                  maxConversationMessageLength: 1000
                """;
    }

    public static class Discord {
        public String token;
        public String guildId;
        public String activity = "chillt und antwortet auf @mention";
        public String activityType = "listening";
        public boolean insecureSkipHostnameVerification = false;
    }

    public static class Presence {
        public boolean enabled = false;
        public int intervalSeconds = 30;
        public List<String> activities = new ArrayList<>();
    }

    public static class OpenAI {
        public String apiKey;
        public String baseUrl = "https://api.openai.com/v1";
        public String model = "gpt-4o-mini";
        public double temperature = 0.7;
        public int maxTokens = 320;
        public int timeoutSeconds = 30;
        public String systemPrompt = """
                Du bist Nebi, ein sympathischer, lustiger und cooler Discord-Bot.
                Antworte knapp, locker und hilfsbereit. Verwende Humor, aber bleib freundlich.
                Nutze Wissen und User-Kontext nur, wenn es wirklich passt und erwaehne den Kontext nicht direkt.
                Antworte in der Sprache der Nachricht.
                """;
    }

    public static class Database {
        public String path = "data/nebi.db";
    }

    public static class UX {
        public int cooldownSeconds = 15;
        public String cooldownReply = "Ich atme kurz durch. In ein paar Sekunden bin ich wieder da.";
        public String errorReply = "Uff, mein Kopf raucht gerade. Versuch es gleich nochmal.";
        public boolean typingIndicator = true;
        public int maxUserMessageLength = 1200;
        public int maxContextLength = 800;
        public int maxKnowledgeLength = 1500;
        public int maxKnowledgeEntries = 20;
        public int maxConversationMessages = 12;
        public int maxConversationMessageLength = 1000;
    }
}
