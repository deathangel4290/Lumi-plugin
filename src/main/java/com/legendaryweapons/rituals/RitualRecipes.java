package com.legendaryweapons.rituals;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Ritual recipes. Each weapon's recipe is a list of up to {@link #MAX_INGREDIENTS} tokens like
 * "NETHER_STAR", "ECHO_SHARD:8" or "FRAGMENT_T3:2". Every entry needs its own altar slot holding
 * at least that many; extra items in a matched slot are handed back, extra slots fail the match.
 */
public final class RitualRecipes {
   public static final int MAX_INGREDIENTS = 5;
   private final LegendaryWeapons plugin;

   public RitualRecipes(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public record Entry(Material material, String customId, int amount) {
      public boolean matches(ItemStack item, LegendaryWeapons plugin) {
         if (item == null || item.getType().isAir() || item.getAmount() < this.amount) {
            return false;
         } else if (this.customId != null) {
            return this.customId.equals(CustomItems.get().idOf(item));
         } else {
            return item.getType() == this.material && !CustomItems.get().isCustom(item) && !plugin.getWeaponManager().isLegendaryWeapon(item);
         }
      }

      public ItemStack display() {
         if (this.customId != null) {
            ItemStack custom = CustomItems.get().create(this.customId, Math.min(64, this.amount));
            return custom != null ? custom : new ItemStack(Material.BARRIER);
         } else {
            return new ItemStack(this.material, Math.max(1, Math.min(64, this.amount)));
         }
      }

      public String describe() {
         String name = this.customId != null
            ? CustomItems.get().displayName(this.customId)
            : prettify(this.material.name());
         return this.amount + "x " + name;
      }
   }

   /** A successful match: which altar slot satisfies which recipe entry. */
   public record Match(String weaponKey, int[] slotForEntry, List<Entry> entries) {
   }

   public static String prettify(String enumName) {
      String[] words = enumName.toLowerCase(Locale.ROOT).split("_");
      StringBuilder out = new StringBuilder();

      for (String word : words) {
         if (!word.isEmpty()) {
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
         }
      }

      return out.toString().trim();
   }

   public List<Entry> entries(String weaponKey) {
      List<Entry> out = new ArrayList<>();
      boolean fragments = this.plugin.getConfigManager().areFragmentsRequired();

      for (String token : this.plugin.getConfig().getStringList("weapons." + weaponKey + ".recipe")) {
         Entry entry = this.parse(weaponKey, token);
         if (entry != null && (fragments || entry.customId() == null || !entry.customId().startsWith("fragment-t"))) {
            out.add(entry);
         }
      }

      if (out.size() > MAX_INGREDIENTS) {
         this.plugin.getLogger().warning("Recipe for " + weaponKey + " has " + out.size() + " ingredients; the altar only has " + MAX_INGREDIENTS + " slots.");
      }

      return out;
   }

   private Entry parse(String weaponKey, String token) {
      String[] parts = token.split(":");
      int amount = 1;
      if (parts.length > 1) {
         try {
            amount = Math.max(1, Integer.parseInt(parts[1].trim()));
         } catch (NumberFormatException var6) {
            this.plugin.getLogger().warning("Bad amount in recipe for " + weaponKey + ": " + token);
            return null;
         }
      }

      String customId = CustomItems.tokenToId(parts[0]);
      if (customId != null) {
         return new Entry(null, customId, amount);
      } else {
         Material material = Material.matchMaterial(parts[0].trim());
         if (material != null && !material.isAir()) {
            return new Entry(material, null, amount);
         } else {
            this.plugin.getLogger().warning("Unknown material in recipe for " + weaponKey + ": " + parts[0]);
            return null;
         }
      }
   }

   /** Find the weapon whose recipe the altar contents satisfy. */
   public Match match(List<ItemStack> slots) {
      int filled = 0;

      for (ItemStack item : slots) {
         if (item != null && !item.getType().isAir()) {
            filled++;
         }
      }

      if (filled == 0) {
         return null;
      } else {
         for (String weaponKey : this.plugin.getWeaponManager().getWeaponKeys()) {
            List<Entry> entries = this.entries(weaponKey);
            if (!entries.isEmpty() && entries.size() == filled) {
               int[] assignment = new int[entries.size()];
               if (this.assign(entries, slots, 0, assignment, new boolean[slots.size()])) {
                  return new Match(weaponKey, assignment, entries);
               }
            }
         }

         return null;
      }
   }

   private boolean assign(List<Entry> entries, List<ItemStack> slots, int index, int[] assignment, boolean[] used) {
      if (index == entries.size()) {
         return true;
      } else {
         for (int slot = 0; slot < slots.size(); slot++) {
            if (!used[slot] && entries.get(index).matches(slots.get(slot), this.plugin)) {
               used[slot] = true;
               assignment[index] = slot;
               if (this.assign(entries, slots, index + 1, assignment, used)) {
                  return true;
               }

               used[slot] = false;
            }
         }

         return false;
      }
   }

   /**
    * Fragment fusion: one slot holding at least fragments.fusion-cost fragments of a tier below 4,
    * every other slot empty. Returns the tier consumed, or 0.
    */
   public int fusionTier(List<ItemStack> slots) {
      if (!this.plugin.getConfigManager().isFragmentFusionEnabled()) {
         return 0;
      } else {
         ItemStack only = null;

         for (ItemStack item : slots) {
            if (item != null && !item.getType().isAir()) {
               if (only != null) {
                  return 0;
               }

               only = item;
            }
         }

         int tier = CustomItems.get().fragmentTier(only);
         return tier > 0 && tier < 4 && only.getAmount() >= this.fusionCost() ? tier : 0;
      }
   }

   public int fusionCost() {
      return Math.max(1, this.plugin.getConfig().getInt("fragments.fusion-cost", 4));
   }
}
