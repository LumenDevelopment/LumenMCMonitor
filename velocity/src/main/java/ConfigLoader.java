import com.velocitypowered.api.proxy.ProxyServer;

import java.util.List;
import java.util.logging.Level;

public class ConfigLoader {
    private static ProxyServer server;

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

    }

    public static void setServer(ProxyServer server) {
        ConfigLoader.server = server;
    }
}
