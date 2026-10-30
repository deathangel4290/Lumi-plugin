package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Server settings in game. Two views:
 *
 *  - the front page (path null): the settings people change most, as one-click toggles and
 *    values, plus shortcuts into the big sections;
 *  - a section (path "effects", "bosses.wither_king", ... or "" for the top): every key in it, in
 *    config.yml's order, with the comment from config.yml as its description.
 *
 * Booleans toggle on click. Numbers: left-click up, right-click down, shift for ten steps, Q to
 * type a value. Text: click to type it (or cycle, when the setting has fixed choices). Lists of
 * text: left-click adds a line, shift-right-click removes the last one. A setting changed from the
 * default has a ✎ after its name, and Ctrl+Q puts the default back.
 *
 * Every change is written to config.yml (its comments are kept) and applied like /lw reload.
 */
public final class ConfigMenu extends Menu {
   private static final String[] QUICK_TOGGLES = {
      "acquisition.ritual-crafting", "acquisition.single-craft-per-weapon", "acquisition.require-fragments",
      "effects.sprites", "ritual.windows.enabled", "ritual.custom-altar", "trust-system.prevent-ability-damage"
   };
   private static final String[] QUICK_VALUES = {
      "effects.default-player-level", "ritual.timer", "fragments.fusion-cost", "audio.volume",
      "ritual.altar-mode", "outposts.capture-seconds", "passives.off-hand-strength"
   };
   private static final String[][] SHORTCUTS = {
      {"weapons", "Weapons"}, {"bosses", "Bosses"}, {"events", "Events"}, {"effects", "Effects"},
      {"ritual", "Rituals"}, {"teams", "Teams"}, {"", "Every setting"}
   };
   /** Settings with a fixed set of values: a click cycles through them (shared with ConfigCheck). */
   private static final Map<String, List<String>> CHOICES = com.lumiweapons.managers.ConfigCheck.CHOICES;
   /** Bookkeeping the plugin manages itself. */
   private static final List<String> HIDDEN = List.of("config-version", "balance-revision");
   private final String path;

   public ConfigMenu(LumiWeapons plugin, Player viewer, Menu parent, String path) {
      super(plugin, viewer, parent);
      this.path = path;
   }

   private FileConfiguration config() {
      return this.plugin.getConfig();
   }

   @Override
   protected String title() {
      if (this.path == null) {
         return "Server Settings";
      }

      if (this.path.isEmpty()) {
         return "Every setting";
      }

      List<String> crumbs = new ArrayList<>();
      for (String part : this.path.split("\\.")) {
         crumbs.add(pretty(part));
      }

      String title = String.join(" › ", crumbs);
      while (title.length() > 32 && crumbs.size() > 1) {      // keep the end of the path when it's long
         crumbs.remove(0);
         title = "… › " + String.join(" › ", crumbs);
      }

      return title;
   }

   @Override
   protected int rows() {
      return 6;
   }

   private List<String> keys() {
      List<String> out = new ArrayList<>();
      ConfigurationSection section = this.path == null ? null : (this.path.isEmpty() ? this.config() : this.config().getConfigurationSection(this.path));
      if (section != null) {
         for (String key : section.getKeys(false)) {
            String full = this.path.isEmpty() ? key : this.path + "." + key;
            if (!HIDDEN.contains(full)) {
               out.add(full);
            }
         }
      }

      return out;
   }

   @Override
   protected int pages() {
      return this.path == null ? 1 : pagesFor(this.keys().size(), this.inner().length);
   }

   // ------------------------------------------------------------------ drawing

   @Override
   protected void draw() {
      if (this.path == null) {
         this.drawFront();
         return;
      }

      Icon header = Icon.of(Material.KNOWLEDGE_BOOK).name(this.path.isEmpty() ? "config.yml" : this.path, ACCENT);
      this.describe(header, this.path);
      this.show(4, header.blank().text("Changes save to config.yml and apply straight away. A few (crafting recipes) need a restart.", DIM).build());
      int[] slots = this.inner();
      List<String> shown = this.slice(this.keys(), slots.length);
      for (int i = 0; i < shown.size(); i++) {
         this.entry(slots[i], shown.get(i));
      }
   }

