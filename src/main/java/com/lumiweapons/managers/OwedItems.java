package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Thrown weapons that are out of the player's inventory while they fly. The exact items are kept on
 * the player (count + one serialized stack per throw), so a logout or restart mid-flight hands back
 * the player's own weapon with its enchantments and name, not a fresh copy from the config.
 */
public final class OwedItems {
   private final LumiWeapons plugin;
   private final NamespacedKey countKey;
   private final String itemPrefix;

   /** countKeyName is the key older versions stored the bare count under, so old debts still pay out. */
   public OwedItems(LumiWeapons plugin, String countKeyName) {
      this.plugin = plugin;
      this.countKey = LumiWeapons.key(countKeyName);
      this.itemPrefix = countKeyName + "-item-";
   }

   private NamespacedKey slot(int index) {
      return LumiWeapons.key(this.itemPrefix + index);
   }

   public int count(Player player) {
      Integer value = player.getPersistentDataContainer().get(this.countKey, PersistentDataType.INTEGER);
      return value == null ? 0 : Math.max(0, value);
   }

   /**
    * Take back the most recent throw: the stored item, or the fallback when only a count survives
    * (debts from older versions). Null when nothing is owed, so nothing is ever minted for free.
    */
   public ItemStack pop(Player player, Supplier<ItemStack> fallback) {
      int count = this.count(player);
      if (count <= 0) {
         return null;
      }

      PersistentDataContainer pdc = player.getPersistentDataContainer();
      NamespacedKey key = this.slot(count - 1);
      byte[] bytes = pdc.get(key, PersistentDataType.BYTE_ARRAY);
      pdc.remove(key);
      if (count - 1 <= 0) {
         pdc.remove(this.countKey);
      } else {
         pdc.set(this.countKey, PersistentDataType.INTEGER, count - 1);
      }

      if (bytes != null) {
         try {
            return ItemStack.deserializeBytes(bytes);
         } catch (RuntimeException ignored) {
            // fall through to a fresh copy
         }
      }

      return fallback.get();
   }
}
