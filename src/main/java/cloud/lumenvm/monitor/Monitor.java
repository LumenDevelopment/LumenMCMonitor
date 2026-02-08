package cloud.lumenvm.monitor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import me.clip.placeholderapi.PlaceholderAPI;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.logging.Level;

//    ▖          ▖  ▖▄▖  ▖  ▖    ▘▗
//    ▌ ▌▌▛▛▌█▌▛▌▛▖▞▌▌   ▛▖▞▌▛▌▛▌▌▜▘▛▌▛▘
//    ▙▖▙▌▌▌▌▙▖▌▌▌▝ ▌▙▖  ▌▝ ▌▙▌▌▌▌▐▖▙▌▌
//

public class Monitor extends JavaPlugin implements Listener {

    // Loaders
    LanguageLoader langLoader;
    Embed embedLoader;

    public Map<String, Embed> embeds;

    // Addon manager
    public AddonManager manager;

    // Locale
    private String locale;

    // Http client
    private static HttpClient httpClient;

    // Lists
    public Map<String, Webhook> webhooks;
    public List<String> userWebhookNames;
    Collection<String> requiredExpansions = new ArrayList<>();

    // Help variables
    private boolean reloading = false;
    private boolean starting = true;

    // Debug
    public boolean debug;

    @Override
    public void onLoad() {
        // Set http client
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Webhook.httpClient = httpClient;

        // Set plugins
        Webhook.setPlugin(this);
        UserWebhook.setPlugin(this);
        Embed.setPlugin(this);
        ConfigLoader.setPlugin(this);
        UserConfigLoader.setPlugin(this);
        LanguageLoader.setPlugin(this);

        // Config
        saveDefaultConfig();
        reloadConfig();

        // Set locale
        locale = getConfig().getString("locale");

        // Set debug
        debug = getConfig().getBoolean("debug", false);

        // Loaders
        langLoader = new LanguageLoader(this);

        // Webhooks
        webhooks = new HashMap<>();

        embeds = new HashMap<>();

        UserWebhook.userWebhookCount = new HashMap<>();

        // Add required PAPI expansions
        requiredExpansions.add("server");
        requiredExpansions.add("player");
    }

    @Override
    public void onEnable() {
        String asciiArt =
                """
                        
                        §5▖          ▖  ▖▄▖  §1▖  ▖    ▘▗
                        §5▌ ▌▌▛▛▌█▌▛▌▛▖▞▌▌   §1▛▖▞▌▛▌▛▌▌▜▘▛▌▛▘
                        §5▙▖▙▌▌▌▌▙▖▌▌▌▝ ▌▙▖  §1▌▝ ▌▙▌▌▌▌▐▖▙▌▌§r
                        """ +
                "Version: " + getDescription().getVersion() + " On: " +getServer().getBukkitVersion() + "\n";

        if (!reloading) getServer().getConsoleSender().sendMessage(asciiArt + "§r");

        // Metrics
        metrics();

        // Check for PAPI
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            if (debug) getLogger().info("Debug: PlaceholderAPI detected");
            new PapiExpansion(this).register();
        } else {
            Bukkit.getPluginManager().disablePlugin(this);
        }

        // Add webhooks
        ConfigurationSection webhooksSection = getConfig().getConfigurationSection("webhooks");
        if (webhooksSection != null) {
            Set<String> webhooksNames = webhooksSection.getKeys(false);
            for (String name : webhooksNames) {
                webhooks.put(name , new Webhook(name, false, null));
            }
        } else {
            getLogger().severe("Failed to load webhooks in config.yml");
            getServer().getPluginManager().disablePlugin(this);
        }

        List<String> userWebhookList = getConfig().getStringList("user_configs");
        userWebhookNames = new ArrayList<>();
        for (String uuid : userWebhookList) {
            File userdata = new File(getDataFolder(), "userdata/" + uuid + ".yml");
            YamlConfiguration userConfig = YamlConfiguration.loadConfiguration(userdata);
            ConfigurationSection webhooksUserSection = userConfig.getConfigurationSection("");
            if (webhooksUserSection != null) {
                Set<String> webhooksUserNames = webhooksUserSection.getKeys(false);
                for (String name : webhooksUserNames) {
                    webhooks.put(name + "_" + uuid, new UserWebhook(name, UUID.fromString(uuid)));
                    userWebhookNames.add(name + "_" + uuid);
                }
            }
        }

