package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;

public class ConfigManager {
   private final LumiWeapons plugin;
   private FileConfiguration config;

   /** Bump when shipped defaults change enough that an old config.yml would misrepresent the plugin. */
   public static final int CONFIG_VERSION = 4;
   private static final Pattern PLACEHOLDER = Pattern.compile("\\{(s|n|h|pct|x):([a-z0-9-]+)}");

   public ConfigManager(LumiWeapons plugin) {
      this.plugin = plugin;
      this.plugin.saveDefaultConfig();
      this.migrateIfOutdated();
      this.config = plugin.getConfig();
      this.loadDefaults();
   }

   /**
    * Bukkit only ever ADDS missing keys, so a server that ran 1.1 keeps every old balance value,
    * lore line and recipe forever. When the file predates CONFIG_VERSION it is moved aside to
    * config-v<old>-backup.yml and the new defaults are written in its place.
    */
   private void migrateIfOutdated() {
      File file = new File(this.plugin.getDataFolder(), "config.yml");
      // Read the file itself: getConfig() falls back to the jar's defaults, which already say CONFIG_VERSION.
      int found = file.exists() ? YamlConfiguration.loadConfiguration(file).getInt("config-version", 1) : CONFIG_VERSION;
      if (found < CONFIG_VERSION && file.exists()) {
         File backup = new File(this.plugin.getDataFolder(), "config-v" + found + "-backup.yml");
         if (backup.exists()) {
            backup.delete();
         }

         if (file.renameTo(backup)) {
            this.plugin.saveDefaultConfig();
            this.plugin.reloadConfig();
            this.plugin.getLogger().warning("config.yml was from an older version (" + found + "); it was saved as " + backup.getName()
               + " and replaced with the " + CONFIG_VERSION + " defaults. Re-apply any custom edits from the backup.");
         } else {
            this.plugin.getLogger().severe("config.yml is outdated but could not be backed up; keeping it. Delete it to get the new defaults.");
         }
      }
   }

   /**
    * Defaults retuned after release, as {revision, path, old default, new default}. On start, a
    * server still on the old default is moved to the new one; a value somebody changed is left
    * alone. Each revision runs once (balance-revision in config.yml remembers where it got to).
    */
   private static final Object[][] RETUNED = {
      {1, "weapons.titans-axe.abilities.slam-damage", 6.0, 14.0},
      // a String old value against a list removes that line, if it is still there
      {2, "token-shop.offers", "LUMINOSITY_FRAGMENT 1 @ 45", null},
      {3, "bosses.wither_king.damage-multiplier", 1.0, 1.5},
      {3, "bosses.wither_king.volley-interval", 8, 6},
      {3, "bosses.wither_king.slam-damage", 8, 14},
      {4, "bosses.hollow_warden.damage-multiplier", 1.0, 1.2},
      {4, "bosses.leviathan.damage-multiplier", 1.0, 1.4},
      {4, "bosses.ender_colossus.damage-multiplier", 1.0, 1.4},
      // the big armour-ignoring hits, so that with the multipliers they hurt but do not one-shot
      {5, "bosses.wither_king.nova-damage", 14, 11},
      {5, "bosses.wither_king.slam-damage", 14, 10},
      {5, "bosses.ender_colossus.stomp-damage", 13, 10},
      {5, "bosses.leviathan.wave-damage", 11, 9},
      // the animated BetterModel models (plugins/BetterModel/models); unused until BetterModel is installed
      {6, "bosses.hollow_warden.model.bettermodel", "", "hollow_warden"},
      {6, "bosses.wither_king.model.bettermodel", "", "wither_king"},
      {6, "bosses.wither_king.model.bettermodel-landed", "", "wither_king_landed"},
      {6, "bosses.ender_colossus.model.bettermodel", "", "ender_colossus"},
      {6, "bosses.leviathan.model.bettermodel", "", "leviathan"},
      {6, "bosses.leviathan.model.bettermodel-scale", 1.0, 1.692},
      {6, "bosses.leviathan.scale", 2.2, 1.3},
      {6, "token-shop.trader.model.bettermodel", "", "token_trader"},
      // the rename
      {7, "messages.prefix", "&6[LegendaryWeapons]&r", "&6[LumiWeapons]&r"},
   };

   /** Settings of removed features, dropped from existing configs on start. */
   private static final String[] OBSOLETE = {"season"};

   private void retune() {
      File file = new File(this.plugin.getDataFolder(), "config.yml");
      if (!file.exists()) {
         return;
      }

      int found = YamlConfiguration.loadConfiguration(file).getInt("balance-revision", 0);
      int latest = 0;
      for (Object[] entry : RETUNED) {
         int revision = (Integer)entry[0];
         latest = Math.max(latest, revision);
         if (revision > found) {
            String path = (String)entry[1];
            Object current = this.config.get(path);
            if (entry[3] == null && entry[2] instanceof String line && current instanceof List<?> list) {
               if (list.contains(line)) {
                  List<Object> kept = new ArrayList<>(list);
                  kept.remove(line);
                  this.config.set(path, kept);
                  this.plugin.getLogger().info("Retuned " + path + ": removed '" + line + "'");
               }

               continue;
            }

            boolean untouched = current instanceof Number n && entry[2] instanceof Number old
               ? Math.abs(n.doubleValue() - old.doubleValue()) < 1.0E-9
               : entry[2].equals(current);
            if (untouched) {
               this.config.set(path, entry[3]);
               this.plugin.getLogger().info("Retuned " + path + ": " + entry[2] + " -> " + entry[3]);
            }
         }
      }

      if (latest > found) {
         this.config.set("balance-revision", latest);
      }

      // lore now lives in lore.yml: a weapon still carrying an old default lore list (never edited) drops it
      java.io.InputStream bundled = this.plugin.getResource("old-lore.yml");
      if (bundled != null && this.config.isConfigurationSection("weapons")) {
         YamlConfiguration old = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(bundled, java.nio.charset.StandardCharsets.UTF_8));
         for (String weapon : this.config.getConfigurationSection("weapons").getKeys(false)) {
            List<String> current = this.config.getStringList("weapons." + weapon + ".lore");
            List<?> variants = old.getList(weapon);
            if (!current.isEmpty() && variants != null && variants.contains(current)) {
               this.config.set("weapons." + weapon + ".lore", null);
               this.plugin.getLogger().info("Moved " + weapon + " to the new lore (lore.yml)");
            }
         }
      }

