package com.lumiweapons.abilities;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.HudManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * A legendary with abilities and an ultimate. Controls, the same on every one of them:
 *
 *   ability key ............. Ability 1
 *   sneak + ability key ..... Ability 2 (Ability 1 again on weapons with only one)
 *   double-tap ability key .. the Ultimate, once its meter is full (the first tap is still Ability 1)
 *
 * Hitting players and using abilities fill the meter; it drains out of combat (like Luminosity's
 * SuperNova) so a full ultimate can't be banked for the next fight. Per weapon, under
 * weapons.<weapon>.abilities: ultimate-charge-max (100), ultimate-charge-per-hit,
 * ultimate-charge-per-ability, charge-drain-delay (ticks), charge-drain-per-second. The
 * double-tap window is ultimates.double-tap-millis.
 *
 * Also the toolkit the weapons share: targeting (around, arc, allies), a stepped projectile
 * (fly), knockback, aiming at the ground, fall-damage protection after a leap, and per-target
 * immunity so pulls and roots can't be chained forever.
 */
public abstract class UltimateWeapon extends AbstractWeaponListener implements ChargedWeapon {
   /** Wielders mid-ultimate: the meter neither drains nor refills while it plays out. */
   protected final Set<UUID> ulting = new HashSet<>();
   /** Wielders already told their ultimate is ready (so the chime plays once per fill). */
   private final Set<UUID> announced = new HashSet<>();
   private final Map<UUID, Long> lastTap = new HashMap<>();
   /** player -> until when a fall can't hurt them (after a leap or a launch). */
   private final Map<UUID, Long> safeFall = new HashMap<>();
   /** "effect:target" -> until when that target can't be hit by that effect again. */
   private final Map<String, Long> immunity = new HashMap<>();

