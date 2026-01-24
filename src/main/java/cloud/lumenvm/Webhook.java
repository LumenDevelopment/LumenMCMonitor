package cloud.lumenvm;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class Webhook {

    private static Monitor plugin;
    public final ConfigLoader confLoader;
    public static HttpClient httpClient;
    static final AtomicBoolean drainLock = new AtomicBoolean(false);
    private static final Gson gson = new Gson();
    private static int taskId = -1;


    public int watchdogHeartbeatTaskId = -1;
    public int watchdogCheckerTaskId = -1;

    private Handler commonHandler;
    private boolean handlersAttached = false;

    private PrintStream originalOut;
    private PrintStream originalErr;

    private final Deque<Integer> recentHashes = new ArrayDeque<>();
    private static final int DEDUPE_WINDOW = 256;

    public final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    private final java.util.logging.Formatter julFormatter = new java.util.logging.Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };

    Webhook(String name) {
        this.confLoader = new ConfigLoader(plugin, name);

        if (confLoader.failedToLoadConfig) {
            plugin.getLogger().severe("Webhook URL is NOT set. Pleas adjust pterodactyl server configuration/config.yml accordingly and RESTART the server :)");
            plugin.getServer().getPluginManager().disablePlugin(plugin);
            return;
        }

        if (confLoader.captureSystemStreams) attachSystemStreamsTEE();

        commonHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!confLoader.enableLogs) return;
                if (record == null || !isLoggable(record)) return;
                if (record.getLevel().intValue() < confLoader.minLevel.intValue()) return;

                String msg = formatRecord(record);

                int h = Objects.hash(record.getMillis(), record.getLevel(), record.getLoggerName(), msg);
                synchronized (recentHashes) {
                    if (recentHashes.contains(h)) return;
                    recentHashes.addLast(h);
                    if (recentHashes.size() > DEDUPE_WINDOW) recentHashes.removeFirst();
                }

                for (String chunk : Webhook.splitMessage(filterMessage(msg), confLoader.maxMessageLength)) {
                    queue.offer(chunk);
                }
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        commonHandler.setLevel(Level.ALL);

        attachHandlers();

        if (plugin.debug) plugin.getLogger().info("Debug: Handlers connected in onEnable(); queueing logs");


        if (confLoader.watchdogEnabled) {
            // Heartbeat
            watchdogHeartbeatTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> confLoader.lastTickNanos = System.nanoTime(), 0L, 1L).getTaskId();

            // Async control
            int checkTicks = msToTicks((int) confLoader.watchdogCheckIntervalMs);
            watchdogCheckerTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
                long now = System.nanoTime();
                long elapsedMs = (now - confLoader.lastTickNanos) / 1_000_000L;

                if (elapsedMs >= confLoader.watchdogTimeoutMs) {
                    if (!confLoader.watchdogAlerted) {
                        confLoader.watchdogAlerted = true;
                        enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + PlaceholderAPI.setPlaceholders(null, plugin.langLoader.get("watchdog_alert_message")));
                        if (plugin.debug) plugin.getLogger().warning("WATCHDOG alert: main thread stalled for " + elapsedMs + " ms");
                    }
                } else {
                    // Restored
                    if (confLoader.watchdogAlerted) {
                        confLoader.watchdogAlerted = false;
                        enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + PlaceholderAPI.setPlaceholders(null, plugin.langLoader.get("watchdog_recovery_message")));
                    }
                }
            }, checkTicks, checkTicks).getTaskId();

            if (plugin.debug) plugin.getLogger().info("Debug: Watchdog launched (timeout " + confLoader.watchdogTimeoutMs + " ms, control every " + confLoader.watchdogCheckIntervalMs + " ms).");
        }
    }

    private void attachSystemStreamsTEE() {
        if (originalOut == null) originalOut = System.out;
        if (originalErr == null) originalErr = System.err;

        System.setOut(new PrintStream(originalOut) {
            @Override public void println(String x) {
                enqueueIfAllowed("[" + Instant.now() + "] [INFO] [System.out] " + x);
                super.println(x);
            }
        });
        System.setErr(new PrintStream(originalErr) {
            @Override public void println(String x) {
                enqueueIfAllowed("[" + Instant.now() + "] [INFO] [System.err] " + x);
                super.println(x);
            }
        });
        if (plugin.debug) plugin.getLogger().info("Debug: System streams on.");
    }

    private String formatRecord(LogRecord record) {
        StringBuilder sb = new StringBuilder();

        sb.append("[")
                .append(Instant.ofEpochMilli(record.getMillis()))
                .append("] [")
                .append(record.getLevel().getName())
                .append("] ");

        if (record.getLoggerName() != null && !record.getLoggerName().isEmpty()) {
            sb.append("[").append(record.getLoggerName()).append("] ");
        }

        String formattedMsg;
        try { formattedMsg = julFormatter.format(record); }
        catch (Exception ex) { formattedMsg = record.getMessage(); }

        if (formattedMsg != null) sb.append(formattedMsg);

        if (confLoader.includeStackTraces && record.getThrown() != null) {
            sb.append("\n").append(stackTraceToString(record.getThrown()));
        }

        String result = sb.toString();
        if (confLoader.removeMentions) {
            result = result.replace("@everyone", "＠everyone").replace("@here", "＠here");
        }
        return result;
    }

    private String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    public void enqueueIfAllowed(String content) {
        if (confLoader.removeMentions) {
            content = content.replace("@everyone", "＠everyone").replace("@here", "＠here");
        }
        for (String chunk : splitMessage(filterMessage(content), confLoader.maxMessageLength)) {
            queue.offer(chunk);
        }
    }

    static void sendContent(String content, URI webhookUri) {
        try {
            WebhookContentPayload payload = new WebhookContentPayload(content);
            payload.username = plugin.getConfig().getString("username", "LumenMC");
            payload.avatar_url = plugin.getConfig().getString("avatar_url", "https://cdn.lumenvm.cloud/logo.png");
            String json = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (plugin.debug) {
                plugin.getLogger().info("Debug: Webhook HTTP " + status +
                        (response.body() != null ? (" body: " + response.body()) : ""));
            }

            if (status < 200 || status >= 300) {
                plugin.getLogger().warning("Discord webhook returned HTTP " + status + ": " + response.body());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error when sending to webhook", e);
        }
    }

    public void sendJson(String json) {
        try {
            URI webhookUri;
            try {
                webhookUri = URI.create(confLoader.url);
            } catch (Exception e) {
                plugin.getLogger().severe("Invalid url when sending embed");
                return;
            }

            if (confLoader.debug) {
                plugin.getLogger().info("Debug: Sending embed to Discord \n" + json);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (confLoader.debug) {
                plugin.getLogger().info("Debug: Webhook embed sent: HTTP " + response.statusCode());
                plugin.getLogger().info("Debug: Response body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            plugin.getLogger().warning("Error when sending embed to Discord: " + e);
        }
    }

    public String filterMessage(String msg) {
        if (msg == null || confLoader.ignorePatterns == null) return null;
        for (String pattern : confLoader.ignorePatterns) {
            if (pattern == null || pattern.isBlank()) continue;
            try {
                if (msg.contains(pattern)) {
                    msg = msg.replace(pattern, "\\*\\*\\*\\*\\*");
                }
            }
            catch (Exception ignored) {}
        }
        return msg;
    }

    public static void drainAndSend() {
        // Anti-double-drain lock
        if (!drainLock.compareAndSet(false, true)) {
            return;
        }
        for (Webhook webhook : plugin.webhooks) {
            try {
                List<String> batch = new ArrayList<>(webhook.confLoader.maxBatchSize);
                while (batch.size() < webhook.confLoader.maxBatchSize) {
                    String item = webhook.queue.poll();
                    if (item == null) break;
                    batch.add(item);
                }
                if (batch.isEmpty()) return;

                String combined = String.join("\n", batch);
                List<String> payloads =  splitMessage(combined, webhook.confLoader.maxMessageLength);

                if (plugin.debug)
                    plugin.getLogger().info("Debug: Sending batch: " + batch.size() + " messages, payloads: " + payloads.size());

                for (String content : payloads) {
                    URI webhookUri = new URI(webhook.confLoader.url);
                    sendContent(content, webhookUri);
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (URISyntaxException e) {
                plugin.getLogger().severe("Invalid url when trying to send: " + e);
            } finally {
                drainLock.set(false);
            }
        }
    }

    public static List<String> splitMessage(String msg, int maxLen) {
        List<String> parts = new ArrayList<>();
        if (msg == null) return parts;
        if (msg.length() <= maxLen) { parts.add(msg); return parts; }
        int i = 0;
        while (i < msg.length()) {
            int end = Math.min(i + maxLen, msg.length());
            parts.add(msg.substring(i, end));
            i = end;
        }
        return parts;
    }

    public static void setPlugin(Monitor plugin) {
        Webhook.plugin = plugin;
    }

    public static int msToTicks(int ms) {
        int ticks = (int) Math.ceil(ms / 50.0);
        return Math.max(1, ticks);
    }

    private void detachSystemStreamsTEE() {
        if (originalOut != null) {
            System.setOut(originalOut);
            originalOut = null;
        }
        if (originalErr != null) {
            System.setErr(originalErr);
            originalErr = null;
        }
    }

    // Handlers

    private void attachHandlers() {
        if (handlersAttached) return;

        try {
            Logger bukkit = Bukkit.getLogger();
            bukkit.addHandler(commonHandler);
            bukkit.setLevel(Level.ALL);
            bukkit.setUseParentHandlers(true);
            for (Handler h : bukkit.getHandlers()) {
                try { h.setLevel(Level.ALL); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Can't connect to bukkit logger.", e);
        }

        try {
            Logger root = Logger.getLogger("");
            root.addHandler(commonHandler);
            root.setLevel(Level.ALL);
            root.setUseParentHandlers(true);
            for (Handler h : root.getHandlers()) {
                try { h.setLevel(Level.ALL); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Can't connect to root JUL logger", e);
        }

        attachNamedLoggers();

        handlersAttached = true;
        if (plugin.debug) plugin.getLogger().info("Debug: Handlers connected (root/Bukkit level = ALL).");
    }

    private void attachNamedLoggers() {
        String[] names = { "Minecraft", "org.bukkit", "net.minecraft" };
        for (String name : names) {
            try {
                Logger l = Logger.getLogger(name);
                l.addHandler(commonHandler);
                l.setUseParentHandlers(true);
                l.setLevel(Level.ALL);
                for (Handler h : l.getHandlers()) {
                    try { h.setLevel(Level.ALL); } catch (Exception ignored) {}
                }
                if (plugin.debug) plugin.getLogger().info("Debug: Handler connected to logger  '" + name + "'");
            } catch (Exception e) {
                if (plugin.debug) plugin.getLogger().warning("Debug: Couldn't connect to logger  '" + name + "': " + e.getMessage());
            }
        }
    }

    private void detachHandlers() {
        if (!handlersAttached) return;
        try { Bukkit.getLogger().removeHandler(commonHandler); } catch (Exception ignored) {}
        try { Logger.getLogger("").removeHandler(commonHandler); } catch (Exception ignored) {}
        handlersAttached = false;
    }

    public static void startTask() {
        int ticks = Webhook.msToTicks(plugin.getConfig().getInt("batch_interval_ms"));

        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, Webhook::drainAndSend, ticks, ticks)
                .getTaskId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Webhook::drainAndSend);
    }

    public static void endTask() {
        Bukkit.getScheduler().cancelTask(taskId);
        if (plugin.debug) plugin.getLogger().info("Canceled task " + taskId);
        taskId = -1;
    }

    public void removeAllWebhooks() {
        detachSystemStreamsTEE();
        detachHandlers();

        if (watchdogHeartbeatTaskId != -1) {
            Bukkit.getScheduler().cancelTask(watchdogHeartbeatTaskId);
            watchdogHeartbeatTaskId = -1;
        }
        if (watchdogCheckerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(watchdogCheckerTaskId);
            watchdogCheckerTaskId = -1;
        }
    }

    public static void addWebhook(String name, String url) {
        InputStreamReader stream = new InputStreamReader(Objects.requireNonNull(plugin.getResource("webhookTemplate.yml")));
        YamlConfiguration template = YamlConfiguration.loadConfiguration(stream);

        if (plugin.getConfig().getKeys(true).contains(name)) {
            return;
        }

        for (String key : template.getKeys(true)) {
            Object value = template.get(key);
            plugin.getConfig().set("webhooks." + name.toLowerCase().replace(" ", "") + "." + key, value);
        }

        plugin.getConfig().set("webhooks." + name.toLowerCase().replace(" ", "") + ".url", url);
    }

    public static void removeWebhook(String name) {
        plugin.getConfig().set("webhooks." + name, null);
    }

    // Content-only payload
    static class WebhookContentPayload {
        @SerializedName("username")String username;
        @SerializedName("avatar_url")String avatar_url;
        @SerializedName("content")String content;
        WebhookContentPayload(String content) { this.content = content; }
    }
}
