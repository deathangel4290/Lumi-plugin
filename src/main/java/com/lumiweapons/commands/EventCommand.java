package com.lumiweapons.commands;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.events.EventDirector;
import com.lumiweapons.events.EventManager;
import com.lumiweapons.events.SupplyDrops;
import com.lumiweapons.items.CustomItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
 *   draft start [captain...] | cancel | status   (no captains: opens the set-up menu)
 *   altar place | scatter [count] [radius] | remove | list    Ancient Altars
 *   team add <player> <team> | remove <player> | create <name> <colour> | disband <team|all> | list
 *   boss arena <id> [radius] | start [id] | stop | status
 *   status | stopall
 */
public class EventCommand implements CommandExecutor, TabCompleter {
   private final LumiWeapons plugin;

   public EventCommand(LumiWeapons plugin) {
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
      if (!sender.hasPermission("lumiweapons.admin")) {
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
            case "chosen":
               switch (action) {
                  case "pick", "random", "start", "choose" -> {
                     String error = this.events().chosen().pick();
                     reply(sender, error, error == null ? this.events().chosen().status() : null);
                  }
                  case "set", "give" -> {
                     String error = args.length < 3 ? "Usage: /lwevent chosen set <player>" : this.events().chosen().set(args[2]);
                     reply(sender, error, error == null ? this.events().chosen().status() : null);
                  }
                  case "clear", "stop" -> reply(sender, this.events().chosen().clear(), "Nobody is the Chosen now.");
                  default -> sender.sendMessage(Component.text(this.events().chosen().status(), NamedTextColor.GRAY));
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
            case "draft":
               switch (action) {
                  case "start" -> {
                     List<Player> captains = new ArrayList<>();
                     for (int i = 2; i < args.length; i++) {
                        Player captain = Bukkit.getPlayerExact(args[i]);
                        if (captain == null) {
                           reply(sender, "'" + args[i] + "' is not online.", null);
                           return true;
                        }

                        captains.add(captain);
                     }

                     if (captains.isEmpty() && sender instanceof Player player) {
                        new com.lumiweapons.guis.DraftMenu.Setup(this.plugin, player, new com.lumiweapons.guis.LumiMenu(this.plugin, player)).open();
                     } else {
                        reply(sender, com.lumiweapons.teams.Draft.start(this.plugin, this.plugin.getTeamManager(), captains, new ArrayList<>(Bukkit.getOnlinePlayers())), null);
                     }
                  }
                  case "cancel", "stop" -> {
                     var draft = this.plugin.getTeamManager().draft();
                     if (draft == null) {
                        reply(sender, "No draft is running.", null);
                     } else {
                        draft.cancel(null);
                     }
                  }
                  default -> {
                     var draft = this.plugin.getTeamManager().draft();
                     reply(sender, null, draft == null ? "No draft is running. /lwevent draft start [captains...]" : "Draft running: " + draft.current().name + " is picking, " + draft.pool().size() + " left in the pool.");
                  }
               }
               break;
            case "team":
            case "teams": {
               var teams = this.plugin.getTeamManager();
               switch (action) {
                  case "add" -> {
                     if (args.length < 4) {
                        reply(sender, "Usage: /lwevent team add <player> <team>", null);
                     } else {
                        java.util.UUID id = this.plugin.getPlayerRosterManager().idOf(args[2]);
                        var team = teams.byName(String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)));
                        if (id == null) {
                           reply(sender, "Nobody called '" + args[2] + "' has played here.", null);
                        } else if (team == null) {
                           reply(sender, "No team called '" + args[3] + "'.", null);
                        } else {
                           teams.add(team, id, args[2]);
                           reply(sender, null, args[2] + " is now on " + team.name + ".");
                        }
                     }
                  }
                  case "remove", "kick" -> {
                     java.util.UUID id = args.length < 3 ? null : this.plugin.getPlayerRosterManager().idOf(args[2]);
                     if (id == null || teams.teamOf(id) == null) {
                        reply(sender, "Usage: /lwevent team remove <player on a team>", null);
                     } else {
                        teams.remove(id);
                        reply(sender, null, args[2] + " is no longer on a team.");
                     }
                  }
                  case "create" -> {
                     NamedTextColor colour = args.length < 4 ? null : NamedTextColor.NAMES.value(args[3].toLowerCase(Locale.ROOT));
                     if (args.length < 4 || colour == null) {
                        reply(sender, "Usage: /lwevent team create <name> <red|blue|green|yellow|aqua|light_purple|gold|white>", null);
                     } else {
                        teams.create(args[2], colour, null, null);
                        reply(sender, null, "Team " + args[2] + " made. Add players with /lwevent team add <player> " + args[2]);
                     }
                  }
                  case "disband" -> {
                     if (args.length > 2 && args[2].equalsIgnoreCase("all")) {
                        teams.disbandAll();
                        reply(sender, null, "Every team is disbanded.");
                     } else {
                        var team = args.length < 3 ? null : teams.byName(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
                        if (team == null) {
                           reply(sender, "Usage: /lwevent team disband <team|all>", null);
                        } else {
                           teams.disband(team);
                           reply(sender, null, team.name + " is disbanded.");
                        }
                     }
                  }
                  default -> TeamCommand.list(sender, teams);
               }
               break;
            }
            case "altar":
            case "altars": {
               var rituals = this.plugin.getRitualManager();
               switch (action) {
                  case "place" -> {
                     if (!(sender instanceof Player player)) {
                        reply(sender, "Run this in-game, standing where the altar should be.", null);
                     } else {
                        String error = rituals.placeAncient(player.getLocation());
                        reply(sender, error, "Ancient Altar placed at your feet (" + rituals.ancientAltars().size() + "/" + rituals.ancientMax() + ").");
                     }
                  }
                  case "scatter" -> {
                     int count = minutes(args, 2, rituals.ancientMax());
                     int radius = minutes(args, 3, this.plugin.getConfig().getInt("ritual.ancient-altars.scatter-radius", 1500));
                     World world = sender instanceof Player player ? player.getWorld() : Bukkit.getWorlds().get(0);
                     int placed = rituals.scatterAncient(world, count, radius);
                     reply(sender, placed == 0 ? "No Ancient Altars placed (the cap is " + rituals.ancientMax() + ", or no open ground was found)." : null,
                        placed + " Ancient Altar" + (placed == 1 ? "" : "s") + " hidden within " + radius + " blocks of spawn. Their spots are in the server log; /lwevent altar list shows them.");
                  }
                  case "remove" -> {
                     if (!(sender instanceof Player player)) {
                        reply(sender, "Run this in-game, next to the altar.", null);
                     } else {
                        Location nearest = null;
                        for (Location altar : rituals.ancientAltars()) {
                           if (altar.getWorld().equals(player.getWorld()) && altar.distanceSquared(player.getLocation()) < 36.0) {
                              nearest = altar;
                           }
                        }

                        if (nearest == null) {
                           reply(sender, "No Ancient Altar within 6 blocks.", null);
                        } else {
                           nearest.getBlock().setType(org.bukkit.Material.AIR);
                           rituals.removeRitualBlock(nearest);
                           reply(sender, null, "Ancient Altar removed.");
                        }
                     }
                  }
                  default -> {
                     sender.sendMessage(Component.text("Altar mode " + rituals.altarMode() + ". Ancient Altars (" + rituals.ancientAltars().size() + "/" + rituals.ancientMax() + "):", NamedTextColor.LIGHT_PURPLE));
                     for (Location altar : rituals.ancientAltars()) {
                        sender.sendMessage(Component.text("  " + altar.getWorld().getName() + " " + altar.getBlockX() + ", " + altar.getBlockY() + ", " + altar.getBlockZ(), NamedTextColor.GRAY));
                     }

                     sender.sendMessage(Component.text("Rituals start " + rituals.clock().describe() + (rituals.clock().windowsEnabled() ? "" : " (ritual.windows.enabled is off)") + ".", NamedTextColor.GRAY));
                  }
               }
               break;
            }
            case "boss":
            case "bosses":
               switch (action) {
                  case "arena" -> {
                     if (!(sender instanceof Player player)) {
                        reply(sender, "Run this in-game, standing at the arena's centre.", null);
                     } else if (args.length < 3) {
                        reply(sender, "Usage: /lwevent boss arena <" + String.join("|", com.lumiweapons.bosses.BossManager.IDS) + "> [radius]", null);
                     } else {
                        double radius = 0.0;
                        if (args.length > 3) {
                           try {
                              radius = Double.parseDouble(args[3]);
                           } catch (NumberFormatException var13) {
                              reply(sender, "'" + args[3] + "' is not a number.", null);
                              return true;
                           }
                        }

                        reply(sender, this.plugin.getBossManager().setArena(args[2], player.getLocation(), radius), "Arena for " + args[2] + " set here.");
                     }
                  }
                  case "start" -> reply(sender, this.plugin.getBossManager().start(args.length > 2 ? args[2] : null), "The boss awakens.");
                  case "stop" -> {
                     this.plugin.getBossManager().stop();
                     reply(sender, null, "Boss dismissed (no rewards).");
                  }
                  default -> sender.sendMessage(Component.text(this.plugin.getBossManager().status(), NamedTextColor.GRAY));
               }
               break;
            case "trader":
            case "traders":
               switch (action) {
                  case "spawn" -> {
                     if (!(sender instanceof Player player)) {
                        reply(sender, "Run this in-game, standing where the trader should stand.", null);
                     } else {
                        this.events().tokenShop().spawn(player.getLocation());
                        reply(sender, null, "Token Trader placed here, facing you.");
                     }
                  }
                  case "remove" -> {
                     if (!(sender instanceof Player player)) {
                        reply(sender, "Run this in-game, next to the trader.", null);
                     } else {
                        int removed = this.events().tokenShop().removeNear(player.getLocation(), 4.0);
                        reply(sender, removed == 0 ? "No Token Trader within 4 blocks." : null, "Removed " + removed + " Token Trader" + (removed == 1 ? "." : "s."));
                     }
                  }
                  default -> {
                     List<Location> homes = this.events().tokenShop().homes();
                     sender.sendMessage(Component.text(homes.isEmpty() ? "No Token Traders placed." : "Token Traders:", NamedTextColor.GOLD));
                     for (Location home : homes) {
                        sender.sendMessage(Component.text("  " + home.getWorld().getName() + " " + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ(), NamedTextColor.GRAY));
                     }
                  }
               }
               break;
            case "stopall":
               this.events().bounty().stop(false);
               this.events().koth().stop(false);
               this.events().fallenStar().stop();
               this.events().bloodMoon().stop();
               this.plugin.getBossManager().stop();
               reply(sender, null, "Stopped the bounty hunt, King of the Hill, Fallen Star, Blood Moon and any boss (no prizes). Most Wanted and outposts are left as they are.");
               break;
            case "status":
               sender.sendMessage(Component.text(this.events().bounty().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().koth().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().mostWanted().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().chosen().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text("Supply drops waiting to be claimed: " + this.events().supplyDrops().active(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().fallenStar().status(), NamedTextColor.GRAY));
               sender.sendMessage(Component.text(this.events().bloodMoon().status(), NamedTextColor.GRAY));
               this.events().relics().status().forEach(line -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
               this.events().director().status().forEach(line -> sender.sendMessage(Component.text(line, NamedTextColor.GRAY)));
               sender.sendMessage(Component.text(this.plugin.getBossManager().status(), NamedTextColor.GRAY));
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
         "/lwevent chosen pick | set <player> | clear | status",
         "/lwevent fallenstar start [world] | stop | status",
         "/lwevent bloodmoon start [world] | stop | status",
         "/lwevent director on | off | status | skip | run <event>",
         "/lwevent outpost create <name> <type> [radius] | remove <name> | reset <name> | list",
         "/lwevent boss arena <boss> [radius] | start [boss] | stop | status",
         "/lwevent trader spawn | remove | list",
         "/lwevent draft start [captains...] | cancel | status",
         "/lwevent altar place | scatter [count] [radius] | remove | list",
         "/lwevent team add <player> <team> | remove <player> | create <name> <colour> | disband <team|all> | list",
         "/lwevent status | stopall"
      }) {
         sender.sendMessage(Component.text(line, NamedTextColor.GOLD));
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      List<String> options = switch (args.length) {
         case 1 -> List.of("bounty", "koth", "supplydrop", "mostwanted", "chosen", "fallenstar", "bloodmoon", "director", "outpost", "draft", "team", "altar", "boss", "trader", "status", "stopall");
         case 2 -> switch (args[0].toLowerCase(Locale.ROOT)) {
            case "bounty" -> List.of("start", "stop", "status");
            case "koth" -> List.of("marker", "setcenter", "start", "stop", "status");
            case "supplydrop", "supply" -> SupplyDrops.TIERS;
            case "mostwanted", "mw" -> List.of("set", "confirm", "clear", "status");
            case "chosen" -> List.of("pick", "set", "clear", "status");
            case "fallenstar", "star", "bloodmoon" -> List.of("start", "stop", "status");
            case "director" -> List.of("on", "off", "status", "skip", "run");
            case "outpost", "outposts" -> List.of("create", "remove", "reset", "list");
            case "boss", "bosses" -> List.of("arena", "start", "stop", "status");
            case "trader", "traders" -> List.of("spawn", "remove", "list");
            case "draft" -> List.of("start", "cancel", "status");
            case "altar", "altars" -> List.of("place", "scatter", "remove", "list");
            case "team", "teams" -> List.of("add", "remove", "create", "disband", "list");
            default -> List.of();
         };
         case 3 -> args[0].equalsIgnoreCase("mostwanted") && args[1].equalsIgnoreCase("set")
            ? this.plugin.getPlayerRosterManager().entries().stream().map(java.util.Map.Entry::getValue).toList()
            : args[0].equalsIgnoreCase("chosen") && args[1].equalsIgnoreCase("set") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
            : (args[0].toLowerCase(Locale.ROOT).startsWith("boss") && (args[1].equalsIgnoreCase("arena") || args[1].equalsIgnoreCase("start")) ? com.lumiweapons.bosses.BossManager.IDS
            : (args[0].equalsIgnoreCase("director") && args[1].equalsIgnoreCase("run") ? List.of("supplydrop:early", "supplydrop:mid", "supplydrop:late", "bounty", "koth", "fallenstar", "bloodmoon", "boss", "boss:hollow_warden", "boss:wither_king", "boss:leviathan", "boss:ender_colossus")
            : (args[0].toLowerCase(Locale.ROOT).startsWith("outpost") && !args[1].equalsIgnoreCase("create") ? this.events().outposts().names()
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
