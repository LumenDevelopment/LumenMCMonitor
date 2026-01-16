package cloud.lumenvm;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.bukkit.Bukkit;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class Webhook {

    private static Monitor plugin;
    private static ConfigLoader confLoader;
    public static HttpClient httpClient;
    private final URI webhookUri;
    private final AtomicBoolean drainLock = new AtomicBoolean(false);
    private final Gson gson = new Gson();

    private int taskId = -1;

    public final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();

    Webhook(String url) {
        this.webhookUri = URI.create(url);
        int ticks = msToTicks(confLoader.batchIntervalMs);
        plugin.getLogger().info("New task: " + taskId);
        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, this::drainAndSend, ticks, ticks)
                .getTaskId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::drainAndSend);

        if (confLoader.debug) plugin.getLogger().info("Debug: Sending activated (interval " + confLoader.batchIntervalMs + " ms / " + ticks + " ticks).");
    }

    public void enqueueIfAllowed(String content) {
        if (confLoader.removeMentions) {
            content = content.replace("@everyone", "＠everyone").replace("@here", "＠here");
        }
        if (!shouldIgnore(content)) {
            for (String chunk : splitMessage(content, confLoader.maxMessageLength)) {
                queue.offer(chunk);
            }
        }
    }

    public void drainAndSend() {

        // Anti-double-drain lock
        if (!drainLock.compareAndSet(false, true)) {
            return;
        }
        try {
            List<String> batch = new ArrayList<>(confLoader.maxBatchSize);
            while (batch.size() < confLoader.maxBatchSize) {
                String item = queue.poll();
                if (item == null) break;
                batch.add(item);
            }
            if (batch.isEmpty()) return;

            String combined = String.join("\n", batch);
            List<String> payloads = splitMessage(combined, confLoader.maxMessageLength);

            if (confLoader.debug) plugin.getLogger().info("Debug: Sending batch: " + batch.size() + " messages, payloads: " + payloads.size());

            for (String content : payloads) {
                sendContent(content, webhookUri);
                try { Thread.sleep(250); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        } finally {
            drainLock.set(false);
        }
    }

    private void sendContent(String content, URI webhookUri) {
        try {
            WebhookContentPayload payload = new WebhookContentPayload(content);
            payload.username = "LumenMC";
            payload.avatar_url = "https://cdn.lumenvm.cloud/logo.png";
            String json = gson.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (confLoader.debug) {
                plugin.getLogger().info("Debug: Webhook HTTP " + status +
                        (response.body() != null ? (" body: " + response.body()) : ""));
            }

            if (status < 200 || status >= 300) {
                plugin.getLogger().warning("Discord webhook returned HTTP " + status + ": " + response.body());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error when sending to webhook", e);
        }
    }

    public void sendEmbed(String title, String description, int color) {
        try {
            Embed embed = new Embed();
            embed.title = title;
            embed.description = description;
            embed.color = color;
            embed.timestamp = Instant.now().toString();
            embed.footer = new Footer("LumenMC Monitor " + plugin.getDescription().getVersion() + " | " + LocalDateTime.now());
            embed.image = new Image("https://cdn.lumenvm.cloud/lumenmc-banner.png");

            if (confLoader.P_SERVER_LOCATION != null && !confLoader.P_SERVER_LOCATION.isBlank() && confLoader.P_SERVER_UUID != null && !confLoader.P_SERVER_UUID.isBlank()) {
                embed.fields = Arrays.asList(
                        new Field("Time Zone", confLoader.TZ, true),
                        new Field("Server Memory", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024.0 * 1024.0) + "MB", true),
                        new Field("Server IP", confLoader.SERVER_IP, true),
                        new Field("Server Port", confLoader.SERVER_PORT, true),
                        new Field("Server Location", confLoader.P_SERVER_LOCATION, true),
                        new Field("Server UUID", "```" + confLoader.P_SERVER_UUID + "```", true),
                        new Field("Server Version", plugin.getServer().getVersion(), true),
                        new Field("Number of Plugins", String.valueOf(plugin.getServer().getPluginManager().getPlugins().length), true)
                );
            } else {
                embed.fields = Arrays.asList(
                        new Field("Time Zone", TimeZone.getDefault().getID(), true),
                        new Field("Server Memory", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / (1024.0 * 1024.0) + "MB", true),
                        new Field("Server Version", plugin.getServer().getVersion(), true),
                        new Field("Number of Plugins", String.valueOf(plugin.getServer().getPluginManager().getPlugins().length), true)
                );
            }

            WebhookEmbedPayload payload = new WebhookEmbedPayload();
            payload.username = "LumenMC";
            payload.avatar_url = "https://cdn.lumenvm.cloud/logo.png";
            payload.embeds = Collections.singletonList(embed);

            String json = gson.toJson(payload);

            if (confLoader.debug) {
                plugin.getLogger().info("Debug: Sending embed to Discord \n" + json);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(webhookUri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (confLoader.debug) {
                plugin.getLogger().info("Debug: Webhook embed sent: HTTP " + response.statusCode());
                plugin.getLogger().info("Debug: Response body: " + response.body());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error when sending embed to Discord: ", e);
        }
    }

    public static boolean shouldIgnore(String msg) {
        if (msg == null || confLoader.ignorePatterns == null) return false;
        for (String pattern : confLoader.ignorePatterns) {
            if (pattern == null || pattern.isBlank()) continue;
            try { if (msg.matches(pattern)) return true; }
            catch (Exception ignored) {}
        }
        return false;
    }

    public static List<String> splitMessage(String msg, int maxLen) {
        List<String> parts = new ArrayList<>();
        if (msg == null) return parts;
        if (msg.length() <= maxLen) { parts.add(msg); return parts; }
        int i = 0;
        while (i < msg.length()) {
            int end = Math.min(i + maxLen, msg.length());
            parts.add(msg.substring(i, end));
            i = end;
        }
        return parts;
    }

    public static void setPlugin(Monitor plugin) {
        Webhook.plugin = plugin;
    }

    public static void setConfLoader(ConfigLoader confLoader) {
        Webhook.confLoader = confLoader;
    }

    private int msToTicks(int ms) {
        int ticks = (int) Math.ceil(ms / 50.0);
        return Math.max(1, ticks);
    }

    public void endTask() {
        Bukkit.getScheduler().cancelTask(taskId);
        plugin.getLogger().info("Canceled task " + taskId);
        taskId = -1;
    }

    // Content-only payload
    static class WebhookContentPayload {
        @SerializedName("username")String username;
        @SerializedName("avatar_url")String avatar_url;
        @SerializedName("content")String content;
        WebhookContentPayload(String content) { this.content = content; }
    }

    // Embed-only payload
    static class WebhookEmbedPayload {
        @SerializedName("username")String username;
        @SerializedName("avatar_url")String avatar_url;
        @SerializedName("embeds")List<Embed> embeds;
    }

    static class Image {
        @SerializedName("url") String url;
        Image(String url) { this.url = url; }
    }

    static class Embed {
        @SerializedName("image")Image image;
        @SerializedName("title")String title;
        @SerializedName("description")String description;
        @SerializedName("color")Integer color;
        @SerializedName("timestamp")String timestamp;
        @SerializedName("footer")Footer footer;
        @SerializedName("fields")List<Field> fields;
    }

    static class Footer {
        @SerializedName("text")String text;
        Footer(String text) { this.text = text; }
    }

    static class Field {
        @SerializedName("name")String name;
        @SerializedName("value")String value;
        @SerializedName("inline")Boolean inline;
        Field(String name, String value, boolean inline) {
            this.name = name; this.value = value; this.inline = inline;
        }
    }
}
