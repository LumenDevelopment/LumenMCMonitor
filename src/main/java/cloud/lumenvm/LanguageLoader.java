package cloud.lumenvm;

import org.apache.commons.io.FileUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

public class LanguageLoader {

    HashMap<String, String> translationMap = new HashMap<>();


    public LanguageLoader(Monitor plugin){
        File languageDirectory = new File(plugin.getDataFolder(), "languages/");
        File defaultLanguageFile = new File(plugin.getDataFolder(), "languages/en_US.yml");
        FileConfiguration defaultTranslation = YamlConfiguration.loadConfiguration(defaultLanguageFile);
        if (!languageDirectory.isDirectory()) languageDirectory.mkdir();

        if (plugin.getConfig().getString("locale") != null && !plugin.getConfig().getString("locale", "en_US").equalsIgnoreCase("en_US")) {
            if (Monitor.debug) plugin.getLogger().info("Debug: Loading custom language file: " + plugin.getConfig().getString("locale"));
            File customLanguageFile = new File(plugin.getDataFolder(), "languages/" + plugin.getConfig().getString("locale") + ".yml");
            if (!customLanguageFile.exists()) {
                try {
                    InputStream stream = plugin.getResource("template.yml");
                    assert stream != null;
                    FileUtils.copyInputStreamToFile(stream, customLanguageFile);
                } catch (IOException e){
                    plugin.getLogger().severe("Unable to create custom language files: " + e);
                }
            }
            FileConfiguration translations = YamlConfiguration.loadConfiguration(customLanguageFile);
            if (translations.getKeys(true).equals(defaultTranslation.getKeys(true))) {
                if (Monitor.debug) plugin.getLogger().info("Debug: Keys are the same...");
                for (String translation : translations.getKeys(true)){
                    translationMap.put(translation, translations.getString(translation));
                }
            } else {
                plugin.getLogger().severe("Keys are not the same! Loading default...");
                loadDefault(defaultLanguageFile, plugin, defaultTranslation);
            }
        } else {
            loadDefault(defaultLanguageFile, plugin, defaultTranslation);
        }
        if (Monitor.debug) plugin.getLogger().info("Debug: Translation map: " + translationMap);
    }

    private void loadDefault(File defaultLanguageFile, JavaPlugin plugin, FileConfiguration defaultTranslations) {
        if (Monitor.debug) plugin.getLogger().info("Debug: Loading default language file: en_US");
        if (defaultLanguageFile.exists()) defaultLanguageFile.delete();
        try {
            InputStream stream = plugin.getResource("en_US.yml");
            assert stream != null;
            FileUtils.copyInputStreamToFile(stream, defaultLanguageFile);
        } catch (IOException e){
            plugin.getLogger().severe("Unable to create default language file: " + e);
        }
        for (String translation : defaultTranslations.getKeys(false)){
            translationMap.put(translation, defaultTranslations.getString(translation));
        }
    }

    public String get(String path){
        return translationMap.get(path);
    }

    // Returns response
    public String createLang(Monitor plugin, String langName) {
        langName.toLowerCase();
        if (Monitor.debug) plugin.getLogger().info("Debug: Creating custom language file: " + langName);
        File customLanguageFile = new File(plugin.getDataFolder(), "languages/" + langName + ".yml");
        if (!customLanguageFile.exists()) {
            try {
                InputStream stream = plugin.getResource("template.yml");
                assert stream != null;
                FileUtils.copyInputStreamToFile(stream, customLanguageFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Unable to create custom language file: " + e);
            }
            FileConfiguration translations = YamlConfiguration.loadConfiguration(customLanguageFile);
            for (String translation : translations.getKeys(true)){
                translationMap.put(translation, translations.getString(translation));
            }
            plugin.getConfig().set("locale", langName);
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.readConfig();
            return "Custom language created and it's being used right now!";
        } else {
            return "Language already exists";
        }
    }

    public String removeLang(Monitor plugin, String langName) {
        langName.toLowerCase();
        if (Monitor.debug) plugin.getLogger().info("Debug: Removing custom language file " + langName);
        File customLanguageFile = new File(plugin.getDataFolder(), "languages/" + langName + ".yml");
        if (customLanguageFile.exists()) {
            customLanguageFile.delete();
            plugin.getConfig().set("locale", "en_US");
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.readConfig();
            return "Removed language file " + langName + " and using default";
        } else {
            return "Language file doesn't exist";
        }
    }

    public String setLang(Monitor plugin, String langName) {
        langName.toLowerCase();
        File customLanguageFile = new File(plugin.getDataFolder(), "languages/" + langName + ".yml");
        if (Monitor.debug) plugin.getLogger().info("Debug: Setting language file " + customLanguageFile.getName());
        if (customLanguageFile.exists()) {
            plugin.getConfig().set("locale", langName);
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.readConfig();
            if (Monitor.getLocale().equalsIgnoreCase("en_US")) {
                return "Set to default language";
            }
            return "Language set successfully";
        } else {
            return "Language file doesn't exist";
        }
    }

    public String[] listLang(Monitor plugin) {
        File languageDirectory = new File(plugin.getDataFolder(), "languages/");
        return languageDirectory.list();
    }
}
