package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public class RitualGUI implements InventoryHolder {
   private final LegendaryWeapons plugin;
   private final Inventory inventory;

   public RitualGUI(LegendaryWeapons plugin) {
      this.plugin = plugin;
      int size = 27;
      String title = "Ritual of Legendary Crafting";
      this.inventory = Bukkit.createInventory(this, size, title);
   }

   public void setup() {
      ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
      ItemMeta borderMeta = border.getItemMeta();
      if (borderMeta != null) {
         borderMeta.setDisplayName(" ");
         border.setItemMeta(borderMeta);
      }

      for (int i = 0; i < this.inventory.getSize(); i++) {
         this.inventory.setItem(i, border);
      }

      this.inventory.setItem(11, new ItemStack(Material.AIR));
      this.inventory.setItem(13, new ItemStack(Material.AIR));
      this.inventory.setItem(15, new ItemStack(Material.AIR));
   }

   public void update(Player player) {
      List<ItemStack> items = new ArrayList<>();
      items.add(this.inventory.getItem(11));
      items.add(this.inventory.getItem(13));
      items.add(this.inventory.getItem(15));
      String weaponKey = this.plugin.getRitualManager().getWeaponKeyFromRecipe(items);
      ItemStack resultItem;
      ItemMeta resultMeta;
      if (weaponKey != null) {
         resultItem = new ItemStack(Material.ANVIL);
         resultMeta = resultItem.getItemMeta();
         if (resultMeta != null) {
            resultMeta.setDisplayName("§aBegin Ritual");
            resultMeta.setLore(Collections.singletonList("§7You are about to craft the " + weaponKey));
         }
      } else {
         resultItem = new ItemStack(Material.BARRIER);
         resultMeta = resultItem.getItemMeta();
         if (resultMeta != null) {
            resultMeta.setDisplayName("§cInvalid Recipe");
            resultMeta.setLore(Collections.singletonList("§7The items do not form a valid recipe."));
         }
      }

      if (resultItem != null) {
         resultItem.setItemMeta(resultMeta);
      }

      this.inventory.setItem(22, resultItem);
   }

   @NotNull
   public Inventory getInventory() {
      return this.inventory;
   }
}
