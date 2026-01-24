package cloud.lumenvm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import me.clip.placeholderapi.PlaceholderAPI;
import org.apache.commons.io.FileUtils;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.TimeZone;

public class EmbedLoader {
    private static Monitor plugin;
    File embedDirectory;
    public String start;
    private static final Gson gson = new Gson();

    EmbedLoader() {
        embedDirectory = new File(plugin.getDataFolder(), "embeds/");
        File startJsonFile = new File(plugin.getDataFolder(), "embeds/start.json");
        String startJson = null;
        if (!embedDirectory.isDirectory()) embedDirectory.mkdir();

        try {
            InputStream stream = plugin.getResource("start.json");
            assert stream != null;
            FileUtils.copyInputStreamToFile(stream, startJsonFile);
        } catch (IOException e){
            plugin.getLogger().severe("Unable to create embed templates: " + e);
        }

        try {
            startJson = Files.readString(startJsonFile.toPath());
        } catch (IOException e) {
            plugin.getLogger().severe("Error when reading embeds: " + e);
        }

        assert startJson != null;
        JsonElement element = JsonParser.parseString(startJson);
        start = gson.toJson(element);

        OfflinePlayer player = plugin.getServer().getPlayer("snvk_dev");

        start = PlaceholderAPI.setPlaceholders(player, start);
        start = start.replace("%lumenmc_tz%", TimeZone.getDefault().getID());
    }

    public static void setPlugin(Monitor plugin) {
        EmbedLoader.plugin = plugin;
    }
}
