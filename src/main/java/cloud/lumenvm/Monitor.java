package cloud.lumenvm;

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
import org.checkerframework.checker.nullness.qual.NonNull;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
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

@SuppressWarnings("FieldCanBeLocal")
public class Monitor extends JavaPlugin implements Listener {
    // Config
    private LanguageLoader langLoader;
    private ConfigLoader confLoader;
    private String locale;
    private static HttpClient httpClient;
    private URI webhookUri = URI.create("");
    private boolean reloading = false;

    public int watchdogHeartbeatTaskId = -1;
    public int watchdogCheckerTaskId = -1;

    // Queue
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    // Scheduler
    private int taskId = -1;

    // Handlers
    private Handler commonHandler;
    private boolean handlersAttached = false;

    // Anti double drain
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
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        saveDefaultConfig();
        reloadConfig();
        confLoader = new ConfigLoader(this);
        langLoader = new LanguageLoader(this, confLoader);

        if (confLoader.failedToLoadConfig) {
            getLogger().severe("Webhook URL is NOT set. Pleas adjust pterodactyl server configuration/config.yml accordingly :)");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        commonHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (!confLoader.enableLogs) return;
                if (record == null || !isLoggable(record)) return;
                if (record.getLevel().intValue() < confLoader.minLevel.intValue()) return;

                String msg = formatRecord(record);
                if (shouldIgnore(msg)) return;

                int h = Objects.hash(record.getMillis(), record.getLevel(), record.getLoggerName(), msg);
                synchronized (recentHashes) {
                    if (recentHashes.contains(h)) return;
                    recentHashes.addLast(h);
                    if (recentHashes.size() > DEDUPE_WINDOW) recentHashes.removeFirst();
                }

                for (String chunk : splitMessage(msg, confLoader.maxMessageLength)) {
                    queue.offer(chunk);
                }
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        commonHandler.setLevel(Level.ALL);

        attachHandlers();

        if (confLoader.debug) getLogger().info("Debug: Handlers connected in onLoad(); queueing logs");
    }

