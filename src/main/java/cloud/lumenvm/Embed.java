package cloud.lumenvm;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Map;

public class Embed {
    // Plugin
    private static Monitor plugin;

    // Embeds directory
    File embedDirectory;

    public String embed;

    // Gson
    private static final Gson gson = new Gson();

    public Embed(String name) {
        // Sets embeds directory (create if it doesn't exist)
        embedDirectory = new File(plugin.getDataFolder(), "embeds/");
        if (!embedDirectory.isDirectory()) embedDirectory.mkdir();

        // Load JSON
        embed = readJson("embeds/" + name + ".json", "embeds/" + name + ".json");



    }

    private String readJson(String fileName, String child) {
        // JSON file
        File file = new File(plugin.getDataFolder(), child);
        String json = null;

        // Create if it doesn't exist
        if (!file.exists()) {
            try {
                InputStream stream;
                if (fileName.equals("quit") || fileName.equals("join") || fileName.equals("start") || fileName.equals("stop") || fileName.equals("death") || fileName.equals("watchdog")) {
                    stream = plugin.getResource(fileName);
                } else {
                    stream = plugin.getResource("embeds/template.json");
                }
                assert stream != null;
                FileUtils.copyInputStreamToFile(stream, file);
            } catch (IOException e) {
                plugin.getLogger().severe("Unable to create embed templates: " + e);
            }
        }

        // Read and return JSON
        try {
            json = Files.readString(file.toPath());
        } catch (IOException e) {
            plugin.getLogger().severe("Error when reading embeds: " + e);
        }
        assert json != null;
        JsonElement element = JsonParser.parseString(json);
        return gson.toJson(element);
    }

    // Set Plugin
    public static void setPlugin(Monitor plugin) {
        Embed.plugin = plugin;
    }
}
