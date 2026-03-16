package cloud.lumenvm.monitor;

import cloud.lumenvm.api.AddonContext;
import cloud.lumenvm.api.MonitorAPI;
import cloud.lumenvm.api.MonitorAddon;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

// Managing addons
public class AddonManager {

    // API
    private final MonitorAPI api;

    // Addons
    private final List<MonitorAddon> loadedAddons = new ArrayList<>();

    // Plugin instance
    private final Monitor plugin;

    // Addon manager instance
    public AddonManager(Monitor plugin) {
        this.api = new MonitorAPI(plugin, plugin.embeds);
        this.plugin = plugin;

        File addonsDir = new File(plugin.getDataFolder(), "addons");
        if (!addonsDir.exists()) addonsDir.mkdirs();

        for (File jar : Objects.requireNonNull(addonsDir.listFiles(f -> f.getName().endsWith(".jar")))) {
            loadAddon(jar);
        }
    }

    // Loads addon and triggers onLoad function inside the addon
    private void loadAddon(File file) {
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{file.toURI().toURL()},
                    plugin.getClass().getClassLoader()
            );

            ServiceLoader<MonitorAddon> serviceLoader =
                    ServiceLoader.load(MonitorAddon.class, loader);

            for (MonitorAddon addon : serviceLoader) {
                File addonFolder = new File(plugin.getDataFolder(), file.getName().replace(".jar", ""));
                AddonContext context = new AddonContext(addonFolder, file);
                addon.onLoad(api, context);
                loadedAddons.add(addon);
                plugin.getServer().getConsoleSender().sendMessage("§aLoaded addon: §r" + addon.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().severe(e.toString());
        }
    }

    // Triggers unLoad function inside all of the addons
    public void unLoad() {
        for (MonitorAddon addon : loadedAddons) {
            try {
                addon.onUnload();
            } catch (Exception e) {
                plugin.getLogger().severe(e.toString());
            }
        }
        loadedAddons.clear();
    }

}
