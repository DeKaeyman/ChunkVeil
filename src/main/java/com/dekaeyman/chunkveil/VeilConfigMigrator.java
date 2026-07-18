package com.dekaeyman.chunkveil;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class VeilConfigMigrator {
    static final int CURRENT_CONFIG_VERSION = 1;

    private VeilConfigMigrator() {
    }

    static List<String> migrate(JavaPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        Configuration defaults = config.getDefaults();
        List<String> added = addMissingDefaults(config, defaults);

        if (config.getInt("config-version", 0) != CURRENT_CONFIG_VERSION) {
            config.set("config-version", CURRENT_CONFIG_VERSION);
            if (!added.contains("config-version")) {
                added.add("config-version");
            }
        }

        if (!added.isEmpty()) {
            config.options().copyDefaults(true);
            plugin.saveConfig();
        }
        return List.copyOf(added);
    }

    static List<String> addMissingDefaults(Configuration config, Configuration defaults) {
        List<String> added = new ArrayList<>();
        if (defaults == null) {
            return added;
        }

        for (String path : defaults.getKeys(true)) {
            Object defaultValue = defaults.get(path);
            if (!(defaultValue instanceof ConfigurationSection) && !config.contains(path, true)) {
                config.set(path, defaultValue);
                added.add(path);
            }
        }
        return added;
    }
}
