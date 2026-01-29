package cloud.lumenvm;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.net.http.HttpClient;

public class Webhook {

    public final ConfigLoader confLoader;

    // Http client
    public static HttpClient httpClient;

    // Server
    private static ProxyServer server;
    private static Logger logger;

    public Webhook(String name, Logger logger) {
        this.confLoader = new ConfigLoader(name);
        logger.warn(name);
    }

    public static void setServer(ProxyServer server) {
        Webhook.server = server;
    }
}
