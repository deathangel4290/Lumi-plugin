package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** Opens the recipe book ({@link RecipesMenu}); kept so existing callers stay as they are. */
public class RecipesGUI {
   private final LumiWeapons plugin;

   public RecipesGUI(LumiWeapons plugin) {
      this.plugin = plugin;
   }

   public void open(@NotNull Player player) {
      new RecipesMenu(this.plugin, player, null).open();
   }
}
