package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.items.CustomItems;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

/**
 * Reads config.yml the way a careful admin would and lists what won't work as written, so a typo
 * shows up as a message instead of a feature that silently does nothing. It never changes the
 * file: the plugin still falls back to the default for anything it can't use.
 *
 *   - keys the plugin doesn't know (typos, or settings from an older version)
 *   - the wrong kind of value (text where a number goes, a list where a switch goes, ...)
 *   - values outside their fixed choices (CHOICES, shared with the settings menu)
 *   - negative numbers where only zero or more makes sense
 *   - unknown materials, mobs, enchantments, colours, time zones and clock times
 *   - recipe and loot lines that can't be turned into items
 *
 * Runs on start and on every reload (the problems go to the console), on /lw checkconfig, and
 * marks the affected settings in the in-game settings editor.
 */
public final class ConfigCheck {
   /** Settings with a fixed set of values. The settings menu cycles through these. */
   public static final Map<String, List<String>> CHOICES = choices();

   /** Sections whose keys are names the admin chooses (mobs, outpost types, windows, ...). */
   private static final String[] FREE_FORM = {
      "fragments.mob-drops.", "outposts.types.", "director.windows.", "ritual-block-recipe.ingredients.", "messages.",
      "token-shop.trader.model.states.", "token-shop.trader.model.animations.", "events.chosen.counterparts."
   };

   private final LumiWeapons plugin;
   private final Map<String, String> problems = new LinkedHashMap<>();

   public ConfigCheck(LumiWeapons plugin) {
      this.plugin = plugin;
   }

   private static Map<String, List<String>> choices() {
      List<String> colours = new ArrayList<>();
      colours.add("TEAM");
      for (String name : NamedTextColor.NAMES.keys()) {
         colours.add(name.toUpperCase(Locale.ROOT));
      }

      Map<String, List<String>> out = new LinkedHashMap<>();
      out.put("effects.default-player-level", List.of("FULL", "REDUCED", "MINIMAL", "OFF"));
      out.put("director.supply-tier", List.of("early", "mid", "late"));
      out.put("passives.off-hand-strength", List.of("FULL", "REDUCED"));
      out.put("ritual.altar-mode", List.of("CRAFTED", "ANCIENT", "BOTH"));
      out.put("teams.draft.order", List.of("SNAKE", "ROUND"));
      out.put("teams.draft.captain-order", List.of("CHOSEN", "RANDOM"));
      out.put("teams.nametags.captain-symbol-colour", List.copyOf(colours));
      out.put("teams.nametags.member-symbol-colour", List.copyOf(colours));
      return out;
   }

   /** The problem with one setting, or null (for the settings menu). */
   public String problemWith(String path) {
      return this.problems.get(path);
   }

   /** Every problem, as "path: what's wrong" lines. */
   public List<String> lines() {
      List<String> out = new ArrayList<>();
      this.problems.forEach((path, what) -> out.add(path + ": " + what));
      return out;
   }

   public int count() {
      return this.problems.size();
   }

   /** Checks the config as it is now and writes a summary to the console. */
   public void runAndLog() {
      this.run();
      if (this.problems.isEmpty()) {
         return;
      }

      this.plugin.getLogger().warning("config.yml has " + this.problems.size() + " setting" + (this.problems.size() == 1 ? "" : "s")
         + " that won't work as written (the default is used instead). /lw checkconfig lists them in game:");
      for (String line : this.lines()) {
         this.plugin.getLogger().warning("  " + line);
      }
   }

   public void run() {
      this.problems.clear();
      FileConfiguration config = this.plugin.getConfig();
      Configuration defaults = config.getDefaults();
      if (defaults == null) {
         return;
      }

      this.shape(config, defaults);
      this.choices(config);
      this.weapons(config);
      this.places(config);
      this.loot(config, "");
   }

   private void add(String path, String what) {
      this.problems.putIfAbsent(path, what);
   }

   private static boolean freeForm(String path) {
      for (String prefix : FREE_FORM) {
         if (path.startsWith(prefix) || path.contains("." + prefix)) {
            return true;
         }
      }

      // optional keys the docs offer but the defaults don't set
      return path.matches("(bosses\\.[^.]+|token-shop\\.trader)\\.model\\..*")
         || path.matches("weapons\\.[^.]+\\.(lore|ritual-time|melee|melee\\..*)");
   }

