package cloud.lumenvm;

import me.clip.placeholderapi.events.ExpansionRegisterEvent;
import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.checkerframework.checker.nullness.qual.NonNull;
import me.clip.placeholderapi.PlaceholderAPI;

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

public class Monitor extends JavaPlugin implements Listener {
    LanguageLoader langLoader;
    private String locale;
    private static HttpClient httpClient;
    private boolean reloading = false;
    public boolean debug;
    public List<Webhook> webhooks;
    private final List<String> expansions = new ArrayList<>();
    Collection<String> requiredExpansions = new ArrayList<>();
    private boolean starting = true;


    @Override
    public void onLoad() {
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Webhook.httpClient = httpClient;
        saveDefaultConfig();
        reloadConfig();
        debug = getConfig().getBoolean("debug", false);
        langLoader = new LanguageLoader(this);

        Webhook.setPlugin(this);
        EmbedLoader.setPlugin(this);
        webhooks = new ArrayList<>();
        requiredExpansions.add("server");
        requiredExpansions.add("player");
    }

    @Override
    public void onEnable() {
        new Metrics(this, 28902);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            if (debug) getLogger().info("Debug: PlaceholderAPI detected");
            new PapiExpansion(this).register();
        } else {
            Bukkit.getPluginManager().disablePlugin(this);
        }

        ConfigurationSection webhooksSection = getConfig().getConfigurationSection("webhooks");
        if (webhooksSection != null) {
            Set<String> webhooksNames = webhooksSection.getKeys(false);
            for (String name : webhooksNames) {
                webhooks.add(new Webhook(name));
            }
        } else {
            getLogger().severe("Failed to load webhooks in config.yml");
            getServer().getPluginManager().disablePlugin(this);
        }

        for (Webhook webhook : webhooks) {
            if (webhook.confLoader.failedToLoadConfig) {
                return;
            }
        }

        Webhook.startTask();

        // Register events
        if (!reloading) {
            getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @Override
    public void onDisable() {
        if (webhooks != null) {
            for (Webhook webhook : webhooks) {
                if (!webhook.confLoader.failedToLoadConfig && webhook.confLoader.embedsStartStopEnabled && !reloading && !starting) {
                    String embedStopTitle = PlaceholderAPI.setPlaceholders(null, langLoader.get("embed_stop_title"));
                    String embedStopDescription = PlaceholderAPI.setPlaceholders(null, langLoader.get("embed_stop_description"));
                    webhook.sendEmbed(embedStopTitle, embedStopDescription, Integer.parseInt(langLoader.get("embed_stop_color")));
                }
            }
            for (Webhook webhook : webhooks) {
                webhook.removeAllWebhooks();
            }

            Webhook.endTask();

            Webhook.drainAndSend();
        }
    }

    // Events (chat, commands, etc.)

    @EventHandler
    public void onExpansionRegister(ExpansionRegisterEvent event) {
        expansions.add(event.getExpansion().getIdentifier());
    }

    @EventHandler
    public void onExpansionsLoaded(ExpansionsLoadedEvent event) {
        if (new HashSet<>(expansions).containsAll(requiredExpansions) && starting) {
            for (Webhook webhook : webhooks) {
                if (!webhook.confLoader.sendServerLoad) continue;
                if (webhook.confLoader.embedsStartStopEnabled) {
                    webhook.sendStart();
                } else {
                    String content = "[" + Instant.now() + "] [SERVER] Startup complete. For help, type \"help\"";
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
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendChat) continue;
            String message = event.getMessage();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_player_chat");
            content = content.replace("%lumenmc_msg%", message);
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendPlayerCommands) continue;
            String cmd = event.getMessage();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_player_command");
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            content = content.replace("%lumenmc_cmd%", cmd);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendConsoleCommands) continue;
            String cmd = event.getCommand();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_server_command");
            content = PlaceholderAPI.setPlaceholders(null, content);
            content = content.replace("%lumenmc_cmd%", cmd);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendJoinQuit) continue;
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_join");
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendJoinQuit) continue;
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_quit");
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendDeaths) continue;
            String msg = event.getDeathMessage();
            if (msg == null || msg.isBlank()) continue;
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_death");
            content = content.replace("%player_deathmsg%", msg);
            content = PlaceholderAPI.setPlaceholders(event.getEntity(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendGamemodeChanges) continue;
            GameMode newMode = event.getNewGameMode();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_player_gamemode_change");
            content = content.replace("%player_gamemode%", prettyMode(newMode));
            content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {

    }

    private String prettyMode(GameMode mode) {
        return switch (mode) {
            case SURVIVAL -> "Survival Mode";
            case CREATIVE -> "Creative Mode";
            case ADVENTURE -> "Adventure Mode";
            case SPECTATOR -> "Spectator Mode";
        };
    }

    // Plugin command

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command command, @NonNull String label, String @NonNull [] args) {
        if (!command.getName().equalsIgnoreCase("lumenmc")) return false;

        if (args.length == 0) {
            return false;
        }
        List<String> webhooksNames = Objects.requireNonNull(getConfig().getConfigurationSection("webhooks")).getKeys(false).stream().toList();


        if (args[0].equalsIgnoreCase("test")) {
            String content = "🔧 LumenMC test message in " + getDescription().getVersion() +
                    " @ " + Instant.now();
            for (Webhook webhook : webhooks) {
                webhook.queue.offer(content);
            }
            sender.sendMessage("§aTesting message sent...");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            pluginReload();
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
                sender.sendMessage(langLoader.createLang(this, langName));
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
                sender.sendMessage(langLoader.removeLang(this, langName));
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
                sender.sendMessage(langLoader.setLang(this, langName));
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
                    sender.sendMessage(langLoader.editLang(this, key, newObject.toString()));
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
                    getConfig().set(args[1], args[2]);
                    saveConfig();
                    pluginReload();
                    sender.sendMessage("§aOption §r" + args[1] + " §awas set to §r" + getConfig().get(args[1]));
                    return true;
                }
                sender.sendMessage("Use: /lumenmc config [configPath] [value]");
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NonNull CommandSender sender, @NonNull Command command, @NonNull String alias, String @NonNull [] args) {
        List<String> list = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 1) {
            list.add("test");
            list.add("reload");
            list.add("lang");
            list.add("webhook");
            list.sort(null);
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 2 && args[0].equalsIgnoreCase("webhook")) {
            list.add("add");
            list.add("remove");
            list.add("list");
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("webhook") && args[1].equalsIgnoreCase("remove")) {
            List<String> webhooksNames = new ArrayList<>(Objects.requireNonNull(getConfig().getConfigurationSection("webhooks")).getKeys(false).stream().toList());
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

    // Config

    void pluginReload() {
        reloading = true;
        onDisable();
        onLoad();
        onEnable();
        reloading = false;
    }

    // Help methods

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

    public String getLocale() {
        return locale;
    }

    public void setLocale(String setLocale) {
        locale = setLocale;
    }
}
