package cloud.lumenvm.api;

/**
 * This interface is used to create addons for LumenMC Monitor.
 */
public interface MonitorAddon {

    /**@return name of the addon*/
    String getName();

    /**
     * This will trigger when the addon loads. You can set api, plugin here.
     * You also <strong>must register a Bukkit event</strong> for the addon to work.
     * @param api the {@link MonitorAPI}
     */
    void onLoad(MonitorAPI api, Context context);

    /**
     * This will trigger when the addon unloads.
     */
    void onUnload();

}
