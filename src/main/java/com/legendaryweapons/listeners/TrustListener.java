package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.ConfigManager;
import com.legendaryweapons.managers.TrustManager;
import com.legendaryweapons.managers.WeaponManager;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class TrustListener implements Listener {
   private final LegendaryWeapons plugin;
   private final TrustManager trustManager;
   private final ConfigManager configManager;
   private final WeaponManager weaponManager;

   public TrustListener(LegendaryWeapons plugin, TrustManager trustManager, ConfigManager configManager, WeaponManager weaponManager) {
      this.plugin = plugin;
      this.trustManager = trustManager;
      this.configManager = configManager;
      this.weaponManager = weaponManager;
   }

   @EventHandler
   public void onPlayerDamage(EntityDamageByEntityEvent event) {
      if (event.getEntity() instanceof Player) {
         Player victim = (Player)event.getEntity();
         Player damager = null;
         if (event.getDamager() instanceof Player) {
            damager = (Player)event.getDamager();
         } else if (event.getDamager() instanceof Arrow) {
            Arrow arrow = (Arrow)event.getDamager();
            if (arrow.getShooter() instanceof Player) {
               damager = (Player)arrow.getShooter();
            }
         }

         if (damager != null && !damager.equals(victim) && this.trustManager.isMutuallyTrusted(damager, victim)) {
            if (event.getDamager() instanceof Player && this.configManager.isPreventMeleeDamage()) {
               event.setCancelled(true);
               return;
            }

            if (event.getDamager() instanceof Arrow && this.configManager.isPreventProjectileDamage()) {
               event.setCancelled(true);
            }
         }
      }
   }
}
