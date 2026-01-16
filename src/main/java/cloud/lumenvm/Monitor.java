package cloud.lumenvm;

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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;
import java.util.List;
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
    private boolean reloading = false;

    List<Webhook> webhooks;

    public int watchdogHeartbeatTaskId = -1;
    public int watchdogCheckerTaskId = -1;

    // Handlers
    private Handler commonHandler;
    private boolean handlersAttached = false;

    // Anti double drain
    private final Deque<Integer> recentHashes = new ArrayDeque<>();
    private static final int DEDUPE_WINDOW = 256;

    // System streams
    private PrintStream originalOut;
    private PrintStream originalErr;

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
        Webhook.httpClient = httpClient;
        saveDefaultConfig();
        reloadConfig();
        confLoader = new ConfigLoader(this);
        langLoader = new LanguageLoader(this, confLoader);

        Webhook.setPlugin(this);
        Webhook.setConfLoader(confLoader);
        webhooks = new ArrayList<>();

        if (confLoader.failedToLoadConfig) {
            getLogger().severe("Webhook URL is NOT set. Pleas adjust pterodactyl server configuration/config.yml accordingly and RESTART the server :)");
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
                if (Webhook.shouldIgnore(msg)) return;

                int h = Objects.hash(record.getMillis(), record.getLevel(), record.getLoggerName(), msg);
                synchronized (recentHashes) {
                    if (recentHashes.contains(h)) return;
                    recentHashes.addLast(h);
                    if (recentHashes.size() > DEDUPE_WINDOW) recentHashes.removeFirst();
                }

                for (String chunk : Webhook.splitMessage(msg, confLoader.maxMessageLength)) {
                    for (Webhook webhook : webhooks) {
                        webhook.queue.offer(chunk);
                    }
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

        for (String url : confLoader.urls) {
            webhooks.add(new Webhook(url));
        }

        if (confLoader.failedToLoadConfig) {
            return;
        }

        // Register events
        if (!reloading) {
            getServer().getPluginManager().registerEvents(this, this);
        }

        if (confLoader.captureSystemStreams) attachSystemStreamsTEE();

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
                        for (Webhook webhook : webhooks) {
                            webhook.enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + langLoader.get("watchdog_alert_message"));
                        }
                        if (confLoader.debug) getLogger().warning("WATCHDOG alert: main thread stalled for " + elapsedMs + " ms");
                    }
                } else {
                    // Restored
                    if (confLoader.watchdogAlerted) {
                        confLoader.watchdogAlerted = false;
                        for (Webhook webhook : webhooks) {
                            webhook.enqueueIfAllowed("[" + Instant.now() + "] [WATCHDOG] " + langLoader.get("watchdog_recovery_message"));
                        }
                    }
                }
            }, checkTicks, checkTicks).getTaskId();

            if (confLoader.debug) getLogger().info("Debug: Watchdog launched (timeout " + confLoader.watchdogTimeoutMs + " ms, control every " + confLoader.watchdogCheckIntervalMs + " ms).");
        }

    }

    @Override
    public void onDisable() {
        if (!confLoader.failedToLoadConfig && confLoader.embedsStartStopEnabled && !reloading) {
            for (Webhook webhook : webhooks) {
                webhook.sendEmbed(langLoader.get("embed_stop_title"), langLoader.get("embed_stop_description"), Integer.parseInt(langLoader.get("embed_stop_color")));
            }
        }

        detachHandlers();

        for (Webhook webhook : webhooks) {
            webhook.endTask();
        }

        detachSystemStreamsTEE();

        for (Webhook webhook : webhooks) {
            webhook.drainAndSend();
        }

        webhooks = null;

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
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!confLoader.sendPlayerCommands) return;
        String player = event.getPlayer().getName();
        String cmd = event.getMessage();

        String content = "[" + Instant.now() + "] [CMD] " + player + ": " + cmd;
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!confLoader.sendConsoleCommands) return;
        String cmd = event.getCommand();
        String content = langLoader.get("on_server_command");
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content.replace("%lumenmc_cmd%", "[" + Instant.now() + "]" + cmd));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        if (!confLoader.sendJoinQuit) return;
        String name = event.getPlayer().getName();
        String content = "[" + Instant.now() + "] [JOIN] " + name + " joined the game";
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content);
        }

        String loc = event.getPlayer().getWorld().getName() + "]" +
                event.getPlayer().getLocation().getBlockX() + ", " +
                event.getPlayer().getLocation().getBlockY() + ", " +
                event.getPlayer().getLocation().getBlockZ();
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed("[" + Instant.now() + "] [JOIN] " + name + " logged in at ([" + loc + ")");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        if (!confLoader.sendJoinQuit) return;
        String name = event.getPlayer().getName();
        String content = "[" + Instant.now() + "] [QUIT] " + name + " left the game";
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!confLoader.sendDeaths) return;
        String msg = event.getDeathMessage();
        if (msg == null || msg.isBlank()) return;
        String content = "[" + Instant.now() + "] [DEATH] " + msg;
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        if (!confLoader.sendGamemodeChanges) return;
        String name = event.getPlayer().getName();
        GameMode newMode = event.getNewGameMode();
        String content = "[" + Instant.now() + "] [GAMEMODE] " + name + " set own game mode to " + prettyMode(newMode);
        for (Webhook webhook : webhooks) {
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (!confLoader.sendServerLoad) return;
        if (confLoader.embedsStartStopEnabled) {
            for (Webhook webhook : webhooks) {
                webhook.sendEmbed(langLoader.get("embed_start_title"), langLoader.get("embed_start_description"), Integer.parseInt(langLoader.get("embed_start_color")));
            }
        }
        else{
            String content = "[" + Instant.now() + "] [SERVER] Startup complete. For help, type \"help\"";
            for (Webhook webhook : webhooks) {
                webhook.enqueueIfAllowed(content);
            }
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

    // Plugin command

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command command, @NonNull String label, String @NonNull [] args) {
        if (!command.getName().equalsIgnoreCase("lumenmc")) return false;

        if (args.length == 0) {
            return false;
        }

        if (args[0].equalsIgnoreCase("test") && !confLoader.failedToLoadConfig) {
            String content = "🔧 LumenMC test message in " + getDescription().getVersion() +
                    " @ " + Instant.now();
            for (Webhook webhook : webhooks) {
                webhook.queue.offer(content);
            }
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
                for (Webhook webhook : webhooks) {
                    webhook.enqueueIfAllowed("[" + Instant.now() + "] [INFO] [System.out] " + x);
                }
                super.println(x);
            }
        });
        System.setErr(new PrintStream(originalErr) {
            @Override public void println(String x) {
                for (Webhook webhook : webhooks) {
                    webhook.enqueueIfAllowed("[" + Instant.now() + "] [INFO] [System.err] " + x);
                }
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

    public String getLocale() {
        return locale;
    }

    public void setLocale(String setLocale) {
        locale = setLocale;
    }
}
