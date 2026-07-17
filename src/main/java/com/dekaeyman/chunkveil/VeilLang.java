package com.dekaeyman.chunkveil;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

final class VeilLang {
    private static final String DEFAULT_PREFIX = "&3[ChunkVeil] &7";

    private final String prefix;
    private final FileConfiguration config;

    private VeilLang(String prefix, FileConfiguration config) {
        this.prefix = color(prefix);
        this.config = config;
    }

    static VeilLang load(ChunkVeilPlugin plugin) {
        File langFile = new File(plugin.getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(langFile);

        // Keys added in newer releases fall back to the embedded defaults so
        // upgraded servers with an older lang.yml never see raw message paths.
        InputStream embedded = plugin.getResource("lang.yml");
        if (embedded != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(embedded, StandardCharsets.UTF_8)));
        }

        return new VeilLang(config.getString("prefix", DEFAULT_PREFIX), config);
    }

    String raw(String path) {
        return color(config.getString(path, path));
    }

    String message(String path) {
        return prefix + raw(path);
    }

    String message(String path, Map<String, ?> placeholders) {
        return prefix + replace(raw(path), placeholders);
    }

    List<String> messages(String path, Map<String, ?> placeholders) {
        List<String> lines = config.getStringList(path);
        if (lines.isEmpty()) {
            String fallback = config.getString(path);
            if (fallback == null || fallback.isBlank()) {
                return Collections.singletonList(message(path, placeholders));
            }
            lines = Collections.singletonList(fallback);
        }

        return lines.stream()
                .map(VeilLang::color)
                .map(line -> prefix + replace(line, placeholders))
                .toList();
    }

    String state(boolean enabled) {
        return raw(enabled ? "states.enabled" : "states.disabled");
    }

    private static String replace(String message, Map<String, ?> placeholders) {
        String replaced = message;
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            replaced = replaced.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return replaced;
    }

    private static String color(String message) {
        return ChatColor.translateAlternateColorCodes('&', message == null ? "" : message);
    }
}
