package com.legendaryweapons.guis;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Routes clicks to the open {@link Menu}. Nothing can be taken out of or put into a menu. */
public final class MenuListener implements Listener {
   private static final long DEBOUNCE_MILLIS = 120L;
   private final Map<UUID, Long> lastClick = new HashMap<>();

   @EventHandler(priority = EventPriority.LOW)
   public void onClick(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
         return;
      }

      event.setCancelled(true);
      int slot = event.getRawSlot();
      if (slot < 0 || slot >= event.getView().getTopInventory().getSize() || !(event.getWhoClicked() instanceof Player player)) {
         return;
      }

      // a double-click sends two clicks; a toggle must not flip twice
      long now = System.currentTimeMillis();
      Long last = this.lastClick.put(player.getUniqueId(), now);
      if (last != null && now - last < DEBOUNCE_MILLIS) {
         return;
      }

      menu.handle(slot, event.getClick());
   }

   @EventHandler(priority = EventPriority.LOW)
   public void onDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Menu) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onClose(InventoryCloseEvent event) {
      if (event.getInventory().getHolder() instanceof Menu menu) {
         menu.closed();
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.lastClick.remove(event.getPlayer().getUniqueId());
   }
}