   protected UltimateWeapon(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::drainTick, 20L, 20L);
   }

   /** "Divine Ascension", "Final Harvest", ... for the HUD and messages. */
   protected abstract String ultimateName();

   /** Ability 1: the ability key. */
   protected abstract void firstAbility(Player player);

   /** Ability 2: sneak + the ability key. Weapons with one ability use it for both. */
   protected void secondAbility(Player player) {
      this.firstAbility(player);
   }

   /** The ultimate. The meter is already spent when this runs. */
   protected abstract void ultimate(Player player);

   /** Null when the ultimate can fire now; otherwise why not (the meter is kept). */
   protected String ultimateBlocked(Player player) {
      return null;
   }

   /** Whether this player wields the weapon for its abilities (the main hand; a shield may say otherwise). */
   protected boolean wields(Player player) {
      return this.isHolding(player);
   }

   protected NamedTextColor ultimateColour() {
      return NamedTextColor.GOLD;
   }

   @Override
   public final boolean trigger(Player player, boolean secondary) {
      if (!this.wields(player)) {
         return true;
      }

      UUID id = player.getUniqueId();
      if (secondary) {
         this.secondAbility(player);
         return true;
      }

      long now = System.currentTimeMillis();
      Long last = this.lastTap.put(id, now);
      long window = Math.max(100L, this.plugin.getConfig().getLong("ultimates.double-tap-millis", 400L));
      if (last != null && now - last <= window && this.ultimateReady(player) && !this.ulting.contains(id)) {
         this.lastTap.remove(id);
         String blocked = this.ultimateBlocked(player);
         if (blocked != null) {
            HudManager.message(player, Component.text(blocked, NamedTextColor.GRAY));
            return true;
         }

         this.plugin.getChargeMeter().consume(player, this.meterKey());
         this.announced.remove(id);
         this.ultimate(player);
         return true;
      }

      this.firstAbility(player);
      return true;
   }

   // ------------------------------------------------------------------ the meter

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
            .append(Component.text("  (double-tap the ability key)", NamedTextColor.GRAY)));
         this.plugin.getSoundManager().self(player, Sound.BLOCK_BEACON_POWER_SELECT, 0.7F, 1.8F);
      }
   }

   /** Using the weapon's own ability feeds the ultimate (ultimate-charge-per-ability). */
   protected void chargeFromAbility(Player player) {
      this.chargeUltimate(player, this.ability("ultimate-charge-per-ability", 20.0));
   }

   protected Component ultimateHud(Player player) {
      return HudManager.meter(this.ultimateName(), this.ultimateFraction(player), this.ultimateColour());
   }

   private void drainTick() {
      long delay = this.abilityLong("charge-drain-delay", 300L) * 50L;
      double perSecond = this.ability("charge-drain-per-second", 1.0);
      long now = System.currentTimeMillis();
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (!this.ulting.contains(player.getUniqueId())) {
            this.plugin.getChargeMeter().drain(player, this.meterKey(), delay, perSecond, this.ultimateMax());
            if (!this.ultimateReady(player)) {
               this.announced.remove(player.getUniqueId());
            }
         }
      }

      this.immunity.values().removeIf(until -> until < now);
      this.safeFall.values().removeIf(until -> until < now);
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

   /** Whether `other` is on this player's side: themselves, a teammate, or someone they /trust. */
   protected boolean isAlly(Player player, Player other) {
      return player.equals(other) || this.plugin.getTrustManager().isTrusted(player, other);
   }

   /** This player's side within `radius` of `centre` (themselves included when `self`). */
   protected List<Player> allies(Player player, Location centre, double radius, boolean self) {
      List<Player> out = new ArrayList<>();
      for (Player other : centre.getWorld().getPlayers()) {
         if ((self || !other.equals(player)) && !other.isDead() && other.getGameMode() != org.bukkit.GameMode.SPECTATOR
            && this.isAlly(player, other) && other.getLocation().distanceSquared(centre) <= radius * radius) {
            out.add(other);
         }
      }

      return out;
   }

   protected static Vector horizontal(Vector v) {
      return new Vector(v.getX(), 0.0, v.getZ());
   }

   /** The flat direction a player faces. */
   protected static Vector facing(Player player) {
      Vector flat = horizontal(player.getLocation().getDirection());
      return flat.lengthSquared() < 1.0E-6 ? new Vector(0, 0, 1) : flat.normalize();
   }

   /** Push away from `from` (flat), with a little lift. */
   protected static void push(LivingEntity target, Location from, double strength, double lift) {
      Vector away = horizontal(target.getLocation().toVector().subtract(from.toVector()));
      Vector velocity = away.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 0) : away.normalize().multiply(strength);
      target.setVelocity(velocity.setY(lift));
   }

   /** The ground at the block a player looks at (within `range`), else straight ahead. */
   protected Location aimedGround(Player player, double range) {
      RayTraceResult look = player.rayTraceBlocks(range, FluidCollisionMode.NEVER);
      Location aim = look != null ? look.getHitPosition().toLocation(player.getWorld())
         : player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(Math.min(range, 20.0)));
      RayTraceResult ground = player.getWorld().rayTraceBlocks(aim.clone().add(0.0, 1.0, 0.0), new Vector(0, -1, 0), 40.0, FluidCollisionMode.NEVER, true);
      return ground != null ? ground.getHitPosition().toLocation(player.getWorld()) : aim;
   }

   /** The next fall within `ticks` doesn't hurt (a leap, a launch, a flight). */
   protected void protectFall(Player player, long ticks) {
      this.safeFall.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void cancelSafeFall(EntityDamageEvent event) {
      if (event.getCause() == EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player) {
         Long until = this.safeFall.get(player.getUniqueId());
         if (until != null && until >= System.currentTimeMillis()) {
            this.safeFall.remove(player.getUniqueId());
            event.setCancelled(true);
         }
      }
   }

   /** True (and the immunity started) when `target` may be hit by `effect` now; false while immune. */
   protected boolean takeImmunity(String effect, Entity target, long ticks) {
      String key = effect + ":" + target.getUniqueId();
      long now = System.currentTimeMillis();
      Long until = this.immunity.get(key);
      if (until != null && until > now) {
         return false;
      }

      this.immunity.put(key, now + ticks * 50L);
      return true;
   }

   // ------------------------------------------------------------------ projectiles

   /**
    * A projectile drawn with particles. It moves `speed` blocks a tick in small steps (so it never
    * skips through a thin wall or a player), stops at blocks unless block() says to keep going,
    * and calls hit() once per target it touches until hit() returns true.
    */
   protected abstract static class Flight {
      protected void draw(Location at, int tick) {
      }

      /** Touched a target; true stops the flight here. */
      protected boolean hit(LivingEntity target, Location at) {
         return true;
      }

      /** Hit a block face; true keeps flying (change `direction` to bounce). */
      protected boolean block(Location at, Vector direction, BlockFace face) {
         return false;
      }

      /** The flight is over (target, block, range or time). */
      protected void end(Location at) {
      }
   }

   protected void fly(Player source, Location from, Vector direction, double speed, double range, double radius, Flight flight) {
      final Vector heading = direction.clone().normalize();
      final Location at = from.clone();
      final Set<UUID> touched = new HashSet<>();
      new BukkitRunnable() {
         int tick = 0;
         double travelled = 0.0;

         @Override
         public void run() {
            if (at.getWorld() == null || !source.isOnline() || this.tick++ > 200) {
               flight.end(at);
               this.cancel();
               return;
            }

            int steps = Math.max(1, (int)Math.ceil(speed / 0.4));
            double step = speed / steps;
            for (int i = 0; i < steps; i++) {
               RayTraceResult wall = at.getWorld().rayTraceBlocks(at, heading, step, FluidCollisionMode.NEVER, true);
               if (wall != null && wall.getHitBlock() != null) {
                  Location point = wall.getHitPosition().toLocation(at.getWorld());
                  if (!flight.block(point, heading, wall.getHitBlockFace())) {
                     flight.end(point);
                     this.cancel();
                     return;
                  }

                  heading.normalize();
                  at.setX(point.getX());
                  at.setY(point.getY());
                  at.setZ(point.getZ());
                  at.add(heading.clone().multiply(0.05));
                  continue;
               }

               at.add(heading.clone().multiply(step));
               this.travelled += step;
               for (Entity nearby : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
                  if (nearby instanceof LivingEntity living && !nearby.equals(source) && touched.add(nearby.getUniqueId())
                     && UltimateWeapon.this.canAffect(source, nearby)) {
                     if (flight.hit(living, at.clone())) {
                        flight.end(at);
                        this.cancel();
                        return;
                     }
                  }
               }

               if (this.travelled >= range) {
                  flight.end(at);
                  this.cancel();
                  return;
               }
            }

            flight.draw(at.clone(), this.tick);
         }
      }.runTaskTimer(this.plugin, 0L, 1L);
   }
}
