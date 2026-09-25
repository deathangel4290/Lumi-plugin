package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.commands.AdminCommand;
import com.legendaryweapons.commands.BindsCommand;
import com.legendaryweapons.commands.GiveRitualBlockCommand;
import com.legendaryweapons.commands.RecipesCommand;
import com.legendaryweapons.commands.TestingCommand;
import com.legendaryweapons.commands.WpSparkCommand;
import com.legendaryweapons.guis.BindsGUI;
import com.legendaryweapons.guis.WeaponGiveGUI;
import com.legendaryweapons.listeners.SwordOfEclipseListener;

public class CommandManager {
   private final LegendaryWeapons plugin;

   public CommandManager(LegendaryWeapons plugin, SwordOfEclipseListener swordOfEclipseListener, AbilityDispatcher abilityDispatcher) {
      this.plugin = plugin;
      this.registerCommands(swordOfEclipseListener, abilityDispatcher);
   }

   private void registerCommands(SwordOfEclipseListener swordOfEclipseListener, AbilityDispatcher abilityDispatcher) {
      AdminCommand adminCommand = new AdminCommand(this.plugin, swordOfEclipseListener);
      this.plugin.getCommand("lw").setExecutor(adminCommand);
      this.plugin.getCommand("lw").setTabCompleter(adminCommand);
      this.plugin.getCommand("recipes").setExecutor(new RecipesCommand(this.plugin));
      this.plugin.getCommand("giveritualblock").setExecutor(new GiveRitualBlockCommand(this.plugin));
      BindsGUI bindsGUI = new BindsGUI(this.plugin);
      BindsCommand bindsCommand = new BindsCommand(bindsGUI, this.plugin.getKeybindManager());
      this.plugin.getCommand("binds").setExecutor(bindsCommand);
      this.plugin.getCommand("binds").setTabCompleter(bindsCommand);
      this.plugin.getCommand("unbind").setExecutor(bindsCommand);
      WpSparkCommand wpSparkCommand = new WpSparkCommand(this.plugin, abilityDispatcher);
      this.plugin.getCommand("wpspark").setExecutor(wpSparkCommand);
      this.plugin.getCommand("wpspark").setTabCompleter(wpSparkCommand);
      WeaponGiveGUI weaponGiveGUI = new WeaponGiveGUI(this.plugin);
      TestingCommand testingCommand = new TestingCommand(this.plugin, abilityDispatcher, weaponGiveGUI);

      for (String name : new String[]{"gui", "resetcd", "setkills", "charge"}) {
         this.plugin.getCommand(name).setExecutor(testingCommand);
         this.plugin.getCommand(name).setTabCompleter(testingCommand);
      }
   }
}
