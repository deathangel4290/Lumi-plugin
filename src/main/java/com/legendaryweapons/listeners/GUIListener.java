package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.GUIManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class GUIListener implements Listener {
   private final GUIManager guiManager;

   public GUIListener(GUIManager guiManager) {
      this.guiManager = guiManager;
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().equals(this.guiManager.getRecipesGUI().getInventory())) {
         event.setCancelled(true);
      }
   }
}
