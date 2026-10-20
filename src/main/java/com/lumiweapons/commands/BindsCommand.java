package com.lumiweapons.commands;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.guis.SettingsMenu;
import com.lumiweapons.managers.KeybindManager;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class BindsCommand implements CommandExecutor, TabCompleter {
   private final LumiWeapons plugin;
   private final KeybindManager keybindManager;

   public BindsCommand(LumiWeapons plugin) {
      this.plugin = plugin;
      this.keybindManager = plugin.getKeybindManager();
   }

   public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage("This command can only be used by a player.");
         return true;
      } else {
         boolean unbindRequested = command.getName().equalsIgnoreCase("unbind") || args.length > 0 && args[0].equalsIgnoreCase("unbind");
         if (unbindRequested) {
            if (this.keybindManager.removeKeybind(player)) {
               player.sendMessage("§aAbility bind cleared.");
            } else {
               player.sendMessage("§7You don't have an ability bind set.");
            }

            return true;
         } else {
            new SettingsMenu(this.plugin, player, null).open();
            return true;
         }
      }
   }

   public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
      return command.getName().equalsIgnoreCase("binds") && args.length == 1 && "unbind".startsWith(args[0].toLowerCase())
         ? Collections.singletonList("unbind")
         : Collections.emptyList();
   }
}
