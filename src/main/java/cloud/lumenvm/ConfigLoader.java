package cloud.lumenvm;

import java.util.List;
import java.util.logging.Level;

public class ConfigLoader {
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
    public String name;

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

    // Watchdog
    public volatile long lastTickNanos = System.nanoTime();
    public boolean watchdogEnabled;
    public long watchdogTimeoutMs;
    public long watchdogCheckIntervalMs;
    public volatile boolean watchdogAlerted = false;

    // Pterodactyl variables
    public final String WEBHOOK_URL = System.getenv("WEBHOOK_URL");
    public final String P_SERVER_LOCATION = System.getenv("P_SERVER_LOCATION");
    public final String P_SERVER_UUID = System.getenv("P_SERVER_UUID");
    public final String TZ = System.getenv("TZ");
    public final String SERVER_IP = System.getenv("SERVER_IP");
    public final String SERVER_PORT = System.getenv("SERVER_PORT");

    public ConfigLoader(Monitor plugin, String name) {
        debug = plugin.getConfig().getBoolean("debug", false);
        this.name = name;
        url = plugin.getConfig().getString("webhooks." + name + ".url", "https://discord.com/api/webhooks/XXXXXXXXXXX/XXXXXXXXXXX");

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

        minLevel = plugin.parseLevel(plugin.getConfig().getString("min_level", "INFO"));
        ignorePatterns = plugin.getConfig().getStringList("ignore_patterns");
        includeStackTraces = plugin.getConfig().getBoolean("include_stack_traces", true);
        batchIntervalMs = plugin.getConfig().getInt("batch_interval_ms", 2000);
        maxBatchSize = plugin.getConfig().getInt("max_batch_size", 50);
        maxMessageLength = plugin.getConfig().getInt("max_message_length", 1900);
        removeMentions = plugin.getConfig().getBoolean("remove_mentions", true);
        captureSystemStreams = plugin.getConfig().getBoolean("capture_system_streams", true);
        plugin.setLocale(plugin.getConfig().getString("locale", "en_US"));
        enableLogs = plugin.getConfig().getBoolean("enable_logs", false);

        sendChat = plugin.getConfig().getBoolean("send_chat", true);
        sendPlayerCommands = plugin.getConfig().getBoolean("send_player_commands", true);
        sendConsoleCommands = plugin.getConfig().getBoolean("send_console_commands", true);
        sendUuidPrelogin = plugin.getConfig().getBoolean("send_uuid_prelogin", true);
        sendJoinQuit = plugin.getConfig().getBoolean("send_join_quit", true);
        sendDeaths = plugin.getConfig().getBoolean("send_deaths", true);
        sendGamemodeChanges = plugin.getConfig().getBoolean("send_gamemode_changes", true);
        sendServerLoad = plugin.getConfig().getBoolean("send_server_load", true);

        embedsStartStopEnabled = plugin.getConfig().getBoolean("embeds_start_stop_enabled", true);

        watchdogEnabled = plugin.getConfig().getBoolean("watchdog_enabled", true);
        watchdogTimeoutMs = plugin.getConfig().getLong("watchdog_timeout_ms", 10000L);
        watchdogCheckIntervalMs = plugin.getConfig().getLong("watchdog_check_interval_ms", 2000L);
        failedToLoadConfig = false;
    }
}
