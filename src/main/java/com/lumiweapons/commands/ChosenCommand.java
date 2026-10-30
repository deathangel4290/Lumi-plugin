package com.lumiweapons.commands;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.events.ChosenRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /chosen: who the Chosen is, for everyone. Admins also get pick | set <player> | clear, the same
 * as /lwevent chosen.
 */
public class ChosenCommand implements CommandExecutor, TabCompleter {
   private final LumiWeapons plugin;

   public ChosenCommand(LumiWeapons plugin) {
      this.plugin = plugin;
   }

   private static void reply(CommandSender sender, String error, String success) {
      if (error != null) {
         sender.sendMessage(Component.text(error, NamedTextColor.RED));
      } else if (success != null) {
         sender.sendMessage(Component.text(success, NamedTextColor.GREEN));
      }
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      ChosenRole chosen = this.plugin.getEventManager().chosen();
      String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
      boolean admin = sender.hasPermission("lumiweapons.admin");
      if (!admin && !action.equals("status")) {
         sender.sendMessage(Component.text("Only admins can choose the Chosen.", NamedTextColor.RED));
         return true;
      }

      switch (action) {
         case "pick", "random", "start", "choose" -> {
            String error = chosen.pick();
            reply(sender, error, error == null ? chosen.status() : null);
         }
         case "set", "give" -> {
            String error = args.length < 2 ? "Usage: /" + label + " set <player>" : chosen.set(args[1]);
            reply(sender, error, error == null ? chosen.status() : null);
         }
         case "clear", "stop", "remove" -> reply(sender, chosen.clear(), "Nobody is the Chosen now.");
         case "status", "who" -> sender.sendMessage(Component.text(chosen.status(), NamedTextColor.GOLD));
         default -> sender.sendMessage(Component.text("Usage: /" + label + (admin ? " [pick | set <player> | clear | status]" : ""), NamedTextColor.GOLD));
      }

      return true;
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      List<String> options = !sender.hasPermission("lumiweapons.admin") ? List.of()
         : args.length == 1 ? List.of("pick", "set", "clear", "status")
         : args.length == 2 && args[0].equalsIgnoreCase("set") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
         : List.of();
      String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
      List<String> out = new ArrayList<>();
      for (String option : options) {
         if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            out.add(option);
         }
      }

      return out;
   }
}
