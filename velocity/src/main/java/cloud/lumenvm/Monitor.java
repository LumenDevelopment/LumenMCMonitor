package cloud.lumenvm;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.apache.commons.io.FileUtils;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.http.HttpClient;
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
    private final Metrics.Factory metricsFactory;
    private static File configFile;
    private String locale;
    public boolean debug;
    public List<Webhook> webhooks;
    Yaml yaml = new Yaml();
    Map<String, Object> configMap = new LinkedHashMap<>();

    @Inject
    public Monitor(Logger logger, ProxyServer server, @DataDirectory @NonNull Path dataDirectory, Metrics.Factory metricsFactory) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.server = server;
        this.metricsFactory = metricsFactory;

        // Set http client
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Webhook.httpClient = httpClient;

        Webhook.setServer(server);
        ConfigLoader.setServer(server);

        configFile = new File(dataDirectory.toFile(), "config.yml");

        if (!configFile.exists()) {
            extractConfig();
        } else {
            InputStream stream = this.getClass().getClassLoader().getResourceAsStream("config.yml");
            Map<String, Object> yamlMap = yaml.load(stream);
            flatten("", yamlMap, configMap);
        }

        locale = configMap.get("locale").toString();

        debug = Boolean.parseBoolean(configMap.get("debug").toString());

        webhooks = new ArrayList<>();
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        metrics(this);

        logger.warn(configMap.toString());

        // Webhooks
        List<String> webhookNames = configMap.keySet().stream()
                .filter(k -> k.startsWith("webhooks."))
                .map(k -> k.split("\\.")[1])
                .distinct()
                .toList();

        for (String name : webhookNames) {
                webhooks.add(new Webhook(name, logger));
            }
    }

    private void extractConfig() {
        // Extract config
        try {
            InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"));
            Map<String, Object> yamlMap = yaml.load(stream);
            FileUtils.copyInputStreamToFile(stream, configFile);
            flatten("", yamlMap, configMap);
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

    private void metrics(Object plugin) {
        metricsFactory.make(plugin, 29156);
    }
}
