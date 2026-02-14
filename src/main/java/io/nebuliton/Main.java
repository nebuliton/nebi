package io.nebuliton;

import com.neovisionaries.ws.client.WebSocketFactory;
import io.nebuliton.ai.AIManager;
import io.nebuliton.ai.Commands;
import io.nebuliton.ai.ContextStore;
import io.nebuliton.ai.OpenAIClient;
import io.nebuliton.ai.PingListener;
import io.nebuliton.ai.PresenceManager;
import io.nebuliton.ai.RateLimiter;
import io.nebuliton.config.Config;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Main {
    private static final boolean COLOR = !Boolean.getBoolean("nebi.nocolor");
    private static final String RESET = "\u001B[0m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BOLD = "\u001B[1m";
    private static final String BOT_NAME = "Nebi";
    private static boolean warningFilterInstalled = false;

    static {
        installNativeWarningFilter();
    }

    public static void main(String[] args) throws Exception {
        if (restartWithNativeAccessIfNeeded(args)) {
            return;
        }
        Path configPath = args.length > 0 ? Path.of(args[0]) : Path.of("config", "config.yml");
        printBanner();
        printInfo("Config", configPath.toAbsolutePath().toString());
        Config config;
        try {
            config = Config.load(configPath);
        } catch (IllegalStateException e) {
            System.err.println(color(RED, e.getMessage()));
            return;
        }
        printInfo("Database", Path.of(config.database.path).toAbsolutePath().toString());

        // Activity/Presence Info
        if (config.presence.enabled && config.presence.activities != null && !config.presence.activities.isEmpty()) {
            printInfo("Presence", config.presence.activities.size() + " Status (rotiert alle " + config.presence.intervalSeconds + "s)");
        } else if (config.discord.activity != null && !config.discord.activity.isBlank()) {
            String activityType = config.discord.activityType == null || config.discord.activityType.isBlank()
                    ? "playing"
                    : config.discord.activityType;
            printInfo("Activity", config.discord.activity + " (" + activityType + ")");
        }

        printInfo("Status", "Starting Discord connection...");
        Database database = new Database(config.database.path);
        ContextStore contextStore = new ContextStore(database);
        OpenAIClient openAIClient = new OpenAIClient(config.openai);
        AIManager aiManager = new AIManager(openAIClient, contextStore, config);
        RateLimiter rateLimiter = new RateLimiter(Duration.ofSeconds(config.ux.cooldownSeconds));

        JDABuilder builder = JDABuilder.createDefault(config.discord.token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .disableCache(CacheFlag.ACTIVITY, CacheFlag.CLIENT_STATUS, CacheFlag.VOICE_STATE)
                .addEventListeners(
                        new Commands(contextStore, config, aiManager),
                        new PingListener(aiManager, contextStore, config, rateLimiter)
                );

        // Nur statische Activity setzen wenn Presence-Rotation NICHT aktiv ist
        if (!config.presence.enabled) {
            Activity activity = buildActivity(config.discord);
            if (activity != null) {
                builder.setActivity(activity);
            }
        }

        if (config.discord.insecureSkipHostnameVerification) {
            WebSocketFactory factory = new WebSocketFactory();
            factory.setVerifyHostname(false);
            builder.setWebsocketFactory(factory);
            printInfo("Warning", color(YELLOW, "Hostname verification disabled (unsafe)"));
        }

        JDA jda = builder.build();
        jda.awaitReady();
        printInfo("Logged in", jda.getSelfUser().getAsTag());
        if (config.discord.guildId != null && !config.discord.guildId.isBlank()) {
            printInfo("Commands", "Registering for guild " + config.discord.guildId + "...");
        } else {
            printInfo("Commands", "Registering globally...");
        }
        Commands.registerCommands(jda, config);

        // Starte Presence-Rotation wenn aktiviert
        PresenceManager presenceManager = new PresenceManager(jda, config);
        presenceManager.start();

        System.out.println(color(GREEN, "Ready") + " Mention me with @ to chat.");
    }

    private static Activity buildActivity(Config.Discord discord) {
        if (discord.activity == null || discord.activity.isBlank()) {
            return null;
        }
        String type = discord.activityType == null ? "" : discord.activityType.trim().toLowerCase();
        return switch (type) {
            case "listening" -> Activity.listening(discord.activity);
            case "watching" -> Activity.watching(discord.activity);
            case "competing" -> Activity.competing(discord.activity);
            default -> Activity.playing(discord.activity);
        };
    }

    private static void printBanner() {
        System.out.println(color(BOLD + CYAN, " _   _  _____  ____ ___ "));
        System.out.println(color(BOLD + CYAN, "| \\ | || ____|| __ )_ _|"));
        System.out.println(color(BOLD + CYAN, "|  \\| ||  _|  |  _ \\| | "));
        System.out.println(color(BOLD + CYAN, "| |\\  || |___ | |_) | | "));
        System.out.println(color(BOLD + CYAN, "|_| \\_||_____||____/___|"));
        System.out.println(color(BOLD + CYAN, "          " + BOT_NAME));
    }

    private static void printInfo(String label, String value) {
        System.out.println(color(DIM, String.format("%-8s", label + ":")) + " " + value);
    }

    private static void installNativeWarningFilter() {
        if (warningFilterInstalled) {
            return;
        }
        warningFilterInstalled = true;
        PrintStream original = System.err;
        System.setErr(new PrintStream(new OutputStream() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public void write(int b) {
                if (b == '\r') {
                    return;
                }
                if (b == '\n') {
                    flushBuffer();
                    return;
                }
                buffer.append((char) b);
            }

            @Override
            public void flush() {
                flushBuffer();
            }

            private void flushBuffer() {
                if (buffer.isEmpty()) {
                    return;
                }
                String line = buffer.toString();
                buffer.setLength(0);
                if (shouldSuppress(line)) {
                    return;
                }
                original.println(line);
            }
        }, true));
    }

    private static boolean shouldSuppress(String line) {
        return line.contains("restricted method in java.lang.System")
                || line.contains("System::load has been called by org.sqlite.SQLiteJDBCLoader")
                || line.contains("enable-native-access=ALL-UNNAMED")
                || line.contains("Restricted methods will be blocked in a future release");
    }

    private static String color(String code, String text) {
        return COLOR ? code + text + RESET : text;
    }

    private static boolean restartWithNativeAccessIfNeeded(String[] args) {
        if (Boolean.getBoolean("nebi.native.access")) {
            return false;
        }
        if (Boolean.getBoolean("nebi.noNativeRestart")) {
            return false;
        }
        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        for (String arg : inputArgs) {
            if (arg.startsWith("--enable-native-access")) {
                return false;
            }
        }
        Path jarPath = resolveJarPath();
        if (jarPath == null) {
            return false;
        }

        String javaBin = resolveJavaBin();
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("--enable-native-access=ALL-UNNAMED");
        command.addAll(inputArgs);
        if (inputArgs.stream().noneMatch(arg -> arg.startsWith("-Dnebi.native.access="))) {
            command.add("-Dnebi.native.access=true");
        }
        command.add("-jar");
        command.add(jarPath.toString());
        command.addAll(Arrays.asList(args));

        System.out.println(color(DIM, "Restarting with native access to silence SQLite warnings..."));
        try {
            new ProcessBuilder(command).inheritIO().start().waitFor();
        } catch (Exception e) {
            System.err.println(color(RED, "Restart failed: " + e.getMessage()));
        }
        return true;
    }

    private static Path resolveJarPath() {
        try {
            Path path = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (path.toString().toLowerCase().endsWith(".jar")) {
                return path;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String resolveJavaBin() {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path javaPath = Path.of(javaHome, "bin", windows ? "java.exe" : "java");
        return javaPath.toString();
    }
}
