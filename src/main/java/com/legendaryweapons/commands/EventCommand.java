package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.EventDirector;
import com.legendaryweapons.events.EventManager;
import com.legendaryweapons.events.SupplyDrops;
import com.legendaryweapons.items.CustomItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * /lwevent -- every event is started by hand.
 *
 *   bounty start [minutes] | stop | status
 *   koth marker | setcenter | start [minutes] | stop | status
 *   supplydrop <early|mid|late> [count] [world]
 *   mostwanted set <player> | confirm | clear | status
 *   fallenstar start [world] | stop | status
 *   bloodmoon start [world] | stop | status
 *   director on | off | status | skip | run <event>
 *   outpost create <name> <type> [radius] | remove <name> | reset <name> | list
 *   season status | reset confirm | give <player> <amount>
 *   status | stopall
 */
public class EventCommand implements CommandExecutor, TabCompleter {
   private final LegendaryWeapons plugin;

   public EventCommand(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   private EventManager events() {
      return this.plugin.getEventManager();
   }

   private static void reply(CommandSender sender, String error, String success) {
      if (error != null) {
         sender.sendMessage(Component.text(error, NamedTextColor.RED));
      } else if (success != null) {
         sender.sendMessage(Component.text(success, NamedTextColor.GREEN));
      }
   }

   private static int minutes(String[] args, int index, int fallback) {
      if (args.length > index) {
         try {
            return Math.max(1, Integer.parseInt(args[index]));
         } catch (NumberFormatException var4) {
            return fallback;
         }
      } else {
         return fallback;
      }
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!sender.hasPermission("legendaryweapons.admin")) {
         sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
         return true;
      } else if (args.length == 0) {
         this.usage(sender);
         return true;
      } else {
         String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
         switch (args[0].toLowerCase(Locale.ROOT)) {
            case "bounty":
               switch (action) {
                  case "start" -> reply(sender, this.events().bounty().start(minutes(args, 2, this.plugin.getConfig().getInt("events.bounty.default-minutes", 30))), "Bounty hunt started.");
                  case "stop" -> {
                     this.events().bounty().stop(true);
                     reply(sender, null, "Bounty hunt stopped and prizes awarded.");
                  }
                  default -> sender.sendMessage(Component.text(this.events().bounty().status(), NamedTextColor.GRAY));
               }
               break;
            case "koth":
               switch (action) {
                  case "marker" -> {
                     if (sender instanceof Player player) {
                        player.getInventory().addItem(new ItemStack[]{CustomItems.get().create(CustomItems.HILL_MARKER, 1)});
                        reply(sender, null, "Place the Hill Marker where the hill should be.");
                     } else {
                        reply(sender, "Run this in-game.", null);
                     }
                  }
                  case "setcenter" -> {
                     if (sender instanceof Player player) {
                        this.events().koth().setCenter(player.getLocation());
                        reply(sender, null, "Hill centre set to your position.");
                     } else {
                        reply(sender, "Run this in-game.", null);
                     }
                  }
                  case "start" -> reply(sender, this.events().koth().start(minutes(args, 2, this.plugin.getConfig().getInt("events.koth.default-minutes", 15))), "King of the Hill started.");
                  case "stop" -> {
                     this.events().koth().stop(true);
                     reply(sender, null, "King of the Hill stopped and prizes awarded.");
                  }
                  default -> sender.sendMessage(Component.text(this.events().koth().status(), NamedTextColor.GRAY));
               }
               break;
            case "supplydrop":
            case "supply":
               if (args.length < 2) {
                  reply(sender, "Usage: /lwevent supplydrop <early|mid|late> [count] [world]", null);
               } else {
                  int count = 1;
                  if (args.length > 2) {
                     try {
                        count = Integer.parseInt(args[2]);
                     } catch (NumberFormatException var10) {
                        reply(sender, "'" + args[2] + "' is not a number.", null);
                        return true;
                     }
                  }

                  World world = args.length > 3 ? Bukkit.getWorld(args[3]) : (sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0));
                  if (world == null) {
                     reply(sender, "Unknown world '" + args[3] + "'.", null);
                  } else {
                     reply(sender, this.events().supplyDrops().spawn(args[1], count, world), "Supply drop" + (count > 1 ? "s" : "") + " on the way.");
                  }
               }
               break;
            case "mostwanted":
            case "mw":
               switch (action) {
                  case "set" -> {
                     if (args.length < 3) {
                        reply(sender, "Usage: /lwevent mostwanted set <player>", null);
                     } else {
                        reply(sender, this.events().mostWanted().set(args[2]), args[2] + " is now the Most Wanted. They will be anonymous from the moment they join; confirm with /lwevent mostwanted confirm.");
                     }
                  }
                  case "confirm", "start" -> reply(sender, this.events().mostWanted().confirm(), "The Most Wanted event has started.");
                  case "clear", "stop" -> reply(sender, this.events().mostWanted().clear(), "Most Wanted cleared.");
                  default -> sender.sendMessage(Component.text(this.events().mostWanted().status(), NamedTextColor.GRAY));
               }
               break;
            case "fallenstar":
            case "star":
               switch (action) {
                  case "start" -> {
                     World world = args.length > 2 ? Bukkit.getWorld(args[2]) : (sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0));
                     if (world == null) {
                        reply(sender, "Unknown world '" + args[2] + "'.", null);
                     } else {
                        reply(sender, this.events().fallenStar().start(world), "A star is falling.");
                     }
                  }
                  case "stop" -> {
                     this.events().fallenStar().stop();
                     reply(sender, null, "Fallen Star stopped; the crater heals shortly.");
                  }
                  default -> sender.sendMessage(Component.text(this.events().fallenStar().status(), NamedTextColor.GRAY));
               }
               break;
            case "bloodmoon":
               switch (action) {
                  case "start" -> {
                     World world = args.length > 2 ? Bukkit.getWorld(args[2]) : (sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0));
                     if (world == null) {
                        reply(sender, "Unknown world '" + args[2] + "'.", null);
                     } else {
                        reply(sender, this.events().bloodMoon().arm(world), "Blood Moon armed: it rises at nightfall (or now, if it is night).");
                     }
                  }
                  case "stop" -> {
                     this.events().bloodMoon().stop();
                     reply(sender, null, "Blood Moon stopped.");
                  }
                  default -> sender.sendMessage(Component.text(this.events().bloodMoon().status(), NamedTextColor.GRAY));
               }
               break;
            case "director":
               switch (action) {
                  case "on", "enable" -> {
                     this.events().director().setEnabled(true);
                     reply(sender, null, "Event Director ON.");
                  }
                  case "off", "disable" -> {
                     this.events().director().setEnabled(false);
                     reply(sender, null, "Event Director OFF.");
                  }
                  case "skip" -> {
                     this.events().director().skip();
                     reply(sender, null, "Skipped the next planned event.");
                  }
                  case "run" -> {
                     if (args.length < 3) {
                        reply(sender, "Usage: /lwevent director run <supplydrop[:tier]|bounty|koth|fallenstar|bloodmoon>", null);
                     } else {
                        reply(sender, this.events().director().run(args[2]), "Started " + EventDirector.displayName(args[2]) + ".");
                     }
                  }
                  default -> this.events().director().status().forEach(line -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
               }
               break;
            case "outpost":
            case "outposts":
               switch (action) {
                  case "create" -> {
                     if (!(sender instanceof Player player)) {
                        reply(sender, "Run this in-game, standing at the outpost's centre.", null);
                     } else if (args.length < 4) {
                        reply(sender, "Usage: /lwevent outpost create <name> <type> [radius]. Types: " + this.events().outposts().types(), null);
                     } else {
                        double radius = 0.0;
                        if (args.length > 4) {
                           try {
                              radius = Double.parseDouble(args[4]);
                           } catch (NumberFormatException var12) {
                              reply(sender, "'" + args[4] + "' is not a number.", null);
                              return true;
                           }
                        }

                        reply(sender, this.events().outposts().create(args[2], args[3].toLowerCase(Locale.ROOT), player.getLocation(), radius), "Outpost '" + args[2] + "' created here.");
                     }
                  }
                  case "remove", "delete" -> reply(sender, args.length < 3 ? "Usage: /lwevent outpost remove <name>" : this.events().outposts().remove(args[2]), "Outpost removed.");
                  case "reset" -> reply(sender, args.length < 3 ? "Usage: /lwevent outpost reset <name>" : this.events().outposts().reset(args[2]), "Outpost is unclaimed again.");
                  default -> this.events().outposts().list().forEach(sender::sendMessage);
               }
               break;
            case "season":
               switch (action) {
                  case "reset" -> {
                     if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                        reply(sender, "This ends the season, pays the top three and wipes the boards. Run /lwevent season reset confirm", null);
                     } else {
                        this.events().season().reset();
                     }
                  }
                  case "give" -> {
                     if (args.length < 4) {
                        reply(sender, "Usage: /lwevent season give <player> <amount>", null);
                     } else {
                        java.util.UUID id = this.plugin.getPlayerRosterManager().idOf(args[2]);
                        try {
                           double amount = Double.parseDouble(args[3]);
                           if (id == null) {
                              reply(sender, "Unknown player '" + args[2] + "'.", null);
                           } else {
                              this.events().season().give(id, args[2], amount);
                              reply(sender, null, "Gave " + args[3] + " Renown to " + args[2] + ".");
                           }
                        } catch (NumberFormatException var11) {
                           reply(sender, "'" + args[3] + "' is not a number.", null);
                        }
                     }
                  }
                  default -> sender.sendMessage(Component.text(this.events().season().status(), NamedTextColor.GRAY));
               }
               break;
            case "stopall":
               this.events().bounty().stop(false);
               this.events().koth().stop(false);
               this.events().fallenStar().stop();
               this.events().bloodMoon().stop();
               reply(sender, null, "Stopped the bounty hunt, King of the Hill, Fallen Star and Blood Moon (no prizes). Most Wanted and outposts are left as they are.");
               break;
            case "status":
               sender.sendMessage(Component.text(this.events().bounty().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().koth().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().mostWanted().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text("Supply drops waiting to be claimed: " + this.events().supplyDrops().active(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().fallenStar().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().bloodMoon().status(), NamedTextColor.GRAY));
               this.events().relics().status().forEach(line -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
               this.events().director().status().forEach(line -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
               sender.sendMessage(Component.text(this.events().season().status(), NamedTextColor.GRAY));
               break;
            default:
               this.usage(sender);
         }

         return true;
      }
   }

   private void usage(CommandSender sender) {
      for (String line : new String[]{
         "/lwevent bounty start [minutes] | stop | status",
         "/lwevent koth marker | setcenter | start [minutes] | stop | status",
         "/lwevent supplydrop <early|mid|late> [count] [world]",
         "/lwevent mostwanted set <player> | confirm | clear | status",
         "/lwevent fallenstar start [world] | stop | status",
         "/lwevent bloodmoon start [world] | stop | status",
         "/lwevent director on | off | status | skip | run <event>",
         "/lwevent outpost create <name> <type> [radius] | remove <name> | reset <name> | list",
         "/lwevent season status | reset confirm | give <player> <amount>",
         "/lwevent status | stopall"
      }) {
         sender.sendMessage(Component.text(line, NamedTextColor.GOLD));
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      List<String> options = switch (args.length) {
         case 1 -> List.of("bounty", "koth", "supplydrop", "mostwanted", "fallenstar", "bloodmoon", "director", "outpost", "season", "status", "stopall");
         case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
            case "bounty" -> List.of("start", "stop", "status");
            case "koth" -> List.of("marker", "setcenter", "start", "stop", "status");
            case "supplydrop", "supply" -> SupplyDrops.TIERS;
            case "mostwanted", "mw" -> List.of("set", "confirm", "clear", "status");
            case "fallenstar", "star", "bloodmoon" -> List.of("start", "stop", "status");
            case "director" -> List.of("on", "off", "status", "skip", "run");
            case "outpost", "outposts" -> List.of("create", "remove", "reset", "list");
            case "season" -> List.of("status", "reset", "give");
            default -> List.of();
         };
         case 3 -> args[0].equalsIgnoreCase("mostwanted") && args[1].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("season") && args[1].equalsIgnoreCase("give")
            ? this.plugin.getPlayerRosterManager().entries().stream().map(java.util.Map.Entry::getValue).toList()
            : (args[0].equalsIgnoreCase("director") && args[1].equalsIgnoreCase("run") ? List.of("supplydrop:early", "supplydrop:mid", "supplydrop:late", "bounty", "koth", "fallenstar", "bloodmoon")
            : (args[0].toLowerCase(Locale.ROOT).startsWith("outpost") && !args[1].equalsIgnoreCase("create") ? this.events().outposts().names()
            : (args[0].equalsIgnoreCase("season") && args[1].equalsIgnoreCase("reset") ? List.of("confirm")
            : ((args[0].equalsIgnoreCase("fallenstar") || args[0].equalsIgnoreCase("bloodmoon")) && args[1].equalsIgnoreCase("start") ? Bukkit.getWorlds().stream().map(World::getName).toList()
            : (args[1].equalsIgnoreCase("start") ? List.of("5", "10", "15", "30") : List.of())))));
         case 4 -> args[0].toLowerCase(Locale.ROOT).startsWith("supply") ? Bukkit.getWorlds().stream().map(World::getName).toList()
            : (args[0].toLowerCase(Locale.ROOT).startsWith("outpost") && args[1].equalsIgnoreCase("create") ? this.events().outposts().types() : List.of());
         default -> List.of();
      };
      String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
      List<String> out = new ArrayList<>();

      for (String option : options) {
         if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
            out.add(option);
         }
      }

      return out;
   }
}
