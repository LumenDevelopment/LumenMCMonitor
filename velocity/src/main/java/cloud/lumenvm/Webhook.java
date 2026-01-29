package cloud.lumenvm;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.velocitypowered.api.proxy.ProxyServer;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

public class Webhook {

    public final ConfigLoader confLoader;

    // Http client
    public static HttpClient httpClient;

    // Server
    private static ProxyServer server;
    private static Logger logger;
    private static Monitor plugin;

    private Handler commonHandler;
    private boolean handlersAttached = false;
    private final Deque<Integer> recentHashes = new ArrayDeque<>();
    private static final int DEDUPE_WINDOW = 256;

    private static int taskId = -1;

    // Gson
    private static final Gson gson = new Gson();

    // System streams
    private PrintStream originalOut;
    private PrintStream originalErr;

    // Queue
    public final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    // JUL
    private final java.util.logging.Formatter julFormatter = new java.util.logging.Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };

    public Webhook(String name) {
        this.confLoader = new ConfigLoader(name);

        if (confLoader.failedToLoadConfig) {
            logger.error("Webhook URL is NOT set. Pleas adjust pterodactyl server configuration/config.yml accordingly and RESTART the server :)");
            plugin.eventManager.unregisterListeners(plugin);
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
    }

    // System streams
    private void attachSystemStreamsTEE() {
        if (originalOut == null) originalOut = System.out;
        if (originalErr == null) originalErr = System.err;
        System.setOut(new PrintStream(originalOut) {
            @Override public void println(String x) {
                enqueueIfAllowed("[" + plugin.prettyTime() + "] [INFO] [System.out] " + x);
                super.println(x);
            }
        });
        System.setErr(new PrintStream(originalErr) {
            @Override public void println(String x) {
                enqueueIfAllowed("[" + plugin.prettyTime() + "] [INFO] [System.err] " + x);
                super.println(x);
            }
        });
        if (plugin.debug) logger.info("Debug: System streams on.");
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
            java.util.logging.Logger bukkit = Bukkit.getLogger();
            bukkit.addHandler(commonHandler);
            bukkit.setLevel(Level.ALL);
            bukkit.setUseParentHandlers(true);
            for (Handler h : bukkit.getHandlers()) {
                try { h.setLevel(Level.ALL); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.warn("Can't connect to bukkit logger.", e);
        }

        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.addHandler(commonHandler);
            root.setLevel(Level.ALL);
            root.setUseParentHandlers(true);
            for (Handler h : root.getHandlers()) {
                try { h.setLevel(Level.ALL); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            logger.warn("Can't connect to root JUL logger", e);
        }

        attachNamedLoggers();

        handlersAttached = true;
        if (plugin.debug) logger.info("Debug: Handlers connected (root/Bukkit level = ALL).");
    }

    private void attachNamedLoggers() {
        String[] names = { "Minecraft", "org.bukkit", "net.minecraft" };
        for (String name : names) {
            try {
                java.util.logging.Logger l = java.util.logging.Logger.getLogger(name);
                l.addHandler(commonHandler);
                l.setUseParentHandlers(true);
                l.setLevel(Level.ALL);
                for (Handler h : l.getHandlers()) {
                    try { h.setLevel(Level.ALL); } catch (Exception ignored) {}
                }
                if (plugin.debug) logger.info("Debug: Handler connected to logger  '" + name + "'");
            } catch (Exception e) {
                if (plugin.debug) logger.warn("Debug: Couldn't connect to logger  '" + name + "': " + e.getMessage());
            }
        }
    }

    private void detachHandlers() {
        if (!handlersAttached) return;
        try { Bukkit.getLogger().removeHandler(commonHandler); } catch (Exception ignored) {}
        try { java.util.logging.Logger.getLogger("").removeHandler(commonHandler); } catch (Exception ignored) {}
        handlersAttached = false;
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

    // Filter
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

    // Format logs
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

    // Sending
    public void enqueueIfAllowed(String content) {
        // Remove mentions
        if (confLoader.removeMentions) {
            content = content.replace("@everyone", "＠everyone").replace("@here", "＠here");
        }

        // Add to queue
        for (String chunk : splitMessage(filterMessage(content), confLoader.maxMessageLength)) {
            queue.offer(chunk);
        }
    }

    static void sendContent(String content, URI webhookUri) {
        try {
            WebhookContentPayload payload = new WebhookContentPayload(content);
            payload.username = plugin.configMap.get("username").toString();
            if (payload.username == null || payload.username.isEmpty()) payload.username = "LumenMC";
            payload.avatar_url = plugin.configMap.get("avatar_url").toString();
            if (payload.avatar_url == null || payload.username.isEmpty()) payload.avatar_url = "https://cdn.lumenvm.cloud/logo.png";
            String json = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (plugin.debug) {
                logger.info("Debug: Webhook HTTP " + status +
                        (response.body() != null ? (" body: " + response.body()) : ""));
            }

            if (status < 200 || status >= 300) {
                logger.warn("Discord webhook returned HTTP " + status + ": " + response.body());
            }
        } catch (Exception e) {
            logger.warn("Error when sending to webhook" + e);
        }
    }

    public void sendJson(String json) {
        try {
            URI webhookUri;
            try {
                webhookUri = URI.create(confLoader.url);
            } catch (Exception e) {
                logger.error("Invalid url when sending embed");
                return;
            }

            if (confLoader.debug) {
                logger.info("Debug: Sending embed to Discord \n" + json);
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (confLoader.debug) {
                logger.info("Debug: Webhook embed sent: HTTP " + response.statusCode());
                logger.info("Debug: Response body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            logger.warn("Error when sending embed to Discord: " + e);
        }
    }

    // Help methods

    public static int msToTicks(int ms) {
        int ticks = (int) Math.ceil(ms / 50.0);
        return Math.max(1, ticks);
    }

    public static void startTask() {
        int ticks = Webhook.msToTicks(Integer.parseInt(plugin.configMap.get("batch_interval_ms").toString()));


    }

    public static void endTask() {

        taskId = -1;
    }

    public static void setServer(ProxyServer server) {
        Webhook.server = server;
    }

    public static void setLogger(Logger logger) {
        Webhook.logger = logger;
    }

    public static void setPlugin(Monitor plugin) {
        Webhook.plugin = plugin;
    }

    // Content-only payload
    static class WebhookContentPayload {
        @SerializedName("username")String username;
        @SerializedName("avatar_url")String avatar_url;
        @SerializedName("content")String content;
        WebhookContentPayload(String content) { this.content = content; }
    }
}
