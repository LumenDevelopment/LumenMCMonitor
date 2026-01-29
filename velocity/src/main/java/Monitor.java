import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Objects;

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

    @Inject
    public Monitor(Logger logger, ProxyServer server, @DataDirectory Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.server = server;

        // Set http client
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Webhook.httpClient = httpClient;
    }

    @Inject
    public void onProxyInitialization(ProxyInitializeEvent event) {
        configFile = new File(dataDirectory.toFile(), "config.yml");

        // Extract config
        try {
            InputStream stream = Objects.requireNonNull(getClass().getResourceAsStream("/config.yml"));
            FileUtils.copyInputStreamToFile(stream, configFile);
        } catch (IOException e) {
            logger.error("Unable to create custom language file: {}", String.valueOf(e));
        }
    }
}
