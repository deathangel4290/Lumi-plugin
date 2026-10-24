package com.lumiweapons.guis;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * A menu button's item, built in one chain:
 *
 *   Icon.of(Material.COMPARATOR).name("Settings", Menu.ACCENT).text("Particles, ability bind.").click("open").build()
 *
 * Every line is non-italic, and vanilla's extra tooltip lines (attributes, enchantments, ...) are
 * hidden, so all menus read the same way.
 */
public final class Icon {
   private static final int WRAP = 34;
   private final ItemStack item;
   private Component name;
   private final List<Component> lore = new ArrayList<>();
   private Boolean glow;

   private Icon(ItemStack item) {
      this.item = item;
   }

   public static Icon of(Material material) {
      return new Icon(new ItemStack(material));
   }

   /** Starts from an existing item (a weapon, a custom item) and keeps its look and lore. */
   public static Icon of(ItemStack base) {
      Icon icon = new Icon(base.clone());
      if (base.hasItemMeta()) {
         List<Component> existing = base.getItemMeta().lore();
         if (existing != null) {
            icon.lore.addAll(existing);
         }
      }

      return icon;
   }

   /** A player's head; the skin fills in from the server's profile cache. */
   public static Icon head(UUID id) {
      return head(id, null);
   }

   /** A player's head with a known skin (texture property), so offline players aren't Steve. */
   public static Icon head(UUID id, com.destroystokyo.paper.profile.ProfileProperty skin) {
      Icon icon = of(Material.PLAYER_HEAD);
      icon.item.editMeta(meta -> {
         if (meta instanceof SkullMeta skull) {
            org.bukkit.entity.Player online = Bukkit.getPlayer(id);
            if (online != null) {
               skull.setPlayerProfile(online.getPlayerProfile());
            } else if (skin != null) {
               com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(id, null);
               profile.setProperty(skin);
               skull.setPlayerProfile(profile);
            } else {
               skull.setOwningPlayer(Bukkit.getOfflinePlayer(id));
            }
         }
      });
      return icon;
   }

   public static Component plain(String text, TextColor colour) {
      return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
   }

   public Icon name(String text, TextColor colour) {
      this.name = Component.text(text, colour).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false);
      return this;
   }

   public Icon name(Component component) {
      this.name = component.decoration(TextDecoration.ITALIC, false);
      return this;
   }

   /** Grey description text, wrapped to a comfortable tooltip width. */
   public Icon text(String text) {
      return this.text(text, Menu.TEXT);
   }

   public Icon text(String text, TextColor colour) {
      for (String line : wrap(text)) {
         this.lore.add(plain(line, colour));
      }

      return this;
   }

   public Icon line(Component component) {
      this.lore.add(component.decoration(TextDecoration.ITALIC, false));
      return this;
   }

   public Icon lines(List<? extends Component> components) {
      for (Component component : components) {
         this.line(component);
      }

      return this;
   }

   /** Drops the lore an item started with (Icon.of(ItemStack) keeps it). */
   public Icon clearLore() {
      this.lore.clear();
      return this;
   }

   /** "Label: value" with the value picked out. */
   public Icon stat(String label, String value, TextColor valueColour) {
      this.lore.add(plain(label + ": ", Menu.TEXT).append(Component.text(value, valueColour)));
      return this;
   }

   public Icon blank() {
      if (!this.lore.isEmpty()) {
         this.lore.add(Component.empty());
      }

      return this;
   }

   /** A control hint, e.g. click("toggle") reads "▸ Click to toggle". */
   public Icon click(String what) {
      return this.hint("Click", what);
   }

   public Icon hint(String button, String what) {
      this.lore.add(plain("▸ ", Menu.ACCENT).append(Component.text(button, NamedTextColor.YELLOW)).append(Component.text(" to " + what, Menu.DIM)));
      return this;
   }

   public Icon glow(boolean on) {
      this.glow = on;
      return this;
   }

   public Icon amount(int amount) {
      this.item.setAmount(Math.max(1, Math.min(64, amount)));
      return this;
   }

   public Icon model(String key) {
      NamespacedKey parsed = key == null ? null : NamespacedKey.fromString(key);
      if (parsed != null) {
         this.item.editMeta(meta -> meta.setItemModel(parsed));
      }

      return this;
   }

   public ItemStack build() {
      this.item.editMeta(meta -> {
         if (this.name != null) {
            meta.displayName(this.name);
         }

         meta.lore(this.lore);
         meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
         if (this.glow != null) {
            meta.setEnchantmentGlintOverride(this.glow);
         }
      });
      return this.item;
   }

   /** Filler glass: no tooltip at all. */
   public static ItemStack pane(Material material) {
      ItemStack pane = new ItemStack(material);
      pane.editMeta(meta -> meta.setHideTooltip(true));
      return pane;
   }

   static List<String> wrap(String text) {
      List<String> out = new ArrayList<>();
      for (String paragraph : text.split("\n")) {
         StringBuilder line = new StringBuilder();
         for (String word : paragraph.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > WRAP) {
               out.add(line.toString());
               line.setLength(0);
            }

            if (line.length() > 0) {
               line.append(' ');
            }

            line.append(word);
         }

         out.add(line.toString());
      }

      return out;
   }
}
