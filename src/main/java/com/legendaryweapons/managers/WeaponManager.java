package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

public class WeaponManager {
   private final LegendaryWeapons plugin;
   private final ConfigManager configManager;
   private final Map<String, ItemStack> legendaryWeapons = new LinkedHashMap<>();
   private final NamespacedKey revisionKey;
   private final NamespacedKey splitKey;

   public WeaponManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.revisionKey = new NamespacedKey(plugin, "lore-revision");
      this.splitKey = new NamespacedKey(plugin, "eclipse-split-sword");
      this.loadWeapons();
   }

   public void reload() {
      this.legendaryWeapons.clear();
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
         this.applyMelee(meta, weaponKey);
         NamespacedKey key = new NamespacedKey(this.plugin, "legendary-weapon");
         meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, weaponKey);
         meta.getPersistentDataContainer().set(this.revisionKey, PersistentDataType.INTEGER, this.revision(weaponKey));
         weapon.setItemMeta(meta);
      }

      return weapon;
   }

   /**
    * Configured melee stats replace the material's own. The modifiers use vanilla's base ids so the
    * tooltip shows them as plain "8 Attack Damage / 1.6 Attack Speed" like a sword (a player's base
    * damage is 1 and base speed 4, hence the offsets).
    */
   private void applyMelee(ItemMeta meta, String weaponKey) {
      double[] melee = this.configManager.getWeaponMelee(weaponKey);
      if (melee != null) {
         meta.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
         meta.removeAttributeModifier(Attribute.ATTACK_SPEED);
         meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(NamespacedKey.minecraft("base_attack_damage"), melee[0] - 1.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
         meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(NamespacedKey.minecraft("base_attack_speed"), melee[1] - 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
      }
   }

   /** A fingerprint of the configured name, lore, model and melee stats; items carrying an older one get refreshed. */
   private int revision(String weaponKey) {
      double[] melee = this.configManager.getWeaponMelee(weaponKey);
      return (this.configManager.getWeaponDisplayName(weaponKey)
            + (melee == null ? "" : melee[0] + "/" + melee[1])
            + this.configManager.getWeaponLore(weaponKey)
            + this.configManager.getWeaponCustomModelData(weaponKey)
            + this.configManager.getWeaponPowerTier(weaponKey))
         .hashCode();
   }

   /**
    * Brings an existing legendary's name, lore, model and melee stats up to date with the config, so balance
    * changes show up on weapons players already own. Enchantments, durability and anything else
    * a player added are left alone. Returns true if the item changed.
    */
   public boolean refresh(ItemStack item) {
      if (!this.isLegendaryWeapon(item)) {
         return false;
      } else {
         ItemMeta meta = item.getItemMeta();
         if (meta.getPersistentDataContainer().has(this.splitKey, PersistentDataType.BYTE)) {
            return false;
         } else {
            String weaponKey = this.getWeaponKey(item);
            if (!this.legendaryWeapons.containsKey(weaponKey)) {
               return false;
            } else {
               int current = this.revision(weaponKey);
               Integer stored = meta.getPersistentDataContainer().get(this.revisionKey, PersistentDataType.INTEGER);
               if (stored != null && stored == current) {
                  return false;
               } else {
                  ItemMeta fresh = this.legendaryWeapons.get(weaponKey).getItemMeta();
                  meta.setDisplayName(fresh.getDisplayName());
                  meta.setLore(fresh.getLore());
                  meta.setCustomModelDataComponent(fresh.getCustomModelDataComponent());
                  this.applyMelee(meta, weaponKey);
                  meta.getPersistentDataContainer().set(this.revisionKey, PersistentDataType.INTEGER, current);
                  item.setItemMeta(meta);
                  return true;
               }
            }
         }
      }
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
