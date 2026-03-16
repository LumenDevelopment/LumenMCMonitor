package cloud.lumenvm.monitor;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

// This class extends the normal ConfigLoader
public class UserConfigLoader extends ConfigLoader {

    // Plugin instance
    private static Monitor plugin;

    // User config
    YamlConfiguration config;

    // Player's UUID
    UUID playerUUID;

    public UserConfigLoader(String section, UUID playerUUID) {
        // Set things up
        super(null, true);
        this.playerUUID = playerUUID;
        this.name = section + "_" + playerUUID;

        // Get user config file (file is generated when player creates their first webhook and deleted with the removal of theirs last webhook)
        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        // Config loading
        this.config = YamlConfiguration.loadConfiguration(userdata);

        this.url = config.getString(section + ".url", "https://discord.com/api/webhooks/XXXXXXXXXXX/XXXXXXXXXXX");
        this.sendServerLoad = config.getBoolean(section + ".send_server_start_stop", true);
        this.embedsStartStopEnabled = config.getBoolean(section + ".embeds_start_stop_enabled", true);
        this.ignorePatterns = config.getStringList(section + ".ignore_patterns");
        this.removeMentions = config.getBoolean(section + ".remove_mentions", true);

        this.failedToLoadConfig = false;
    }

    public static void setPlugin(Monitor plugin) {
        UserConfigLoader.plugin = plugin;
    }
}