   private void drawFront() {
      this.show(4, Icon.of(Material.REPEATER).name("Server Settings", ACCENT)
         .text("The settings changed most often. Everything else is under Every setting.")
         .blank().text("Changes save to config.yml and apply straight away.", DIM).build());
      int slot = 10;
      for (String key : QUICK_TOGGLES) {
         if (this.config().contains(key)) {
            this.entry(slot++, key);
         }
      }

      slot = 19;
      for (String key : QUICK_VALUES) {
         if (this.config().contains(key)) {
            this.entry(slot++, key);
         }
      }

      slot = 37;
      for (String[] shortcut : SHORTCUTS) {
         String target = shortcut[0];
         if (!target.isEmpty() && !this.config().isConfigurationSection(target)) {
            continue;
         }

         Icon icon = Icon.of(target.isEmpty() ? Material.BOOKSHELF : sectionIcon(target)).name(shortcut[1], GOLD);
         if (!target.isEmpty()) {
            this.describe(icon, target);
            icon.stat("Settings inside", String.valueOf(this.config().getConfigurationSection(target).getKeys(true).size()), NamedTextColor.WHITE);
         } else {
            icon.text("Browse the whole of config.yml, section by section.");
         }

         this.button(slot++, icon.blank().click("open").build(), click -> {
            this.clickSound(click.player());
            new ConfigMenu(this.plugin, click.player(), this, target).open();
         });
      }
   }

   /** The comment above `key` in config.yml (and the one after it), as tooltip text. */
   private void describe(Icon icon, String key) {
      if (key.isEmpty()) {
         return;
      }

      // ConfigCheck's verdict first: a setting that won't work says so, a section counts its problems
      var check = this.plugin.getConfigCheck();
      if (check != null) {
         String problem = check.problemWith(key);
         long inside = check.lines().stream().filter(line -> line.startsWith(key + ".")).count();
         if (problem != null) {
            icon.line(Icon.plain("⚠ " + problem, BAD)).blank();
         } else if (inside > 0) {
            icon.line(Icon.plain("⚠ " + inside + " setting" + (inside == 1 ? "" : "s") + " inside won't work as written", BAD)).blank();
         }
      }

      List<String> lines = new ArrayList<>();
      for (String comment : this.config().getComments(key)) {
         if (comment != null && !comment.isBlank()) {
            lines.add(comment.trim());
         }
      }

      for (String comment : this.config().getInlineComments(key)) {
         if (comment != null && !comment.isBlank()) {
            lines.add(comment.trim());
         }
      }

      String text = String.join(" ", lines).replaceAll("\\s+", " ").trim();
      if (!text.isEmpty()) {
         List<String> wrapped = Icon.wrap(text);
         for (int i = 0; i < wrapped.size() && i < 8; i++) {
            icon.line(Icon.plain(wrapped.get(i) + (i == 7 && wrapped.size() > 8 ? " ..." : ""), TEXT));
         }

         icon.blank();
      }
   }

   private void entry(int slot, String key) {
      FileConfiguration config = this.config();
      String name = pretty(key.substring(key.lastIndexOf('.') + 1)) + (this.changed(key) ? " ✎" : "");
      if (config.isConfigurationSection(key)) {
         Icon icon = Icon.of(this.iconFor(key)).clearLore().name(name, GOLD);
         this.describe(icon, key);
         icon.stat("Settings inside", String.valueOf(config.getConfigurationSection(key).getKeys(true).size()), NamedTextColor.WHITE);
         this.button(slot, icon.blank().click("open").build(), click -> {
            this.clickSound(click.player());
            new ConfigMenu(this.plugin, click.player(), this, key).open();
         });
      } else if (config.isBoolean(key)) {
         boolean on = config.getBoolean(key);
         Icon icon = Icon.of(on ? Material.LIME_DYE : Material.GRAY_DYE).name(name, on ? GOOD : BAD);
         this.describe(icon, key);
         icon.stat("Now", onOff(on), on ? GOOD : BAD);
         this.defaultLine(icon, key);
         this.button(slot, icon.blank().click("turn " + (on ? "off" : "on")).glow(on).build(), click -> {
            if (this.reset(click, key)) {
               return;
            }

            this.change(key, !on);
            this.toggleSound(click.player(), !on);
         });
      } else if (config.isInt(key) || config.isLong(key) || config.isDouble(key)) {
         this.numberEntry(slot, key, name);
      } else if (config.isString(key)) {
         this.textEntry(slot, key, name);
      } else if (config.isList(key)) {
         this.listEntry(slot, key, name);
      } else {
         Icon icon = Icon.of(Material.PAPER).name(name, NamedTextColor.WHITE);
         this.describe(icon, key);
         this.show(slot, icon.stat("Now", String.valueOf(config.get(key)), NamedTextColor.WHITE).text("Edit this one in config.yml.", DIM).build());
      }
   }

