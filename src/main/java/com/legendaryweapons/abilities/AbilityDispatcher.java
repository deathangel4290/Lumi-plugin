package com.legendaryweapons.abilities;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.ConfigManager;
import com.legendaryweapons.managers.TrustManager;
import com.legendaryweapons.managers.WeaponManager;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class AbilityDispatcher {
   private final WeaponManager weaponManager;
   private final TrustManager trustManager;
   private final ConfigManager configManager;
   private final Map<String, WeaponAbility> abilities = new HashMap<>();

   public AbilityDispatcher(LegendaryWeapons plugin) {
      this.weaponManager = plugin.getWeaponManager();
      this.trustManager = plugin.getTrustManager();
      this.configManager = plugin.getConfigManager();
   }

   public void register(WeaponAbility ability) {
      WeaponAbility previous = this.abilities.put(ability.weaponKey(), ability);
      if (previous != null) {
         throw new IllegalStateException(
            "Two abilities registered for weapon key '"
               + ability.weaponKey()
               + "': "
               + previous.getClass().getSimpleName()
               + " and "
               + ability.getClass().getSimpleName()
         );
      }
   }

   public boolean isRegistered(String weaponKey) {
      return this.abilities.containsKey(weaponKey);
   }

   public WeaponAbility get(String weaponKey) {
      return weaponKey == null ? null : this.abilities.get(weaponKey);
   }

   public Collection<WeaponAbility> all() {
      return Collections.unmodifiableCollection(this.abilities.values());
   }

   public Set<String> registeredKeys() {
      return Collections.unmodifiableSet(this.abilities.keySet());
   }

   public boolean isProtectedTrustedTarget(Player player) {
      Entity target = player.getTargetEntity(5);
      return target instanceof Player && this.trustManager.isTrusted(player, (Player)target) && this.configManager.isPreventAbilityDamage();
   }

   public String heldWeaponKey(Player player) {
      ItemStack weapon = player.getInventory().getItemInMainHand();
      return this.weaponManager.isLegendaryWeapon(weapon) ? this.weaponManager.getWeaponKey(weapon) : null;
   }

   public boolean trigger(Player player) {
      return this.trigger(player, AbilityMode.PRIMARY);
   }

   public boolean trigger(Player player, AbilityMode mode) {
      String weaponKey = this.heldWeaponKey(player);
      if (weaponKey != null && !this.isProtectedTrustedTarget(player)) {
         WeaponAbility ability = this.abilities.get(weaponKey);
         if (ability == null) {
            return false;
         } else {
            boolean secondary = mode == AbilityMode.SECONDARY || player.isSneaking();
            return ability.trigger(player, secondary);
         }
      } else {
         return false;
      }
   }

   @Deprecated
   public boolean trigger(Player player, AbilityDispatcher.Mode mode) {
      return this.trigger(player, mode == AbilityDispatcher.Mode.SECONDARY ? AbilityMode.SECONDARY : AbilityMode.PRIMARY);
   }

   @Deprecated
   public static enum Mode {
      PRIMARY,
      SECONDARY;
   }
}
