package com.lumiweapons.rituals;

import com.lumiweapons.LumiWeapons;
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
      return LumiWeapons.key("ritual-block");
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
         if (LumiWeapons.getPlugin(LumiWeapons.class).getConfig().getBoolean("ritual.custom-altar", true)) {
            meta.setItemModel(new NamespacedKey("luminara", "ritual_altar"));
         } else {
            meta.setEnchantmentGlintOverride(true);
         }
         meta.getPersistentDataContainer().set(key(), PersistentDataType.BYTE, (byte)1);
         item.setItemMeta(meta);
      }

      return item;
   }

   private static NamespacedKey ancientKey() {
      return LumiWeapons.key("ancient-altar");
   }

   /** Admins only: placing it sets an Ancient Altar (counts against ritual.ancient-altars.max). */
   public static ItemStack getAncientAltarItem() {
      ItemStack item = new ItemStack(Material.ENCHANTING_TABLE);
      item.editMeta(meta -> {
         meta.displayName(Component.text("✦ Ancient Altar ✦", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false));
         meta.lore(List.of(
            Component.text("Admin item. Place it somewhere players", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("will have to find it.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("It can't be broken (admins: sneak to break).", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
         ));
         if (LumiWeapons.getPlugin(LumiWeapons.class).getConfig().getBoolean("ritual.custom-altar", true)) {
            meta.setItemModel(new NamespacedKey("luminara", "ritual_altar"));
         }

         meta.setEnchantmentGlintOverride(true);
         meta.getPersistentDataContainer().set(ancientKey(), PersistentDataType.BYTE, (byte)1);
      });
      return item;
   }

   public static boolean isAncientAltarItem(ItemStack item) {
      return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(ancientKey(), PersistentDataType.BYTE);
   }

   public static boolean isRitualBlock(ItemStack item) {
      return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key(), PersistentDataType.BYTE);
   }
}
