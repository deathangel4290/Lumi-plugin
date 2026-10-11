package com.legendaryweapons.rituals;

import com.legendaryweapons.LegendaryWeapons;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class RitualBlock {
   private static NamespacedKey key() {
      return new NamespacedKey(LegendaryWeapons.getPlugin(LegendaryWeapons.class), "ritual-block");
   }

   public static ItemStack getRitualBlockItem() {
      ItemStack item = new ItemStack(Material.ENCHANTING_TABLE);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.displayName(Component.text("✦ Ritual Altar ✦", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
         meta.lore(List.of(
            Component.text("Place it, then right-click to forge", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("a legendary weapon.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
         ));
         if (LegendaryWeapons.getPlugin(LegendaryWeapons.class).getConfig().getBoolean("ritual.custom-altar", true)) {
            meta.setItemModel(new NamespacedKey("luminara", "ritual_altar"));
         } else {
            meta.setEnchantmentGlintOverride(true);
         }
         meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte)1);
         item.setItemMeta(meta);
      }

      return item;
   }

   public static boolean isRitualBlock(ItemStack item) {
      return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
   }
}
