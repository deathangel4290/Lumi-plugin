package com.legendaryweapons.abilities;

import org.bukkit.entity.Player;

public interface KillTrackedWeapon extends WeaponAbility {
   void setKillCount(Player var1, int var2);

   int getKillCount(Player var1);
}
