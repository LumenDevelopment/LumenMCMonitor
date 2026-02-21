package cloud.lumenvm.api;

/**
 * Used to create addons for LumenMC Monitor.
 */
public interface MonitorAddon {

    /**@return name of the addon*/
    String getName();

    /**
     * Triggers when the addon loads. You can set api, plugin here.
     * You also <strong>must register a Bukkit event</strong> for the addon to work.
     * @param api the {@link MonitorAPI}
     * @param context the {@link AddonContext}
     */
    void onLoad(MonitorAPI api, AddonContext context);

    /**
     * Triggers when the addon unloads.
     */
    void onUnload();

}
