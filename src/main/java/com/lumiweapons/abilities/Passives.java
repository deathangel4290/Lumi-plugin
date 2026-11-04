package com.lumiweapons.abilities;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.WeaponManager;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Off-hand passives. A legendary in the main hand gives everything: abilities and passives. In the
 * off-hand it gives only its passives, so a player can combine two: the main-hand weapon's and the
 * off-hand one's (e.g. Axe of Zeus lightning with the Scythe's heal on kill).
 *
 * How strong the off-hand passives are is set in passives.* (FULL, or REDUCED to reduced-multiplier).
 * Everything a passive does goes through {@link #strength}: 1 in the main hand, the off-hand strength
 * in the off-hand, 0 when the weapon isn't held. Multipliers, chances and durations scale with it.
 */
public final class Passives {
   /** Weapons with passives worth having in the off-hand. The rest only have abilities. */
   public static final Set<String> WITH_PASSIVES = Set.of(
      "scythe-of-diablo", "axe-of-zeus", "hermes-blade", "chiones-blade", "titans-axe", "blade-of-diminution",
      "blade-of-talaria", "oculus", "horizon",    // the shield and block, Watcher, True Aim
      "earthshaker-hammer", "abyssal-trident", "glacial-glaive"   // Heavy Impact, Deep Pressure, Frostbite
   );

   private Passives() {
   }

   private static ConfigurationSection config(LumiWeapons plugin) {
      return plugin.getConfig().getConfigurationSection("passives");
   }

   public static boolean offHandEnabled(LumiWeapons plugin) {
      ConfigurationSection config = config(plugin);
      return config == null || config.getBoolean("off-hand", true);
   }

   /** Off-hand strength: 1 for FULL, passives.reduced-multiplier for REDUCED. */
   public static double offHandStrength(LumiWeapons plugin) {
      ConfigurationSection config = config(plugin);
      if (config == null) {
         return 1.0;
      }

      String mode = config.getString("off-hand-strength", "FULL").trim().toUpperCase(Locale.ROOT);
      return mode.equals("REDUCED") ? Math.max(0.0, Math.min(1.0, config.getDouble("reduced-multiplier", 0.5))) : 1.0;
   }

   private static boolean is(WeaponManager weapons, ItemStack item, String weaponKey) {
      return weapons.isLegendaryWeapon(item) && weaponKey.equals(weapons.getWeaponKey(item));
   }

   public static boolean inMainHand(LumiWeapons plugin, Player player, String weaponKey) {
      return is(plugin.getWeaponManager(), player.getInventory().getItemInMainHand(), weaponKey);
   }

   public static boolean inOffHand(LumiWeapons plugin, Player player, String weaponKey) {
      return is(plugin.getWeaponManager(), player.getInventory().getItemInOffHand(), weaponKey);
   }

   /** 1 in the main hand, the off-hand strength in the off-hand (when allowed), 0 otherwise. */
   public static double strength(LumiWeapons plugin, Player player, String weaponKey) {
      if (inMainHand(plugin, player, weaponKey)) {
         return 1.0;
      }

      if (WITH_PASSIVES.contains(weaponKey) && offHandEnabled(plugin) && inOffHand(plugin, player, weaponKey)) {
         return offHandStrength(plugin);
      }

      return 0.0;
   }

   /** A damage multiplier at this strength: x1.5 at half strength is x1.25. */
   public static double multiplier(double multiplier, double strength) {
      return 1.0 + (multiplier - 1.0) * strength;
   }

   /** A potion amplifier at this strength, never below level I (amplifier 0). */
   public static int amplifier(int amplifier, double strength) {
      return Math.max(0, (int)Math.ceil((amplifier + 1) * strength) - 1);
   }

   public static int ticks(long ticks, double strength) {
      return (int)Math.max(1L, Math.round(ticks * strength));
   }
}