    @Override
    public void onEnable() {

        if (confLoader.failedToLoadConfig) {
            return;
        }

        // Register events
        if (!reloading) {
            getServer().getPluginManager().registerEvents(this, this);
        }

        int ticks = msToTicks(confLoader.batchIntervalMs);
        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(this, this::drainAndSend, ticks, ticks)
                .getTaskId();

        // Delete drain
        Bukkit.getScheduler().runTaskAsynchronously(this, this::drainAndSend);

        if (confLoader.captureSystemStreams) attachSystemStreamsTEE();

        if (confLoader.debug) getLogger().info("Debug: Sending activated (interval " + confLoader.batchIntervalMs + " ms / " + ticks + " ticks).");


        if (confLoader.watchdogEnabled) {
            // Heartbeat
            watchdogHeartbeatTaskId = Bukkit.getScheduler().runTaskTimer(this, () -> confLoader.lastTickNanos = System.nanoTime(), 0L, 1L).getTaskId();

            // Async control
            int checkTicks = msToTicks((int) confLoader.watchdogCheckIntervalMs);
            watchdogCheckerTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                long now = System.nanoTime();
                long elapsedMs = (now - confLoader.lastTickNanos) / 1_000_000L;

                if (elapsedMs >= confLoader.watchdogTimeoutMs) {
                    if (!confLoader.watchdogAlerted) {
                        confLoader.watchdogAlerted = true;
                        enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + langLoader.get("watchdog_alert_message"));
                        if (confLoader.debug) getLogger().warning("WATCHDOG alert: main thread stalled for " + elapsedMs + " ms");
                    }
                } else {
                    // Restored
                    if (confLoader.watchdogAlerted) {
                        confLoader.watchdogAlerted = false;
                        enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + langLoader.get("watchdog_recovery_message"));
                        if (confLoader.debug) getLogger().info("WATCHDOG recovery: main thread delay " + elapsedMs + " ms");
                    }
                }
            }, checkTicks, checkTicks).getTaskId();

            if (confLoader.debug) getLogger().info("Debug: Watchdog launched (timeout " + confLoader.watchdogTimeoutMs + " ms, control every " + confLoader.watchdogCheckIntervalMs + " ms).");
        }

    }

    @Override
    public void onDisable() {
        if (!confLoader.failedToLoadConfig && confLoader.embedsStartStopEnabled && !reloading) {
            sendEmbed(langLoader.get("embed_stop_title"), langLoader.get("embed_stop_description"), Integer.parseInt(langLoader.get("embed_stop_color")));
        }

        detachHandlers();

        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        detachSystemStreamsTEE();
        drainAndSend();

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
        if (!confLoader.sendChat) return;
        String player = event.getPlayer().getName();
        String message = event.getMessage();

        String content = "[" + Instant.now() + "] [CHAT] <" + player + "> " + message;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!confLoader.sendPlayerCommands) return;
        String player = event.getPlayer().getName();
        String cmd = event.getMessage();

        String content = "[" + Instant.now() + "] [CMD] " + player + ": " + cmd;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!confLoader.sendConsoleCommands) return;
        String cmd = event.getCommand();
        String content = langLoader.get("on_server_command");
        enqueueIfAllowed(content.replace("%lumenmc_cmd%", "[" + Instant.now() + "]" + cmd));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!confLoader.sendJoinQuit) return;
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
        if (!confLoader.sendJoinQuit) return;
        String name = event.getPlayer().getName();
        String content = "[" + Instant.now() + "] [QUIT] " + name + " left the game";
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!confLoader.sendDeaths) return;
        String msg = event.getDeathMessage();
        if (msg == null || msg.isBlank()) return;
        String content = "[" + Instant.now() + "] [DEATH] " + msg;
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        if (!confLoader.sendGamemodeChanges) return;
        String name = event.getPlayer().getName();
        GameMode newMode = event.getNewGameMode();
        String content = "[" + Instant.now() + "] [GAMEMODE] " + name + " set own game mode to " + prettyMode(newMode);
        enqueueIfAllowed(content);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (!confLoader.sendServerLoad) return;
        if (confLoader.embedsStartStopEnabled) {
            sendEmbed(langLoader.get("embed_start_title"), langLoader.get("embed_start_description"), Integer.parseInt(langLoader.get("embed_start_color")));
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
        if (confLoader.removeMentions) {
            content = content.replace("@everyone", "＠everyone").replace("@here", "＠here");
        }
        if (!shouldIgnore(content)) {
            for (String chunk : splitMessage(content, confLoader.maxMessageLength)) {
                queue.offer(chunk);
            }
        }
    }

    private void drainAndSend() {
        if (webhookUri == null) return;

        // Anti-double-drain lock
        if (!drainLock.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> batch = new ArrayList<>(confLoader.maxBatchSize);
            while (batch.size() < confLoader.maxBatchSize) {
                String item = queue.poll();
                if (item == null) break;
                batch.add(item);
            }
            if (batch.isEmpty()) return;

            String combined = String.join("\n", batch);
            List<String> payloads = splitMessage(combined, confLoader.maxMessageLength);

            if (confLoader.debug) getLogger().info("Debug: Sending batch: " + batch.size() + " messages, payloads: " + payloads.size());

            for (String content : payloads) {
                sendContent(content);
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            drainLock.set(false);
        }
    }

    private void sendContent(String content) {
        for (String s : confLoader.urls) {
            webhookUri = URI.create(s);
            try {
                WebhookContentPayload payload = new WebhookContentPayload(content);
                payload.username = "LumenMC";
                payload.avatar_url = "https://cdn.lumenvm.cloud/logo.png";
                String json = gson.toJson(payload);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(webhookUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (confLoader.debug) {
                    getLogger().info("Debug: Webhook HTTP " + status +
                            (response.body() != null ? (" body: " + response.body()) : ""));
                }

                if (status < 200 || status >= 300) {
                    getLogger().warning("Discord webhook returned HTTP " + status + ": " + response.body());
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Error when sending to webhook", e);
            }
        }
    }

    private void sendEmbed(String title, String description, int color) {
        try {
            for (String s : confLoader.urls) {
                webhookUri = URI.create(s);
                Embed embed = new Embed();
                embed.title = title;
                embed.description = description;
                embed.color = color;
                embed.timestamp = Instant.now().toString();
                embed.footer = new Footer("LumenMC Monitor " + getDescription().getVersion() + " | " + LocalDateTime.now());
                embed.image = new Image("https://cdn.lumenvm.cloud/lumenmc-banner.png");

                if (confLoader.P_SERVER_LOCATION != null && !confLoader.P_SERVER_LOCATION.isBlank() && confLoader.P_SERVER_UUID != null && !confLoader.P_SERVER_UUID.isBlank()) {
                    embed.fields = Arrays.asList(
                            new Field("Time Zone", confLoader.TZ, true),
                            new Field("Server Memory", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024.0 * 1024.0) + "MB", true),
                            new Field("Server IP", confLoader.SERVER_IP, true),
                            new Field("Server Port", confLoader.SERVER_PORT, true),
                            new Field("Server Location", confLoader.P_SERVER_LOCATION, true),
                            new Field("Server UUID", "```" + confLoader.P_SERVER_UUID + "```", true),
                            new Field("Server Version", getServer().getVersion(), true),
                            new Field("Number of Plugins", String.valueOf(getServer().getPluginManager().getPlugins().length), true)
                    );
                } else {
                    embed.fields = Arrays.asList(
                            new Field("Time Zone", TimeZone.getDefault().getID(), true),
                            new Field("Server Memory", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024.0 * 1024.0) + "MB", true),
                            new Field("Server Version", getServer().getVersion(), true),
                            new Field("Number of Plugins", String.valueOf(getServer().getPluginManager().getPlugins().length), true)
                    );
                }

                WebhookEmbedPayload payload = new WebhookEmbedPayload();
                payload.username = "LumenMC";
                payload.avatar_url = "https://cdn.lumenvm.cloud/logo.png";
                payload.embeds = Collections.singletonList(embed);

                String json = gson.toJson(payload);

                if (confLoader.debug) {
                    getLogger().info("Debug: Sending embed to Discord \n" + json);
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(webhookUri)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (confLoader.debug) {
                    getLogger().info("Debug: Webhook embed sent: HTTP " + response.statusCode());
                    getLogger().info("Debug: Response body: " + response.body());
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error when sending embed to Discord: ", e);
        }
    }

    // Plugin command

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command command, @NonNull String label, String @NonNull [] args) {
        if (!command.getName().equalsIgnoreCase("lumenmc")) return false;

        if (args.length == 0) {
            return false;
        }

        if (args[0].equalsIgnoreCase("test") && !confLoader.failedToLoadConfig) {
            if (webhookUri == null) {
                sender.sendMessage("§cWebhook URL is NOT set. Pleas set it in config.yml :)");
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
            reloading = true;
            onDisable();
            onLoad();
            onEnable();
            sender.sendMessage("§aReloaded...");
            reloading = false;
            return true;
        }

        if (args[0].equalsIgnoreCase("lang")  && !confLoader.failedToLoadConfig) {
            if (args.length == 1) {
                sender.sendMessage("Use: /lumenmc lang create|remove|set|list");
                return true;
            }

            if (args[1].equalsIgnoreCase("create")) {
                if (args.length == 2) {
                    sender.sendMessage("Use: /lumenmc lang create [language]");
                    return true;
                }

                String langName = args[2];
                if (langName == null || langName.isBlank()) {
                    sender.sendMessage("Use: /lumenmc lang create [language]");
                    return true;
                }
                if (args.length > 3) {
                    sender.sendMessage("Don't enter spaces, please. Use: /lumenmc lang create [language]");
                    return true;
                }
                if (langName.contains(".yml")) langName = langName.replace(".yml", "");
                sender.sendMessage(langLoader.createLang(this, langName));
                return true;
            }

            if (args[1].equalsIgnoreCase("remove")) {
                if (args.length == 2) {
                    sender.sendMessage("Use: /lumenmc lang remove [language]");
                    return true;
                }

                String langName = args[2];
                if (langName == null || langName.isBlank()) {
                    sender.sendMessage("Use: /lumenmc lang remove [language]");
                    return true;
                }
                if (args.length > 3) {
                    sender.sendMessage("§cDon't enter spaces, please. Use: /lumenmc lang remove [language]");
                    return true;
                }
                if (langName.contains(".yml")) langName = langName.replace(".yml", "");
                sender.sendMessage(langLoader.removeLang(this, langName));
                return true;
            }

            if (args[1].equalsIgnoreCase("set")) {
                if (args.length == 2) {
                    sender.sendMessage("Use: /lumenmc lang set [language]");
                    return true;
                }

                String langName = args[2];
                if (langName == null || langName.isBlank()) {
                    sender.sendMessage("Use: /lumenmc lang set [language]");
                    return true;
                }
                if (args.length > 3) {
                    sender.sendMessage("Don't enter spaces, please. Use: /lumenmc lang set [language]");
                    return true;
                }
                if (langName.contains(".yml")) langName = langName.replace(".yml", "");
                sender.sendMessage(langLoader.setLang(this, langName));
                return true;
            }

            if (args[1].equalsIgnoreCase("list")) {
                sender.sendMessage("These are available language files:");
                ArrayList<String> list = langLoader.listLang();
                for (String s : list) {
                    sender.sendMessage(s);
                }
                return true;
            }

            if (args[1].equalsIgnoreCase("edit")) {
                if (args.length == 2) {
                    sender.sendMessage("Use: /lumenmc lang edit [key] [new string]");
                    return true;
                }

                String key = args[2];
                if (args.length == 3) {
                    String message = langLoader.get(key);
                    if (message == null) {
                        sender.sendMessage("§cThat key doesn't exist");
                        return true;
                    }
                    sender.sendMessage("§aThe key is set to " + message);
                    return true;
                }

                StringBuilder newObject = new StringBuilder(args[3]);
                if (args.length > 3) {
                    for (int i = 4; i < args.length ; i++) {
                        newObject.append(" ").append(args[i]);
                    }
                }
                try {
                    sender.sendMessage(langLoader.editLang(this, key, newObject.toString()));
                } catch (IOException e) {
                    getLogger().severe("§cError when editing " + locale + ".yml");
                }
                return true;
            }

            sender.sendMessage("Use: /lumenmc lang create|remove|set|list|edit");
            return true;
        }

        if (args[0].equalsIgnoreCase("webhook")  && !confLoader.failedToLoadConfig) {
            if (args.length == 1) {
                sender.sendMessage("Use: /lumenmc webhook add|remove");
                return true;
            }
            if (args[1].equalsIgnoreCase("add")) {
                if (args.length == 2) {
                    sender.sendMessage("Use: /lumenmc webhook add [webhookUrl]");
                    return true;
                }
                if (webhookTest(this, args[2])) {
                    confLoader.urls.add(args[2]);
                    getConfig().set("webhook_url", confLoader.urls);
                    saveConfig();
                    reloadConfig();
                    loadConfig();
                    sender.sendMessage("§aSet webhook url to: " + args[2]);
                } else {
                    sender.sendMessage("§cInvalid webhook url: " + args[1]);
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("remove")) {
                if (args.length == 2) {
                    sender.sendMessage("Use: /lumenmc webhook remove [webhookUrl]");
                    return true;
                }
                if (confLoader.urls.contains(args[2]) && webhookTest(this, args[2])) {
                    confLoader.urls.remove(args[2]);
                    getConfig().set("webhook_url", confLoader.urls);
                    saveConfig();
                    reloadConfig();
                    loadConfig();
                    sender.sendMessage("§aRemoved webhook url: " + args[2]);
                } else {
                    sender.sendMessage("§cInvalid webhook url: " + args[2]);
                }
                return true;
            }
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        List<String> list = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 1) {
            list.add("test");
            list.add("reload");
            list.add("lang");
            list.add("webhook");
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("webhook")) {
            list.add("add");
            list.add("remove");
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("webhook") && args[1].equalsIgnoreCase("remove")) {
            return confLoader.urls;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            list.add("create");
            list.add("remove");
            list.add("list");
            list.add("set");
            list.add("edit");
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("lang") && args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove")) {
            list = langLoader.listLang();
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("lang") && args[1].equalsIgnoreCase("edit")) {
            return langLoader.getKeys();
        }
        return list;
    }

    // Config

    void loadConfig() {
        reloading = true;
        onDisable();
        onLoad();
        onEnable();
        reloading = false;
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
        if (confLoader.debug) getLogger().info("Debug: Handlers connected (root/Bukkit level = ALL).");
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
                if (confLoader.debug) getLogger().info("Debug: Handler connected to logger  '" + name + "'");
            } catch (Exception e) {
                if (confLoader.debug) getLogger().warning("Debug: Couldn't connect to logger  '" + name + "': " + e.getMessage());
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
        if (confLoader.debug) getLogger().info("Debug: System streams on.");
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

    public boolean webhookTest(Monitor plugin, String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = Monitor.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return false;
            } else return response.statusCode() == 200;
        } catch (Exception e) {
            plugin.getLogger().warning("Error when testing webhook urls: " + e);
            return false;
        }
    }

    Level parseLevel(String s) {
        try { return Level.parse(s == null ? "INFO" : s.toUpperCase()); }
        catch (Exception e) { return Level.INFO; }
    }

    private int msToTicks(int ms) {
        int ticks = (int) Math.ceil(ms / 50.0);
        return Math.max(1, ticks);
    }

    private boolean shouldIgnore(String msg) {
        if (msg == null || confLoader.ignorePatterns == null) return false;
        for (String pattern : confLoader.ignorePatterns) {
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

    public String getLocale() {
        return locale;
    }

    public void setLocale(String setLocale) {
        locale = setLocale;
    }

    // Content-only payload
    static class WebhookContentPayload {
        @SerializedName("username") String username;
        @SerializedName("avatar_url") String avatar_url;
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
        @SerializedName("fields")      List<Field> fields;
    }

    static class Footer {
        @SerializedName("text") String text;
        Footer(String text) { this.text = text; }
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
