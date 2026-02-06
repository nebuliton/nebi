package io.nebuliton.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    public Discord discord = new Discord();
    public OpenAI openai = new OpenAI();
    public Database database = new Database();
    public UX ux = new UX();

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
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load config file at " + path, e);
        }
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
