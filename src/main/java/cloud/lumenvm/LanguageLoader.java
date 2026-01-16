package cloud.lumenvm;

import org.apache.commons.io.FileUtils;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class LanguageLoader {

    HashMap<String, String> translationMap = new HashMap<>();
    File languageDirectory;
    File defaultLanguageFile;
    File customLanguageFile;
    FileConfiguration defaultTranslations;
    private final ConfigLoader confLoader;


    public LanguageLoader(Monitor plugin, ConfigLoader configLoader){
        confLoader = configLoader;
        languageDirectory = new File(plugin.getDataFolder(), "languages/");
        defaultLanguageFile = new File(plugin.getDataFolder(), "languages/en_US.yml");
        defaultTranslations = YamlConfiguration.loadConfiguration(defaultLanguageFile);
        if (!languageDirectory.isDirectory()) languageDirectory.mkdir();

        if (plugin.getConfig().getString("locale") != null && !plugin.getConfig().getString("locale", "en_US").equalsIgnoreCase("en_US")) {
            if (confLoader.debug) plugin.getLogger().info("Debug: Loading custom language file: " + plugin.getConfig().getString("locale"));
            customLanguageFile = new File(plugin.getDataFolder(), "languages/" + plugin.getConfig().getString("locale") + ".yml");
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
            if (translations.getKeys(true).equals(defaultTranslations.getKeys(true))) {
                if (confLoader.debug) plugin.getLogger().info("Debug: Keys are the same...");
                for (String translation : translations.getKeys(true)){
                    translationMap.put(translation, translations.getString(translation));
                }
            } else {
                plugin.getLogger().severe("Keys are not the same! Loading default...");
                loadDefault(plugin);
            }
        } else {
            loadDefault(plugin);
        }
        if (confLoader.debug) plugin.getLogger().info("Debug: Translation map: " + translationMap);
    }

    private void loadDefault(Monitor plugin) {
        if (confLoader.debug) plugin.getLogger().info("Debug: Loading default language file: en_US");
        if (defaultLanguageFile.exists()) defaultLanguageFile.delete();
        try {
            InputStream stream = plugin.getResource("en_US.yml");
            assert stream != null;
            FileUtils.copyInputStreamToFile(stream, defaultLanguageFile);
        } catch (IOException e){
            plugin.getLogger().severe("§cUnable to create default language file: " + e);
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
        if (confLoader.debug) plugin.getLogger().info("Debug: Creating custom language file: " + langName);
        customLanguageFile = new File(plugin.getDataFolder(), "languages/" + langName + ".yml");
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
            plugin.loadConfig();
            return "§aCustom language created and it's being used right now!";
        } else {
            return "§cLanguage already exists";
        }
    }

    public String removeLang(Monitor plugin, String langName) {
        langName.toLowerCase();
        if (confLoader.debug) plugin.getLogger().info("Debug: Removing custom language file " + langName);
        customLanguageFile = new File(plugin.getDataFolder(), "languages/" + langName + ".yml");
        if (customLanguageFile.exists()) {
            customLanguageFile.delete();
            plugin.getConfig().set("locale", "en_US");
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.loadConfig();
            return "§aRemoved language file " + langName + " and using default";
        } else {
            return "§cLanguage file doesn't exist";
        }
    }

    public String setLang(Monitor plugin, String langName) {
        langName.toLowerCase();
        customLanguageFile = new File(plugin.getDataFolder(), "languages/" + langName + ".yml");
        if (confLoader.debug) plugin.getLogger().info("Debug: Setting language file " + customLanguageFile.getName());
        if (customLanguageFile.exists()) {
            plugin.getConfig().set("locale", langName);
            plugin.saveConfig();
            plugin.reloadConfig();
            plugin.loadConfig();
            if (plugin.getLocale().equalsIgnoreCase("en_US")) {
                return "§aSet to default language";
            }
            return "§aLanguage set successfully";
        } else {
            return "§cLanguage file doesn't exist";
        }
    }

    public ArrayList<String> listLang() {
        String[] array = languageDirectory.list();
        assert array != null;
        ArrayList<String> list = new ArrayList<>(Arrays.asList(array).subList(0, Objects.requireNonNull(array).length));
        list.removeIf(s -> !s.contains(".yml"));
        return list;
    }

    public String editLang(Monitor plugin, String key, String newObject) throws IOException {
        String locale = plugin.getConfig().getString("locale", "en_US").toLowerCase();
        if (!locale.equalsIgnoreCase("en_US")) {
            //File customLanguageFile = new File(plugin.getDataFolder(), "languages/" + locale + ".yml");
            FileConfiguration translations = YamlConfiguration.loadConfiguration(customLanguageFile);
            if (customLanguageFile.exists()) {
                translations.set(key, newObject);
                translations.save(customLanguageFile);
                plugin.saveConfig();
                plugin.reloadConfig();
                plugin.loadConfig();
                return "Successfully edited " + locale;
            } else {
                return "Language file doesn't exist";
            }
        } else {
            return "You can't write to default language file. You can create new one with /lumenmc lang create [language name]";
        }
    }

    public List<String> getKeys() {
        return defaultTranslations.getKeys(true).stream().toList();
    }
}
