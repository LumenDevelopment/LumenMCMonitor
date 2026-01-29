import com.velocitypowered.api.proxy.ProxyServer;

import java.net.http.HttpClient;

public class Webhook {

    // Http client
    public static HttpClient httpClient;

    // Server
    private static ProxyServer server;

    public Webhook(String name) {

    }

    public static void setServer(ProxyServer server) {
        Webhook.server = server;
    }
}
