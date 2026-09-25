package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;

public class WeaponProtectionListener implements Listener {
   private final LegendaryWeapons plugin;

   public WeaponProtectionListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onItemDespawn(ItemDespawnEvent event) {
      if (this.plugin.getWeaponManager().isLegendaryWeapon(event.getEntity().getItemStack())) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onEntityDamage(EntityDamageEvent event) {
      if (event.getEntity() instanceof Item) {
         Item item = (Item)event.getEntity();
         if (this.plugin.getWeaponManager().isLegendaryWeapon(item.getItemStack())) {
            event.setCancelled(true);
         }
      }
   }
}
