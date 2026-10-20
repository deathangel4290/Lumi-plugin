package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
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

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      return List.of();
   }
}
