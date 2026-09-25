package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.abilities.ChargedWeapon;
import com.legendaryweapons.abilities.KillTrackedWeapon;
import com.legendaryweapons.guis.WeaponGiveGUI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class TestingCommand implements CommandExecutor, TabCompleter {
   private final LegendaryWeapons plugin;
   private final AbilityDispatcher dispatcher;
   private final WeaponGiveGUI giveGUI;

   public TestingCommand(LegendaryWeapons plugin, AbilityDispatcher dispatcher, WeaponGiveGUI giveGUI) {
      this.plugin = plugin;
      this.dispatcher = dispatcher;
      this.giveGUI = giveGUI;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!sender.hasPermission("legendaryweapons.admin")) {
         sender.sendMessage("§cYou don't have permission to use this command.");
         return true;
      } else {
         String var5 = command.getName().toLowerCase();
         switch (var5) {
            case "gui":
               return this.handleGui(sender);
            case "resetcd":
               return this.handleResetCd(sender, args);
            case "setkills":
               return this.handleSetKills(sender, args);
            case "charge":
               return this.handleCharge(sender, args);
            default:
               return false;
         }
      }
   }

   private boolean handleGui(CommandSender sender) {
      if (sender instanceof Player player) {
         this.giveGUI.open(player);
         return true;
      } else {
         sender.sendMessage("§cThis command can only be used by a player.");
         return true;
      }
   }

   private boolean handleResetCd(CommandSender sender, String[] args) {
      Player target = this.resolveTarget(sender, args, 0);
      if (target == null) {
         return true;
      } else {
         this.plugin.getCooldownManager().clearAll(target);
         this.plugin.getChargePool().clear(target);

         for (String key : this.plugin.getWeaponManager().getWeaponKeys()) {
            Material material = this.plugin.getConfigManager().getWeaponMaterial(key);
            if (material != null && !material.isAir()) {
               target.setCooldown(material, 0);
            }
         }

         sender.sendMessage("§aReset all cooldowns and charge pools for §f" + target.getName() + "§a.");
         if (!target.equals(sender)) {
            target.sendMessage("§aYour legendary cooldowns were reset.");
         }

         return true;
      }
   }

   private boolean handleSetKills(CommandSender sender, String[] args) {
      if (args.length < 1) {
         sender.sendMessage("§cUsage: /setkills <amount> [player]");
         return true;
      } else {
         int kills;
         try {
            kills = Math.max(0, Integer.parseInt(args[0]));
         } catch (NumberFormatException var7) {
            sender.sendMessage("§c'" + args[0] + "' is not a number.");
            return true;
         }

         Player target = this.resolveTarget(sender, args, 1);
         if (target == null) {
            return true;
         } else if (this.dispatcher.get(this.dispatcher.heldWeaponKey(target)) instanceof KillTrackedWeapon killWeapon) {
            killWeapon.setKillCount(target, kills);
            sender.sendMessage("§aSet §f" + target.getName() + "§a's " + killWeapon.weaponKey() + " kills to §f" + kills + "§a.");
            return true;
         } else {
            sender.sendMessage("§c" + target.getName() + " is not holding a kill-based weapon. Hold e.g. the Sword of Eclipse or Hermes's Blade.");
            return true;
         }
      }
   }

   private boolean handleCharge(CommandSender sender, String[] args) {
      double percent = 100.0;
      if (args.length >= 1) {
         try {
            percent = Double.parseDouble(args[0]);
         } catch (NumberFormatException var10) {
            sender.sendMessage("§c'" + args[0] + "' is not a number.");
            return true;
         }
      }

      double fraction = Math.max(0.0, Math.min(1.0, percent / 100.0));
      Player target = this.resolveTarget(sender, args, 1);
      if (target == null) {
         return true;
      } else if (this.dispatcher.get(this.dispatcher.heldWeaponKey(target)) instanceof ChargedWeapon chargedWeapon) {
         chargedWeapon.setChargeFraction(target, fraction);
         sender.sendMessage("§aSet §f" + target.getName() + "§a's " + chargedWeapon.weaponKey() + " charge to §f" + Math.round(fraction * 100.0) + "%§a.");
         return true;
      } else {
         sender.sendMessage("§c" + target.getName() + " is not holding a charged weapon. Hold e.g. Titan's Axe or Luminosity.");
         return true;
      }
   }

   private Player resolveTarget(CommandSender sender, String[] args, int nameIndex) {
      if (args.length > nameIndex) {
         Player named = Bukkit.getPlayerExact(args[nameIndex]);
         if (named == null) {
            sender.sendMessage("§cPlayer '" + args[nameIndex] + "' is not online.");
         }

         return named;
      } else if (sender instanceof Player player) {
         return player;
      } else {
         sender.sendMessage("§cRun this from in-game, or name a player.");
         return null;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      String name = command.getName().toLowerCase();

      int nameArg = switch (name) {
         case "setkills", "charge" -> 2;
         case "resetcd" -> 1;
         default -> -1;
      };
      if (args.length == nameArg) {
         String prefix = args[args.length - 1].toLowerCase();
         return Bukkit.getOnlinePlayers().stream().<String>map(Player::getName).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
      } else {
         return new ArrayList<>();
      }
   }
}
