package org.Cry_Legende.sHProgress;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;


/** Textes de messages.yml. Un messages.yml plus ancien reprend les textes manquants du fichier du jar. */
public final class Messages {

    private final JavaPlugin plugin;
    private volatile FileConfiguration messages;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** Relit messages.yml. Un fichier illisible n'empêche pas le démarrage : les textes du jar servent jusqu'au prochain reload. */
    public void load() {
        try {
            messages = ConfigFiles.read(plugin, "messages.yml");
        } catch (ConfigFiles.ReadError e) {
            plugin.getLogger().severe(e.file() + " illisible, textes du jar utilisés : " + e.detail());
            messages = ConfigFiles.bundled(plugin, "messages.yml");
        }
    }

    /** Remplace les textes par un messages.yml déjà lu sans erreur. */
    public void apply(FileConfiguration loaded) {
        messages = loaded;
    }

    /**
     * Texte précédé du préfixe, couleurs appliquées.
     *
     * @param replacements paires variable, valeur (par exemple {@code "%player%", nom})
     */
    public String get(String key, String... replacements) {
        // getString(clé) lit le fichier du serveur puis celui du jar ; getString(clé, défaut) ignorerait celui du jar
        String text = messages.getString(key);
        if (text == null) {
            text = "";
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        String prefix = messages.getString("prefix");
        return ChatColor.translateAlternateColorCodes('&', (prefix == null ? "" : prefix) + text);
    }
}
