import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Plugin(
        id = "lumenmc-monitor",
        name = "LumenMCMonitor",
        version = "b1.1",
        authors = {"Lumen Development", "SNVK"}
)
public class Monitor {

    // Http client
    private static HttpClient httpClient;

    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDirectory;
    private static File configFile;
    private String locale;
    Yaml yaml = new Yaml();
    Map<String, Object> configMap;

    @Inject
    public Monitor(Logger logger, ProxyServer server, @DataDirectory @NonNull Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.server = server;

        // Set http client
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Webhook.httpClient = httpClient;

        Webhook.setServer(server);
        ConfigLoader.setServer(server);

        configFile = new File(dataDirectory.toFile(), "config.yml");

        if (!configFile.exists()) {
            extractConfig();
        }

    }

    @Inject
    public void onProxyInitialization(ProxyInitializeEvent event) {
    }

    private void extractConfig() {
        // Extract config
        try {
            InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"));
            Map<String, Object> yamlMap = yaml.load(stream);
            // Print the map
            FileUtils.copyInputStreamToFile(stream, configFile);
            Map<String, Object> flatMap = new LinkedHashMap<>();
            flatten("", yamlMap, flatMap);
            flatMap.forEach((k, v) -> logger.warn("{} = {}", k, v));
        } catch (IOException e) {
            logger.error("Unable to extract config file: {}", String.valueOf(e));
        }
    }

    private static void flatten(String prefix, Object value, Map<String, Object> result) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = entry.getKey().toString();
                String newPrefix = prefix.isEmpty() ? key : prefix + "." + key;
                flatten(newPrefix, entry.getValue(), result);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), result);
            }
        } else {
            result.put(prefix, value);
        }
    }
}
