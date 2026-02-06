package io.nebuliton.ai;

import io.nebuliton.config.Config;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verwaltet wechselnde Discord-Presence mit Placeholder-Support.
 *
 * Verfügbare Placeholder:
 * - {servers} - Anzahl der Server
 * - {users} - Anzahl der User (alle Server)
 * - {uptime} - Bot-Uptime (z.B. "2h 15m")
 * - {ping} - Gateway Ping in ms
 * - {memory} - Verwendeter RAM in MB
 * - {version} - Bot Version
 */
public final class PresenceManager {
    private static final Logger LOG = LoggerFactory.getLogger(PresenceManager.class);
    private static final long START_TIME = System.currentTimeMillis();

    private final JDA jda;
    private final Config config;
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger currentIndex = new AtomicInteger(0);

    public PresenceManager(JDA jda, Config config) {
        this.jda = jda;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "presence-updater");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        Config.Presence presence = config.presence;

        if (!presence.enabled || presence.activities == null || presence.activities.isEmpty()) {
            LOG.debug("Presence-Rotation deaktiviert");
            return;
        }

        int interval = Math.max(10, presence.intervalSeconds);
        LOG.info("Presence-Rotation gestartet ({} Status, alle {}s)", presence.activities.size(), interval);

        // Sofort ersten Status setzen
        updatePresence();

        // Dann im Interval rotieren
        scheduler.scheduleAtFixedRate(this::updatePresence, interval, interval, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void updatePresence() {
        try {
            List<String> activities = config.presence.activities;
            if (activities == null || activities.isEmpty()) {
                return;
            }

            int index = currentIndex.getAndUpdate(i -> (i + 1) % activities.size());
            String template = activities.get(index);
            String parsed = parsePlaceholders(template);

            Activity activity = parseActivity(parsed);
            jda.getPresence().setActivity(activity);

        } catch (Exception e) {
            LOG.warn("Fehler beim Presence-Update: {}", e.getMessage());
        }
    }

    private String parsePlaceholders(String template) {
        String result = template;

        // {servers} - Anzahl Server
        result = result.replace("{servers}", String.valueOf(jda.getGuilds().size()));

        // {users} - Anzahl User
        long userCount = jda.getGuilds().stream()
                .mapToLong(g -> g.getMemberCount())
                .sum();
        result = result.replace("{users}", String.valueOf(userCount));

        // {uptime} - Uptime formatiert
        result = result.replace("{uptime}", formatUptime());

        // {ping} - Gateway Ping
        result = result.replace("{ping}", String.valueOf(jda.getGatewayPing()));

        // {memory} - RAM Usage in MB
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        result = result.replace("{memory}", usedMb + "MB");

        // {version} - Bot Version
        result = result.replace("{version}", "1.0");

        // {date} - Aktuelles Datum
        result = result.replace("{date}", java.time.LocalDate.now().toString());

        // {time} - Aktuelle Uhrzeit
        result = result.replace("{time}", java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HH:mm")));

        return result;
    }

    private String formatUptime() {
        long uptimeMs = System.currentTimeMillis() - START_TIME;
        Duration duration = Duration.ofMillis(uptimeMs);

        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        long minutes = duration.toMinutes() % 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        } else if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }

    private Activity parseActivity(String text) {
        // Format: "type:text" oder nur "text" (default = playing)
        String lowerText = text.toLowerCase();

        if (lowerText.startsWith("playing:")) {
            return Activity.playing(text.substring(8).trim());
        } else if (lowerText.startsWith("listening:")) {
            return Activity.listening(text.substring(10).trim());
        } else if (lowerText.startsWith("watching:")) {
            return Activity.watching(text.substring(9).trim());
        } else if (lowerText.startsWith("competing:")) {
            return Activity.competing(text.substring(10).trim());
        } else if (lowerText.startsWith("streaming:")) {
            // Streaming braucht URL, nehmen wir Twitch als Fallback
            return Activity.streaming(text.substring(10).trim(), "https://twitch.tv/nebuliton");
        }

        // Default: Nutze den konfigurierten activityType
        String type = config.discord.activityType;
        if (type == null) type = "playing";

        return switch (type.toLowerCase()) {
            case "listening" -> Activity.listening(text);
            case "watching" -> Activity.watching(text);
            case "competing" -> Activity.competing(text);
            default -> Activity.playing(text);
        };
    }
}