   // ------------------------------------------------------------------ keys and kinds of value

   private void shape(FileConfiguration config, Configuration defaults) {
      List<String> unknown = new ArrayList<>();
      for (String path : config.getKeys(true)) {
         Object value = config.get(path);
         if (!defaults.isSet(path)) {
            boolean underUnknown = unknown.stream().anyMatch(parent -> path.startsWith(parent + "."));
            if (!underUnknown && !freeForm(path)) {
               unknown.add(path);                         // its own settings aren't listed one by one
               this.add(path, path.startsWith("weapons.") && path.split("\\.").length == 2
                  ? "not a legendary this plugin knows: it would be an item with no powers"
                  : "not a setting LumiWeapons knows (a typo, or left over from an older version)");
            }

            continue;
         }

         Object fallback = defaults.get(path);
         if (fallback instanceof ConfigurationSection || value instanceof ConfigurationSection) {
            if (fallback instanceof ConfigurationSection != value instanceof ConfigurationSection) {
               this.add(path, "should be a section of settings");
            }
         } else if (fallback instanceof Boolean && !(value instanceof Boolean)) {
            this.add(path, "should be true or false, not '" + value + "'");
         } else if (fallback instanceof Number && !(value instanceof Number)) {
            this.add(path, "should be a number, not '" + value + "'");
         } else if (fallback instanceof List && !(value instanceof List)) {
            this.add(path, "should be a list");
         } else if (fallback instanceof Number number && value instanceof Number now && number.doubleValue() >= 0.0 && now.doubleValue() < 0.0
            && !path.contains("offset") && !path.endsWith("-y")) {
            this.add(path, "can't be negative (" + now + ")");
         }
      }
   }

   private void choices(FileConfiguration config) {
      CHOICES.forEach((path, allowed) -> {
         String value = config.getString(path);
         if (value != null && allowed.stream().noneMatch(choice -> choice.equalsIgnoreCase(value.trim()))) {
            this.add(path, "'" + value + "' isn't one of " + String.join(", ", allowed.size() > 6 ? allowed.subList(0, 6) : allowed) + (allowed.size() > 6 ? ", ..." : ""));
         }
      });
   }

   // ------------------------------------------------------------------ legendaries

   private void weapons(FileConfiguration config) {
      ConfigurationSection weapons = config.getConfigurationSection("weapons");
      if (weapons == null) {
         return;
      }

      for (String key : weapons.getKeys(false)) {
         String base = "weapons." + key;
         String material = config.getString(base + ".material");
         if (material != null && Material.matchMaterial(material.trim()) == null) {
            this.add(base + ".material", "'" + material + "' isn't a Minecraft item");
         }

         for (String colour : config.getStringList(base + ".ritual-colors")) {
            if (!isHex(colour)) {
               this.add(base + ".ritual-colors", "'" + colour + "' isn't a colour like \"#B14CFF\"");
            }
         }

         List<String> recipe = config.getStringList(base + ".recipe");
         for (String token : recipe) {
            String why = recipeProblem(token);
            if (why != null) {
               this.add(base + ".recipe", why);
            }
         }

         if (recipe.size() > com.lumiweapons.rituals.RitualRecipes.MAX_INGREDIENTS) {
            this.add(base + ".recipe", recipe.size() + " ingredients, but the altar only has " + com.lumiweapons.rituals.RitualRecipes.MAX_INGREDIENTS + " slots");
         }

         for (String enchant : config.getStringList(base + ".enchantments")) {
            String name = enchant.split(":")[0].trim().toLowerCase(Locale.ROOT);
            org.bukkit.NamespacedKey id = name.contains(":") ? org.bukkit.NamespacedKey.fromString(name) : org.bukkit.NamespacedKey.minecraft(name);
            if (id == null || org.bukkit.Registry.ENCHANTMENT.get(id) == null) {
               this.add(base + ".enchantments", "'" + enchant + "' isn't an enchantment (use NAME:LEVEL, e.g. SHARPNESS:5)");
            }
         }
      }

      ConfigurationSection ingredients = config.getConfigurationSection("ritual-block-recipe.ingredients");
      if (ingredients != null) {
         for (String letter : ingredients.getKeys(false)) {
            String material = ingredients.getString(letter, "");
            if (Material.matchMaterial(material.trim()) == null) {
               this.add("ritual-block-recipe.ingredients." + letter, "'" + material + "' isn't a Minecraft item");
            }
         }
      }
   }

