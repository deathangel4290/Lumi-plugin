package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
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
   private final LumiWeapons plugin;
   private final ConfigManager configManager;
   private final Map<String, ItemStack> legendaryWeapons = new LinkedHashMap<>();
   private final NamespacedKey revisionKey;
   private final NamespacedKey splitKey;
   private final com.lumiweapons.items.WeaponLore lore;
   /** Weapon fingerprints, worked out once per reload (refresh() runs on every hotbar scroll). */
   private final Map<String, Integer> revisions = new java.util.HashMap<>();

   public WeaponManager(LumiWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.lore = new com.lumiweapons.items.WeaponLore(plugin);
      this.revisionKey = LumiWeapons.key("lore-revision");
      this.splitKey = LumiWeapons.key("eclipse-split-sword");
      this.loadWeapons();
   }

   public void reload() {
      this.lore.reload();
      this.revisions.clear();
      this.legendaryWeapons.clear();
      this.loadWeapons();
   }

   private void loadWeapons() {
      for (String key : this.configManager.getWeaponKeys()) {
         this.legendaryWeapons.put(key, this.createWeapon(key));
      }
   }

   public ItemStack createWeapon(String weaponKey) {
      Material material = this.configManager.getWeaponMaterial(weaponKey);
      ItemStack weapon = new ItemStack(material);
      ItemMeta meta = weapon.getItemMeta();
      if (meta != null) {
         String displayName = this.configManager.getWeaponDisplayName(weaponKey);
         Map<Enchantment, Integer> enchantments = this.configManager.getWeaponEnchantments(weaponKey);
         meta.setDisplayName(displayName);
         meta.lore(this.lore.build(weaponKey));
         meta.setUnbreakable(true);
         int customModelData = this.configManager.getWeaponCustomModelData(weaponKey);
         if (customModelData > 0) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float)customModelData));
            meta.setCustomModelDataComponent(component);
         }

         enchantments.forEach((enchant, level) -> meta.addEnchant(enchant, level, true));
         this.applyMelee(meta, weaponKey);
         applyCooldownGroup(meta, weaponKey);
         NamespacedKey key = LumiWeapons.key("legendary-weapon");
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

   /**
    * Gives the weapon its own cooldown group (luminara:<weapon>), so an ability cooldown greys out
    * this legendary only, not every netherite sword in the hotbar. The component's own cooldown is
    * one tick: it is only there for the group.
    */
   static void applyCooldownGroup(ItemMeta meta, String weaponKey) {
      org.bukkit.inventory.meta.components.UseCooldownComponent cooldown = meta.getUseCooldown();
      cooldown.setCooldownSeconds(0.05F);
      cooldown.setCooldownGroup(new NamespacedKey("luminara", weaponKey.toLowerCase(java.util.Locale.ROOT)));
      meta.setUseCooldown(cooldown);
   }

   private String lorePlain(String weaponKey) {
      StringBuilder text = new StringBuilder();
      for (net.kyori.adventure.text.Component line : this.lore.build(weaponKey)) {
         text.append(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
      }

      return text.toString();
   }

   /** A fingerprint of the configured name, lore, model and melee stats; items carrying an older one get refreshed. */
   private int revision(String weaponKey) {
      return this.revisions.computeIfAbsent(weaponKey, this::computeRevision);
   }

   private int computeRevision(String weaponKey) {
      double[] melee = this.configManager.getWeaponMelee(weaponKey);
      return (this.configManager.getWeaponDisplayName(weaponKey)
            + (melee == null ? "" : melee[0] + "/" + melee[1])
            + this.lorePlain(weaponKey)
            + this.configManager.getWeaponCustomModelData(weaponKey)
            + this.configManager.getWeaponPowerTier(weaponKey)
            + "|cooldown-group")
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
                  meta.lore(fresh.lore());
                  meta.setCustomModelDataComponent(fresh.getCustomModelDataComponent());
                  this.applyMelee(meta, weaponKey);
                  applyCooldownGroup(meta, weaponKey);
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
         NamespacedKey key = LumiWeapons.key("legendary-weapon");
         return meta.getPersistentDataContainer().has(key, PersistentDataType.STRING);
      } else {
         return false;
      }
   }

   public String getWeaponKey(ItemStack item) {
      if (!this.isLegendaryWeapon(item)) {
         return null;
      } else {
         NamespacedKey key = LumiWeapons.key("legendary-weapon");
         return (String)item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
      }
   }
}
