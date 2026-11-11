package org.Cry_Legende.sHProgress.command;

import org.Cry_Legende.sHProgress.ConfigFiles;
import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.AdvancementManager;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CommandManager implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("progress", "reload", "import", "info");

    private final SHProgress plugin;
    private final AdvancementManager advancementManager;
    private final PlayerDataManager dataManager;

    public CommandManager(SHProgress plugin) {
        this.plugin = plugin;
        this.advancementManager = plugin.getAdvancementManager();
        this.dataManager = plugin.getPlayerDataManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        List<String> allowed = allowedSubcommands(sender);
        if (allowed.isEmpty()) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            send(sender, "usage", "%subcommands%", String.join("|", allowed));
            return true;
        }
        String subCommand = args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "progress":
                return handleProgress(sender, args);
            case "reload":
                return handleReload(sender);
            case "import":
                return handleImport(sender);
            case "info":
                return handleInfo(sender, args);
            default:
                send(sender, "unknown-subcommand", "%subcommands%", String.join("|", allowed));
                return true;
        }
    }

    /** Les sous-commandes que l'expéditeur a le droit d'utiliser, dans l'ordre d'affichage. */
    private List<String> allowedSubcommands(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        for (String name : SUBCOMMANDS) {
            if (sender.hasPermission("shprogress.admin." + name)) {
                allowed.add(name);
            }
        }
        return allowed;
    }

    /**
     * Relit config.yml, messages.yml et chaque arbre de trees/. Tout est lu avant le moindre remplacement : si un
     * fichier est illisible, la configuration, les textes et les arbres actuels restent actifs. playerdata/ et la base
     * ne sont pas touchés.
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("shprogress.admin.reload")) {
            send(sender, "no-permission");
            return true;
        }

        YamlConfiguration loadedMessages;
        try {
            ConfigFiles.read(plugin, "config.yml");
            loadedMessages = ConfigFiles.read(plugin, "messages.yml");
            File[] trees = new File(plugin.getDataFolder(), "trees").listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
            if (trees != null) {
                for (File tree : trees) {
                    ConfigFiles.check(tree, "trees/" + tree.getName());
                }
            }
        } catch (ConfigFiles.ReadError e) {
            plugin.getLogger().severe("Reload annulé, " + e.file() + " illisible : " + e.detail());
            send(sender, "reload-error", "%file%", e.file(), "%error%", e.detail());
            return true;
        }

        plugin.reloadConfig();
        plugin.getMessages().apply(loadedMessages);
        advancementManager.loadAdvancementTrees();
        advancementManager.syncOnlinePlayers();

        send(sender, "reloaded", "%trees%", String.valueOf(advancementManager.getTreeCount()),
            "%advancements%", String.valueOf(advancementManager.getConfiguredAdvancements().size()));
        return true;
    }

    private boolean handleImport(CommandSender sender) {
        if (!sender.hasPermission("shprogress.admin.import")) {
            send(sender, "no-permission");
            return true;
        }
        boolean started = dataManager.importToNetwork(new PlayerDataManager.ImportListener() {
            @Override
            public void done(int players, int rows, int rewards) {
                send(sender, "import-done", "%players%", String.valueOf(players), "%rows%", String.valueOf(rows),
                    "%rewards%", String.valueOf(rewards));
            }

            @Override
            public void failed(String error) {
                send(sender, "import-failed", "%error%", error);
            }
        });
        send(sender, started ? "import-started" : "import-inactive");
        return true;
    }

    /** Progrès terminés par arbre et progrès les plus avancés d'un joueur, en lecture seule. */
    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shprogress.admin.info")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length != 2) {
            send(sender, "info-usage");
            return true;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            Map<String, Integer> progress = dataManager.snapshot(online.getUniqueId());
            if (progress == null) {
                send(sender, "info-loading", "%player%", online.getName());
            } else {
                showInfo(sender, online.getName(), progress);
            }
            return true;
        }
        if (dataManager.isNetwork()) {
            send(sender, "info-network-offline", "%player%", args[1]);
            return true;
        }
        // Le réseau identifie les joueurs par leur UUID hors ligne ; la casse exacte du pseudo en fait partie
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(args[1]);
        String name = cached != null && cached.getName() != null ? cached.getName() : args[1];
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, Integer> progress = dataManager.readFile(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (progress == null) {
                    send(sender, "info-no-data", "%player%", name);
                } else {
                    showInfo(sender, name, progress);
                }
            });
        });
        return true;
    }

    private void showInfo(CommandSender sender, String name, Map<String, Integer> progress) {
        Map<String, int[]> perTree = new LinkedHashMap<>();
        List<ConfiguredAdvancement> inProgress = new ArrayList<>();
        int done = 0;
        int total = 0;
        for (ConfiguredAdvancement advancement : advancementManager.getConfiguredAdvancements()) {
            int amount = Math.max(1, advancement.getAmount());
            int current = progress.getOrDefault(advancement.getFullId(), 0);
            int[] counts = perTree.computeIfAbsent(advancement.getTabId(), key -> new int[2]);
            counts[1]++;
            total++;
            if (current >= amount) {
                counts[0]++;
                done++;
            } else if (current > 0) {
                inProgress.add(advancement);
            }
        }
        send(sender, "info-header", "%player%", name, "%done%", String.valueOf(done), "%total%", String.valueOf(total));
        perTree.forEach((tree, counts) -> send(sender, "info-tree", "%tree%", tree,
            "%done%", String.valueOf(counts[0]), "%total%", String.valueOf(counts[1])));
        inProgress.sort(Comparator.comparingDouble((ConfiguredAdvancement advancement) ->
            (double) progress.getOrDefault(advancement.getFullId(), 0) / Math.max(1, advancement.getAmount())).reversed());
        for (ConfiguredAdvancement advancement : inProgress.subList(0, Math.min(3, inProgress.size()))) {
            send(sender, "info-current", "%advancement%", advancement.getFullId(),
                "%progress%", String.valueOf(progress.getOrDefault(advancement.getFullId(), 0)),
                "%amount%", String.valueOf(advancement.getAmount()));
        }
    }

    private boolean handleProgress(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shprogress.admin.progress")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length != 4) {
            send(sender, "progress-usage");
            return true;
        }
        // Depuis la console (SHCrates à chaque box ouverte), pas de message de retour
        return grant(sender, args[1], args[2], args[3], sender instanceof Player);
    }

    private boolean grant(CommandSender sender, String playerName, String advancementId, String rawAmount, boolean feedback) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            send(sender, "player-offline", "%player%", playerName);
            return true;
        }
        ConfiguredAdvancement advConfig = advancementManager.getConfiguredAdvancements().stream()
            .filter(adv -> adv.getFullId().equalsIgnoreCase(advancementId))
            .findFirst().orElse(null);

        if (advConfig == null) {
            send(sender, "unknown-advancement", "%advancement%", advancementId);
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(rawAmount);
        } catch (NumberFormatException e) {
            send(sender, "invalid-amount");
            return true;
        }

        int before = dataManager.getProgression(target.getUniqueId(), advConfig.getFullId());
        dataManager.incrementProgression(target, advConfig, amount);
        int after = dataManager.getProgression(target.getUniqueId(), advConfig.getFullId());

        if (feedback) {
            String key = after > before ? "progress-gained" : after >= advConfig.getAmount() ? "progress-already-done" : "progress-locked";
            send(sender, key, "%gained%", String.valueOf(after - before), "%player%", target.getName(),
                "%advancement%", advConfig.getFullId(), "%progress%", String.valueOf(after), "%amount%", String.valueOf(advConfig.getAmount()));
        }
        return true;
    }

    private void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(plugin.getMessages().get(key, replacements));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.addAll(allowedSubcommands(sender));
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("progress") || args[0].equalsIgnoreCase("info"))
            && sender.hasPermission("shprogress.admin." + args[0].toLowerCase(Locale.ROOT))) {
            List<String> playerNames = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> playerNames.add(p.getName()));
            return StringUtil.copyPartialMatches(args[1], playerNames, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("progress") && sender.hasPermission("shprogress.admin.progress")) {
            return StringUtil.copyPartialMatches(args[2], advancementManager.getAdvancementIds(), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
