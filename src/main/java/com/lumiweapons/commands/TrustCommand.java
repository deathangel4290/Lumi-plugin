package com.lumiweapons.commands;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.TrustManager;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class TrustCommand implements CommandExecutor {
   private final LumiWeapons plugin;
   private final TrustManager trustManager;

   public TrustCommand(LumiWeapons plugin, TrustManager trustManager) {
      this.plugin = plugin;
      this.trustManager = trustManager;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (command.getName().equalsIgnoreCase("trust")) {
            this.handleTrust(player, args);
         } else if (command.getName().equalsIgnoreCase("untrust")) {
            this.handleUntrust(player, args);
         } else if (command.getName().equalsIgnoreCase("trustlist")) {
            new com.lumiweapons.guis.TrustMenu(this.plugin, player, new com.lumiweapons.guis.LumiMenu(this.plugin, player)).open();
         }

         return true;
      } else {
         sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
         return true;
      }
   }

   private void handleTrust(Player player, String[] args) {
      if (args.length < 1) {
         player.sendMessage(ChatColor.RED + "Usage: /trust <player>");
      } else {
         Player target = Bukkit.getPlayer(args[0]);
         if (target == null) {
            player.sendMessage(ChatColor.RED + "Player not found.");
         } else if (player.equals(target)) {
            player.sendMessage(ChatColor.RED + "You cannot trust yourself.");
         } else if (this.trustManager.isTrusted(player, target)) {
            player.sendMessage(ChatColor.YELLOW + "You already trust this player.");
         } else {
            this.trustManager.addTrustedPlayer(player, target);
            player.sendMessage(ChatColor.GREEN + "You now trust " + target.getName() + ".");
         }
      }
   }

   private void handleUntrust(Player player, String[] args) {
      if (args.length < 1) {
         player.sendMessage(ChatColor.RED + "Usage: /untrust <player>");
      } else {
         OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
         if (!this.trustManager.getTrustedPlayers(player).contains(target.getUniqueId().toString())) {
            player.sendMessage(ChatColor.RED + "You do not trust this player.");
         } else {
            this.trustManager.removeTrustedPlayer(player, target);
            player.sendMessage(ChatColor.GREEN + "You no longer trust " + target.getName() + ".");
         }
      }
   }
}