        // Check if config failed to load
        for (Webhook webhook : webhooks.values()) {
            if (webhook.confLoader.failedToLoadConfig) {
                return;
            }
        }

        // Start drainAndSend() task
        Webhook.startTask();

        embeds.put("start", new Embed("start"));
        embeds.put("stop", new Embed("stop"));
        embeds.put("join", new Embed("join"));
        embeds.put("quit", new Embed("quit"));
        embeds.put("death", new Embed("death"));
        embeds.put("watchdog", new Embed("watchdog"));

        manager = new AddonManager(this);

        // Register events
        if (!reloading) {
            getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @Override
    public void onDisable() {

        if (manager != null) {
            manager.unLoad();
        }

        if (webhooks != null) {
            // Send stop Embed
            for (Webhook webhook : webhooks.values()) {
                if (!webhook.confLoader.failedToLoadConfig && webhook.confLoader.embedsStartStopEnabled && !reloading && !starting) {
                    webhook.sendJson(PlaceholderAPI.setPlaceholders(null, embeds.get("stop").embed));
                }
            }
            // Remove webhooks if there are any
            for (Webhook webhook : webhooks.values()) {
                webhook.removeAllWebhooks();
            }

            // End drainAndSend() task
            Webhook.endTask();

            // Last drainAndSend()
            Webhook.drainAndSend();
        }
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        checkUpdate();
    }

    // Events (chat, commands, etc.)
    @EventHandler
    public void onExpansionsLoaded(ExpansionsLoadedEvent event) {
        // Check if required PAPI expansions are registered
        if (PlaceholderAPI.isRegistered("Player") && PlaceholderAPI.isRegistered("Server") && starting) {
            for (Webhook webhook : webhooks.values()) {
                if (!webhook.confLoader.sendServerLoad) continue;
                if (webhook.confLoader.embedsStartStopEnabled) {
                    webhook.sendJson(PlaceholderAPI.setPlaceholders(null, embeds.get("start").embed));
                } else {
                    String content = prettyTime() + "[SERVER] Startup complete. For help, type \"help\"";
                    webhook.enqueueIfAllowed(content);
                }
                starting = false;
            }
        } else {
            getLogger().severe("Please install following PAPI extensions: Player, Server");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        // Send chat if enabled
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendChat) continue;
            String message = event.getMessage();
            String content = prettyTime() + langLoader.get("on_player_chat");
            content = content.replace("%lumenmc_msg%", message);
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        // Send command if enabled
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendPlayerCommands) continue;
            String cmd = event.getMessage();
            String content = prettyTime() + langLoader.get("on_player_command");
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            content = content.replace("%lumenmc_cmd%", cmd);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        // Send command if enabled
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendConsoleCommands) continue;
            String cmd = event.getCommand();
            String content = prettyTime() + langLoader.get("on_server_command");
            content = PlaceholderAPI.setPlaceholders(null, content);
            content = content.replace("%lumenmc_cmd%", cmd);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        // Send join (embed) if enabled
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendJoinQuit) continue;
            if (webhook.confLoader.embedsJoinQuitEnabled){
                webhook.sendJson(PlaceholderAPI.setPlaceholders(event.getPlayer(), embeds.get("join").embed));
            } else {
                String content = prettyTime() + langLoader.get("on_join");
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
                webhook.enqueueIfAllowed(content);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        // Send quit (embed) if enabled
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendJoinQuit) continue;
            if (webhook.confLoader.embedsJoinQuitEnabled) {
                webhook.sendJson(PlaceholderAPI.setPlaceholders(event.getPlayer(), embeds.get("quit").embed));
            } else {
                String content = prettyTime() + langLoader.get("on_quit");
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
                webhook.enqueueIfAllowed(content);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        // Send death (embed) if enabled
        String msg = event.getDeathMessage();
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendDeaths) continue;
            if (msg == null || msg.isBlank()) return;
            if (webhook.confLoader.embedsDeathsEnabled) {
                webhook.sendJson(PlaceholderAPI.setPlaceholders(event.getEntity(), embeds.get("death").embed.replace("%lumenmc_deathmsg%", msg)));
            } else {
                String content = prettyTime() + langLoader.get("on_death");
                content = content.replace("%lumenmc_deathmsg%", msg);
                content = PlaceholderAPI.setPlaceholders(event.getEntity(), content);
                webhook.enqueueIfAllowed(content);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        // Send gamemode change if enabled
        for (Webhook webhook : webhooks.values()) {
            if (!webhook.confLoader.sendGamemodeChanges) continue;
            GameMode newMode = event.getNewGameMode();
            String content = prettyTime() + langLoader.get("on_player_gamemode_change");
            content = content.replace("%player_gamemode%", prettyMode(newMode));
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    // Commands
    @Override
    public boolean onCommand(@NonNull CommandSender sender, @NonNull Command command, @NonNull String label, String @NonNull [] args) {
        List<String> webhooksNames = new ArrayList<>();
        for (Webhook webhook : webhooks.values()) {
            webhooksNames.add(webhook.confLoader.name);
        }
        if (command.getName().equalsIgnoreCase("lumenmc")) {
            if (args.length == 0) {
                return false;
            }


            if (args[0].equalsIgnoreCase("test")) {
                String content = "🔧 LumenMC test message in " + getDescription().getVersion() +
                        " @ " + Instant.now();
                for (Webhook webhook : webhooks.values()) {
                    webhook.queue.offer(content);
                }
                sender.sendMessage("§aTesting message sent...");
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                pluginReload();
                sender.sendMessage("§aReloaded...");
                return true;
            }

            if (args[0].equalsIgnoreCase("lang")) {
                if (args.length == 1) {
                    sender.sendMessage("Use: /lumenmc lang create|remove|set|list");
                    return true;
                }

                if (args[1].equalsIgnoreCase("create")) {
                    if (args.length == 2) {
                        sender.sendMessage("Use: /lumenmc lang create [language]");
                        return true;
                    }

                    String langName = args[2];
                    if (langName == null || langName.isBlank()) {
                        sender.sendMessage("Use: /lumenmc lang create [language]");
                        return true;
                    }
                    if (args.length > 3) {
                        sender.sendMessage("Don't enter spaces, please. Use: /lumenmc lang create [language]");
                        return true;
                    }
                    if (langName.contains(".yml")) langName = langName.replace(".yml", "");
                    sender.sendMessage(langLoader.createLang(langName));
                    return true;
                }

                if (args[1].equalsIgnoreCase("remove")) {
                    if (args.length == 2) {
                        sender.sendMessage("Use: /lumenmc lang remove [language]");
                        return true;
                    }

                    String langName = args[2];
                    if (langName == null || langName.isBlank()) {
                        sender.sendMessage("Use: /lumenmc lang remove [language]");
                        return true;
                    }
                    if (args.length > 3) {
                        sender.sendMessage("§cDon't enter spaces, please. Use: /lumenmc lang remove [language]");
                        return true;
                    }
                    if (langName.contains(".yml")) langName = langName.replace(".yml", "");
                    sender.sendMessage(langLoader.removeLang(langName));
                    return true;
                }

                if (args[1].equalsIgnoreCase("set")) {
                    if (args.length == 2) {
                        sender.sendMessage("Use: /lumenmc lang set [language]");
                        return true;
                    }

                    String langName = args[2];
                    if (langName == null || langName.isBlank()) {
                        sender.sendMessage("Use: /lumenmc lang set [language]");
                        return true;
                    }
                    if (args.length > 3) {
                        sender.sendMessage("Don't enter spaces, please. Use: /lumenmc lang set [language]");
                        return true;
                    }
                    if (langName.contains(".yml")) langName = langName.replace(".yml", "");
                    sender.sendMessage(langLoader.setLang(langName));
                    return true;
                }

                if (args[1].equalsIgnoreCase("list")) {
                    sender.sendMessage("These are available language files:");
                    ArrayList<String> list = langLoader.listLang();
                    for (String s : list) {
                        sender.sendMessage(s);
                    }
                    return true;
                }

                if (args[1].equalsIgnoreCase("edit")) {
                    if (args.length == 2) {
                        sender.sendMessage("Use: /lumenmc lang edit [key] [new string]");
                        return true;
                    }

                    String key = args[2];
                    if (args.length == 3) {
                        String message = langLoader.get(key);
                        if (message == null) {
                            sender.sendMessage("§cThat key doesn't exist");
                            return true;
                        }
                        sender.sendMessage("§aThe key is set to " + message);
                        return true;
                    }

                    StringBuilder newObject = new StringBuilder(args[3]);
                    if (args.length > 3) {
                        for (int i = 4; i < args.length ; i++) {
                            newObject.append(" ").append(args[i]);
                        }
                    }
                    try {
                        sender.sendMessage(langLoader.editLang(key, newObject.toString()));
                    } catch (IOException e) {
                        getLogger().severe("§cError when editing " + locale + ".yml");
                    }
                    return true;
                }

                sender.sendMessage("Use: /lumenmc lang create|remove|set|list|edit");
                return true;
            }

            if (args[0].equalsIgnoreCase("webhook")) {
                if (args.length == 1) {
                    sender.sendMessage("Use: /lumenmc webhook add|remove");
                    return true;
                }
                if (args[1].equalsIgnoreCase("add")) {
                    if (args.length == 2 || args.length == 3) {
                        sender.sendMessage("Use: /lumenmc webhook add [webhookName] [webhookUrl]");
                        return true;
                    }
                    if (webhookTest(args[3])) {
                        Webhook.addWebhook(args[2], args[3]);
                        saveConfig();
                        reloadConfig();
                        pluginReload();
                        sender.sendMessage("§aAdded webhook: §r" + args[2] + " §awith url: §r" + args[3]);
                    } else {
                        sender.sendMessage("§cInvalid webhook url: " + args[3]);
                    }
                    return true;
                }
                if (args[1].equalsIgnoreCase("remove")) {
                    if (args.length == 2) {
                        sender.sendMessage("Use: /lumenmc webhook remove [webhookName]");
                        return true;
                    }
                    if (args[2].equalsIgnoreCase("default")) {
                        sender.sendMessage("§cYou can't remove default!");
                        return true;
                    }
                    for (String name : userWebhookNames) {
                        if (name.equalsIgnoreCase(args[2])) {
                            UserWebhook.removeUserWebhook(args, UUID.fromString(args[2].substring(args[2].length() - 36)), args[2].substring(0, args[2].length() - 37));
                            sender.sendMessage("§aRemoved webhook: §r" + args[2]);
                            return true;
                        }
                    }
                    if (webhooksNames.contains(args[2])) {
                        Webhook.removeWebhook(args[2]);
                        saveConfig();
                        reloadConfig();
                        pluginReload();
                        sender.sendMessage("§aRemoved webhook: §r" + args[2]);
                    } else {
                        sender.sendMessage("§cInvalid name url: " + args[2]);
                    }
                    return true;
                }
                if (args[1].equalsIgnoreCase("list")) {
                    for (int i = 0; i < webhooksNames.size(); i++) {
                        sender.sendMessage("[§a" + (i + 1) + "§r.] " + webhooksNames.get(i));
                    }
                    return true;
                }
            }

            if (args[0].equalsIgnoreCase("config")) {
                if (args.length == 1) {
                    sender.sendMessage("Use: /lumenmc config [configPath] [value]");
                    return true;
                }
                if (getConfig().getKeys(true).contains(args[1])) {
                    if (args.length == 2) {
                        sender.sendMessage("§aThe value of §r" + args[1] + " §ais §r" + Objects.requireNonNull(getConfig().get(args[1])));
                        return true;
                    }
                    if (args.length == 3) {
                        if (args[1].contains("ignore_patterns")) {
                            List<String> filter = getConfig().getStringList(args[1]);
                            if (filter.contains(args[2])) {
                                filter.remove(args[2]);
                            } else {
                                filter.add(args[2]);
                            }
                            getConfig().set(args[1], filter);
                            saveConfig();
                            pluginReload();
                            sender.sendMessage("§aOption §r" + args[1] + " §awas set to §r" + getConfig().get(args[1]));
                            return true;
                        }
                        if (args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("false")) {
                            boolean option = Boolean.parseBoolean(args[2]);
                            getConfig().set(args[1], option);
                            saveConfig();
                            pluginReload();
                            sender.sendMessage("§aOption §r" + args[1] + " §awas set to §r" + getConfig().get(args[1]));
                            return true;
                        }
                        try {
                            int number = Integer.parseInt(args[2]);
                            getConfig().set(args[1], number);
                            saveConfig();
                            pluginReload();
                            sender.sendMessage("§aOption §r" + args[1] + " §awas set to §r" + getConfig().get(args[1]));
                        } catch (Exception e) {
                            getConfig().set(args[1], args[2]);
                            saveConfig();
                            pluginReload();
                            sender.sendMessage("§aOption §r" + args[1] + " §awas set to §r" + getConfig().get(args[1]));
                        }
                        return true;
                    }
                    sender.sendMessage("Use: /lumenmc config [configPath] [value]");
                    return true;
                }
            }

            if (args[0].equalsIgnoreCase("send")) {
                if (args.length == 1) {
                    sender.sendMessage("Use: /lumenmc send [webhook] [content]");
                    return true;
                }

                if (webhooksNames.contains(args[1])) {
                    for (Webhook webhook : webhooks.values()) {
                        for (String name : webhooksNames) {
                            if (name.equalsIgnoreCase(args[1])) break;
                            sender.sendMessage("Use: /lumenmc send [webhook] [content]");
                            return true;
                        }
                        if (args.length == 2) {
                            sender.sendMessage("Use: /lumenmc send [webhook] [content]");
                            return true;
                        }
                        StringBuilder joinText = new StringBuilder();
                        joinText.append(prettyTime()).append(" ").append("Message: ");
                        for (int i = 2; i <= (args.length - 1); i++) {
                            joinText.append(args[(i)]).append(" ");
                        }
                        webhook.enqueueIfAllowed(joinText.toString());
                        return true;
                    }
                    sender.sendMessage("Use: /lumenmc send [webhook] [content]");
                    return true;
                }

                sender.sendMessage("Use: /lumenmc send [webhook] [content]");
                return true;
            }
        }
        if (command.getName().equalsIgnoreCase("webhook") && sender instanceof Player) {
            if (args.length == 0) {
                sender.sendMessage("Use: /webhook add|remove|config|list");
                return true;
            }

            if (args[0].equalsIgnoreCase("add")) {
                if (args.length == 1) {
                    sender.sendMessage("Use: /webhook add [webhookName] [webhookUrl]");
                    return true;
                }
                if (!webhookTest(args[2])) {
                    sender.sendMessage("Webhook url is invalid!");
                    return true;
                }
                sender.sendMessage(UserWebhook.addUserWebhook(args, ((Player) sender).getUniqueId(), args[1], args[2]));
                return true;
            }
            if (args[0].equalsIgnoreCase("remove")) {
                if (args.length == 1) {
                    sender.sendMessage("Use: /webhook remove [webhookName]");
                    return true;
                }
                sender.sendMessage(UserWebhook.removeUserWebhook(args, ((Player) sender).getUniqueId(), args[1]));
                return true;
            }
            if (args[0].equalsIgnoreCase("list")) {
                List<String> userWebhooks = new ArrayList<>();
                for (String name : webhooksNames) {
                    if (name.contains(((Player) sender).getUniqueId().toString())) {
                        userWebhooks.add(name.replace("_" + ((Player) sender).getUniqueId(), ""));
                    }
                }
                for (int i = 0; i < userWebhooks.size(); i++) {
                    sender.sendMessage("[§a" + (i + 1) + "§r.] " + userWebhooks.get(i));
                }
                return true;
            }
            sender.sendMessage("Use: /webhook add|remove|config|list");
            return true;
        } else {
            sender.sendMessage("§cOnly players can use this command");
        }
        return false;
    }

    // Tab complete
    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        List<String> list = new ArrayList<>();
        List<String> webhooksNames = new ArrayList<>();
        for (Webhook webhook : webhooks.values()) {
            webhooksNames.add(webhook.confLoader.name);
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 1) {
            list.add("test");
            list.add("reload");
            list.add("lang");
            list.add("webhook");
            list.add("config");
            list.add("send");
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("send")) {
            return webhooksNames;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("webhook")) {
            list.add("add");
            list.add("remove");
            list.add("list");
            return list;
        }
        if (command.getName().equalsIgnoreCase("webhook") && args.length == 1) {
            list.add("add");
            list.add("remove");
            list.add("list");
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("webhook") && args[1].equalsIgnoreCase("remove")) {
            webhooksNames.remove("default");
            return webhooksNames;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("config")) {
            list = getConfig().getKeys(true).stream().toList();
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("lang")) {
            list.add("create");
            list.add("remove");
            list.add("list");
            list.add("set");
            list.add("edit");
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("lang") && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("remove"))) {
            list = langLoader.listLang();
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("lang") && args[1].equalsIgnoreCase("edit")) {
            return langLoader.getKeys();
        }
        return list;
    }

    // Config reload
    public void pluginReload() {
        reloading = true;
        onDisable();
        onLoad();
        onEnable();
        reloading = false;
    }

    // bStats metrics
    private void metrics() {
        Metrics metrics = new Metrics(this, 28902);
        metrics.addCustomChart(new Metrics.SimplePie("isPtero", () -> {
            if (System.getenv("P_SERVER_UUID") != null) {
                return "Yes";
            } else {
                return "No";
            }
        }));

    }

    // Help methods
    public void fireContent(String content) {
        for (Webhook webhook : webhooks.values()) {
            webhook.enqueueIfAllowed(content);
        }
    }

    public void fireEmbed(String embedJson) {
        for (Webhook webhook : webhooks.values()) {
            webhook.sendJson(PlaceholderAPI.setPlaceholders(null, embedJson));
        }
    }

    private String prettyMode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> "Survival Mode";
            case CREATIVE -> "Creative Mode";
            case ADVENTURE -> "Adventure Mode";
            case SPECTATOR -> "Spectator Mode";
        };
    }

    public String prettyTime() {
        return "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] ";
    }

    public boolean webhookTest(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .build();

            HttpResponse<String> response = Monitor.httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 401) {
                return false;
            } else return response.statusCode() == 200;
        } catch (Exception e) {
            getLogger().warning("Error when testing webhook urls: " + e);
            return false;
        }
    }

    Level parseLevel(String s) {
        try { return Level.parse(s == null ? "INFO" : s.toUpperCase()); }
        catch (Exception e) { return Level.INFO; }
    }

    private void checkUpdate() {
        try {
            HttpRequest request1 = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.modrinth.com/v2/project/KOXT4g2K"))
                    .GET()
                    .build();

            HttpResponse<String> response1 = httpClient.send(request1, HttpResponse.BodyHandlers.ofString());

            String json1 = response1.body();

            JsonObject root1 = JsonParser.parseString(json1).getAsJsonObject();
            JsonArray versionsJson1 = root1.getAsJsonArray("versions");

            List<String> versions = new Gson().fromJson(
                    versionsJson1,
                    new TypeToken<List<String>>() {}.getType()
            );

            HttpRequest request2 = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.modrinth.com/v2/version/" + versions.get(versions.size() - 1)))
                    .GET()
                    .build();

            HttpResponse<String> response2 = httpClient.send(request2, HttpResponse.BodyHandlers.ofString());

            String json2 = response2.body();

            JsonObject root2 = JsonParser.parseString(json2).getAsJsonObject();
            String latest = root2.get("version_number").toString().replace("\"", "");

            if (getDescription().getVersion().equalsIgnoreCase(latest)) {
                getServer().getConsoleSender().sendMessage("§aLumenMC Monitor is up to date");
            } else {
                getServer().getConsoleSender().sendMessage("§cLumenMC Monitor is outdated, please download the newest version. §rYou're on §c" + getDescription().getVersion() + "§r the latest is §a" + latest + "§r");
            }

        } catch (IOException | InterruptedException e) {
            getLogger().warning("Unable to check for updates: " + e);

        }
    }

    // Get locale
    public String getLocale() {
        return locale;
    }
}
