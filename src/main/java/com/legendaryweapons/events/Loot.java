package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

/**
 * Loot tables for supply drops, the Most Wanted chest and event rewards.
 *
 * An entry is "ITEM [amount] [wWEIGHT]", e.g.
 *   GOLDEN_APPLE 1-4 w10                        plain material, 1 to 4 of them, weight 10
 *   ENCHANTED_BOOK:SHARPNESS:5 1 w1             enchanted book
 *   POTION:STRONG_HEALING 2 w6                  potion (also SPLASH_POTION / LINGERING_POTION)
 *   ENCHANTED:DIAMOND_SWORD:SHARPNESS=3,UNBREAKING=3 1 w3
 *   FRAGMENT_T2 1 w3 / LUMINOSITY_FRAGMENT 1 / ALL_IN_ONE_POTION 1
 * A table section has rolls ("4-6"), guaranteed (always included) and items (weighted picks).
 */
public final class Loot {
   private final LegendaryWeapons plugin;

   public Loot(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   private record Entry(String item, int min, int max, int weight) {
   }

   private static int[] range(String raw) {
      String[] parts = raw.split("-");
      int min = Integer.parseInt(parts[0].trim());
      int max = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : min;
      return new int[]{Math.max(0, min), Math.max(min, max)};
   }

   private Entry parseEntry(String line) {
      String[] parts = line.trim().split("\\s+");
      int[] amount = new int[]{1, 1};
      int weight = 1;

      try {
         for (int i = 1; i < parts.length; i++) {
            if (parts[i].toLowerCase(Locale.ROOT).startsWith("w")) {
               weight = Integer.parseInt(parts[i].substring(1));
            } else {
               amount = range(parts[i]);
            }
         }
      } catch (NumberFormatException var6) {
         this.plugin.getLogger().warning("Bad loot entry: " + line);
         return null;
      }

      return new Entry(parts[0], amount[0], amount[1], Math.max(1, weight));
   }

   private static int roll(int min, int max) {
      return min >= max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
   }

   /** Build one item from its spec, or null (logged) if it cannot be resolved. */
   public ItemStack item(String spec, int amount) {
      if (amount <= 0) {
         return null;
      } else {
         String[] parts = spec.split(":");
         ItemStack custom = CustomItems.get().fromToken(parts[0], amount);
         if (custom != null) {
            return custom;
         } else {
            String head = parts[0].toUpperCase(Locale.ROOT);

            try {
               switch (head) {
                  case "ENCHANTED_BOOK": {
                     ItemStack book = new ItemStack(Material.ENCHANTED_BOOK, amount);
                     Enchantment enchantment = enchantment(parts[1]);
                     int level = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
                     book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(enchantment, level, true));
                     return book;
                  }
                  case "POTION":
                  case "SPLASH_POTION":
                  case "LINGERING_POTION": {
                     ItemStack potion = new ItemStack(Material.valueOf(head), amount);
                     PotionType type = Registry.POTION.get(NamespacedKey.minecraft(parts[1].toLowerCase(Locale.ROOT)));
                     if (type == null) {
                        throw new IllegalArgumentException("unknown potion " + parts[1]);
                     }

                     potion.editMeta(PotionMeta.class, meta -> meta.setBasePotionType(type));
                     return potion;
                  }
                  case "ENCHANTED": {
                     ItemStack item = new ItemStack(Material.valueOf(parts[1].toUpperCase(Locale.ROOT)), amount);
                     if (parts.length > 2) {
                        for (String pair : parts[2].split(",")) {
                           String[] kv = pair.split("=");
                           item.addUnsafeEnchantment(enchantment(kv[0]), kv.length > 1 ? Integer.parseInt(kv[1]) : 1);
                        }
                     }

                     return item;
                  }
                  default: {
                     Material material = Material.matchMaterial(head);
                     if (material == null || material.isAir()) {
                        throw new IllegalArgumentException("unknown material " + head);
                     }

                     return new ItemStack(material, amount);
                  }
               }
            } catch (RuntimeException var10) {
               this.plugin.getLogger().warning("Could not build loot item '" + spec + "': " + var10.getMessage());
               return null;
            }
         }
      }
   }

   private static Enchantment enchantment(String name) {
      Enchantment enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name.trim().toLowerCase(Locale.ROOT)));
      if (enchantment == null) {
         throw new IllegalArgumentException("unknown enchantment " + name);
      } else {
         return enchantment;
      }
   }

   /** Roll a whole table. */
   public List<ItemStack> roll(ConfigurationSection table) {
      List<ItemStack> out = new ArrayList<>();
      if (table == null) {
         return out;
      } else {
         for (String line : table.getStringList("guaranteed")) {
            Entry entry = this.parseEntry(line);
            if (entry != null) {
               ItemStack item = this.item(entry.item(), roll(entry.min(), entry.max()));
               if (item != null) {
                  out.add(item);
               }
            }
         }

         List<Entry> pool = new ArrayList<>();
         int total = 0;

         for (String line : table.getStringList("items")) {
            Entry entry = this.parseEntry(line);
            if (entry != null) {
               pool.add(entry);
               total += entry.weight();
            }
         }

         if (total > 0) {
            int[] rolls;
            try {
               rolls = range(table.getString("rolls", "4-6"));
            } catch (NumberFormatException var11) {
               rolls = new int[]{4, 6};
            }

            int count = roll(rolls[0], rolls[1]);

            for (int i = 0; i < count; i++) {
               int pick = ThreadLocalRandom.current().nextInt(total);

               for (Entry entry : pool) {
                  pick -= entry.weight();
                  if (pick < 0) {
                     ItemStack item = this.item(entry.item(), roll(entry.min(), entry.max()));
                     if (item != null) {
                        out.add(item);
                     }
                     break;
                  }
               }
            }
         }

         return out;
      }
   }

   /** Scatter items into random free slots, keeping {@code reservedSlot} (if >= 0) for the centrepiece. */
   public static void scatter(Inventory inventory, List<ItemStack> items, int reservedSlot) {
      List<Integer> slots = new ArrayList<>();

      for (int i = 0; i < inventory.getSize(); i++) {
         if (i != reservedSlot && (inventory.getItem(i) == null || inventory.getItem(i).getType().isAir())) {
            slots.add(i);
         }
      }

      Collections.shuffle(slots);
      int next = 0;

      for (ItemStack item : items) {
         if (next < slots.size()) {
            inventory.setItem(slots.get(next++), item);
         } else {
            inventory.addItem(new ItemStack[]{item});
         }
      }
   }

   /** Build reward entries (same "ITEM [amount]" format as tables) into items. */
   public List<ItemStack> build(List<String> specs) {
      List<ItemStack> out = new ArrayList<>();
      for (String spec : specs) {
         Entry entry = this.parseEntry(spec);
         ItemStack item = entry == null ? null : this.item(entry.item(), roll(entry.min(), entry.max()));
         if (item != null) {
            out.add(item);
         }
      }

      return out;
   }

   /** Give reward entries (same "ITEM [amount]" format as tables) to a player, dropping overflow. */
   public void give(Player player, List<String> rewards) {
      for (String reward : rewards) {
         Entry entry = this.parseEntry(reward);
         ItemStack item = entry == null ? null : this.item(entry.item(), roll(entry.min(), entry.max()));
         if (item != null) {
            player.getInventory().addItem(new ItemStack[]{item}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
         }
      }
   }
}
