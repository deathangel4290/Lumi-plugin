package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.rituals.RitualBlock;
import com.legendaryweapons.rituals.RitualRecipes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The recipe book. Built fresh on every open so it always reflects the live config, whether
 * fragments are required, and which one-per-server legendaries have already been forged.
 */
public class RecipesGUI {
   private final LegendaryWeapons plugin;

   public RecipesGUI(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public static final class Holder implements InventoryHolder {
      private Inventory inventory;

      @NotNull
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   private static Component line(String text, NamedTextColor colour) {
      return Component.text(text, colour).decoration(TextDecoration.ITALIC, false);
   }

   public void open(@NotNull Player player) {
      Holder holder = new Holder();
      Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Legendary Recipes", NamedTextColor.DARK_PURPLE));
      holder.inventory = inventory;
      ItemStack border = RitualGUI.named(Material.PURPLE_STAINED_GLASS_PANE, Component.text(" "), List.of());

      for (int i = 0; i < 54; i++) {
         if (i < 9 || i > 44 || i % 9 == 0 || i % 9 == 8) {
            inventory.setItem(i, border);
         }
      }

      int slot = 10;
      if (this.plugin.getConfigManager().isRitualBlockRecipeEnabled()) {
         ItemStack altar = RitualBlock.getRitualBlockItem();
         List<Component> lore = new ArrayList<>();
         lore.add(line("Crafting table recipe:", NamedTextColor.YELLOW));

         for (String row : this.plugin.getConfigManager().getRitualBlockRecipeShape()) {
            lore.add(line("  " + row, NamedTextColor.GRAY));
         }

         for (Map.Entry<Character, Material> entry : this.plugin.getConfigManager().getRitualBlockRecipeIngredients().entrySet()) {
            lore.add(line(entry.getKey() + " = " + RitualRecipes.prettify(entry.getValue().name()), NamedTextColor.GRAY));
         }

         altar.editMeta(meta -> meta.lore(lore));
         inventory.setItem(slot++, altar);
      }

      for (String weaponKey : this.plugin.getWeaponManager().getWeaponKeys()) {
         if (slot > 43) {
            break;
         }

         ItemStack display = this.plugin.getWeaponManager().getWeapon(weaponKey);
         List<RitualRecipes.Entry> entries = this.plugin.getRitualRecipes().entries(weaponKey);
         boolean forged = this.plugin.getConfigManager().isSingleCraft() && this.plugin.getRitualManager().isCrafted(weaponKey);
         display.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(line("Ritual ingredients:", NamedTextColor.YELLOW));

            for (RitualRecipes.Entry entry : entries) {
               lore.add(line(" - " + entry.describe(), entry.customId() != null ? NamedTextColor.AQUA : NamedTextColor.GRAY));
            }

            lore.add(Component.empty());
            lore.add(forged ? line("Already forged on this server", NamedTextColor.RED) : line("Not yet forged", NamedTextColor.GREEN));
            meta.lore(lore);
         });
         inventory.setItem(slot, display);
         if (++slot % 9 == 8) {
            slot += 2;
         }
      }

      inventory.setItem(49, RitualGUI.named(Material.AMETHYST_SHARD, Component.text("Weapon fragments", NamedTextColor.AQUA), List.of(
         line("Dropped by bosses, found in supply drops,", NamedTextColor.GRAY),
         line("and won from events.", NamedTextColor.GRAY),
         line(this.plugin.getRitualRecipes().fusionCost() + " of one tier fuse into the next at the altar.", NamedTextColor.GRAY),
         line("Luminosity Fragments come only from events.", NamedTextColor.GOLD)
      )));
      player.openInventory(inventory);
   }
}