   private void numberEntry(int slot, String key, String name) {
      FileConfiguration config = this.config();
      boolean whole = config.isInt(key) || config.isLong(key);
      double value = config.getDouble(key);
      double step = step(value, whole);
      Icon icon = Icon.of(Material.REPEATER).name(name, NamedTextColor.WHITE);
      this.describe(icon, key);
      icon.stat("Now", number(value, whole), GOLD);
      this.defaultLine(icon, key);
      icon.blank().hint("Left-click", "add " + number(step, whole)).hint("Right-click", "take " + number(step, whole))
         .hint("Shift", "for " + number(step * 10.0, whole)).hint("Q", "type a value");
      this.button(slot, icon.build(), click -> {
         if (this.reset(click, key)) {
            return;
         }

         if (click.drop()) {
            this.clickSound(click.player());
            ChatPrompt.ask(click.player(), "New value for " + key + ":", number(value, whole), typed -> {
               try {
                  double parsed = Double.parseDouble(typed.replace(',', '.'));
                  if (whole && parsed != Math.rint(parsed)) {
                     return "This setting takes a whole number.";
                  }

                  this.change(key, whole ? (Object)(long)parsed : (Object)parsed);
                  return null;
               } catch (NumberFormatException failure) {
                  return "'" + typed + "' is not a number.";
               }
            }, this::open);
            return;
         }

         double by = (click.right() ? -step : step) * (click.shift() ? 10.0 : 1.0);
         double next = value + by;
         if (value >= 0.0 && next < 0.0) {
            next = 0.0;                                   // a setting that was never negative stays that way
         }

         next = Math.round(next * 10000.0) / 10000.0;
         this.change(key, whole ? (Object)(long)Math.round(next) : (Object)next);
         this.sound(click.player(), org.bukkit.Sound.UI_BUTTON_CLICK, click.right() ? 1.0F : 1.6F);
      });
   }

   private void textEntry(int slot, String key, String name) {
      String value = this.config().getString(key, "");
      List<String> choices = CHOICES.get(key);
      Icon icon = Icon.of(choices != null ? Material.COMPASS : Material.NAME_TAG).name(name, NamedTextColor.WHITE);
      this.describe(icon, key);
      icon.stat("Now", value.isEmpty() ? "(empty)" : value, GOLD);
      if (choices != null) {
         icon.text("Choices: " + String.join(", ", choices), DIM);
      }

      this.defaultLine(icon, key);
      icon.blank();
      if (choices != null) {
         icon.click("pick the next one");
      } else {
         icon.click("type a new value").hint("Shift-right-click", "clear it");
      }

      this.button(slot, icon.build(), click -> {
         if (this.reset(click, key)) {
            return;
         }

         if (choices != null) {
            int at = -1;
            for (int i = 0; i < choices.size(); i++) {
               if (choices.get(i).equalsIgnoreCase(value)) {
                  at = i;
               }
            }

            this.change(key, choices.get((at + (click.right() ? choices.size() - 1 : 1)) % choices.size()));
            this.clickSound(click.player());
         } else if (click.shift() && click.right()) {
            this.change(key, "");
            this.toggleSound(click.player(), false);
         } else {
            this.clickSound(click.player());
            ChatPrompt.ask(click.player(), "New value for " + key + ":", value, typed -> {
               this.change(key, typed);
               return null;
            }, this::open);
         }
      });
   }

   private void listEntry(int slot, String key, String name) {
      List<?> list = this.config().getList(key, List.of());
      boolean text = list.stream().allMatch(item -> item instanceof String);
      Icon icon = Icon.of(Material.BOOK).name(name, NamedTextColor.WHITE);
      this.describe(icon, key);
      icon.stat("Entries", String.valueOf(list.size()), GOLD);
      for (int i = 0; i < list.size() && i < 8; i++) {
         icon.line(Icon.plain(" • " + list.get(i), NamedTextColor.WHITE));
      }

      if (list.size() > 8) {
         icon.text(" ... and " + (list.size() - 8) + " more", DIM);
      }

      icon.blank();
      if (!text) {
         this.show(slot, icon.text("Edit this list in config.yml.", DIM).build());
         return;
      }

      icon.hint("Left-click", "add a line").hint("Shift-right-click", "remove the last line");
      this.button(slot, icon.build(), click -> {
         if (this.reset(click, key)) {
            return;
         }

         List<Object> edited = new ArrayList<>(list);
         if (click.shift() && click.right()) {
            if (edited.isEmpty()) {
               this.errorSound(click.player());
               return;
            }

            edited.remove(edited.size() - 1);
            this.change(key, edited);
            this.toggleSound(click.player(), false);
         } else if (click.left()) {
            this.clickSound(click.player());
            ChatPrompt.ask(click.player(), "Add a line to " + key + ":", null, typed -> {
               edited.add(typed);
               this.change(key, edited);
               return null;
            }, this::open);
         }
      });
   }

