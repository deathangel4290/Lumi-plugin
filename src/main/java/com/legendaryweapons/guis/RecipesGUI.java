package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.ConfigManager;
import com.legendaryweapons.managers.WeaponManager;
import com.legendaryweapons.rituals.RitualBlock;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public class RecipesGUI {
   private final LegendaryWeapons plugin;
   private final ConfigManager configManager;
   private final WeaponManager weaponManager;
   private Inventory recipesInventory;

   public RecipesGUI(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.weaponManager = plugin.getWeaponManager();
      this.createRecipesInventory();
   }

   private void createRecipesInventory() {
      int size = 54;
      String title = "Legendary Weapon Recipes";
      this.recipesInventory = Bukkit.createInventory(null, size, title);
      ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
      ItemMeta borderMeta = border.getItemMeta();
      if (borderMeta != null) {
         borderMeta.setDisplayName(" ");
         border.setItemMeta(borderMeta);
      }

      for (int i = 0; i < size; i++) {
         if (i < 9 || i > 44 || i % 9 == 0 || i % 9 == 8) {
            this.recipesInventory.setItem(i, border);
         }
      }

      int slot = 10;
      if (this.configManager.isRitualBlockRecipeEnabled()) {
         ItemStack ritualBlockItem = RitualBlock.getRitualBlockItem();
         ItemMeta ritualMeta = ritualBlockItem.getItemMeta();
         if (ritualMeta != null) {
            List<String> lore = ritualMeta.getLore();
            lore.add(" ");
            lore.add("§eCrafting Recipe:");
            List<String> shape = this.configManager.getRitualBlockRecipeShape();
            if (shape.size() == 3) {
               lore.add("§7  " + shape.get(0));
               lore.add("§7  " + shape.get(1));
               lore.add("§7  " + shape.get(2));
            }

            lore.add(" ");
            lore.add("§eIngredients:");
            Map<Character, Material> ingredients = this.configManager.getRitualBlockRecipeIngredients();

            for (Entry<Character, Material> entry : ingredients.entrySet()) {
               lore.add("§7" + entry.getKey() + " = " + this.formatMaterialName(entry.getValue()));
            }

            ritualMeta.setLore(lore);
            ritualBlockItem.setItemMeta(ritualMeta);
         }

         this.recipesInventory.setItem(slot, ritualBlockItem);
         slot++;
      }

      for (String weaponKey : this.weaponManager.getWeaponKeys()) {
         if (slot > 43) {
            break;
         }

         ItemStack weaponDisplay = this.weaponManager.getWeapon(weaponKey);
         ItemMeta weaponMeta = weaponDisplay.getItemMeta();
         if (weaponMeta != null) {
            List<String> lore = weaponMeta.getLore();
            lore.add(" ");
            lore.add("§eRecipe Ingredients:");

            for (ItemStack ingredient : this.configManager.getWeaponRecipe(weaponKey)) {
               lore.add("§7- " + ingredient.getAmount() + "x " + this.formatMaterialName(ingredient.getType()));
            }

            weaponMeta.setLore(lore);
            weaponDisplay.setItemMeta(weaponMeta);
         }

         this.recipesInventory.setItem(slot, weaponDisplay);
         if (++slot % 9 == 8) {
            slot += 2;
         }
      }
   }

   private String formatMaterialName(Material material) {
      return material.toString().toLowerCase().replace('_', ' ');
   }

   public void open(@NotNull Player player) {
      player.openInventory(this.recipesInventory);
   }

   public Inventory getInventory() {
      return this.recipesInventory;
   }
}
