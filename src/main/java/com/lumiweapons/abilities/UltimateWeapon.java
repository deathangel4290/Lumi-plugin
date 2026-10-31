package com.lumiweapons.abilities;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.HudManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/**
 * A legendary with an ultimate: hitting players and using the weapon's abilities fill its meter,
 * and sneak + the ability key fires the ultimate once it is full. The meter drains out of combat
 * (like Luminosity's SuperNova) so a full ultimate can't be banked for the next fight.
 *
 * Numbers, per weapon, under weapons.<weapon>.abilities:
 *   ultimate-charge-max (100), ultimate-charge-per-hit, ultimate-charge-per-ability,
 *   charge-drain-delay (ticks out of combat before it drains), charge-drain-per-second
 *
 * Also the shared targeting the new weapons use: everyone an ability may touch in a circle or in
 * an arc in front of (or around) the wielder.
 */
public abstract class UltimateWeapon extends AbstractWeaponListener implements ChargedWeapon {
   /** Wielders mid-ultimate: the meter neither drains nor refills while it plays out. */
   protected final Set<UUID> ulting = new HashSet<>();
   /** Wielders already told their ultimate is ready (so the chime plays once per fill). */
   private final Set<UUID> announced = new HashSet<>();

   protected UltimateWeapon(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainTick, 20L, 20L);
   }

   /** "Divine Ascension", "Final Harvest", ... for the HUD and messages. */
   protected abstract String ultimateName();

   protected NamedTextColor ultimateColour() {
      return NamedTextColor.GOLD;
   }

   private String meterKey() {
      return this.weaponKey() + "-ultimate";
   }

   protected double ultimateMax() {
      return Math.max(1.0, this.ability("ultimate-charge-max", 100.0));
   }

   public double ultimateFraction(Player player) {
      return this.plugin.getChargeMeter().get(player, this.meterKey()) / this.ultimateMax();
   }

   /** /charge: set the ultimate meter (0..1). */
   @Override
   public void setChargeFraction(Player player, double fraction) {
      this.plugin.getChargeMeter().set(player, this.meterKey(), fraction * this.ultimateMax(), this.ultimateMax());
      this.plugin.getChargeMeter().markCombat(player);
      this.announced.remove(player.getUniqueId());
   }

   @Override
   public double getChargeFraction(Player player) {
      return this.ultimateFraction(player);
   }

   protected boolean ultimateReady(Player player) {
      return this.ultimateFraction(player) >= 0.999;
   }

   /** Adds to the meter; the first time it fills, a chime and a line on the action bar. */
   protected void chargeUltimate(Player player, double amount) {
      if (amount <= 0.0 || this.ulting.contains(player.getUniqueId())) {
         return;
      }

      boolean was = this.ultimateReady(player);
      this.plugin.getChargeMeter().add(player, this.meterKey(), amount, this.ultimateMax());
      if (!was && this.ultimateReady(player) && this.announced.add(player.getUniqueId())) {
         HudManager.message(player, Component.text(this.ultimateName() + " is ready", this.ultimateColour())
            .append(Component.text("  (sneak + ability)", NamedTextColor.GRAY)));
         this.plugin.getSoundManager().self(player, Sound.BLOCK_BEACON_POWER_SELECT, 0.7F, 1.8F);
      }
   }

   /** Using the weapon's own ability feeds the ultimate (ultimate-charge-per-ability). */
   protected void chargeFromAbility(Player player) {
      this.chargeUltimate(player, this.ability("ultimate-charge-per-ability", 20.0));
   }

   /** Sneak + ability: true (and the meter spent) when the ultimate may fire, else says how far along it is. */
   protected boolean spendUltimate(Player player) {
      if (this.ulting.contains(player.getUniqueId())) {
         return false;
      }

      if (!this.ultimateReady(player)) {
         HudManager.message(player, Component.text(this.ultimateName() + " ", this.ultimateColour())
            .append(Component.text((int)Math.floor(this.ultimateFraction(player) * 100.0) + "% charged", NamedTextColor.GRAY)));
         return false;
      }

      this.plugin.getChargeMeter().consume(player, this.meterKey());
      this.announced.remove(player.getUniqueId());
      return true;
   }

   protected Component ultimateHud(Player player) {
      return HudManager.meter(this.ultimateName(), this.ultimateFraction(player), this.ultimateColour());
   }

   private void drainTick() {
      long delay = this.abilityLong("charge-drain-delay", 300L) * 50L;
      double perSecond = this.ability("charge-drain-per-second", 1.0);
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (!this.ulting.contains(player.getUniqueId())) {
            this.plugin.getChargeMeter().drain(player, this.meterKey(), delay, perSecond, this.ultimateMax());
            if (!this.ultimateReady(player)) {
               this.announced.remove(player.getUniqueId());
            }
         }
      }
   }

   /** Melee hits on players with this weapon in hand fill the meter (ultimate-charge-per-hit). */
   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void chargeOnHit(EntityDamageByEntityEvent event) {
      if (AbilityDamage.active() || !(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof Player victim)) {
         return;
      }

      if (this.isHolding(attacker) && !attacker.equals(victim)) {
         this.chargeUltimate(attacker, this.ability("ultimate-charge-per-hit", 4.0));
         this.plugin.getChargeMeter().markCombat(victim);
      }
   }

   // ------------------------------------------------------------------ targeting

   /** Everyone this wielder's abilities may touch within `radius` (and half that up and down) of `centre`. */
   protected List<LivingEntity> around(Player source, Location centre, double radius) {
      List<LivingEntity> out = new ArrayList<>();
      for (Entity nearby : centre.getWorld().getNearbyEntities(centre, radius, Math.max(2.0, radius / 2.0), radius)) {
         if (nearby instanceof LivingEntity living && this.canAffect(source, nearby)
            && horizontal(nearby.getLocation().toVector().subtract(centre.toVector())).length() <= radius) {
            out.add(living);
         }
      }

      return out;
   }

   /**
    * Everyone in an arc: within `radius` of the wielder, and within `degrees` (total width) of the
    * direction they face turned by `turnDegrees` (0 = in front, 180 = behind).
    */
   protected List<LivingEntity> arc(Player source, double radius, double degrees, double turnDegrees) {
      Vector facing = horizontal(source.getLocation().getDirection());
      if (facing.lengthSquared() < 1.0E-6) {
         facing = new Vector(0, 0, 1);
      }

      facing.normalize().rotateAroundY(Math.toRadians(-turnDegrees));
      double limit = Math.cos(Math.toRadians(Math.min(360.0, degrees) / 2.0));
      List<LivingEntity> out = new ArrayList<>();
      for (LivingEntity living : this.around(source, source.getLocation(), radius)) {
         Vector to = horizontal(living.getLocation().toVector().subtract(source.getLocation().toVector()));
         if (degrees >= 360.0 || to.lengthSquared() < 0.25 || to.normalize().dot(facing) >= limit) {
            out.add(living);
         }
      }

      return out;
   }

   protected static Vector horizontal(Vector v) {
      return new Vector(v.getX(), 0.0, v.getZ());
   }
}
