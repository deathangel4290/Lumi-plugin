package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

public class WeaponManager {
   private final LegendaryWeapons plugin;
   private final ConfigManager configManager;
   private final Map<String, ItemStack> legendaryWeapons = new HashMap<>();

   public WeaponManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.loadWeapons();
   }

   private void loadWeapons() {
      for (String key : this.configManager.getWeaponKeys()) {
         this.legendaryWeapons.put(key, this.createWeapon(key));
      }
   }

   private static String tierLine(int tier) {
      String colour = switch (tier) {
         case 1 -> "§7";
         case 2 -> "§a";
         case 3 -> "§b";
         default -> "§6";
      };
      return "§8Power  " + colour + "◆".repeat(tier) + "§8" + "◇".repeat(Math.max(0, 4 - tier));
   }

   public ItemStack createWeapon(String weaponKey) {
      Material material = this.configManager.getWeaponMaterial(weaponKey);
      ItemStack weapon = new ItemStack(material);
      ItemMeta meta = weapon.getItemMeta();
      if (meta != null) {
         String displayName = this.configManager.getWeaponDisplayName(weaponKey);
         List<String> lore = new ArrayList<>(this.configManager.getWeaponLore(weaponKey));
         int tier = this.configManager.getWeaponPowerTier(weaponKey);
         if (tier > 0) {
            lore.add("");
            lore.add(tierLine(tier));
         }

         Map<Enchantment, Integer> enchantments = this.configManager.getWeaponEnchantments(weaponKey);
         meta.setDisplayName(displayName);
         meta.setLore(lore);
         meta.setUnbreakable(true);
         int customModelData = this.configManager.getWeaponCustomModelData(weaponKey);
         if (customModelData > 0) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float)customModelData));
            meta.setCustomModelDataComponent(component);
         }

         enchantments.forEach((enchant, level) -> meta.addEnchant(enchant, level, true));
         NamespacedKey key = new NamespacedKey(this.plugin, "legendary-weapon");
         meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, weaponKey);
         weapon.setItemMeta(meta);
      }

      return weapon;
   }

   public ItemStack getWeapon(String weaponKey) {
      return this.legendaryWeapons.getOrDefault(weaponKey, new ItemStack(Material.AIR)).clone();
   }

   public Set<String> getWeaponKeys() {
      return this.legendaryWeapons.keySet();
   }

   public boolean isLegendaryWeapon(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         NamespacedKey key = new NamespacedKey(this.plugin, "legendary-weapon");
         return meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
      } else {
         return false;
      }
   }

   public String getWeaponKey(ItemStack item) {
      if (!this.isLegendaryWeapon(item)) {
         return null;
      } else {
         NamespacedKey key = new NamespacedKey(this.plugin, "legendary-weapon");
         return (String)item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
      }
   }
}
