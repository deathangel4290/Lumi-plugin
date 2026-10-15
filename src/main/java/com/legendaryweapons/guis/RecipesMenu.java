package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.rituals.RitualBlock;
import com.legendaryweapons.rituals.RitualRecipes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The recipe book (/recipes, the altar's book button, /lumi). Built on every open, so it always
 * shows the live config, whether fragments are needed, and which one-per-server legendaries are
 * already forged.
 */
public final class RecipesMenu extends Menu {
   public RecipesMenu(LegendaryWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "Legendary Recipes";
   }

   @Override
   protected int rows() {
      return 6;
   }

   private List<String> weapons() {
      return new ArrayList<>(this.plugin.getWeaponManager().getWeaponKeys());
   }

   @Override
   protected int pages() {
      return pagesFor(this.weapons().size() + 1, this.inner().length);
   }

   @Override
   protected void draw() {
      var config = this.plugin.getConfigManager();
      this.show(4, Icon.of(Material.KNOWLEDGE_BOOK).name("How to forge a legendary", ACCENT)
         .text("Put a recipe's ingredients into a Ritual Altar and begin the ritual.")
         .blank().text("It takes " + config.getRitualTimer() / 60 + " minutes, and everyone on the server can see where it is. Breaking the altar ends it.", DIM)
         .build());

      List<ItemStack> entries = new ArrayList<>();
      if (config.isRitualBlockRecipeEnabled()) {
         Icon altar = Icon.of(RitualBlock.getRitualBlockItem()).clearLore().name("Ritual Altar", ACCENT).line(Icon.plain("Crafting table:", GOLD));
         for (String row : config.getRitualBlockRecipeShape()) {
            altar.line(Icon.plain("   " + row.replace(' ', '·'), NamedTextColor.WHITE));
         }

         for (Map.Entry<Character, Material> entry : config.getRitualBlockRecipeIngredients().entrySet()) {
            altar.line(Icon.plain(" " + entry.getKey() + " = " + RitualRecipes.prettify(entry.getValue().name()), TEXT));
         }

         entries.add(altar.build());
      }

      for (String key : this.weapons()) {
         ItemStack weapon = this.plugin.getWeaponManager().getWeapon(key);
         if (weapon == null || weapon.getType().isAir()) {
            continue;
         }

         boolean forged = config.isSingleCraft() && this.plugin.getRitualManager().isCrafted(key);
         Icon icon = Icon.of(weapon).clearLore();
         icon.line(Icon.plain("Ritual ingredients", GOLD));
         for (RitualRecipes.Entry entry : this.plugin.getRitualRecipes().entries(key)) {
            icon.line(Icon.plain(" • " + entry.describe(), entry.customId() != null ? NamedTextColor.AQUA : NamedTextColor.WHITE));
         }

         icon.blank();
         if (config.isSingleCraft()) {
            icon.line(forged ? Icon.plain("✖ Already forged on this server", BAD) : Icon.plain("✔ Not forged yet", GOOD));
         }

         entries.add(icon.build());
      }

      int[] slots = this.inner();
      List<ItemStack> shown = this.slice(entries, slots.length);
      for (int i = 0; i < shown.size(); i++) {
         this.show(slots[i], shown.get(i));
      }

      this.show(this.rows() * 9 - 1, Icon.of(CustomItems.get().create(CustomItems.fragmentId(1), 1)).clearLore().name("Weapon Fragments", NamedTextColor.AQUA)
         .text("Dropped by bosses, found in supply drops and won from events.")
         .blank().text(this.plugin.getRitualRecipes().fusionCost() + " of one tier fuse into one of the next at the altar.", NamedTextColor.WHITE)
         .text("Luminosity Fragments only come from events.", GOLD).build());
   }
}
