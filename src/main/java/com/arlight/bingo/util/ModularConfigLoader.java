package com.arlight.bingo.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Loads clean, independent configuration sections into Bukkit's main view. */
public final class ModularConfigLoader {
    private static final List<String> FILES = List.of(
            "game.yml", "worlds.yml", "campaign.yml", "dungeons.yml", "interface.yml", "somita.yml");

    private ModularConfigLoader() { }

    public static void load(JavaPlugin plugin) {
        File directory = new File(plugin.getDataFolder(), "config-sections");
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear config-sections; se usará config.yml solamente.");
            return;
        }
        FileConfiguration target = plugin.getConfig();
        for (String name : FILES) {
            File file = new File(directory, name);
            boolean creating = !file.isFile();
            if (creating) plugin.saveResource("config-sections/" + name, false);
            YamlConfiguration section = YamlConfiguration.loadConfiguration(file);
            if (creating) {
                // Primera migración: conserva los valores del config.yml monolítico anterior.
                for (String root : section.getKeys(false)) {
                    if (target.contains(root)) section.set(root, target.get(root));
                }
                try {
                    section.save(file);
                } catch (IOException error) {
                    plugin.getLogger().warning("No se pudo migrar " + name + ": " + error.getMessage());
                }
            }
            merge(target, section, "");
        }
    }

    public static void savePath(JavaPlugin plugin, String path, Object value) {
        String fileName = route(path);
        File file = new File(new File(plugin.getDataFolder(), "config-sections"), fileName);
        YamlConfiguration section = YamlConfiguration.loadConfiguration(file);
        section.set(path, value);
        try {
            section.save(file);
        } catch (IOException error) {
            plugin.getLogger().warning("No se pudo guardar " + path + " en " + fileName + ": " + error.getMessage());
        }
        plugin.getConfig().set(path, value);
    }

    private static String route(String path) {
        String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        if (List.of("game", "teams", "boss-objectives", "tiebreak", "goals").contains(root)) return "game.yml";
        if (List.of("arena-world", "world-border", "world-pool", "template-worlds", "safe-world-handoff").contains(root)) return "worlds.yml";
        if (List.of("world-preparation-notice", "client-card-ui", "match-bossbar", "waiting-screen").contains(root)) return "interface.yml";
        if (root.equals("somita")) return "somita.yml";
        if (List.of("campaign", "end-encounter", "end-campaign", "reference-city-pass", "terrain-adaptation", "professional-world-design").contains(root)) return "campaign.yml";
        return "dungeons.yml";
    }

    private static void merge(ConfigurationSection target, ConfigurationSection source, String prefix) {
        for (String key : source.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (source.isConfigurationSection(key)) {
                ConfigurationSection child = source.getConfigurationSection(key);
                if (child != null) merge(target, child, path);
            } else {
                target.set(path, source.get(key));
            }
        }
    }
}
