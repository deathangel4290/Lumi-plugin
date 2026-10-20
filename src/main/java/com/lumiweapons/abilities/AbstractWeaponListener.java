package com.lumiweapons.abilities;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.WeaponManager;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

public abstract class AbstractWeaponListener implements Listener, WeaponAbility {
   protected final LumiWeapons plugin;
   protected final WeaponManager weaponManager;

   protected AbstractWeaponListener(LumiWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
   }

   protected boolean isThisWeapon(ItemStack item) {
      return this.weaponManager.isLegendaryWeapon(item) && this.weaponKey().equals(this.weaponManager.getWeaponKey(item));
   }

   protected boolean isHolding(Player player) {
      return this.isThisWeapon(player.getInventory().getItemInMainHand());
   }

   /** How strongly this weapon's passives apply: 1 in the main hand, less (or 0) in the off-hand. */
   protected double passive(Player player) {
      return Passives.strength(this.plugin, player, this.weaponKey());
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

   protected boolean canAffect(Player source, Entity target) {
      return this.plugin.getProtectionManager().canAffect(source, target);
   }

   protected double cooldownLeft(Player player, String cooldownKey) {
      return this.plugin.getCooldownManager().getRemainingSeconds(player, cooldownKey);
   }

   protected boolean tryCooldown(Player player, String cooldownKey, long ticks) {
      return this.plugin.getCooldownManager().tryUseWithItemFeedback(player, cooldownKey, ticks);
   }
}
