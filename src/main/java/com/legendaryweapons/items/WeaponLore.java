package com.legendaryweapons.items;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.Passives;
import com.legendaryweapons.managers.ConfigManager;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds a legendary's tooltip from lore.yml and the weapon's numbers:
 *
 *   Reaps what it kills.                          flavour
 *   Power ◆◆◆◇
 *
 *   ✦ Passive · also from the off-hand
 *    ▸ Kill a player: heal to full
 *    ▸ Strike from behind: 1.35x damage
 *        Taking a hit resets the combo.           ~note
 *
 *   ✦ Ability
 *    ▸ Ability: throw the glaive for 2.5❤
 *   ◷ Pearl 10s
 *
 * The accent colour is the weapon name's colour. Numbers in a result are picked out in it.
 */
public final class WeaponLore {
   private static final Pattern NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?(?:x|s|%|❤)?");
   private final LegendaryWeapons plugin;
   private final File file;
   private YamlConfiguration lore;

   public WeaponLore(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "lore.yml");
      this.reload();
   }

   public void reload() {
      if (!this.file.exists()) {
         this.plugin.saveResource("lore.yml", false);
      }

      this.lore = YamlConfiguration.loadConfiguration(this.file);
      InputStream bundled = this.plugin.getResource("lore.yml");
      if (bundled != null) {
         // a weapon missing from the server's lore.yml (new in an update) uses the bundled lore
         this.lore.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
      }
   }

   /** The weapon name's first colour code, as the accent colour. */
   public static TextColor accent(String displayName) {
      if (displayName != null) {
         for (int i = 0; i + 1 < displayName.length(); i++) {
            char marker = displayName.charAt(i);
            if (marker == '§' || marker == '&') {
               NamedTextColor colour = switch (Character.toLowerCase(displayName.charAt(i + 1))) {
                  case '0' -> NamedTextColor.BLACK;
                  case '1' -> NamedTextColor.DARK_BLUE;
                  case '2' -> NamedTextColor.DARK_GREEN;
                  case '3' -> NamedTextColor.DARK_AQUA;
                  case '4' -> NamedTextColor.DARK_RED;
                  case '5' -> NamedTextColor.DARK_PURPLE;
                  case '6' -> NamedTextColor.GOLD;
                  case '7' -> NamedTextColor.GRAY;
                  case '8' -> NamedTextColor.DARK_GRAY;
                  case '9' -> NamedTextColor.BLUE;
                  case 'a' -> NamedTextColor.GREEN;
                  case 'b' -> NamedTextColor.AQUA;
                  case 'c' -> NamedTextColor.RED;
                  case 'd' -> NamedTextColor.LIGHT_PURPLE;
                  case 'e' -> NamedTextColor.YELLOW;
                  case 'f' -> NamedTextColor.WHITE;
                  default -> null;
               };
               if (colour != null) {
                  return colour;
               }
            }
         }
      }

      return NamedTextColor.LIGHT_PURPLE;
   }

   private static Component plain(String text, TextColor colour) {
      return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
   }

   /** The result half of a line: white, numbers in the accent colour. */
   private static Component result(String text, TextColor accent) {
      TextComponent.Builder out = Component.text().decoration(TextDecoration.ITALIC, false);
      Matcher matcher = NUMBER.matcher(text);
      int at = 0;
      while (matcher.find()) {
         if (matcher.start() > at) {
            out.append(Component.text(text.substring(at, matcher.start()), NamedTextColor.WHITE));
         }

         out.append(Component.text(matcher.group(), accent));
         at = matcher.end();
      }

      if (at < text.length()) {
         out.append(Component.text(text.substring(at), NamedTextColor.WHITE));
      }

      return out.build();
   }

   private void lines(List<Component> out, List<String> raw, ConfigurationSection abilities, TextColor accent) {
      for (String line : raw) {
         String filled = ConfigManager.fillPlaceholders(line, abilities);
         if (filled.startsWith("~")) {
            out.add(plain("    " + filled.substring(1).trim(), NamedTextColor.DARK_GRAY));
            continue;
         }

         int split = filled.indexOf('|');
         Component row = plain(" ▸ ", accent);
         if (split < 0) {
            row = row.append(plain(filled.trim(), NamedTextColor.GRAY));
         } else {
            row = row.append(plain(filled.substring(0, split).trim(), NamedTextColor.GRAY))
               .append(plain(": ", NamedTextColor.DARK_GRAY))
               .append(result(filled.substring(split + 1).trim(), accent));
         }

         out.add(row);
      }
   }

   private static Component header(String title, TextColor accent) {
      return plain("✦ ", accent).append(Component.text(title, accent, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
   }

   /**
    * The tooltip for `weaponKey`. A weapon with its own lore: list in config.yml keeps that (old
    * configs, or a server's custom lore), with the power line added.
    */
   public List<Component> build(String weaponKey) {
      ConfigManager config = this.plugin.getConfigManager();
      ConfigurationSection abilities = config.getWeaponAbilities(weaponKey);
      TextColor accent = accent(config.getWeaponDisplayName(weaponKey));
      int tier = config.getWeaponPowerTier(weaponKey);
      List<Component> out = new ArrayList<>();
      List<String> custom = this.plugin.getConfig().getStringList("weapons." + weaponKey + ".lore");
      if (!custom.isEmpty()) {
         for (String line : config.getWeaponLore(weaponKey)) {
            out.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(line).decoration(TextDecoration.ITALIC, false));
         }

         if (tier > 0) {
            out.add(Component.empty());
            out.add(power(tier, accent));
         }

         return out;
      }

      ConfigurationSection section = this.lore.getConfigurationSection(weaponKey);
      if (section == null) {
         if (tier > 0) {
            out.add(power(tier, accent));
         }

         return out;
      }

      String flavour = section.getString("flavour", "");
      if (!flavour.isBlank()) {
         out.add(Component.text(flavour, NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
      }

      if (tier > 0) {
         out.add(power(tier, accent));
      }

      List<String> passives = section.getStringList("passives");
      if (!passives.isEmpty()) {
         out.add(Component.empty());
         Component head = header("Passive", accent);
         if (Passives.WITH_PASSIVES.contains(weaponKey) && Passives.offHandEnabled(this.plugin)) {
            double strength = Passives.offHandStrength(this.plugin);
            head = head.append(plain("  · also from the off-hand" + (strength < 1.0 ? " (" + Math.round(strength * 100.0) + "%)" : ""), NamedTextColor.DARK_GRAY));
         }

         out.add(head);
         this.lines(out, passives, abilities, accent);
      }

      List<String> ability = section.getStringList("ability");
      if (!ability.isEmpty()) {
         out.add(Component.empty());
         out.add(header("Ability", accent).append(plain("  · your ability key (/lumi settings)", NamedTextColor.DARK_GRAY)));
         this.lines(out, ability, abilities, accent);
      }

      List<String> cooldowns = section.getStringList("cooldowns");
      if (!cooldowns.isEmpty()) {
         Component row = plain("◷ ", NamedTextColor.GRAY);
         for (int i = 0; i < cooldowns.size(); i++) {
            String filled = ConfigManager.fillPlaceholders(cooldowns.get(i), abilities);
            int split = filled.indexOf('|');
            String label = split < 0 ? "Cooldown" : filled.substring(0, split).trim();
            String value = split < 0 ? filled.trim() : filled.substring(split + 1).trim();
            if (i > 0) {
               row = row.append(plain("  ·  ", NamedTextColor.DARK_GRAY));
            }

            row = row.append(plain(label + " ", NamedTextColor.GRAY)).append(result(value, accent));
         }

         out.add(Component.empty());
         out.add(row);
      }

      List<String> notes = section.getStringList("notes");
      if (!notes.isEmpty()) {
         if (cooldowns.isEmpty()) {
            out.add(Component.empty());
         }

         for (String note : notes) {
            out.add(Component.text(ConfigManager.fillPlaceholders(note, abilities), NamedTextColor.DARK_GRAY, TextDecoration.ITALIC));
         }
      }

      return out;
   }

   private static Component power(int tier, TextColor accent) {
      int shown = Math.max(1, Math.min(4, tier));
      return plain("Power ", NamedTextColor.DARK_GRAY).append(plain("◆".repeat(shown), accent)).append(plain("◇".repeat(4 - shown), NamedTextColor.DARK_GRAY));
   }
}
