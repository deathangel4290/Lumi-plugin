package com.legendaryweapons.abilities;

import org.bukkit.entity.Player;

public interface WeaponAbility {
   String weaponKey();

   boolean trigger(Player var1, boolean var2);
}
