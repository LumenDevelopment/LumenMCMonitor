package cz.snvk.lumenmc.discordwebhooks;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class LumenMCDiscordWebhooks extends JavaPlugin implements Listener {


    // Config
    private HttpClient httpClient;
    private URI webhookUri;
    private Level minLevel;
    private List<String> ignorePatterns;
    private boolean includeStackTraces;
    private int batchIntervalMs;
    private int maxBatchSize;
    private int maxMessageLength;
    private boolean removeMentions;
    private boolean debug;
    private boolean captureSystemStreams;

    private boolean sendChat;
    private boolean sendPlayerCommands;
    private boolean sendConsoleCommands;
    private boolean sendUuidPrelogin;
    private boolean sendJoinQuit;
    private boolean sendDeaths;
    private boolean sendGamemodeChanges;
    private boolean sendServerLoad;

    // Embeds
    private boolean embedsStartStopEnabled;
    private String embedStartTitle = "\uD83D\uDFE6 Server start";
    private String embedStartDescription = "✅ Startup complete.";
    private int embedStartColor = 3447003;
    private String embedStopTitle = "\uD83D\uDFE5 Server stop";
    private String embedStopDescription = "\uD83D\uDED1 Server is shutting down.";
    private int embedStopColor = 15158332;

    // Watchdog
    private volatile long lastTickNanos = System.nanoTime();
    private int watchdogHeartbeatTaskId = -1;
    private int watchdogCheckerTaskId = -1;
    private boolean watchdogEnabled;
    private long watchdogTimeoutMs;
    private long watchdogCheckIntervalMs;
    private String watchdogAlertMessage = "⚠️ Server stopped ticking.";
    private String watchdogRecoveryMessage = "✅ Server has recovered (ticking restored).";
    private volatile boolean watchdogAlerted = false;

    // Pterodactyl variables
    private static final String WEBHOOK_URL = System.getenv("WEBHOOK_URL");
    private static final String WEBHOOK_AVATAR = System.getenv("WEBHOOK_AVATAR");
    private static final String WEBHOOK_NAME = System.getenv("WEBHOOK_NAME");
    private static final String WEBHOOK_IMAGE = System.getenv("WEBHOOK_IMAGE");
    private static final String TZ = System.getenv("TZ");
    private static final String SERVER_MEMORY = System.getenv("SERVER_MEMORY");
    private static final String SERVER_IP = System.getenv("SERVER_IP");
    private static final String SERVER_PORT = System.getenv("SERVER_PORT");
    private static final String P_SERVER_LOCATION = System.getenv("P_SERVER_LOCATION");
    private static final String P_SERVER_UUID = System.getenv("P_SERVER_UUID");


    // Queue
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    // Scheduler
    private int taskId = -1;

    // Handlers
    private Handler commonHandler;
    private boolean handlersAttached = false;

    // ---- De-dupe a anti-double-drain ----
    private final Deque<Integer> recentHashes = new ArrayDeque<>();
    private static final int DEDUPE_WINDOW = 256;
    private final AtomicBoolean drainLock = new AtomicBoolean(false);

    // System streams
    private PrintStream originalOut;
    private PrintStream originalErr;

    // JSON
    private final Gson gson = new Gson();

    // JUL
    private final java.util.logging.Formatter julFormatter = new java.util.logging.Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };

    @Override
    public void onLoad() {
        saveDefaultConfig();
        reloadConfig();
        readConfig();

        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

        commonHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null || !isLoggable(record)) return;
                if (record.getLevel().intValue() < minLevel.intValue()) return;

                String msg = formatRecord(record);
                if (shouldIgnore(msg)) return;

                int h = Objects.hash(record.getMillis(), record.getLevel(), record.getLoggerName(), msg);
                synchronized (recentHashes) {
                    if (recentHashes.contains(h)) return;
                    recentHashes.addLast(h);
                    if (recentHashes.size() > DEDUPE_WINDOW) recentHashes.removeFirst();
                }

                for (String chunk : splitMessage(msg, maxMessageLength)) {
                    queue.offer(chunk);
                }
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        commonHandler.setLevel(Level.ALL);

        attachHandlers();

        if (debug) getLogger().info("LumenMC: Handlers connected in onLoad(); queueing logs");
    }

    @Override
    public void onEnable() {
        if (webhookUri == null) {
            getLogger().severe("Webhook URL is NOT set. Pleas adjust pterodactyl server configuration accordingly :)");
            return;
        }

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        int ticks = msToTicks(batchIntervalMs);
        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(this, this::drainAndSend, ticks, ticks)
                .getTaskId();

        // Delete drain
        Bukkit.getScheduler().runTaskAsynchronously(this, this::drainAndSend);

        if (captureSystemStreams) attachSystemStreamsTEE();

        if (debug) getLogger().info("LumenMC: sending activated (interval " + batchIntervalMs + " ms / " + ticks + " ticks).");


        if (watchdogEnabled) {
            // Heartbeat
            watchdogHeartbeatTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> {
                lastTickNanos = System.nanoTime();
            }, 0L, 1L).getTaskId(); // 1 tick = 50 ms

            // Async control
            int checkTicks = msToTicks((int) watchdogCheckIntervalMs);
            watchdogCheckerTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                long now = System.nanoTime();
                long elapsedMs = (now - lastTickNanos) / 1_000_000L;

                if (elapsedMs >= watchdogTimeoutMs) {
                    if (!watchdogAlerted) {
                        watchdogAlerted = true;
                        enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + watchdogAlertMessage);
                        if (debug) getLogger().warning("WATCHDOG alert: main thread stalled for " + elapsedMs + " ms");
                    }
                } else {
                    // Restored
                    if (watchdogAlerted) {
                        watchdogAlerted = false;
                        enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + watchdogRecoveryMessage);
                        if (debug) getLogger().info("WATCHDOG recovery: main thread delay " + elapsedMs + " ms");
                    }
                }
            }, checkTicks, checkTicks).getTaskId();

            if (debug) getLogger().info("Watchdog launched (timeout " + watchdogTimeoutMs + " ms, control every " + watchdogCheckIntervalMs + " ms).");
        }

    }

    @Override
    public void onDisable() {
        if (webhookUri != null && embedsStartStopEnabled) {
            sendEmbed(embedStopTitle, embedStopDescription, embedStopColor);
        }

        detachHandlers();

        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        detachSystemStreamsTEE();
        drainAndSend();

        if (debug) getLogger().info("LumenMC: off.");

        if (watchdogHeartbeatTaskId != -1) {
            Bukkit.getScheduler().cancelTask(watchdogHeartbeatTaskId);
            watchdogHeartbeatTaskId = -1;
        }
        if (watchdogCheckerTaskId != -1) {
            Bukkit.getScheduler().cancelTask(watchdogCheckerTaskId);
            watchdogCheckerTaskId = -1;
        }
    }

    // Events (chat, commands, etc.)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        if (!sendChat) return;
        String player = event.getPlayer().getName();
        String message = event.getMessage();

        String content = "[" + Instant.now() + "] [CHAT] <" + player + "> " + message;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!sendPlayerCommands) return;
        String player = event.getPlayer().getName();
        String cmd = event.getMessage();

        String content = "[" + Instant.now() + "] [CMD] " + player + ": " + cmd;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!sendConsoleCommands) return;
        String cmd = event.getCommand();

        String content = "[" + Instant.now() + "] [CMD] CONSOLE: /" + cmd;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!sendUuidPrelogin) return;
        String name = event.getName();
        UUID uuid = event.getUniqueId();
        String content = "[" + Instant.now() + "] [LOGIN] UUID of player " + name + " is " + uuid;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!sendJoinQuit) return;
        String name = event.getPlayer().getName();
        String content = "[" + Instant.now() + "] [JOIN] " + name + " joined the game";
        enqueueIfAllowed(content);

        String loc = event.getPlayer().getWorld().getName() + "]" +
                event.getPlayer().getLocation().getBlockX() + ", " +
                event.getPlayer().getLocation().getBlockY() + ", " +
                event.getPlayer().getLocation().getBlockZ();
        enqueueIfAllowed("[" + Instant.now() + "] [JOIN] " + name + " logged in at ([" + loc + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        if (!sendJoinQuit) return;
        String name = event.getPlayer().getName();
        String content = "[" + Instant.now() + "] [QUIT] " + name + " left the game";
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!sendDeaths) return;
        String msg = event.getDeathMessage();
        if (msg == null || msg.isBlank()) return;
        String content = "[" + Instant.now() + "] [DEATH] " + msg;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        if (!sendGamemodeChanges) return;
        String name = event.getPlayer().getName();
        GameMode newMode = event.getNewGameMode();
        String content = "[" + Instant.now() + "] [GAMEMODE] " + name + " set own game mode to " + prettyMode(newMode);
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (!sendServerLoad) return;
        if (embedsStartStopEnabled) {
            sendEmbed(embedStartTitle, embedStartDescription, embedStartColor);
        }
        else{
            String content = "[" + Instant.now() + "] [SERVER] Startup complete. For help, type \"help\"";
            enqueueIfAllowed(content);
        }
    }

    private String prettyMode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> "Survival Mode";
            case CREATIVE -> "Creative Mode";
            case ADVENTURE -> "Adventure Mode";
            case SPECTATOR -> "Spectator Mode";
        };
    }

    // Send

    private void enqueueIfAllowed(String content) {
        if (removeMentions) {
            content = content.replace("@everyone", "＠everyone").replace("@here", "＠here");
        }
        if (!shouldIgnore(content)) {
            for (String chunk : splitMessage(content, maxMessageLength)) {
                queue.offer(chunk);
            }
        }
    }

    private void sendEmbed(String title, String description, int color) {
        try {
            Embed embed = new Embed();
            embed.title = title;
            embed.description = description;
            embed.color = color;
            embed.timestamp = Instant.now().toString();
            embed.footer = new Footer("LumenMC DCW " + getDescription().getVersion() + " | " + LocalDateTime.now());
            embed.image = new Image(WEBHOOK_IMAGE != null && !WEBHOOK_IMAGE.isBlank()
                    ? WEBHOOK_IMAGE
                    : "https://cdn.lumenvm.cloud/lumenmc-banner.png");

            embed.fields = Arrays.asList(
                    new Field("Time Zone", TZ, true),
                    new Field("Server Memory", SERVER_MEMORY + "MB", true),
                    new Field("Server IP", SERVER_IP, true),
                    new Field("Server Port", SERVER_PORT, true),
                    new Field("Server Location", P_SERVER_LOCATION, true),
                    new Field("Server UUID", "```" + P_SERVER_UUID + "```", true),
                    new Field("Server Version", getServer().getVersion(), true),
                    new Field("Number of Plugins", String.valueOf(getServer().getPluginManager().getPlugins().length), true)
            );

            WebhookEmbedPayload payload = new WebhookEmbedPayload();
            payload.username = WEBHOOK_NAME != null ? WEBHOOK_NAME : "LumenMC";
            payload.avatar_url = WEBHOOK_AVATAR != null ? WEBHOOK_AVATAR : "https://cdn.lumenvm.cloud/lumen-avatar.png";
            payload.embeds = Collections.singletonList(embed);

            String json = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (debug) {
                getLogger().info("Webhook embed sent: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error when sending embed to Discord: ", e);
        }
    }

    // Plugin command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lumenmc")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("test")) {
            if (webhookUri == null) {
                sender.sendMessage("§cWebhook URL is NOT set. Pleas set it in config.yml, dumbass :)");
                return true;
            }
            String content = "🔧 LumenMC test message in " + getDescription().getVersion() +
                    " @ " + Instant.now();
            queue.offer(content);
            Bukkit.getScheduler().runTaskAsynchronously(this, this::drainAndSend);
            sender.sendMessage("§aTesting message sent...");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            readConfig();
            sender.sendMessage("§aReloaded...");
            return true;
        }

        sender.sendMessage("Use: /lumenmc test|reload");
        return true;
    }

    // Config

    private void readConfig() {
        //String url = getConfig().getString("webhook_url", "");
        String url = WEBHOOK_URL;

        if (url != null && !url.isBlank() && !url.equals("https://discord.com/api/webhooks/XXXXXXXXXXX/XXXXXXXXXXX")) {
            try { webhookUri = URI.create(url); }
            catch (IllegalArgumentException e) {
                getLogger().severe("Webhook URL is invalid: " + url);
                webhookUri = null;
            }
        } else {
            webhookUri = null;
        }

        minLevel = parseLevel(getConfig().getString("min_level", "INFO"));
        ignorePatterns = getConfig().getStringList("ignore_patterns");
        includeStackTraces = getConfig().getBoolean("include_stack_traces", true);
        batchIntervalMs = getConfig().getInt("batch_interval_ms", 2000);
        maxBatchSize = getConfig().getInt("max_batch_size", 50);
        maxMessageLength = getConfig().getInt("max_message_length", 1900);
        removeMentions = getConfig().getBoolean("remove_mentions", true);
        debug = getConfig().getBoolean("debug", false);
        captureSystemStreams = getConfig().getBoolean("capture_system_streams", true);

        sendChat = getConfig().getBoolean("send_chat", true);
        sendPlayerCommands = getConfig().getBoolean("send_player_commands", true);
        sendConsoleCommands = getConfig().getBoolean("send_console_commands", true);
        sendUuidPrelogin = getConfig().getBoolean("send_uuid_prelogin", true);
        sendJoinQuit = getConfig().getBoolean("send_join_quit", true);
        sendDeaths = getConfig().getBoolean("send_deaths", true);
        sendGamemodeChanges = getConfig().getBoolean("send_gamemode_changes", true);
        sendServerLoad = getConfig().getBoolean("send_server_load", true);

        embedsStartStopEnabled = getConfig().getBoolean("embeds_start_stop_enabled", true);

        watchdogEnabled = getConfig().getBoolean("watchdog_enabled", true);
        watchdogTimeoutMs = getConfig().getLong("watchdog_timeout_ms", 10000L);
        watchdogCheckIntervalMs = getConfig().getLong("watchdog_check_interval_ms", 2000L);
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
            getLogger().log(Level.WARNING, "Can't connect to bukkit logger.", e);
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
            getLogger().log(Level.WARNING, "Can't connect to root JUL logger", e);
        }

        attachNamedLoggers();

        handlersAttached = true;
        if (debug) getLogger().info("LumenMC: Handlers connected (root/Bukkit level = ALL).");
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
                if (debug) getLogger().info("LumenMC: Handler connected to logger  '" + name + "'");
            } catch (Exception e) {
                if (debug) getLogger().warning("LumenMC: Couldn't connect to logger  '" + name + "': " + e.getMessage());
            }
        }
    }

    private void detachHandlers() {
        if (!handlersAttached) return;
        try { Bukkit.getLogger().removeHandler(commonHandler); } catch (Exception ignored) {}
        try { Logger.getLogger("").removeHandler(commonHandler); } catch (Exception ignored) {}
        handlersAttached = false;
    }

    // System streams

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
        if (debug) getLogger().info("LumenMC: System streams on.");
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

    // Help methods

    private Level parseLevel(String s) {
        try { return Level.parse(s == null ? "INFO" : s.toUpperCase()); }
        catch (Exception e) { return Level.INFO; }
    }

    private int msToTicks(int ms) {
        int ticks = (int) Math.ceil(ms / 50.0);
        return Math.max(1, ticks);
    }

    private boolean shouldIgnore(String msg) {
        if (msg == null || ignorePatterns == null) return false;
        for (String pattern : ignorePatterns) {
            if (pattern == null || pattern.isBlank()) continue;
            try { if (msg.matches(pattern)) return true; }
            catch (Exception ignored) {}
        }
        return false;
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

        if (includeStackTraces && record.getThrown() != null) {
            sb.append("\n").append(stackTraceToString(record.getThrown()));
        }

        String result = sb.toString();
        if (removeMentions) {
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

    private List<String> splitMessage(String msg, int maxLen) {
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

    private void drainAndSend() {
        if (webhookUri == null) return;

        // Anti-double-drain lock
        if (!drainLock.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> batch = new ArrayList<>(maxBatchSize);
            while (batch.size() < maxBatchSize) {
                String item = queue.poll();
                if (item == null) break;
                batch.add(item);
            }
            if (batch.isEmpty()) return;

            String combined = String.join("\n", batch);
            List<String> payloads = splitMessage(combined, maxMessageLength);

            if (debug) getLogger().info("LumenMC: Sending batch: " + batch.size() + " messages, payloads: " + payloads.size());

            for (String content : payloads) {
                sendWebhook(content);
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            drainLock.set(false);
        }
    }

    private void sendWebhook(String content) {
        try {
            WebhookContentPayload payload = new WebhookContentPayload(content);
            String json = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (debug) {
                getLogger().info("LumenMC: Webhook HTTP " + status +
                        (response.body() != null ? (" body: " + response.body()) : ""));
            }

            if (status < 200 || status >= 300) {
                getLogger().warning("Discord webhook returned HTTP " + status + ": " + response.body());
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error when sending to webhook", e);
        }
    }



    // Content-only payload
    static class WebhookContentPayload {
        @SerializedName("content") String content;
        WebhookContentPayload(String content) { this.content = content; }
    }

    // Embed-only payload
    static class WebhookEmbedPayload {
        @SerializedName("username") String username;
        @SerializedName("avatar_url") String avatar_url;
        @SerializedName("embeds") List<Embed> embeds;
    }

    static class Image {
        @SerializedName("url") String url;
        Image(String url) { this.url = url; }
    }

    static class Embed {
        @SerializedName("image")       Image image;
        @SerializedName("title")       String title;
        @SerializedName("description") String description;
        @SerializedName("color")       Integer color;
        @SerializedName("timestamp")   String timestamp;
        @SerializedName("footer")      Footer footer;
        @SerializedName("author")      Author author;
        @SerializedName("fields")      List<Field> fields;
    }

    static class Footer {
        @SerializedName("text") String text;
        Footer(String text) { this.text = text; }
    }

    static class Author {
        @SerializedName("name") String name;
        Author(String name) { this.name = name; }
    }

    static class Field {
        @SerializedName("name")   String name;
        @SerializedName("value")  String value;
        @SerializedName("inline") Boolean inline;
        Field(String name, String value, boolean inline) {
            this.name = name; this.value = value; this.inline = inline;
        }
    }
}
