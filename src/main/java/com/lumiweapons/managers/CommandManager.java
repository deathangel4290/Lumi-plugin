package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDispatcher;
import com.lumiweapons.commands.AdminCommand;
import com.lumiweapons.commands.BindsCommand;
import com.lumiweapons.commands.EventCommand;
import com.lumiweapons.commands.GiveRitualBlockCommand;
import com.lumiweapons.commands.RecipesCommand;
import com.lumiweapons.commands.TestingCommand;
import com.lumiweapons.commands.WpSparkCommand;
import com.lumiweapons.listeners.SwordOfEclipseListener;

public class CommandManager {
   private final LumiWeapons plugin;

   public CommandManager(LumiWeapons plugin, SwordOfEclipseListener swordOfEclipseListener, AbilityDispatcher abilityDispatcher) {
      this.plugin = plugin;
      this.registerCommands(swordOfEclipseListener, abilityDispatcher);
   }

   private void registerCommands(SwordOfEclipseListener swordOfEclipseListener, AbilityDispatcher abilityDispatcher) {
      AdminCommand adminCommand = new AdminCommand(this.plugin, swordOfEclipseListener);
      this.plugin.getCommand("lw").setExecutor(adminCommand);
      this.plugin.getCommand("lw").setTabCompleter(adminCommand);
      this.plugin.getCommand("recipes").setExecutor(new RecipesCommand(this.plugin));
      this.plugin.getCommand("giveritualblock").setExecutor(new GiveRitualBlockCommand(this.plugin));
      BindsCommand bindsCommand = new BindsCommand(this.plugin);
      this.plugin.getCommand("binds").setExecutor(bindsCommand);
      this.plugin.getCommand("binds").setTabCompleter(bindsCommand);
      this.plugin.getCommand("unbind").setExecutor(bindsCommand);
      WpSparkCommand wpSparkCommand = new WpSparkCommand(this.plugin, abilityDispatcher);
      this.plugin.getCommand("wpspark").setExecutor(wpSparkCommand);
      this.plugin.getCommand("wpspark").setTabCompleter(wpSparkCommand);
      TestingCommand testingCommand = new TestingCommand(this.plugin, abilityDispatcher);

      for (String name : new String[]{"gui", "resetcd", "setkills", "charge", "lwtest", "lwfx"}) {
         this.plugin.getCommand(name).setExecutor(testingCommand);
         this.plugin.getCommand(name).setTabCompleter(testingCommand);
      }

      com.lumiweapons.commands.LumiCommand lumiCommand = new com.lumiweapons.commands.LumiCommand(this.plugin);
      this.plugin.getCommand("lumi").setExecutor(lumiCommand);
      this.plugin.getCommand("lumi").setTabCompleter(lumiCommand);
      com.lumiweapons.commands.TeamCommand teamCommand = new com.lumiweapons.commands.TeamCommand(this.plugin);
      for (String name : new String[]{"team", "tc", "draft"}) {
         this.plugin.getCommand(name).setExecutor(teamCommand);
         this.plugin.getCommand(name).setTabCompleter(teamCommand);
      }

      EventCommand eventCommand = new EventCommand(this.plugin);
      this.plugin.getCommand("lwevent").setExecutor(eventCommand);
      this.plugin.getCommand("lwevent").setTabCompleter(eventCommand);
      com.lumiweapons.commands.PlayerEventCommand playerEvents = new com.lumiweapons.commands.PlayerEventCommand(this.plugin);
      for (String name : new String[]{"rewards", "tokenshop", "outposts"}) {
         this.plugin.getCommand(name).setExecutor(playerEvents);
         this.plugin.getCommand(name).setTabCompleter(playerEvents);
      }
   }
}
