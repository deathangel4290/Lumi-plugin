package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.guis.DraftMenu;
import com.legendaryweapons.guis.TeamMenu;
import com.legendaryweapons.teams.TeamManager;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Player team commands:
 *   /team            your team (menu)
 *   /team chat       switch your chat between your team and everyone
 *   /team list       every team and its players
 *   /tc <message>    say something to your team
 *   /draft           the draft board (pick there when you're a captain)
 */
public class TeamCommand implements CommandExecutor, TabCompleter {
   private final LegendaryWeapons plugin;

   public TeamCommand(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      TeamManager teams = this.plugin.getTeamManager();
      String name = command.getName().toLowerCase(Locale.ROOT);
      if (name.equals("team") && args.length > 0 && args[0].equalsIgnoreCase("list")) {
         list(sender, teams);
         return true;
      }

      if (!(sender instanceof Player player)) {
         sender.sendMessage("Run this in-game.");
         return true;
      }

      switch (name) {
         case "tc" -> {
            if (args.length == 0) {
               player.sendMessage(Component.text("Usage: /tc <message>", NamedTextColor.GRAY));
            } else if (!teams.say(player, String.join(" ", args))) {
               player.sendMessage(Component.text("You're not on a team.", NamedTextColor.RED));
            }
         }
         case "draft" -> new DraftMenu(this.plugin, player, null).open();
         default -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("chat")) {
               if (teams.teamOf(player) == null) {
                  player.sendMessage(Component.text("You're not on a team.", NamedTextColor.RED));
               } else {
                  boolean on = teams.toggleChat(player);
                  player.sendMessage(Component.text(on ? "Your chat now goes to your team. /team chat to switch back." : "Your chat goes to everyone again.", NamedTextColor.GREEN));
               }
            } else {
               new TeamMenu(this.plugin, player, null).open();
            }
         }
      }

      return true;
   }

   static void list(CommandSender sender, TeamManager teams) {
      if (teams.teams().isEmpty()) {
         sender.sendMessage(Component.text("There are no teams yet.", NamedTextColor.GRAY));
         return;
      }

      for (TeamManager.Team team : teams.teams()) {
         StringBuilder members = new StringBuilder();
         for (java.util.UUID id : team.members) {
            members.append(members.length() == 0 ? "" : ", ").append(team.nameOf(id)).append(id.equals(team.captain) ? "*" : "");
         }

         sender.sendMessage(Component.text(team.name + " (" + team.members.size() + "): ", team.colour).append(Component.text(members.toString(), NamedTextColor.WHITE)));
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      if (command.getName().equalsIgnoreCase("team") && args.length == 1) {
         return List.of("chat", "list").stream().filter(option -> option.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
      }

      return List.of();
   }
}
