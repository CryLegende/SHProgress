package org.Cry_Legende.sHProgress.command;

import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.AdvancementManager;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CommandManager implements CommandExecutor, TabCompleter {

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
        if (args.length == 0) {
            send(sender, "usage", "%label%", label);
            return true;
        }
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "progress":
                return handleProgress(sender, args);
            case "reload":
                return handleReload(sender);
            case "import":
                return handleImport(sender);
            default:
                send(sender, "unknown-subcommand", "%label%", label);
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("shprogress.admin.reload")) {
            send(sender, "no-permission");
            return true;
        }

        plugin.reloadConfig();
        plugin.getMessages().load();
        advancementManager.loadAdvancementTrees();
        advancementManager.syncOnlinePlayers();

        send(sender, "reloaded");
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
            if (sender.hasPermission("shprogress.admin.progress")) completions.add("progress");
            if (sender.hasPermission("shprogress.admin.reload")) completions.add("reload");
            if (sender.hasPermission("shprogress.admin.import")) completions.add("import");
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("progress")) {
            List<String> playerNames = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(p -> playerNames.add(p.getName()));
            return StringUtil.copyPartialMatches(args[1], playerNames, new ArrayList<>());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("progress")) {
            return StringUtil.copyPartialMatches(args[2], advancementManager.getAdvancementIds(), new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
