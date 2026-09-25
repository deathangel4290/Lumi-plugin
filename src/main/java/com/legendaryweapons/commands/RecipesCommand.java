package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class RecipesCommand implements CommandExecutor {
   private final LegendaryWeapons plugin;

   public RecipesCommand(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (sender instanceof Player player) {
         this.plugin.getGuiManager().getRecipesGUI().open(player);
         return true;
      } else {
         sender.sendMessage("This command can only be used by a player.");
         return true;
      }
   }
}
