package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.rituals.RitualRecipes;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The altar menu (5 rows):
 *
 *   row 0  . . . . [info] . . . .
 *   row 1  . . . . [preview]. . . .
 *   row 2  . . [in][in][in][in][in] . .
 *   row 3  . . . . [begin] . . . .
 *   row 4  . . . . [recipes] . . . .
 *
 * Up to five ingredients. The preview shows the weapon the current items would forge (or the
 * fragment they would fuse into), so players see what they are about to commit to.
 */
public class RitualGUI implements InventoryHolder {
   public static final int[] INGREDIENT_SLOTS = new int[]{20, 21, 22, 23, 24};
   public static final int PREVIEW_SLOT = 13;
   public static final int BUTTON_SLOT = 31;
   public static final int RECIPES_SLOT = 40;
   public static final int INFO_SLOT = 4;
   private final LegendaryWeapons plugin;
   private final Inventory inventory;
   private final Location altar;

   public RitualGUI(LegendaryWeapons plugin, Location altar) {
      this.plugin = plugin;
      this.altar = altar;
      this.inventory = Bukkit.createInventory(this, 45, Component.text("✦ ", Menu.ACCENT).append(Component.text("Ritual Altar", NamedTextColor.DARK_GRAY)));
   }

   public Location getAltar() {
      return this.altar;
   }

   public static boolean isIngredientSlot(int slot) {
      for (int s : INGREDIENT_SLOTS) {
         if (s == slot) {
            return true;
         }
      }

      return false;
   }

   static ItemStack named(Material material, Component name, List<Component> lore) {
      ItemStack item = new ItemStack(material);
      item.editMeta(meta -> {
         meta.displayName(name.decoration(TextDecoration.ITALIC, false));
         meta.lore(lore);
      });
      return item;
   }

   static Component line(String text, NamedTextColor colour) {
      return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
   }

   public void setup() {
      ItemStack filler = Icon.pane(Material.GRAY_STAINED_GLASS_PANE);
      ItemStack frame = Icon.pane(Material.BLACK_STAINED_GLASS_PANE);
      ItemStack corner = Icon.pane(Material.PURPLE_STAINED_GLASS_PANE);

      for (int i = 0; i < this.inventory.getSize(); i++) {
         int row = i / 9;
         int col = i % 9;
         boolean edge = row == 0 || row == 4 || col == 0 || col == 8;
         boolean isCorner = (row == 0 || row == 4) && (col == 0 || col == 8);
         this.inventory.setItem(i, isCorner ? corner : (edge ? frame : filler));
      }

      for (int slot : INGREDIENT_SLOTS) {
         this.inventory.setItem(slot, null);
      }

      this.inventory.setItem(INFO_SLOT, named(Material.WRITTEN_BOOK, Component.text("How rituals work", NamedTextColor.LIGHT_PURPLE), List.of(
         line("Place a recipe's ingredients in the", NamedTextColor.GRAY),
         line("five slots below, then press Begin.", NamedTextColor.GRAY),
         Component.empty(),
         line("The ritual takes " + this.plugin.getConfigManager().getRitualTimer() / 60 + " minutes and everyone", NamedTextColor.GRAY),
         line("on the server sees where it is.", NamedTextColor.GRAY),
         line("Breaking the altar ends it and spills", NamedTextColor.DARK_GRAY),
         line("the ingredients.", NamedTextColor.DARK_GRAY),
         Component.empty(),
         line(this.plugin.getRitualRecipes().fusionCost() + " fragments of one tier fuse into the next.", NamedTextColor.AQUA)
      )));
      this.inventory.setItem(RECIPES_SLOT, named(Material.BOOK, Component.text("Recipe book", NamedTextColor.YELLOW), List.of(line("Click to view every recipe.", NamedTextColor.GRAY))));
      this.update(null);
   }

   public List<ItemStack> ingredients() {
      List<ItemStack> items = new ArrayList<>();

      for (int slot : INGREDIENT_SLOTS) {
         items.add(this.inventory.getItem(slot));
      }

      return items;
   }

   public void update(Player viewer) {
      List<ItemStack> items = this.ingredients();
      RitualRecipes recipes = this.plugin.getRitualRecipes();
      RitualRecipes.Match match = recipes.match(items);
      int fusion = match == null ? recipes.fusionTier(items) : 0;
      if (match != null) {
         ItemStack preview = this.plugin.getWeaponManager().getWeapon(match.weaponKey());
         this.inventory.setItem(PREVIEW_SLOT, preview);
         boolean taken = this.plugin.getConfigManager().isSingleCraft() && this.plugin.getRitualManager().isCrafted(match.weaponKey());
         if (taken) {
            this.inventory.setItem(BUTTON_SLOT, named(Material.BARRIER, Component.text("Already forged", NamedTextColor.RED), List.of(line("Each legendary can only exist once.", NamedTextColor.GRAY))));
         } else if (this.plugin.getRitualManager().getRitualAtLocation(this.altar) != null) {
            this.inventory.setItem(BUTTON_SLOT, named(Material.BARRIER, Component.text("Altar busy", NamedTextColor.RED), List.of()));
         } else {
            this.inventory.setItem(BUTTON_SLOT, named(Material.ANVIL, Component.text("Begin Ritual", NamedTextColor.GREEN), List.of(
               line("Takes " + this.plugin.getConfigManager().getRitualTimer() / 60 + " minutes.", NamedTextColor.GRAY),
               line("Extra items are handed back.", NamedTextColor.DARK_GRAY)
            )));
         }
      } else if (fusion > 0) {
         this.inventory.setItem(PREVIEW_SLOT, CustomItems.get().create(CustomItems.fragmentId(fusion + 1), 1));
         this.inventory.setItem(BUTTON_SLOT, named(Material.SMITHING_TABLE, Component.text("Fuse fragments", NamedTextColor.AQUA), List.of(
            line("Costs " + recipes.fusionCost() + " fragments. Instant.", NamedTextColor.GRAY)
         )));
      } else {
         boolean empty = items.stream().allMatch(item -> item == null || item.getType().isAir());
         this.inventory.setItem(PREVIEW_SLOT, named(Material.ENDER_EYE, Component.text(empty ? "Place ingredients" : "No matching recipe", empty ? NamedTextColor.GRAY : NamedTextColor.RED), List.of(
            line("Check the recipe book below.", NamedTextColor.DARK_GRAY)
         )));
         this.inventory.setItem(BUTTON_SLOT, named(Material.BARRIER, Component.text("Invalid recipe", NamedTextColor.RED), List.of()));
      }
   }

   @NotNull
   public Inventory getInventory() {
      return this.inventory;
   }
}