   private void defaultLine(Icon icon, String key) {
      var defaults = this.config().getDefaults();
      Object fallback = defaults == null ? null : defaults.get(key);
      if (this.changed(key)) {
         icon.stat("Default", String.valueOf(fallback), DIM).hint("Ctrl+Q", "reset to default");
      }
   }

   /** A leaf setting this server has changed from the plugin's default. */
   private boolean changed(String key) {
      var defaults = this.config().getDefaults();
      Object fallback = defaults == null || this.config().isConfigurationSection(key) ? null : defaults.get(key);
      Object now = this.config().get(key);
      return fallback != null && now != null && !String.valueOf(fallback).equals(String.valueOf(now));
   }

   /** Ctrl+Q on a changed setting puts the default back. */
   private boolean reset(Menu.Click click, String key) {
      if (!click.reset()) {
         return false;
      }

      if (!this.changed(key)) {
         this.errorSound(click.player());
         return true;
      }

      this.change(key, this.config().getDefaults().get(key));
      this.toggleSound(click.player(), false);
      return true;
   }

   // ------------------------------------------------------------------ changing

   private void change(String key, Object value) {
      apply(this.plugin, this.viewer, key, value);
      this.refresh();
   }

   /** Saves one setting to config.yml and applies it like /lw reload (any menu can use this). */
   static void apply(LumiWeapons plugin, Player who, String key, Object value) {
      plugin.getConfig().set(key, value);
      plugin.saveConfig();
      plugin.reloadSettings();
      plugin.getLogger().info(who.getName() + " set " + key + " to " + value + " (settings menu)");
   }

   // ------------------------------------------------------------------ helpers

   private static double step(double value, boolean whole) {
      double size = Math.abs(value);
      if (whole) {
         return size >= 1000.0 ? 50.0 : (size >= 100.0 ? 10.0 : 1.0);
      }

      return size >= 10.0 ? 1.0 : (size >= 1.0 ? 0.1 : 0.05);
   }

   private static String number(double value, boolean whole) {
      if (whole || value == Math.rint(value) && Math.abs(value) < 1.0E9) {
         return whole ? String.valueOf((long)value) : String.format(Locale.ROOT, "%.1f", value);
      }

      String text = String.format(Locale.ROOT, "%.4f", value);
      return text.replaceAll("0+$", "").replaceAll("\\.$", "");
   }

   static String pretty(String key) {
      String spaced = key.replace('-', ' ').replace('_', ' ');
      return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
   }

   private Material iconFor(String key) {
      String[] parts = key.split("\\.");
      if (parts.length == 2 && parts[0].equals("weapons")) {
         ItemStack weapon = this.plugin.getWeaponManager().getWeapon(parts[1]);
         if (weapon != null && !weapon.getType().isAir()) {
            return weapon.getType();
         }
      }

      if (parts.length == 2 && parts[0].equals("bosses")) {
         return AdminBossMenu.icon(parts[1]);
      }

      return parts.length == 1 ? sectionIcon(key) : Material.CHEST;
   }

   private static Material sectionIcon(String key) {
      return switch (key) {
         case "audio" -> Material.NOTE_BLOCK;
         case "effects" -> Material.BLAZE_POWDER;
         case "hud" -> Material.OAK_SIGN;
         case "protection" -> Material.SHIELD;
         case "acquisition" -> Material.ANVIL;
         case "tools" -> Material.DIAMOND_PICKAXE;
         case "fragments" -> Material.AMETHYST_SHARD;
         case "weapons" -> Material.NETHERITE_SWORD;
         case "ritual" -> Material.ENCHANTING_TABLE;
         case "ritual-block-recipe" -> Material.CRAFTING_TABLE;
         case "events" -> Material.BELL;
         case "director" -> Material.OBSERVER;
         case "teams" -> Material.WHITE_BANNER;
         case "passives" -> Material.TOTEM_OF_UNDYING;
         case "outposts" -> Material.BEACON;
         case "token-shop" -> Material.EMERALD;
         case "bosses" -> Material.WITHER_SKELETON_SKULL;
         case "trust-system" -> Material.LEAD;
         case "messages" -> Material.WRITABLE_BOOK;
         default -> Material.CHEST;
      };
   }
}
