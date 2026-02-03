package cloud.lumenvm.api;

import cloud.lumenvm.Embed;
import cloud.lumenvm.Monitor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**This class is used to communicate between the addon and the main plugin.*/
public class MonitorAPI {

    /**Monitor plugin.*/
    private final Monitor plugin;

    /**Available embeds.*/
    private final Map<String, Embed> embeds;


    public MonitorAPI(Monitor plugin, Map<String, Embed> embeds) {
        this.plugin = plugin;
        this.embeds = embeds;
    }

    /**Send {@link java.lang.String} messages*/
    public void fireContent(String content) {
        plugin.fireContent(content);
    }

    /**Send embeds. To load them use {@link #getEmbeds() getEmbeds().get("my_embed")} (a JSON embed has to be in the embeds folder).*/
    public void fireEmbed(String embedJson) {
        plugin.fireEmbed(embedJson);
    }

    /**@return the LumenMC Monitor plugin*/
    public JavaPlugin getPlugin() {
        return plugin;
    }

    /**@return the available embeds*/
    public Map<String, Embed> getEmbeds() {
        return embeds;
    }
}
