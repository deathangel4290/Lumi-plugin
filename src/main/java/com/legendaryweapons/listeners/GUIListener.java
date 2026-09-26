package com.legendaryweapons.listeners;

import com.legendaryweapons.guis.RecipesGUI;
import com.legendaryweapons.managers.GUIManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public class GUIListener implements Listener {
   public GUIListener(GUIManager guiManager) {
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof RecipesGUI.Holder) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof RecipesGUI.Holder) {
         event.setCancelled(true);
      }
   }
}
