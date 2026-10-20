package com.lumiweapons.abilities;

import org.bukkit.entity.Player;

public interface ChargedWeapon extends WeaponAbility {
   void setChargeFraction(Player var1, double var2);

   double getChargeFraction(Player var1);
}