      // teams 1.3: "allies: true/false" and "coloured-names" became sections
      if (this.config.isBoolean("teams.allies")) {
         boolean allies = this.config.getBoolean("teams.allies");
         this.config.set("teams.allies", null);
         this.config.set("teams.allies.abilities-skip", allies);
         this.config.set("teams.allies.friendly-fire", !allies);
         this.config.set("teams.allies.share-outposts", allies);
      }

      // 1.2: one-per-server lived under ritual; the acquisition section replaced it
      if (this.config.isBoolean("ritual.single-craft-per-weapon")) {
         this.config.set("acquisition.single-craft-per-weapon", this.config.getBoolean("ritual.single-craft-per-weapon"));
         this.config.set("ritual.single-craft-per-weapon", null);
         this.plugin.getLogger().info("Moved ritual.single-craft-per-weapon to acquisition.single-craft-per-weapon");
      }

      if (this.config.isBoolean("teams.coloured-names")) {
         this.config.set("teams.nametags.enabled", this.config.getBoolean("teams.coloured-names"));
         this.config.set("teams.coloured-names", null);
      }

      for (String path : OBSOLETE) {
         if (this.config.isSet(path)) {
            this.config.set(path, null);
            this.plugin.getLogger().info("Removed " + path + " from config.yml (no longer used)");
         }
      }
   }

   public void loadDefaults() {
      this.retune();
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
      return this.config.getBoolean("acquisition.single-craft-per-weapon", true);
   }

   public boolean isRitualCraftingEnabled() {
      return this.config.getBoolean("acquisition.ritual-crafting", true);
   }

   public boolean areFragmentsRequired() {
      return this.config.getBoolean("acquisition.require-fragments", true);
   }

   public boolean isFragmentFusionEnabled() {
      return this.config.getBoolean("acquisition.fragment-fusion", true);
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
      ConfigurationSection abilities = this.getWeaponAbilities(weaponKey);
      return this.config
         .getStringList("weapons." + weaponKey + ".lore")
         .stream()
         .map(line -> ChatColor.translateAlternateColorCodes('&', fillPlaceholders(line, abilities)))
         .collect(Collectors.toList());
   }

   /**
    * Lore reads its numbers from the abilities section so the two can never drift apart:
    *   {s:key}   ticks as seconds      200  -> 10s
    *   {n:key}   plain number          6.0  -> 6
    *   {h:key}   damage as hearts      8.0  -> 4
    *   {pct:key} fraction as percent   0.15 -> 15%
    *   {x:key}   multiplier            1.35 -> 1.35x
    */
   public static String fillPlaceholders(String line, ConfigurationSection abilities) {
      if (abilities == null || line.indexOf('{') < 0) {
         return line;
      } else {
         Matcher matcher = PLACEHOLDER.matcher(line);
         StringBuilder out = new StringBuilder();

         while (matcher.find()) {
            String key = matcher.group(2);
            String replacement;
            if (!abilities.contains(key)) {
               replacement = "?";
            } else {
               double value = abilities.getDouble(key);

               replacement = switch (matcher.group(1)) {
                  case "s" -> number(value / 20.0) + "s";
                  case "h" -> number(value / 2.0);
                  case "pct" -> number(value * 100.0) + "%";
                  case "x" -> number(value) + "x";
                  default -> number(value);
               };
            }

            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
         }

         matcher.appendTail(out);
         return out.toString();
      }
   }

   private static String number(double value) {
      return value == Math.rint(value) ? String.valueOf((long)value) : String.valueOf(Math.round(value * 100.0) / 100.0);
   }

   public int getWeaponCustomModelData(String weaponKey) {
      return this.config.getInt("weapons." + weaponKey + ".custom-model-data", 0);
   }

   /**
    * Optional melee stats, as the tooltip shows them: {attack damage, attack speed}. Null when the
    * weapon keeps its material's vanilla stats.
    */
   public double[] getWeaponMelee(String weaponKey) {
      ConfigurationSection melee = this.config.getConfigurationSection("weapons." + weaponKey + ".melee");
      return melee == null ? null : new double[]{melee.getDouble("attack-damage", 1.0), melee.getDouble("attack-speed", 4.0)};
   }

   public int getWeaponPowerTier(String weaponKey) {
      return Math.max(0, Math.min(4, this.config.getInt("weapons." + weaponKey + ".power-tier", 0)));
   }

   /**
    * Never null: a weapon whose abilities section was deleted from the config gets an empty one, so
    * every lookup falls back to its built-in default instead of throwing mid-fight.
    */
   public ConfigurationSection getWeaponAbilities(String weaponKey) {
      ConfigurationSection section = this.config.getConfigurationSection("weapons." + weaponKey + ".abilities");
      return section != null ? section : new org.bukkit.configuration.file.YamlConfiguration();
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
                  this.plugin.getLogger().warning("[LumiWeapons] Invalid material name in ritual-block-recipe: " + materialName);
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
