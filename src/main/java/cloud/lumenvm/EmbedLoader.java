package cloud.lumenvm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

public class EmbedLoader {
    private static Monitor plugin;
    File embedDirectory;
    public String start;
    public String stop;
    public String join;
    public String quit;
    public String death;
    public String watchdog;
    private static final Gson gson = new Gson();

    EmbedLoader() {
        embedDirectory = new File(plugin.getDataFolder(), "embeds/");
        if (!embedDirectory.isDirectory()) embedDirectory.mkdir();

        start = readJson("embeds/start.json", "embeds/start.json");
        stop = readJson("embeds/stop.json", "embeds/stop.json");
        join = readJson("embeds/join.json", "embeds/join.json");
        quit = readJson("embeds/quit.json", "embeds/quit.json");
        death = readJson("embeds/death.json", "embeds/death.json");
        watchdog = readJson("embeds/watchdog.json", "embeds/watchdog.json");
    }

    private String readJson(String fileName, String child) {
        File file = new File(plugin.getDataFolder(), child);
        String json = null;
        if (!file.exists()) {
            try {
                InputStream stream = plugin.getResource(fileName);
                assert stream != null;
                FileUtils.copyInputStreamToFile(stream, file);
            } catch (IOException e) {
                plugin.getLogger().severe("Unable to create embed templates: " + e);
            }
        }
        try {
            json = Files.readString(file.toPath());
        } catch (IOException e) {
            plugin.getLogger().severe("Error when reading embeds: " + e);
        }
        assert json != null;
        JsonElement element = JsonParser.parseString(json);
        return gson.toJson(element);
    }

    public static void setPlugin(Monitor plugin) {
        EmbedLoader.plugin = plugin;
    }
}
