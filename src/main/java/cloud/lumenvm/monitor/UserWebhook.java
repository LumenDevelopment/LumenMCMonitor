package cloud.lumenvm.monitor;

import org.apache.commons.io.FileUtils;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.List;
import java.util.UUID;

public class UserWebhook extends Webhook{

    private static Monitor plugin;

    static File userdataDirectory;
    File userdata;

    UUID playerUUID;

    UserWebhook(UUID playerUUID) {
        super(null, true, playerUUID);

        this.playerUUID = playerUUID;

        if (userdataDirectory == null) {
            userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        }
        userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
    }

    public static void addUserWebhook(UUID playerUUID, String url) {
        if (userdataDirectory == null) {
            userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        }

        if (!userdataDirectory.exists()) userdataDirectory.mkdir();

        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        if (!userdata.exists()) {
            try {
                InputStream stream = plugin.getResource("webhookUserTemplate.yml");
                assert stream != null;
                FileUtils.copyInputStreamToFile(stream, userdata);
            } catch (IOException e) {
                plugin.getLogger().severe("Unable to create userdata file: " + e);
            }
        }

        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(userdata);

        userConfig.set("url", url);
        try {
            userConfig.save(userdata);
        } catch (IOException e) {
            plugin.getLogger().severe("Error when saving user webhook config: " + e);
        }

        List<String> userConfigs = plugin.getConfig().getStringList("user_configs");
        userConfigs.add(playerUUID.toString());
        plugin.getConfig().set("user_configs", userConfigs);
        plugin.saveConfig();
        plugin.pluginReload();
    }

    public static void removeUserWebhook(UUID playerUUID) {
        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
        userdata.delete();
        List<String> userConfigs = plugin.getConfig().getStringList("user_configs");
        userConfigs.remove(playerUUID.toString());
        plugin.getConfig().set("user_configs", userConfigs);
        plugin.saveConfig();
        plugin.pluginReload();
    }

    public static void setPlugin(Monitor plugin) {
        UserWebhook.plugin = plugin;
    }
}
