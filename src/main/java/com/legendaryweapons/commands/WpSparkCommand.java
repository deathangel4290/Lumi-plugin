package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.abilities.AbilityMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class WpSparkCommand implements CommandExecutor, TabCompleter {
   private static final long MIN_INTERVAL_TICKS = 4L;
   private static final String THROTTLE_KEY = "wpspark-throttle";
   private static final List<String> SECONDARY_ALIASES = Arrays.asList("alt", "secondary", "2", "sneak");
   private final LegendaryWeapons plugin;
   private final AbilityDispatcher dispatcher;

   public WpSparkCommand(LegendaryWeapons plugin, AbilityDispatcher dispatcher) {
      this.plugin = plugin;
      this.dispatcher = dispatcher;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (sender instanceof Player player) {
         if (this.plugin.getCooldownManager().isOnCooldown(player, "wpspark-throttle")) {
            return true;
         } else {
            this.plugin.getCooldownManager().set(player, "wpspark-throttle", 4L);
            AbilityMode mode = args.length > 0 && SECONDARY_ALIASES.contains(args[0].toLowerCase(Locale.ROOT)) ? AbilityMode.SECONDARY : AbilityMode.PRIMARY;
            if (!this.dispatcher.trigger(player, mode) && this.dispatcher.heldWeaponKey(player) == null) {
               player.sendActionBar(Component.text("You are not holding a legendary weapon."));
            }

            return true;
         }
      } else {
         sender.sendMessage("This command can only be used by a player.");
         return true;
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      if (args.length != 1) {
         return List.of();
      } else {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         List<String> matches = new ArrayList<>();

         for (String option : SECONDARY_ALIASES) {
            if (option.startsWith(prefix)) {
               matches.add(option);
            }
         }

         return matches;
      }
   }
}
