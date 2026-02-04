package cloud.lumenvm.monitor;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.UUID;

public class UserConfigLoader extends ConfigLoader{

    private static Monitor plugin;
    YamlConfiguration config;
    UUID playerUUID;

    public UserConfigLoader(UUID playerUUID) {
        super("user", true);

        this.playerUUID = playerUUID;
        this.name = plugin.getServer().getOfflinePlayer(playerUUID).getName();

        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        this.config = YamlConfiguration.loadConfiguration(userdata);

        this.url = config.getString("url");
        this.sendServerLoad = config.getBoolean("send_server_start_stop");
        this.embedsStartStopEnabled = config.getBoolean("embeds_start_stop_enabled");
        this.ignorePatterns = config.getStringList("ignore_patterns");
        this.removeMentions = config.getBoolean("remove_mentions");

        this.failedToLoadConfig = false;
    }

    public static void setPlugin(Monitor plugin) {
        UserConfigLoader.plugin = plugin;
    }
}
