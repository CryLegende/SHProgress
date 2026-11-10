package org.Cry_Legende.sHProgress;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Textes de messages.yml. Un messages.yml plus ancien reprend les textes manquants du fichier du jar. */
public final class Messages {

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                messages.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("messages.yml par défaut illisible : " + e.getMessage());
        }
    }

    /**
     * Texte précédé du préfixe, couleurs appliquées.
     *
     * @param replacements paires variable, valeur (par exemple {@code "%player%", nom})
     */
    public String get(String key, String... replacements) {
        String text = messages.getString(key, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', messages.getString("prefix", "") + text);
    }
}
