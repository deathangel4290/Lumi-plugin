package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.guis.RecipesGUI;
import com.legendaryweapons.guis.RitualGUI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class GUIManager {
   private final RecipesGUI recipesGUI;
   private final LegendaryWeapons plugin;
   private final Map<UUID, RitualGUI> openRitualGUIs = new HashMap<>();

   public GUIManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.recipesGUI = new RecipesGUI(plugin);
      this.startGUIUpdater();
   }

   public void openRitualGUI(Player player) {
      RitualGUI ritualGUI = new RitualGUI(this.plugin);
      ritualGUI.setup();
      player.openInventory(ritualGUI.getInventory());
      this.openRitualGUIs.put(player.getUniqueId(), ritualGUI);
   }

   public void closeRitualGUI(Player player) {
      this.openRitualGUIs.remove(player.getUniqueId());
   }

   public RitualGUI getOpenRitualGUI(Player player) {
      return this.openRitualGUIs.get(player.getUniqueId());
   }

   public RecipesGUI getRecipesGUI() {
      return this.recipesGUI;
   }

   private void startGUIUpdater() {
      (new BukkitRunnable() {
         public void run() {
            for (Entry<UUID, RitualGUI> entry : new HashMap<>(GUIManager.this.openRitualGUIs).entrySet()) {
               Player player = GUIManager.this.plugin.getServer().getPlayer(entry.getKey());
               if (player != null && player.getOpenInventory().getTopInventory().getHolder() instanceof RitualGUI) {
                  entry.getValue().update(player);
               } else {
                  GUIManager.this.openRitualGUIs.remove(entry.getKey());
               }
            }
         }
      }).runTaskTimer(this.plugin, 20L, 20L);
   }
}
