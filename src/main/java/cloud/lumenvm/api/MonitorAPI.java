package cloud.lumenvm.api;

import cloud.lumenvm.monitor.Embed;
import cloud.lumenvm.monitor.Monitor;
import cloud.lumenvm.monitor.Webhook;

import java.util.Map;

/**This class is used to communicate between the addon and the main plugin.*/
public class MonitorAPI {

    /**Monitor plugin.*/
    private final Monitor plugin;

    /**Available embeds.*/
    private final Map<String, Embed> embeds;

    /***
     * @param plugin main plugin
     * @param embeds available embeds
     */
    public MonitorAPI(Monitor plugin, Map<String, Embed> embeds) {
        this.plugin = plugin;
        this.embeds = embeds;
    }

    /**Send messages
     * @param content {@link java.lang.String} content
     */
    public void fireContent(String content, Webhook webhook) {
        plugin.fireContent(content, webhook);
    }

    /**Send embeds. To load them use {@link #getEmbeds() getEmbeds().get("my_embed")} (a JSON embed has to be in the embeds folder).
     * @param embedJson the embed JSON
     */
    public void fireEmbed(String embedJson, Webhook webhook) {
        plugin.fireEmbed(embedJson, webhook);
    }

    /**@return the LumenMC Monitor plugin*/
    public Monitor getPlugin() {
        return plugin;
    }

    /**@return the available embeds*/
    public Map<String, Embed> getEmbeds() {
        return embeds;
    }

    public void registerCommand(AddonCommand addonCommand) {
        plugin.commandRegistry.register(addonCommand);
    }
}
