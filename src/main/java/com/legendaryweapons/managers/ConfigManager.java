package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public class ConfigManager {
   private final LegendaryWeapons plugin;
   private FileConfiguration config;

   public ConfigManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.plugin.saveDefaultConfig();
      this.config = plugin.getConfig();
      this.loadDefaults();
   }

   public void loadDefaults() {
      this.config.addDefault("ritual-block-recipe.enabled", true);
      this.config.addDefault("ritual-block-recipe.shape", Arrays.asList("ODO", "DND", "OOO"));
      this.config.addDefault("ritual-block-recipe.ingredients.O", "OBSIDIAN");
      this.config.addDefault("ritual-block-recipe.ingredients.D", "DIAMOND");
      this.config.addDefault("ritual-block-recipe.ingredients.N", "NETHER_STAR");
      this.config.options().copyDefaults(true);
      this.plugin.saveConfig();
   }

   public void reloadConfig() {
      this.plugin.reloadConfig();
      this.config = this.plugin.getConfig();
   }

   public int getRitualTimer() {
      return this.config.getInt("ritual.timer", 900);
   }

   public boolean isSingleCraft() {
      return this.config.getBoolean("ritual.single-craft-per-weapon", true);
   }

   public String getMessage(String key, boolean withPrefix) {
      String message = this.config.getString("messages." + key, "");
      if (withPrefix) {
         message = this.config.getString("messages.prefix", "") + " " + message;
      }

      return ChatColor.translateAlternateColorCodes('&', message);
   }

   public Set<String> getWeaponKeys() {
      ConfigurationSection weaponsSection = this.config.getConfigurationSection("weapons");
      return weaponsSection == null ? Collections.emptySet() : weaponsSection.getKeys(false);
   }

   public String getWeaponDisplayName(String weaponKey) {
      return ChatColor.translateAlternateColorCodes('&', this.config.getString("weapons." + weaponKey + ".display-name", ""));
   }

   public Material getWeaponMaterial(String weaponKey) {
      String materialName = this.config.getString("weapons." + weaponKey + ".material", "STONE");

      try {
         return Material.valueOf(materialName.toUpperCase());
      } catch (IllegalArgumentException var4) {
         this.plugin.getLogger().warning("Invalid material in config for " + weaponKey + ": " + materialName);
         return Material.STONE;
      }
   }

   public List<String> getWeaponLore(String weaponKey) {
      return this.config
         .getStringList("weapons." + weaponKey + ".lore")
         .stream()
         .map(line -> ChatColor.translateAlternateColorCodes('&', line))
         .collect(Collectors.toList());
   }

   public int getWeaponCustomModelData(String weaponKey) {
      return this.config.getInt("weapons." + weaponKey + ".custom-model-data", 0);
   }

   public int getWeaponPowerTier(String weaponKey) {
      return Math.max(0, Math.min(4, this.config.getInt("weapons." + weaponKey + ".power-tier", 0)));
   }

   public ConfigurationSection getWeaponAbilities(String weaponKey) {
      return this.config.getConfigurationSection("weapons." + weaponKey + ".abilities");
   }

   private Enchantment resolveEnchantment(String name) {
      String normalized = name.trim().toLowerCase(Locale.ROOT);
      NamespacedKey key = normalized.indexOf(58) >= 0 ? NamespacedKey.fromString(normalized) : NamespacedKey.minecraft(normalized);
      return key != null ? (Enchantment)Registry.ENCHANTMENT.get(key) : null;
   }

   public Map<Enchantment, Integer> getWeaponEnchantments(String weaponKey) {
      Map<Enchantment, Integer> enchantments = new HashMap<>();

      for (String enchStr : this.config.getStringList("weapons." + weaponKey + ".enchantments")) {
         int split = enchStr.lastIndexOf(58);
         if (split > 0 && split != enchStr.length() - 1) {
            Enchantment enchantment = this.resolveEnchantment(enchStr.substring(0, split));
            if (enchantment == null) {
               this.plugin.getLogger().warning("Unknown enchantment for " + weaponKey + ": " + enchStr);
            } else {
               try {
                  enchantments.put(enchantment, Integer.parseInt(enchStr.substring(split + 1).trim()));
               } catch (NumberFormatException var8) {
                  this.plugin.getLogger().warning("Invalid enchantment level for " + weaponKey + ": " + enchStr);
               }
            }
         } else {
            this.plugin.getLogger().warning("Malformed enchantment entry for " + weaponKey + ": " + enchStr + " (expected NAME:LEVEL)");
         }
      }

      return enchantments;
   }

   public List<ItemStack> getWeaponRecipe(String weaponKey) {
      return this.config.getStringList("weapons." + weaponKey + ".recipe").stream().map(itemString -> {
         String[] parts = itemString.split(":");
         int amount = 1;

         try {
            Material material = Material.valueOf(parts[0].toUpperCase());
            if (parts.length > 1) {
               amount = Integer.parseInt(parts[1]);
            }

            return new ItemStack(material, amount);
         } catch (IllegalArgumentException var6) {
            this.plugin.getLogger().warning("Invalid material in recipe for " + weaponKey + ": " + parts[0]);
            return null;
         }
      }).filter(item -> item != null).collect(Collectors.toList());
   }

   public boolean isRitualBlockRecipeEnabled() {
      return this.config.getBoolean("ritual-block-recipe.enabled");
   }

   public List<String> getRitualBlockRecipeShape() {
      return this.config.getStringList("ritual-block-recipe.shape");
   }

   public Map<Character, Material> getRitualBlockRecipeIngredients() {
      Map<Character, Material> ingredients = new HashMap<>();
      ConfigurationSection section = this.config.getConfigurationSection("ritual-block-recipe.ingredients");
      if (section != null) {
         for (String key : section.getKeys(false)) {
            if (key.length() == 1) {
               char ingredientChar = key.charAt(0);
               String materialName = section.getString(key);

               try {
                  Material material = Material.valueOf(materialName.toUpperCase());
                  ingredients.put(ingredientChar, material);
               } catch (IllegalArgumentException var81) {
                  this.plugin.getLogger().warning("[LegendaryWeapons] Invalid material name in ritual-block-recipe: " + materialName);
               }
            }
         }
      }

      return ingredients;
   }

   public double getMasterSoundVolume() {
      return this.config.getDouble("audio.volume", 0.5);
   }

   public boolean areHitSoundsEnabled() {
      return this.config.getBoolean("audio.hit-sounds", false);
   }

   public boolean areAmbientSoundsEnabled() {
      return this.config.getBoolean("audio.ambient", false);
   }

   public boolean isPreventAbilityDamage() {
      return this.config.getBoolean("trust-system.prevent-ability-damage", true);
   }

   public boolean isPreventMeleeDamage() {
      return this.config.getBoolean("trust-system.prevent-melee-damage", true);
   }

   public boolean isPreventProjectileDamage() {
      return this.config.getBoolean("trust-system.prevent-projectile-damage", true);
   }
}
