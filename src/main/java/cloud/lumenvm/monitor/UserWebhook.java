package cloud.lumenvm.monitor;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.util.*;

// Working with user webhooks
public class UserWebhook extends Webhook{

    // Plugin instance
    private static Monitor plugin;

    // /userdata in plugin folder
    static File userdataDirectory;

    // YAML file inside userdata folder (playerUUID.yml)
    File userdata;

    // Player UUID
    UUID playerUUID;

    // Counting user webhooks (used for limits)
    public static Map<UUID, Integer> userWebhookCount = new HashMap<>();

    UserWebhook(String name, UUID playerUUID) {
        // Create new webhook
        super(name, true, playerUUID);

        // Assign playerUUID
        this.playerUUID = playerUUID;

        // Add to counter
        userWebhookCount.putIfAbsent(playerUUID, 0);
        userWebhookCount.put(playerUUID, userWebhookCount.get(playerUUID) + 1);

        // Assign userdata directory
        if (userdataDirectory == null) {
            userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        }

        // Assign userdata config file
        userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
    }

    // Adds user webhook
    public static String addUserWebhook(String[] args, UUID playerUUID, String name, String url) {
        // Check and assign userdata directory
        if (userdataDirectory == null) {
            userdataDirectory = new File(plugin.getDataFolder(), "userdata/");
        }

        // Create userdata directory
        if (!userdataDirectory.exists()) userdataDirectory.mkdir();

        // Create and add webhook config
        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");
        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(userdata);

        InputStreamReader stream = new InputStreamReader(Objects.requireNonNull(plugin.getResource("webhookUserTemplate.yml")));
        YamlConfiguration template = YamlConfiguration.loadConfiguration(stream);

        // Check for existing webhooks
        for (String key : userConfig.getKeys(false)) {
            if (key.equalsIgnoreCase(args[1])) {
                return "§cThat webhook already exists!";
            }
        }

        // Set values in user config
        userConfig.set(name, template);
        userConfig.set(name + ".url", url);

        try {
            // Save to file
            userConfig.save(userdata);
        } catch (IOException e) {
            plugin.getLogger().severe("Error when saving userdata: " + e);
        }

        // Adds playerUUID to main config.yml to load when server starts
        List<String> userConfigs = plugin.getConfig().getStringList("user_configs");
        if (!userConfigs.contains(playerUUID.toString())) {
            userConfigs.add(playerUUID.toString());
            plugin.getConfig().set("user_configs", userConfigs);
        }
        plugin.saveConfig();
        plugin.pluginReload();
        return "§aAdded webhook: §r" + args[1] + " §awith url: §r" + args[2];
    }

    // Removes user webhook
    public static String removeUserWebhook(String[] args, UUID playerUUID, String name) {
        // Assign userdata YAML file
        File userdata = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        // User config
        YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(userdata);

        // Delete webhook from user config
        userConfig.set(name, null);

        try {
            // Save to file
            userConfig.save(userdata);
        } catch (IOException e) {
            plugin.getLogger().warning("Error when removing user webhook: " + e);
        }

        // Remove UUID from main config.yml if it was the last webhook
        List<String> userConfigs = plugin.getConfig().getStringList("user_configs");
        if (userConfigs.contains(playerUUID.toString()) && userWebhookCount.get(playerUUID) == 1) {
            userConfigs.remove(playerUUID.toString());
            plugin.getConfig().set("user_configs", userConfigs);
            userdata.delete();
        }
        plugin.saveConfig();
        plugin.pluginReload();
        return "§aRemoved webhook: §r" + args[1];
    }

    public static void setPlugin(Monitor plugin) {
        UserWebhook.plugin = plugin;
    }

    // Used primarily by addons for getting player's enabled webhooks
    public static List<String> getEnabledUserWebhooks(UUID playerUUID) {
        File playerConfigFile = new File(plugin.getDataFolder(), "userdata/" + playerUUID + ".yml");

        YamlConfiguration playerConfig = YamlConfiguration.loadConfiguration(playerConfigFile);
        return playerConfig.getKeys(false).stream().toList();
    }
}
