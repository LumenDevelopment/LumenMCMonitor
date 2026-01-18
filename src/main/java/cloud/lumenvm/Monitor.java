package cloud.lumenvm;

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

@SuppressWarnings("FieldCanBeLocal")
public class Monitor extends JavaPlugin implements Listener {
    LanguageLoader langLoader;
    private String locale;
    private static HttpClient httpClient;
    private boolean reloading = false;
    public boolean debug;
    List<Webhook> webhooks;
    private boolean isPapiEnabled;


    @Override
    public void onLoad() {
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        Webhook.httpClient = httpClient;
        saveDefaultConfig();
        reloadConfig();
        debug = getConfig().getBoolean("debug", false);
        langLoader = new LanguageLoader(this);

        Webhook.setPlugin(this);
        webhooks = new ArrayList<>();
    }

    @Override
    public void onEnable() {
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

        // Register events
        if (!reloading) {
            getServer().getPluginManager().registerEvents(this, this);
        }
    }

    @Override
    public void onDisable() {
        if (webhooks != null) {
            for (Webhook webhook : webhooks) {
                if (!webhook.confLoader.failedToLoadConfig && webhook.confLoader.embedsStartStopEnabled && !reloading) {
                    String embedStopTitle = PlaceholderAPI.setPlaceholders(null, langLoader.get("embed_stop_title"));
                    String embedStopDescription = PlaceholderAPI.setPlaceholders(null, langLoader.get("embed_stop_description"));
                    webhook.sendEmbed(embedStopTitle, embedStopDescription, Integer.parseInt(langLoader.get("embed_stop_color")));
                }
            }
            for (Webhook webhook : webhooks) {
                webhook.endTask();
            }

            for (Webhook webhook : webhooks) {
                webhook.drainAndSend();
            }
        }
    }

    // Events (chat, commands, etc.)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendChat) continue;
            String player = event.getPlayer().getName();
            String message = event.getMessage();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_player_chat");
            if (isPapiEnabled) {
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
                content = content.replace("%lumenmc_msg%", message);
            } else {
                content = content.replace("%player_name%", player).replace("%lumenmc_msg%", message);
            }
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendPlayerCommands) continue;
            String player = event.getPlayer().getName();
            String cmd = event.getMessage();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_player_command");
            if (isPapiEnabled) {
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
                content = content.replace("%lumenmc_cmd%", cmd);
            } else {
                content = content.replace("%player_name%", player).replace("%lumenmc_cmd%", cmd);
            }
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendConsoleCommands) continue;
            String cmd = event.getCommand();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_server_command");
            if (isPapiEnabled) {
                content = PlaceholderAPI.setPlaceholders(null, content);
            }
            content = content.replace("%lumenmc_cmd%", cmd);
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendJoinQuit) continue;
            String name = event.getPlayer().getName();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_join");
            if (isPapiEnabled) {
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            } else {
                content = content.replace("%player_name%", name);
            }
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendJoinQuit) continue;
            String name = event.getPlayer().getName();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_quit");
            if (isPapiEnabled) {
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            } else {
                content = content.replace("%player_name%", name);
            }
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
            if (isPapiEnabled) {
                content = PlaceholderAPI.setPlaceholders(event.getEntity(), content);
            }
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemodeChange(PlayerGameModeChangeEvent event) {
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendGamemodeChanges) continue;
            String name = event.getPlayer().getName();
            GameMode newMode = event.getNewGameMode();
            String content = "[" + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now()) + "] " + langLoader.get("on_player_gamemode_change");
            if (isPapiEnabled) {
                content = content.replace("%player_gamemode%", prettyMode(newMode));
                content = PlaceholderAPI.setPlaceholders(event.getPlayer(), content);
            } else {
                content = content.replace("%player_gamemode%", prettyMode(newMode));
                content = content.replace("%player_name%", name);
            }
            webhook.enqueueIfAllowed(content);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            if (debug) getLogger().info("Debug: PlaceholderAPI detected");
            isPapiEnabled = true;
        } else isPapiEnabled = false;
        for (Webhook webhook : webhooks) {
            if (!webhook.confLoader.sendServerLoad) continue;
            if (webhook.confLoader.embedsStartStopEnabled) {
                String embedStartTitle = PlaceholderAPI.setPlaceholders(null, langLoader.get("embed_start_title"));
                String embedStartDescription = PlaceholderAPI.setPlaceholders(null, langLoader.get("embed_start_description"));
                webhook.sendEmbed(embedStartTitle, embedStartDescription, Integer.parseInt(langLoader.get("embed_start_color")));
            } else {
                String content = "[" + Instant.now() + "] [SERVER] Startup complete. For help, type \"help\"";
                webhook.enqueueIfAllowed(content);
            }
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

    // Plugin command

    @Override
    public boolean onCommand(@NonNull CommandSender sender, Command command, @NonNull String label, String @NonNull [] args) {
        if (!command.getName().equalsIgnoreCase("lumenmc")) return false;

        if (args.length == 0) {
            return false;
        }

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
            reloading = true;
            onDisable();
            onLoad();
            onEnable();
            sender.sendMessage("§aReloaded...");
            reloading = false;
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
            List<?> webhooksNames = Objects.requireNonNull(getConfig().getConfigurationSection("webhooks")).getKeys(false).stream().toList();
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
                    sender.sendMessage("§aAdded webhook: §r" + args[2] + "§awith url: §r" + args[3]);
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
                if (webhooksNames.contains(args[2])) {
                    Webhook.removeWebhook(args[2]);
                    saveConfig();
                    reloadConfig();
                    pluginReload();
                    sender.sendMessage("§aRemoved webhook: " + args[2]);
                } else {
                    sender.sendMessage("§cInvalid name url: " + args[2]);
                }
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
            return list;
        }
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("webhook")) {
            list = Objects.requireNonNull(getConfig().getConfigurationSection("webhooks")).getKeys(false).stream().toList();;
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
        if (command.getName().equalsIgnoreCase("lumenmc") && args.length == 3 && args[0].equalsIgnoreCase("lang") && args[1].equalsIgnoreCase("set")) {
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
