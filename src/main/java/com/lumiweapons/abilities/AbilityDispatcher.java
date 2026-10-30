package com.lumiweapons.abilities;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.ConfigManager;
import com.lumiweapons.managers.TrustManager;
import com.lumiweapons.managers.WeaponManager;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class AbilityDispatcher {
   private final WeaponManager weaponManager;
   private final TrustManager trustManager;
   private final ConfigManager configManager;
   private final Map<String, WeaponAbility> abilities = new HashMap<>();

   public AbilityDispatcher(LumiWeapons plugin) {
      this.weaponManager = plugin.getWeaponManager();
      this.trustManager = plugin.getTrustManager();
      this.configManager = plugin.getConfigManager();
      plugin.getHudManager().register(player -> {
         WeaponAbility ability = this.get(this.heldWeaponKey(player));
         return ability == null ? null : ability.hud(player);
      });
      // a legendary in the off-hand: say whose passives are combined in
      plugin.getHudManager().register(player -> {
         ItemStack off = player.getInventory().getItemInOffHand();
         String key = this.weaponManager.isLegendaryWeapon(off) ? this.weaponManager.getWeaponKey(off) : null;
         if (key == null || !Passives.WITH_PASSIVES.contains(key) || !Passives.offHandEnabled(plugin)) {
            return null;
         }

         String name = off.getItemMeta().hasDisplayName()
            ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(off.getItemMeta().displayName())
            : key;
         double strength = Passives.offHandStrength(plugin);
         return net.kyori.adventure.text.Component.text("◐ ", net.kyori.adventure.text.format.TextColor.color(0xB98CFF))
            .append(net.kyori.adventure.text.Component.text(name + (strength < 1.0 ? " " + Math.round(strength * 100.0) + "%" : ""), net.kyori.adventure.text.format.NamedTextColor.GRAY));
      });
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
