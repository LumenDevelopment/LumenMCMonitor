package cloud.lumenvm;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.TimeZone;

public class PapiExpansion extends PlaceholderExpansion {
    private final Monitor plugin;

    public PapiExpansion(Monitor plugin) {
        this.plugin = plugin;
    }
    @Override
    public @NotNull String getIdentifier() {
        return "lumenmc";
    }

    @Override
    public @NotNull String getAuthor() {
        return String.join(", ", plugin.getDescription().getAuthors());
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0";
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (params.equalsIgnoreCase("timestamp")) {
            return Instant.now().toString();
        }
        if (params.equalsIgnoreCase("tz")) {
            return TimeZone.getDefault().getID();
        }
        if (params.equalsIgnoreCase("plugins")) {
            return String.valueOf(plugin.getServer().getPluginManager().getPlugins().length);
        }
        if (params.equalsIgnoreCase("version")) {
            return plugin.getDescription().getVersion();
        }
        if (params.equalsIgnoreCase("username")) {
            return plugin.getConfig().getString("username", "LumenMC");
        }
        if (params.equalsIgnoreCase("avatar")) {
            return plugin.getConfig().getString("avatar_url", "https://cdn.lumenvm.cloud/lumenmc_monitor.png");
        }
        if (params.equalsIgnoreCase("serverver")) {
            return plugin.getConfig().getString("server_name", "Server");
        }
        return null;
    }
}
