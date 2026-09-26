package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.abilities.ChargedWeapon;
import com.legendaryweapons.abilities.KillTrackedWeapon;
import com.legendaryweapons.abilities.WeaponAbility;
import com.legendaryweapons.guis.WeaponGiveGUI;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;

/**
 * Testing and quality-of-life commands.
 *
 *   /gui                                  give-menu with every weapon, fragment and tool
 *   /resetcd [player]                     clear every cooldown, dash charge, dash lock and freeze immunity
 *   /setkills <amount> [player] [weapon]  kill count on a kill-based weapon (Sword of Eclipse, Hermes's Blade)
 *   /charge [percent] [player] [weapon]   ultimate meter on a charged weapon (Luminosity, Titan's Axe)
 *   /lwtest reset [player]                undo every effect: size, freeze, disguise, meters, cooldowns
 *   /lwtest finishrituals                 complete every running ritual now
 *   /lwtest fragments                     one stack of every fragment and event item
 *   /lwtest give <item> [amount]          FRAGMENT_T1..T4, LUMINOSITY_FRAGMENT, HILL_MARKER, ALL_IN_ONE_POTION, AMETHYST_PICKAXE/_SHOVEL/_AXE
 *   /lwfx <full|reduced|minimal|off>      per-player particle level (every player can use this one)
 *
 * When [weapon] is left out, the weapon in the target's main hand is used.
 */
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
      String name = command.getName().toLowerCase(Locale.ROOT);
      if (name.equals("lwfx")) {
         return this.handleFx(sender, args);
      } else if (!sender.hasPermission("legendaryweapons.admin")) {
         sender.sendMessage("§cYou don't have permission to use this command.");
         return true;
      } else {
         return switch (name) {
            case "gui" -> this.handleGui(sender);
            case "resetcd" -> this.handleResetCd(sender, args);
            case "setkills" -> this.handleSetKills(sender, args);
            case "charge" -> this.handleCharge(sender, args);
            case "lwtest" -> this.handleTest(sender, args);
            default -> false;
         };
      }
   }

   private boolean handleFx(CommandSender sender, String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage("§cRun this in-game.");
      } else {
         ParticleManager.Level level = args.length > 0 ? ParticleManager.Level.parse(args[0]) : null;
         if (level == null) {
            player.sendMessage(Component.text("Particles: " + this.plugin.getParticleManager().levelOf(player).name().toLowerCase(Locale.ROOT) + ". Use /lwfx <full|reduced|minimal|off>.", NamedTextColor.GRAY));
         } else {
            this.plugin.getParticleManager().setLevel(player, level);
            player.sendMessage(Component.text("Legendary weapon particles set to " + level.name().toLowerCase(Locale.ROOT) + ".", NamedTextColor.GREEN));
         }
      }

      return true;
   }

   private boolean handleGui(CommandSender sender) {
      if (sender instanceof Player player) {
         this.giveGUI.open(player);
      } else {
         sender.sendMessage("§cThis command can only be used by a player.");
      }

      return true;
   }

   private boolean handleResetCd(CommandSender sender, String[] args) {
      Player target = this.resolveTarget(sender, args.length > 0 ? args[0] : null);
      if (target != null) {
         this.resetCooldowns(target);
         sender.sendMessage("§aReset all cooldowns, dash charges and locks for §f" + target.getName() + "§a.");
         if (!target.equals(sender)) {
            target.sendMessage("§aYour legendary cooldowns were reset.");
         }
      }

      return true;
   }

   private void resetCooldowns(Player target) {
      this.plugin.resetAllCooldowns(target);
   }

   /** Split trailing args into an optional player name and an optional weapon key, in either order. */
   private Object[] targetAndWeapon(CommandSender sender, String[] args, int from) {
      String playerName = null;
      String weaponKey = null;

      for (int i = from; i < args.length; i++) {
         if (this.plugin.getWeaponManager().getWeaponKeys().contains(args[i].toLowerCase(Locale.ROOT))) {
            weaponKey = args[i].toLowerCase(Locale.ROOT);
         } else {
            playerName = args[i];
         }
      }

      Player target = this.resolveTarget(sender, playerName);
      if (target != null && weaponKey == null) {
         weaponKey = this.dispatcher.heldWeaponKey(target);
      }

      return new Object[]{target, weaponKey};
   }

   private boolean handleSetKills(CommandSender sender, String[] args) {
      if (args.length < 1) {
         sender.sendMessage("§cUsage: /setkills <amount> [player] [weapon]");
         return true;
      } else {
         int kills;
         try {
            kills = Math.max(0, Integer.parseInt(args[0]));
         } catch (NumberFormatException var7) {
            sender.sendMessage("§c'" + args[0] + "' is not a number.");
            return true;
         }

         Object[] resolved = this.targetAndWeapon(sender, args, 1);
         if (resolved[0] instanceof Player target) {
            if (this.dispatcher.get((String)resolved[1]) instanceof KillTrackedWeapon weapon) {
               weapon.setKillCount(target, kills);
               sender.sendMessage("§aSet §f" + target.getName() + "§a's " + weapon.weaponKey() + " kills to §f" + kills + "§a.");
            } else {
               sender.sendMessage("§cNo kill-based weapon. Hold one, or name it: " + String.join(", ", this.killWeapons()));
            }
         }

         return true;
      }
   }

   private boolean handleCharge(CommandSender sender, String[] args) {
      double percent = 100.0;
      int from = 0;
      if (args.length >= 1) {
         try {
            percent = Double.parseDouble(args[0]);
            from = 1;
         } catch (NumberFormatException var9) {
            // "/charge luminosity" -- no percent given
         }
      }

      double fraction = Math.max(0.0, Math.min(1.0, percent / 100.0));
      Object[] resolved = this.targetAndWeapon(sender, args, from);
      if (resolved[0] instanceof Player target) {
         if (this.dispatcher.get((String)resolved[1]) instanceof ChargedWeapon weapon) {
            weapon.setChargeFraction(target, fraction);
            sender.sendMessage("§aSet §f" + target.getName() + "§a's " + weapon.weaponKey() + " charge to §f" + Math.round(fraction * 100.0) + "%§a.");
         } else {
            sender.sendMessage("§cNo charged weapon. Hold one, or name it: " + String.join(", ", this.chargedWeapons()));
         }
      }

      return true;
   }

   private boolean handleTest(CommandSender sender, String[] args) {
      String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
      switch (sub) {
         case "reset": {
            Player target = this.resolveTarget(sender, args.length > 1 ? args[1] : null);
            if (target != null) {
               this.resetCooldowns(target);
               this.plugin.getChargeMeter().clear(target);
               this.plugin.getComboTracker().resetAll(target);
               this.plugin.getBladeOfDiminutionListener().resetSize(target);
               if (!this.plugin.getEventManager().isMostWanted(target)) {
                  this.plugin.getDisguiseManager().remove(target);
               }

               for (PotionEffect effect : new ArrayList<>(target.getActivePotionEffects())) {
                  target.removePotionEffect(effect.getType());
               }

               target.setFreezeTicks(0);
               sender.sendMessage("§aCleared every legendary effect on §f" + target.getName() + "§a.");
            }

            return true;
         }
         case "finishrituals":
            sender.sendMessage("§aCompleted " + this.plugin.getRitualManager().completeAll() + " ritual(s).");
            return true;
         case "fragments":
            if (sender instanceof Player player) {
               for (ItemStack item : CustomItems.get().sampleSet()) {
                  player.getInventory().addItem(new ItemStack[]{item}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
               }

               sender.sendMessage("§aGave one of every fragment and event item.");
            }

            return true;
         case "give":
            if (sender instanceof Player player && args.length > 1) {
               int amount = 1;
               if (args.length > 2) {
                  try {
                     amount = Math.max(1, Integer.parseInt(args[2]));
                  } catch (NumberFormatException var8) {
                  }
               }

               ItemStack item = CustomItems.get().fromToken(args[1], amount);
               if (item == null) {
                  sender.sendMessage("§cUnknown item. Try FRAGMENT_T1..FRAGMENT_T4, LUMINOSITY_FRAGMENT, HILL_MARKER, ALL_IN_ONE_POTION, AMETHYST_PICKAXE, AMETHYST_SHOVEL, AMETHYST_AXE.");
               } else {
                  player.getInventory().addItem(new ItemStack[]{item});
               }
            } else {
               sender.sendMessage("§cUsage: /lwtest give <item> [amount]");
            }

            return true;
         default:
            sender.sendMessage("§6/lwtest reset [player] §7- undo sizes, freezes, disguises, meters, cooldowns");
            sender.sendMessage("§6/lwtest finishrituals §7- complete every running ritual");
            sender.sendMessage("§6/lwtest fragments §7- one of every fragment and event item");
            sender.sendMessage("§6/lwtest give <item> [amount]");
            return true;
      }
   }

   private List<String> killWeapons() {
      List<String> out = new ArrayList<>();

      for (WeaponAbility ability : this.dispatcher.all()) {
         if (ability instanceof KillTrackedWeapon) {
            out.add(ability.weaponKey());
         }
      }

      return out;
   }

   private List<String> chargedWeapons() {
      List<String> out = new ArrayList<>();

      for (WeaponAbility ability : this.dispatcher.all()) {
         if (ability instanceof ChargedWeapon) {
            out.add(ability.weaponKey());
         }
      }

      return out;
   }

   private Player resolveTarget(CommandSender sender, String name) {
      if (name != null) {
         Player named = Bukkit.getPlayerExact(name);
         if (named == null) {
            sender.sendMessage("§cPlayer '" + name + "' is not online.");
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
      String name = command.getName().toLowerCase(Locale.ROOT);
      List<String> options = new ArrayList<>();
      List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
      switch (name) {
         case "lwfx":
            if (args.length == 1) {
               options.addAll(List.of("full", "reduced", "minimal", "off"));
            }
            break;
         case "resetcd":
            if (args.length == 1) {
               options.addAll(players);
            }
            break;
         case "setkills":
            if (args.length >= 2) {
               options.addAll(players);
               options.addAll(this.killWeapons());
            }
            break;
         case "charge":
            if (args.length == 1) {
               options.addAll(List.of("0", "25", "50", "75", "100"));
            } else {
               options.addAll(players);
               options.addAll(this.chargedWeapons());
            }
            break;
         case "lwtest":
            if (args.length == 1) {
               options.addAll(List.of("reset", "finishrituals", "fragments", "give"));
            } else if (args.length == 2 && args[0].equalsIgnoreCase("reset")) {
               options.addAll(players);
            } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
               options.addAll(List.of("FRAGMENT_T1", "FRAGMENT_T2", "FRAGMENT_T3", "FRAGMENT_T4", "LUMINOSITY_FRAGMENT", "HILL_MARKER", "ALL_IN_ONE_POTION", "AMETHYST_PICKAXE", "AMETHYST_SHOVEL", "AMETHYST_AXE"));
            }
      }

      String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
      return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
   }
}
