package cloud.lumenvm.api;

import cloud.lumenvm.Embed;
import cloud.lumenvm.Monitor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public class MonitorAPI {
    private final Monitor plugin;
    private final Map<String, Embed> embeds;
    private final Embed embed;

    public MonitorAPI(Monitor plugin, Map<String, Embed> embeds, Embed embed) {
        this.plugin = plugin;
        this.embeds = embeds;
        this.embed = embed;
    }

    public void fireContent(String content) {
        plugin.fireContent(content);
    }

    public void fireEmbed(String embedJson) { plugin.fireEmbed(embedJson); }

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public Map<String, Embed> getEmbeds() {
        return embeds;
    }

    public Embed getEmbed() {
        return embed;
    }
}
