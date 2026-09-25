package com.legendaryweapons.commands;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.rituals.RitualBlock;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class GiveRitualBlockCommand implements CommandExecutor {
   public GiveRitualBlockCommand(LegendaryWeapons plugin) {
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!(sender instanceof Player)) {
         sender.sendMessage("This command can only be used by a player.");
         return true;
      } else if (!sender.hasPermission("legendaryweapons.admin")) {
         sender.sendMessage("§cYou don't have permission to use this command.");
         return true;
      } else {
         Player player = (Player)sender;
         player.getInventory().addItem(new ItemStack[]{RitualBlock.getRitualBlockItem()});
         player.sendMessage("You have received a Ritual Block.");
         return true;
      }
   }
}
