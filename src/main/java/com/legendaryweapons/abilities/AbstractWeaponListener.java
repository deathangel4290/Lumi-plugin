package com.legendaryweapons.abilities;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.WeaponManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public abstract class AbstractWeaponListener implements Listener, WeaponAbility {
   protected final LegendaryWeapons plugin;
   protected final WeaponManager weaponManager;

   protected AbstractWeaponListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
   }

   protected boolean isThisWeapon(ItemStack item) {
      return this.weaponManager.isLegendaryWeapon(item) && this.weaponKey().equals(this.weaponManager.getWeaponKey(item));
   }

   protected boolean isHolding(Player player) {
      return this.isThisWeapon(player.getInventory().getItemInMainHand());
   }

   protected ConfigurationSection abilities() {
      return this.plugin.getConfigManager().getWeaponAbilities(this.weaponKey());
   }

   protected double ability(String key, double fallback) {
      ConfigurationSection section = this.abilities();
      return section != null ? section.getDouble(key, fallback) : fallback;
   }

   protected int abilityInt(String key, int fallback) {
      ConfigurationSection section = this.abilities();
      return section != null ? section.getInt(key, fallback) : fallback;
   }

   protected long abilityLong(String key, long fallback) {
      ConfigurationSection section = this.abilities();
      return section != null ? section.getLong(key, fallback) : fallback;
   }

   protected Material weaponMaterial() {
      return this.plugin.getConfigManager().getWeaponMaterial(this.weaponKey());
   }

   protected boolean isFullSwing(Player player) {
      return player.getAttackCooldown() > 0.9F;
   }

   protected boolean tryCooldown(Player player, String cooldownKey, long ticks) {
      return this.plugin.getCooldownManager().tryUseWithItemFeedback(player, cooldownKey, ticks);
   }
}
