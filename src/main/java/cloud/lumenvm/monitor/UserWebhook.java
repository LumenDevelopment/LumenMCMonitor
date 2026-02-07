package cloud.lumenvm.monitor;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.*;

public class UserWebhook extends Webhook{

    private static Monitor plugin;

    static File userdataDirectory;
    File userdata;

    UUID playerUUID;

    public static Map<UUID, Integer> userWebhookCount;

    UserWebhook(String name, UUID playerUUID) {
        super(name, true, playerUUID);

        userWebhookCount = new HashMap<>();
        userWebhookCount.putIfAbsent(playerUUID, 0);
        userWebhookCount.put(playerUUID, userWebhookCount.get(playerUUID) + 1);

        this.playerUUID = playerUUID;

        if (userdataDirectory == null) {
            userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        }
        userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
    }

    public static void addUserWebhook(UUID playerUUID, String name, String url) {
        if (userdataDirectory == null) {
            userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        }

        if (!userdataDirectory.exists()) userdataDirectory.mkdir();

        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(userdata);

        InputStreamReader stream = new InputStreamReader(Objects.requireNonNull(plugin.getResource("webhookUserTemplate.yml")));
        YamlConfiguration template = YamlConfiguration.loadConfiguration(stream);

        userConfig.set(name, template);
        userConfig.set(name + ".url", url);

        try {
            userConfig.save(userdata);
        } catch (IOException e) {
            plugin.getLogger().severe("Error when saving userdata: " + e);
        }

        List<String> userConfigs = plugin.getConfig().getStringList("user_configs");
        if (!userConfigs.contains(playerUUID.toString())) {
            userConfigs.add(playerUUID.toString());
            plugin.getConfig().set("user_configs", userConfigs);
        }
        plugin.saveConfig();
        plugin.pluginReload();
    }

    public static void removeUserWebhook(UUID playerUUID, String name) {
        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
        YamlConfiguration webhookConfig = YamlConfiguration.loadConfiguration(userdata);

        webhookConfig.set(name, null);

        try {
            webhookConfig.save(userdata);
        } catch (IOException e) {
            plugin.getLogger().warning("Error when removing user webhook: " + e);
        }

        List<String> userConfigs = plugin.getConfig().getStringList("user_configs");
        if (userConfigs.contains(playerUUID.toString()) && userWebhookCount.get(playerUUID) == 1) {
            userConfigs.remove(playerUUID.toString());
            plugin.getConfig().set("user_configs", userConfigs);
            userdata.delete();
        }
        plugin.saveConfig();
        plugin.pluginReload();
    }

    public static void setPlugin(Monitor plugin) {
        UserWebhook.plugin = plugin;
    }
}
