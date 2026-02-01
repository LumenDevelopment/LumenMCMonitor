package cloud.lumenvm;

import java.util.List;
import java.util.logging.Level;

public class ConfigLoader {
    // Plugin
    private static Monitor plugin;

    public String name;
    public String url;
    public Level minLevel;
    public boolean enableLogs;
    public List<String> ignorePatterns;
    public boolean includeStackTraces;
    public int batchIntervalMs;
    public int maxBatchSize;
    public int maxMessageLength;
    public boolean removeMentions;
    public boolean debug;
    public boolean captureSystemStreams;
    public boolean failedToLoadConfig;
    public String serverName;

    public boolean sendChat;
    public boolean sendPlayerCommands;
    public boolean sendConsoleCommands;
    public boolean sendUuidPrelogin;
    public boolean sendJoinQuit;
    public boolean sendDeaths;
    public boolean sendGamemodeChanges;
    public boolean sendServerLoad;

    // Embeds
    public boolean embedsStartStopEnabled;
    public boolean embedsJoinQuitEnabled;
    public boolean embedsDeathsEnabled;
    public boolean embedsWatchdogEnabled;

    // Watchdog
    public volatile long lastTickNanos = System.nanoTime();
    public boolean watchdogEnabled;
    public long watchdogTimeoutMs;
    public long watchdogCheckIntervalMs;
    public volatile boolean watchdogAlerted = false;

    // Pterodactyl variables
    public final String WEBHOOK_URL = System.getenv("WEBHOOK_URL");

    public ConfigLoader(String name) {
        debug = plugin.getConfig().getBoolean("debug", false);
        this.name = name;
        url = plugin.getConfig().getString("webhooks." + name + ".url", "https://discord.com/api/webhooks/XXXXXXXXXXX/XXXXXXXXXXX");

        // Check for a valid url from config.yml or pterodactyl
        if (!url.isEmpty() && !url.equalsIgnoreCase("https://discord.com/api/webhooks/XXXXXXXXXXX/XXXXXXXXXXX")) {
            if (!plugin.webhookTest(url)) {
                plugin.getLogger().severe("Webhook URL is invalid: " + url);
                failedToLoadConfig = true;
                return;
            }
        } else if (WEBHOOK_URL != null && !WEBHOOK_URL.isBlank()) {
            if (!plugin.webhookTest(WEBHOOK_URL)) {
                url = WEBHOOK_URL;
                plugin.getConfig().set("webhook_url", url);
                plugin.saveConfig();
                if (debug) {
                    plugin.getLogger().info("Debug: Discord Webhook url saved to config.yml");
                }
            } else {
                plugin.getLogger().severe("Webhook URL is invalid: " + url);
                failedToLoadConfig = true;
                return;
            }
        } else {
            url = null;
            failedToLoadConfig = true;
            return;
        }

        if (debug) {
            plugin.getLogger().info("Debug: Discord Webhook is url set to: " + url);
        }

        // Set all the config variables
        minLevel = plugin.parseLevel(plugin.getConfig().getString("webhooks." + name + ".min_level", "INFO"));
        ignorePatterns = plugin.getConfig().getStringList("webhooks." + name + ".ignore_patterns");
        includeStackTraces = plugin.getConfig().getBoolean("webhooks." + name + ".include_stack_traces", true);
        batchIntervalMs = plugin.getConfig().getInt("webhooks." + name + ".batch_interval_ms", 2000);
        maxBatchSize = plugin.getConfig().getInt("webhooks." + name + ".max_batch_size", 50);
        maxMessageLength = plugin.getConfig().getInt("webhooks." + name + ".max_message_length", 1900);
        removeMentions = plugin.getConfig().getBoolean("webhooks." + name + ".remove_mentions", true);
        captureSystemStreams = plugin.getConfig().getBoolean("webhooks." + name + ".capture_system_streams", true);
        enableLogs = plugin.getConfig().getBoolean("webhooks." + name + ".enable_logs", false);
        serverName = plugin.getConfig().getString("webhooks." + name + ".server_name", "Server");

        sendChat = plugin.getConfig().getBoolean("webhooks." + name + ".send_chat", true);
        sendPlayerCommands = plugin.getConfig().getBoolean("webhooks." + name + ".send_player_commands", true);
        sendConsoleCommands = plugin.getConfig().getBoolean("webhooks." + name + ".send_console_commands", true);
        sendUuidPrelogin = plugin.getConfig().getBoolean("webhooks." + name + ".send_uuid_prelogin", true);
        sendJoinQuit = plugin.getConfig().getBoolean("webhooks." + name + ".send_join_quit", true);
        sendDeaths = plugin.getConfig().getBoolean("webhooks." + name + ".send_deaths", true);
        sendGamemodeChanges = plugin.getConfig().getBoolean("webhooks." + name + ".send_gamemode_changes", true);
        sendServerLoad = plugin.getConfig().getBoolean("webhooks." + name + ".send_server_load", true);

        // Embeds
        embedsStartStopEnabled = plugin.getConfig().getBoolean("webhooks." + name + ".embeds_start_stop_enabled", true);
        embedsJoinQuitEnabled = plugin.getConfig().getBoolean("webhooks." + name + ".embeds_join_quit_enabled", true);
        embedsDeathsEnabled = plugin.getConfig().getBoolean("webhooks." + name + ".embeds_deaths_enabled", true);
        embedsWatchdogEnabled = plugin.getConfig().getBoolean("webhooks." + name + ".embeds_watchdog_enabled", true);

        // Watchdog
        watchdogEnabled = plugin.getConfig().getBoolean("webhooks." + name + ".watchdog_enabled", true);
        watchdogTimeoutMs = plugin.getConfig().getLong("webhooks." + name + ".watchdog_timeout_ms", 10000L);
        watchdogCheckIntervalMs = plugin.getConfig().getLong("webhooks." + name + ".watchdog_check_interval_ms", 2000L);

        // Didn't fail to load
        failedToLoadConfig = false;
    }

    // Set plugin
    public static void setPlugin(Monitor plugin) {
        ConfigLoader.plugin = plugin;
    }
}
