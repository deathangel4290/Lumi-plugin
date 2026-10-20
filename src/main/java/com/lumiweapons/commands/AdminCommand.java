package com.lumiweapons.commands;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.listeners.SwordOfEclipseListener;
import com.lumiweapons.managers.WeaponManager;
import com.lumiweapons.rituals.RitualBlock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class AdminCommand implements CommandExecutor, TabCompleter {
   private final LumiWeapons plugin;
   private final WeaponManager weaponManager;
   private final SwordOfEclipseListener swordOfEclipseListener;

   public AdminCommand(LumiWeapons plugin, SwordOfEclipseListener swordOfEclipseListener) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.swordOfEclipseListener = swordOfEclipseListener;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!sender.hasPermission("lumiweapons.admin")) {
         sender.sendMessage("§cYou don't have permission to use this command.");
         return true;
      } else if (args.length == 0 && sender instanceof Player panel) {
         new com.lumiweapons.guis.AdminMenu(this.plugin, panel, null).open();
         return true;
      } else if (args.length == 0) {
         sender.sendMessage("Usage: /lw <give|giveritualblock|reload|setkills|resetcooldowns>");
         return true;
      } else if (args[0].equalsIgnoreCase("give")) {
         if (sender instanceof Player player) {
            if (args.length < 2) {
               player.sendMessage("Usage: /lw give <weapon>");
               return true;
            } else {
               String weaponKey = args[1];
               ItemStack weapon = this.weaponManager.getWeapon(weaponKey);
               if (weapon.getType().isAir()) {
                  player.sendMessage("Unknown weapon: " + weaponKey);
                  return true;
               } else {
                  player.getInventory().addItem(new ItemStack[]{weapon});
                  player.sendMessage("You have been given the " + weapon.getItemMeta().getDisplayName());
                  return true;
               }
            }
         } else {
            sender.sendMessage("This command can only be used by a player.");
            return true;
         }
      } else if (args[0].equalsIgnoreCase("giveritualblock")) {
         if (sender instanceof Player playerx) {
            playerx.getInventory().addItem(new ItemStack[]{RitualBlock.getRitualBlockItem()});
            playerx.sendMessage("You have received a Ritual Block.");
            return true;
         } else {
            sender.sendMessage("This command can only be used by a player.");
            return true;
         }
      } else if (args[0].equalsIgnoreCase("reload")) {
         this.plugin.reloadSettings();
         sender.sendMessage("§aLumiWeapons config reloaded.");
         return true;
      } else if (args[0].equalsIgnoreCase("setkills")) {
         if (sender instanceof Player playerx) {
            if (args.length < 2) {
               playerx.sendMessage("Usage: /lw setkills <amount>");
               return true;
            } else {
               int kills = Integer.parseInt(args[1]);
               this.swordOfEclipseListener.setKills(playerx, kills);
               playerx.sendMessage("§aYour Sword of Eclipse kills have been set to " + kills);
               return true;
            }
         } else {
            sender.sendMessage("This command can only be used by a player.");
            return true;
         }
      } else if (args[0].equalsIgnoreCase("resetcooldowns")) {
         if (sender instanceof Player playerxx) {
            this.plugin.resetAllCooldowns(playerxx);
            sender.sendMessage("§aAll legendary weapon cooldowns have been reset.");
            return true;
         } else {
            sender.sendMessage("This command can only be used by a player.");
            return true;
         }
      } else {
         return false;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      if (args.length == 1) {
         return Arrays.asList("give", "giveritualblock", "reload", "setkills", "resetcooldowns");
      } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
         Set<String> weaponKeys = this.weaponManager.getWeaponKeys();
         return weaponKeys.stream().filter(key -> key.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
      } else {
         return new ArrayList<>();
      }
   }
}
