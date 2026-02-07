package cloud.lumenvm.monitor;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.UUID;

public class UserConfigLoader extends ConfigLoader{

    private static Monitor plugin;
    YamlConfiguration config;
    UUID playerUUID;

    public UserConfigLoader(String section, UUID playerUUID) {
        super(null, true);
        this.playerUUID = playerUUID;
        this.name = plugin.getServer().getOfflinePlayer(playerUUID).getName() + "_" + section;

        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        this.config = YamlConfiguration.loadConfiguration(userdata);

        this.url = config.getString(section + ".url");
        this.sendServerLoad = config.getBoolean(section + ".send_server_start_stop");
        this.embedsStartStopEnabled = config.getBoolean(section + ".embeds_start_stop_enabled");
        this.ignorePatterns = config.getStringList(section + ".ignore_patterns");
        this.removeMentions = config.getBoolean(section + ".remove_mentions");

        this.failedToLoadConfig = false;
    }

    public static void setPlugin(Monitor plugin) {
        UserConfigLoader.plugin = plugin;
    }
}
