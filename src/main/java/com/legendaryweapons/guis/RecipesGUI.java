package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Opens the recipe book ({@link RecipesMenu}); kept so existing callers stay as they are. */
public class RecipesGUI {
   private final LegendaryWeapons plugin;

   public RecipesGUI(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public void open(@NotNull Player player) {
      new RecipesMenu(this.plugin, player, null).open();
   }
}
