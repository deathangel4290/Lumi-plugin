package com.legendaryweapons.abilities;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

public interface WeaponAbility {
   String weaponKey();

   boolean trigger(Player player, boolean secondary);

   /** The action-bar HUD segment shown while this weapon is held, or null for none. */
   default Component hud(Player player) {
      return null;
   }
}
