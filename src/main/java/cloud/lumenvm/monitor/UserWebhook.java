package cloud.lumenvm.monitor;

import org.apache.commons.io.FileUtils;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.Objects;
import java.util.UUID;

public class UserWebhook extends Webhook{

    private static Monitor plugin;

    File userdataDirectory;
    File userdata;

    UserWebhook(UUID playerUUID, String url) {
        super(null, true, playerUUID);

        userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        if (url != null) {
            addUserWebhook(url);
        }
    }

    public void addUserWebhook(String url) {
        if (!userdataDirectory.exists()) userdataDirectory.mkdir();

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
    }

    public static void setPlugin(Monitor plugin) {
        UserWebhook.plugin = plugin;
    }
}
