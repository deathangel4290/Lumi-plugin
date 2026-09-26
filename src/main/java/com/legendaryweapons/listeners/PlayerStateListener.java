package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class PlayerStateListener implements Listener {
   private final LegendaryWeapons plugin;

   public PlayerStateListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0.0) {
         this.plugin.getComboTracker().resetAll(player);
      }
   }

   /** Weapons crafted before a config change get their lore brought up to date. */
   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      for (ItemStack item : event.getPlayer().getInventory().getContents()) {
         this.plugin.getWeaponManager().refresh(item);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onHeld(PlayerItemHeldEvent event) {
      this.plugin.getWeaponManager().refresh(event.getPlayer().getInventory().getItem(event.getNewSlot()));
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.clearAll(event.getEntity());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.clearAll(event.getPlayer());
   }

   private void clearAll(Player player) {
      this.plugin.getComboTracker().resetAll(player);
      this.plugin.getChargeMeter().clear(player);
      // Cooldowns deliberately survive death and relogging: clearing them made dying (or
      // reconnecting) a free reset for long cooldowns like the Veil's.
      this.plugin.getDashTracker().clear(player);
   }
}
