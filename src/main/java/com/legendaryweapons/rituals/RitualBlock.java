package com.legendaryweapons.rituals;

import com.legendaryweapons.LegendaryWeapons;
import java.util.Arrays;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class RitualBlock {
   public static ItemStack getRitualBlockItem() {
      ItemStack item = new ItemStack(Material.ENCHANTING_TABLE);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§5Ritual Block");
         meta.setLore(Arrays.asList("§7Place this block to begin a", "§7legendary ritual."));
         NamespacedKey key = new NamespacedKey(LegendaryWeapons.getPlugin(LegendaryWeapons.class), "ritual-block");
         meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte)1);
         item.setItemMeta(meta);
      }

      return item;
   }

   public static boolean isRitualBlock(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         NamespacedKey key = new NamespacedKey(LegendaryWeapons.getPlugin(LegendaryWeapons.class), "ritual-block");
         return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
      } else {
         return false;
      }
   }
}
