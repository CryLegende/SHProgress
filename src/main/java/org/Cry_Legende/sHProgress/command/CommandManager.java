package org.Cry_Legende.sHProgress.command;

import org.Cry_Legende.sHProgress.SHProgress;
import org.Cry_Legende.sHProgress.advancement.AdvancementManager;
import org.Cry_Legende.sHProgress.advancement.ConfiguredAdvancement;
import org.Cry_Legende.sHProgress.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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

    private static final String PREFIX = "&7&l« &dProgrès &8&l» &7";

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
            send(sender, "Utilisation : &e/" + label + " <progress|reload>");
            return true;
        }
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "progress":
                return handleProgress(sender, args);
            case "reload":
                return handleReload(sender);
            default:
                send(sender, "Sous-commande &cinconnue&7. Utilisation : &e/" + label + " <progress|reload>");
                return true;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("shprogress.admin.reload")) {
            send(sender, "Vous n'avez pas la &cpermission&7.");
            return true;
        }

        advancementManager.loadAdvancementTrees();
        advancementManager.syncOnlinePlayers();

        send(sender, "Configuration &arechargée&7, progression des joueurs connectés resynchronisée.");
        return true;
    }

    private boolean handleProgress(CommandSender sender, String[] args) {
        if (!sender.hasPermission("shprogress.admin.progress")) {
            send(sender, "Vous n'avez pas la &cpermission&7.");
            return true;
        }
        if (args.length != 4) {
            send(sender, "Utilisation : &e/shprogress progress <joueur> <arbre/id_progrès> <quantité>");
            return true;
        }
        // Depuis la console (SHCrates à chaque box ouverte), pas de message de retour
        return grant(sender, args[1], args[2], args[3], sender instanceof Player);
    }

    private boolean grant(CommandSender sender, String playerName, String advancementId, String rawAmount, boolean feedback) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            send(sender, "Le joueur &e" + playerName + " &7n'est pas &cen ligne&7.");
            return true;
        }
        ConfiguredAdvancement advConfig = advancementManager.getConfiguredAdvancements().stream()
            .filter(adv -> adv.getFullId().equalsIgnoreCase(advancementId))
            .findFirst().orElse(null);

        if (advConfig == null) {
            send(sender, "Le progrès &e" + advancementId + " &cn'existe pas&7.");
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(rawAmount);
        } catch (NumberFormatException e) {
            send(sender, "La quantité doit être un &cnombre entier&7.");
            return true;
        }

        int before = dataManager.getProgression(target.getUniqueId(), advConfig.getFullId());
        dataManager.incrementProgression(target, advConfig, amount);
        int after = dataManager.getProgression(target.getUniqueId(), advConfig.getFullId());

        if (feedback) {
            String state = after > before ? "&a+" + (after - before) + " &7pour"
                : after >= advConfig.getAmount() ? "&eDéjà terminé &7pour" : "&6Verrouillé &7(progrès parent non terminé) pour";
            send(sender, state + " &e" + target.getName() + " &7sur &e" + advConfig.getFullId()
                + " &7(" + after + "/" + advConfig.getAmount() + ").");
        }
        return true;
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', PREFIX + message));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        final List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("shprogress.admin.progress")) completions.add("progress");
            if (sender.hasPermission("shprogress.admin.reload")) completions.add("reload");
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
