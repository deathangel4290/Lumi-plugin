package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.SeasonManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Player-facing event commands:
 *   /renown [player]            your (or someone's) season stats and ranks
 *   /renown top [category]      a leaderboard: renown, kills, deaths, boss, bounties, outposts, hearts, stars
 *   /rewards                    open your reward chest
 *   /tokenshop                  where the Token Traders stand
 *   /outposts                   every outpost, where it is and who holds it
 */
public class PlayerEventCommand implements CommandExecutor, TabCompleter {
   private final LegendaryWeapons plugin;

   public PlayerEventCommand(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      var events = this.plugin.getEventManager();
      switch (command.getName().toLowerCase(Locale.ROOT)) {
         case "renown" -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
               SeasonManager.Stat stat = args.length > 1 ? SeasonManager.Stat.byKey(args[1]) : SeasonManager.Stat.RENOWN;
               if (stat == null) {
                  sender.sendMessage(Component.text("Categories: " + String.join(", ", this.categories()), NamedTextColor.RED));
                  return true;
               }

               events.season().leaderboard(stat, 10).forEach(sender::sendMessage);
            } else if (args.length > 0) {
               UUID id = this.plugin.getPlayerRosterManager().idOf(args[0]);
               if (id == null) {
                  sender.sendMessage(Component.text("Nobody called '" + args[0] + "' has played here.", NamedTextColor.RED));
               } else {
                  events.season().summary(id, args[0]).forEach(sender::sendMessage);
               }
            } else if (sender instanceof Player player) {
               events.season().summary(player.getUniqueId(), player.getName()).forEach(sender::sendMessage);
               sender.sendMessage(Component.text("Leaderboards: /renown top [" + String.join("|", this.categories()) + "]", NamedTextColor.DARK_GRAY));
            } else {
               events.season().leaderboard(SeasonManager.Stat.RENOWN, 10).forEach(sender::sendMessage);
            }
         }
         case "rewards" -> {
            if (sender instanceof Player player) {
               events.vault().open(player);
            } else {
               sender.sendMessage("Run this in-game.");
            }
         }
         case "tokenshop" -> {
            // the shop is the Token Traders now: say where they stand
            java.util.List<org.bukkit.Location> homes = events.tokenShop().homes();
            if (homes.isEmpty()) {
               sender.sendMessage(Component.text("There are no Token Traders yet. Ask an admin to place one.", NamedTextColor.GRAY));
            } else {
               sender.sendMessage(Component.text("Trade your Luminara Tokens with a Token Trader:", NamedTextColor.GOLD));
               for (org.bukkit.Location home : homes) {
                  sender.sendMessage(Component.text("  " + com.legendaryweapons.rituals.Ritual.dimensionName(home.getWorld()) + " " + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ(), NamedTextColor.YELLOW));
               }
            }
         }
         case "outposts" -> events.outposts().list().forEach(sender::sendMessage);
         default -> {
            return false;
         }
      }

      return true;
   }

   private List<String> categories() {
      List<String> out = new ArrayList<>();
      for (SeasonManager.Stat stat : SeasonManager.Stat.values()) {
         out.add(stat.key);
      }

      return out;
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      if (!command.getName().equalsIgnoreCase("renown")) {
         return List.of();
      }

      List<String> options = new ArrayList<>();
      if (args.length == 1) {
         options.add("top");
         for (var entry : this.plugin.getPlayerRosterManager().entries()) {
            options.add(entry.getValue());
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("top")) {
         options.addAll(this.categories());
      }

      String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
      options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
      return options;
   }
}
