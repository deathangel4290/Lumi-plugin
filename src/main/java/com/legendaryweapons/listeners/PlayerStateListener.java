package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerStateListener implements Listener {
   private final LegendaryWeapons plugin;

   public PlayerStateListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0.0) {
         this.plugin.getComboTracker().resetAll(player);
      }
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
      this.plugin.getChargePool().clear(player);
      this.plugin.getCooldownManager().clearAll(player);
   }
}
