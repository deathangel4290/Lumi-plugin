package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.guis.AdminEventsMenu;
import com.legendaryweapons.guis.AdminMenu;
import com.legendaryweapons.guis.ConfigMenu;
import com.legendaryweapons.guis.GiveMenu;
import com.legendaryweapons.guis.LumiMenu;
import com.legendaryweapons.guis.RecipesMenu;
import com.legendaryweapons.guis.SettingsMenu;
import com.legendaryweapons.guis.TrustMenu;
import com.legendaryweapons.guis.WorldMenu;
import java.util.ArrayList;
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
 * /lumi [page] -- the Luminara menu. Pages: settings, recipes, team, draft, trust, world; and for
 * admins: admin, config [path], events, give.
 */
public class LumiCommand implements CommandExecutor, TabCompleter {
   private static final List<String> PAGES = List.of("settings", "recipes", "team", "draft", "trust", "world");
   private static final List<String> ADMIN_PAGES = List.of("admin", "config", "events", "give");
   private final LegendaryWeapons plugin;

   public LumiCommand(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage("The Luminara menu is in-game only.");
         return true;
      }

      String page = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
      boolean admin = player.hasPermission(AdminMenu.PERMISSION);
      if (ADMIN_PAGES.contains(page) && !page.equals("events") && !admin) {
         player.sendMessage(Component.text("That page is for admins.", NamedTextColor.RED));
         return true;
      }

      LumiMenu hub = new LumiMenu(this.plugin, player);
      switch (page) {
         case "" -> hub.open();
         case "settings", "binds", "fx" -> new SettingsMenu(this.plugin, player, hub).open();
         case "recipes" -> new RecipesMenu(this.plugin, player, hub).open();
         case "team", "teams" -> new com.legendaryweapons.guis.TeamMenu(this.plugin, player, hub).open();
         case "draft" -> new com.legendaryweapons.guis.DraftMenu(this.plugin, player, hub).open();
         case "trust" -> new TrustMenu(this.plugin, player, hub).open();
         case "world", "events" -> {
            if (page.equals("events") && admin) {
               new AdminEventsMenu(this.plugin, player, new AdminMenu(this.plugin, player, hub)).open();
            } else {
               new WorldMenu(this.plugin, player, hub).open();
            }
         }
         case "admin" -> new AdminMenu(this.plugin, player, hub).open();
         case "config" -> {
            String path = args.length > 1 ? args[1] : null;
            if (path != null && !path.isEmpty() && !this.plugin.getConfig().isConfigurationSection(path)) {
               player.sendMessage(Component.text("No section '" + path + "' in config.yml.", NamedTextColor.RED));
               return true;
            }

            new ConfigMenu(this.plugin, player, new AdminMenu(this.plugin, player, hub), path).open();
         }
         case "give" -> new GiveMenu(this.plugin, player, new AdminMenu(this.plugin, player, hub)).open();
         default -> {
            player.sendMessage(Component.text("Pages: " + String.join(", ", PAGES) + (admin ? ", " + String.join(", ", ADMIN_PAGES) : ""), NamedTextColor.GRAY));
            hub.open();
         }
      }

      return true;
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      List<String> options = new ArrayList<>();
      if (args.length == 1) {
         options.addAll(PAGES);
         if (sender.hasPermission(AdminMenu.PERMISSION)) {
            options.addAll(ADMIN_PAGES);
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("config") && sender.hasPermission(AdminMenu.PERMISSION)) {
         for (String key : this.plugin.getConfig().getKeys(true)) {
            if (this.plugin.getConfig().isConfigurationSection(key)) {
               options.add(key);
            }
         }
      }

      String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
      options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
      return options;
   }
}
