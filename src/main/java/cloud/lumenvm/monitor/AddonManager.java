package cloud.lumenvm.monitor;

import cloud.lumenvm.api.MonitorAPI;
import cloud.lumenvm.api.MonitorAddon;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public class AddonManager {

    private final MonitorAPI api;
    private final List<MonitorAddon> loadedAddons = new ArrayList<>();
    private final Monitor plugin;

    public AddonManager(Monitor plugin) {
        this.api = new MonitorAPI(plugin, plugin.embeds);
        this.plugin = plugin;

        File addonsDir = new File(plugin.getDataFolder(), "addons");
        if (!addonsDir.exists()) addonsDir.mkdirs();

        for (File jar : Objects.requireNonNull(addonsDir.listFiles(f -> f.getName().endsWith(".jar")))) {
            loadAddon(jar);
        }
    }

    private void loadAddon(File file) {
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[]{file.toURI().toURL()},
                    this.getClass().getClassLoader()
            );

            ServiceLoader<MonitorAddon> serviceLoader =
                    ServiceLoader.load(MonitorAddon.class, loader);

            for (MonitorAddon addon : serviceLoader) {
                addon.onLoad(api);
                loadedAddons.add(addon);
                plugin.getServer().getConsoleSender().sendMessage("§aLoaded addon: §r" + addon.getName());
            }
        } catch (Exception e) {
            plugin.getLogger().severe(e.toString());
        }
    }

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