   private static String recipeProblem(String token) {
      String[] parts = token.split(":");
      if (parts.length > 1) {
         try {
            Integer.parseInt(parts[1].trim());
         } catch (NumberFormatException failure) {
            return "'" + token + "': the amount after ':' should be a number";
         }
      }

      if (CustomItems.tokenToId(parts[0]) != null) {
         return null;
      }

      Material material = Material.matchMaterial(parts[0].trim());
      return material == null || material.isAir() ? "'" + parts[0] + "' isn't a Minecraft item or a LumiWeapons item" : null;
   }

   private static boolean isHex(String text) {
      String hex = text == null ? "" : text.trim();
      hex = hex.startsWith("#") ? hex.substring(1) : hex;
      return hex.matches("[0-9A-Fa-f]{6}");
   }

   // ------------------------------------------------------------------ times, mobs, colours

   private void places(FileConfiguration config) {
      for (String path : new String[]{"ritual.timezone", "director.timezone"}) {
         String zone = config.getString(path, "");
         if (!zone.isBlank()) {
            try {
               ZoneId.of(zone.trim());
            } catch (RuntimeException failure) {
               this.add(path, "'" + zone + "' isn't a time zone (use one like America/Chicago)");
            }
         }
      }

      for (String bad : this.plugin.getRitualManager() == null ? List.<String>of() : this.plugin.getRitualManager().clock().badLines()) {
         this.add("ritual.windows.times", "can't read '" + bad + "' (use e.g. \"ALL 16:00-20:00\")");
      }

      ConfigurationSection windows = config.getConfigurationSection("director.windows");
      if (windows != null) {
         for (String name : windows.getKeys(false)) {
            for (String end : new String[]{"from", "to"}) {
               String time = windows.getString(name + "." + end);
               if (time != null && !isClock(time)) {
                  this.add("director.windows." + name + "." + end, "'" + time + "' isn't a time like 19:30");
               }
            }
         }
      }

      ConfigurationSection drops = config.getConfigurationSection("fragments.mob-drops");
      if (drops != null) {
         for (String mob : drops.getKeys(false)) {
            try {
               EntityType.valueOf(mob.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
               this.add("fragments.mob-drops." + mob, "'" + mob + "' isn't a mob");
            }

            String spec = drops.getString(mob, "");
            if (!spec.matches("\\s*[1-4]\\s*:\\s*[0-9.]+\\s*")) {
               this.add("fragments.mob-drops." + mob, "'" + spec + "' should be TIER:CHANCE, e.g. \"2:0.4\"");
            }
         }
      }

      String halo = config.getString("events.chosen.halo.colour");
      if (halo != null && !isHex(halo)) {
         this.add("events.chosen.halo.colour", "'" + halo + "' isn't a colour like \"#FFD24A\"");
      }
   }

   private static boolean isClock(String text) {
      try {
         LocalTime.parse(text.trim().length() == 4 ? "0" + text.trim() : text.trim());
         return true;
      } catch (RuntimeException failure) {
         return text.trim().equals("24:00");
      }
   }

   // ------------------------------------------------------------------ loot tables

   /** Every section with a "rolls" range or "items"/"guaranteed" lists is a loot table. */
   private void loot(ConfigurationSection section, String path) {
      if (section == null) {
         return;
      }

      var loot = this.plugin.getEventManager() == null ? null : this.plugin.getEventManager().loot();
      for (String key : section.getKeys(false)) {
         String full = path.isEmpty() ? key : path + "." + key;
         if (section.isConfigurationSection(key)) {
            this.loot(section.getConfigurationSection(key), full);
         } else if (key.equals("rolls") && !String.valueOf(section.get(key)).trim().matches("\\d+(\\s*-\\s*\\d+)?")) {
            this.add(full, "'" + section.get(key) + "' should be a number or a range like \"2-4\"");
         } else if ((key.equals("items") || key.equals("guaranteed")) && section.isList(key) && loot != null) {
            for (String line : section.getStringList(key)) {
               String why = loot.problem(line);
               if (why != null) {
                  this.add(full, "'" + line + "': " + why);
               }
            }
         }
      }
   }
}
