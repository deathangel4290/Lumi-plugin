package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.abilities.ChargedWeapon;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Luminosity -- the mace.
 *
 * Dash: 2 charges, each comes back 8s after it is spent; spending the last one locks dashing for
 * 3s. SuperNova: charged by hitting players (not mobs), drains out of combat, and telegraphs
 * itself with a wind-up and a warning ring so nobody is deleted without seeing it coming. Its
 * damage goes through armour and is capped at a fraction of the target's max health.
 *
 * The slam runs as a repeating task. Every exit path now goes through finish() in a finally
 * block: previously an exception in detonate() (Particle.FLASH without its Color data on 1.21.9+)
 * skipped the cleanup, so the task ran forever -- particles never stopped and the player was
 * stuck "slamming" and could never ult again.
 */
public class LuminosityListener extends AbstractWeaponListener implements ChargedWeapon {
   private static final String DASH_POOL_KEY = "luminosity-dash";
   private static final String DASH_LOCK_KEY = "luminosity-dash-lock";
   private static final String ULTIMATE_KEY = "luminosity-supernova";
   private final Map<UUID, Long> lastChargeFromVictim = new HashMap<>();
   private final Map<UUID, BukkitRunnable> slamming = new HashMap<>();
   private final Set<UUID> fallImmune = new HashSet<>();

   public LuminosityListener(LegendaryWeapons plugin) {
      super(plugin);
      (new BukkitRunnable() {
         public void run() {
            LuminosityListener.this.drainTick();
         }
      }).runTaskTimer(plugin, 20L, 20L);
   }

   @Override
   public String weaponKey() {
      return "luminosity";
   }

   private double max() {
      return Math.max(1.0, this.ability("ultimate-charge-max", 100.0));
   }

   @Override
   public void setChargeFraction(Player player, double fraction) {
      this.plugin.getChargeMeter().set(player, ULTIMATE_KEY, fraction * this.max(), this.max());
      this.plugin.getChargeMeter().markCombat(player);
   }

   @Override
   public double getChargeFraction(Player player) {
      return this.plugin.getChargeMeter().get(player, ULTIMATE_KEY) / this.max();
   }

   private void drainTick() {
      long delay = this.abilityLong("charge-drain-delay", 200L) * 50L;
      double perSecond = this.ability("charge-drain-per-second", 2.0);

      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (!this.slamming.containsKey(player.getUniqueId())) {
            this.plugin.getChargeMeter().drain(player, ULTIMATE_KEY, delay, perSecond, this.max());
         }
      }
   }

   @Override
   public Component hud(Player player) {
      int charges = this.abilityInt("dash-charges", 2);
      int ready = this.plugin.getChargePool().available(player, DASH_POOL_KEY, charges);
      double lock = Math.max(this.cooldownLeft(player, DASH_LOCK_KEY), this.plugin.getDashTracker().lockedSeconds(player));
      Component dash = HudManager.pips("Dash", lock > 0.0 ? 0 : ready, charges, NamedTextColor.YELLOW);
      if (lock > 0.0) {
         dash = dash.append(Component.text(" locked " + HudManager.seconds(lock), NamedTextColor.RED));
      } else if (ready < charges) {
         dash = dash.append(Component.text(" " + HudManager.seconds(this.plugin.getChargePool().secondsUntilNext(player, DASH_POOL_KEY, charges)), NamedTextColor.GRAY));
      }

      return dash.append(Component.text("  ")).append(HudManager.meter("SuperNova", this.getChargeFraction(player), NamedTextColor.GOLD));
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.stopSlam(event.getEntity().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.stopSlam(event.getPlayer().getUniqueId());
      this.lastChargeFromVictim.remove(event.getPlayer().getUniqueId());
   }

   private void stopSlam(UUID id) {
      BukkitRunnable task = this.slamming.remove(id);
      if (task != null) {
         task.cancel();
      }

      this.fallImmune.remove(id);
   }

   /** The slam itself should not cost fall damage -- the landing IS the attack. */
   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onFall(EntityDamageEvent event) {
      if (event.getCause() == DamageCause.FALL && event.getEntity() instanceof Player player && this.fallImmune.remove(player.getUniqueId())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getEntity() instanceof Player hurt && event.getDamager() instanceof Player hitter) {
         this.plugin.getChargeMeter().markCombat(hurt);
         this.plugin.getChargeMeter().markCombat(hitter);
      }

      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker) && !(event.getFinalDamage() <= 0.0)) {
         boolean isPlayer = event.getEntity() instanceof Player;
         boolean mobsCount = this.abilities() != null && this.abilities().getBoolean("charge-from-mobs", false);
         if (event.getEntity() instanceof LivingEntity && (isPlayer || mobsCount)) {
            if (!(event.getEntity() instanceof Player victim) || !this.plugin.getTrustManager().isTrusted(attacker, victim)) {
               long now = System.currentTimeMillis();
               long victimCooldown = this.abilityLong("ultimate-victim-cooldown", 10L) * 50L;
               UUID victimId = event.getEntity().getUniqueId();
               Long last = this.lastChargeFromVictim.get(victimId);
               if (last == null || now - last >= victimCooldown) {
                  this.lastChargeFromVictim.put(victimId, now);
                  double before = this.plugin.getChargeMeter().get(attacker, ULTIMATE_KEY);
                  double charge = this.plugin.getChargeMeter().add(attacker, ULTIMATE_KEY, this.ability("ultimate-charge-per-hit", 8.0), this.max());
                  if (charge >= this.max() && before < this.max()) {
                     this.plugin.getSoundManager().self(attacker, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 2.0F);
                     HudManager.message(attacker, Component.text("SuperNova ready", NamedTextColor.GOLD));
                  }
               }
            }
         }
      }
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else {
         if (secondary) {
            this.dash(player);
         } else {
            this.superNova(player);
         }

         return true;
      }
   }

   private void dash(Player player) {
      int charges = this.abilityInt("dash-charges", 2);
      if (this.plugin.getDashTracker().isLocked(player)) {
         HudManager.message(player, Component.text("Your dash is chained down!", NamedTextColor.RED));
      } else if (this.plugin.getCooldownManager().isOnCooldown(player, DASH_LOCK_KEY)) {
         HudManager.message(
            player,
            Component.text("Dash locked ", NamedTextColor.GRAY)
               .append(Component.text(HudManager.seconds(this.cooldownLeft(player, DASH_LOCK_KEY)), NamedTextColor.RED))
         );
      } else if (!this.plugin.getChargePool().tryConsume(player, DASH_POOL_KEY, charges, this.abilityLong("dash-recharge", 160L), 0L)) {
         HudManager.message(
            player,
            Component.text("Dash recharging ", NamedTextColor.GRAY)
               .append(Component.text(HudManager.seconds(this.plugin.getChargePool().secondsUntilNext(player, DASH_POOL_KEY, charges)), NamedTextColor.YELLOW))
         );
      } else {
         if (this.plugin.getChargePool().available(player, DASH_POOL_KEY, charges) == 0) {
            this.plugin.getCooldownManager().set(player, DASH_LOCK_KEY, this.abilityLong("dash-lockout", 60L));
         }

         Vector direction = player.getLocation().getDirection().setY(0.0);
         if (direction.lengthSquared() < 1.0E-4) {
            direction = new Vector(0, 0, 1);
         }

         direction.normalize().multiply(this.ability("dash-power", 1.5)).setY(this.ability("dash-lift", 0.35));
         player.setVelocity(direction);
         this.plugin.getDashTracker().markDashing(player, 10L);
         Location loc = player.getLocation();
         ParticleManager.spawn(Particle.END_ROD, loc.clone().add(0.0, 0.2, 0.0), 8, 0.3, 0.1, 0.3, 0.02);
         this.plugin.getSoundManager().world(loc, Sound.ENTITY_BREEZE_SHOOT, 0.6F, 1.3F);
      }
   }

   private void superNova(final Player player) {
      double max = this.max();
      double minimum = this.ability("supernova-min-charge", 25.0);
      double held = this.plugin.getChargeMeter().get(player, ULTIMATE_KEY);
      final UUID id = player.getUniqueId();
      if (this.slamming.containsKey(id)) {
         return;
      }

      if (held < minimum) {
         HudManager.message(
            player,
            Component.text("SuperNova ", NamedTextColor.GRAY)
               .append(Component.text((int)Math.round(held / max * 100.0) + "%", NamedTextColor.YELLOW))
               .append(Component.text(" - needs " + (int)Math.round(minimum / max * 100.0) + "%", NamedTextColor.GRAY))
         );
         return;
      }

      final double power = Math.max(0.0, Math.min(1.0, this.plugin.getChargeMeter().consume(player, ULTIMATE_KEY) / max));
      final double radius = this.radius(power);
      final int windup = Math.max(0, this.abilityInt("supernova-windup", 10));
      final int maxTicks = Math.max(10, this.abilityInt("supernova-max-fall-ticks", 60));
      final double slamVelocity = this.ability("supernova-slam-velocity", -2.2);
      if (player.isOnGround()) {
         player.setVelocity(new Vector(0.0, this.ability("supernova-launch", 0.9), 0.0));
      }

      // Telegraph: a rising charge-up everyone nearby hears, and a warning to anyone in the blast.
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0F, 1.4F);
      this.plugin.getSoundManager().self(player, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 1.6F);

      for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
         if (nearby instanceof Player target && this.canAffect(player, target)) {
            HudManager.message(target, Component.text("⚠ SuperNova incoming - get clear!", NamedTextColor.GOLD));
            this.plugin.getSoundManager().self(target, Sound.BLOCK_NOTE_BLOCK_BELL, 0.9F, 0.6F);
         }
      }

      this.fallImmune.add(id);
      BukkitRunnable task = new BukkitRunnable() {
         int ticks = 0;
         boolean driving = false;
         boolean finished = false;

         private void finish(boolean detonate) {
            if (!this.finished) {
               this.finished = true;
               this.cancel();
               LuminosityListener.this.slamming.remove(id);

               try {
                  if (detonate && player.isOnline() && !player.isDead()) {
                     LuminosityListener.this.detonate(player, power, radius);
                  }
               } finally {
                  // Leave the fall-damage shield up for one more second in case the landing packet lags.
                  LuminosityListener.this.plugin.getServer().getScheduler().runTaskLater(LuminosityListener.this.plugin, () -> LuminosityListener.this.fallImmune.remove(id), 20L);
               }
            }
         }

         public void run() {
            try {
               if (!player.isOnline() || player.isDead() || !LuminosityListener.this.isHolding(player)) {
                  this.finish(false);
                  return;
               }

               this.ticks++;
               player.setFallDistance(0.0F);
               if (this.ticks <= windup) {
                  if (this.ticks % 3 == 1) {
                     LuminosityListener.this.warningRing(player, radius);
                  }

                  Vector v = player.getVelocity();
                  if (v.getY() < 0.0) {
                     player.setVelocity(new Vector(v.getX() * 0.5, Math.max(v.getY(), -0.05), v.getZ() * 0.5));
                  }
               } else {
                  if (!this.driving) {
                     this.driving = true;
                     Vector v = player.getVelocity();
                     player.setVelocity(new Vector(v.getX(), slamVelocity, v.getZ()));
                     LuminosityListener.this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_MACE_SMASH_AIR, 1.0F, 0.7F);
                  }

                  if (this.ticks % 2 == 0) {
                     LuminosityListener.this.plugin.getParticleManager().trail(player.getLocation(), Particle.END_ROD, 2, 0.2, 0.3, 0.2, 0.02);
                  }

                  if (player.isOnGround() || this.ticks >= windup + maxTicks) {
                     this.finish(true);
                  }
               }
            } catch (RuntimeException var2) {
               LuminosityListener.this.plugin.getLogger().warning("SuperNova aborted for " + player.getName() + ": " + var2);
               this.finish(false);
            }
         }
      };
      this.slamming.put(id, task);
      task.runTaskTimer(this.plugin, 1L, 1L);
   }

   private double radius(double power) {
      return this.ability("supernova-radius", 7.0) * (0.45 + power * 0.55);
   }

   /** The ring on the ground where the slam will land: the "get out of here" cue for everyone else. */
   private void warningRing(Player player, double radius) {
      Location ground = player.getLocation();
      Location base = ground.clone();
      int floor = base.getBlockY();

      for (int i = 0; i < 24 && floor > ground.getWorld().getMinHeight(); i++) {
         if (!base.getWorld().getBlockAt(base.getBlockX(), floor - 1, base.getBlockZ()).isPassable()) {
            break;
         }

         floor--;
      }

      Location centre = new Location(ground.getWorld(), ground.getX(), floor + 0.1, ground.getZ());
      Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 200, 60), 1.3F);

      for (int i = 0; i < 28; i++) {
         double angle = Math.PI * 2.0 * i / 28.0;
         ParticleManager.spawn(Particle.DUST, centre.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius), 1, 0.0, 0.0, 0.0, 0.0, dust);
      }
   }

   private void detonate(Player player, double power, double radius) {
      double damage = this.ability("supernova-damage", 20.0) * power;
      double cap = this.ability("supernova-max-health-fraction", 0.6);
      double knockback = this.ability("supernova-knockback", 1.8) * (0.5 + power * 0.5);
      Location centre = player.getLocation();
      player.setFallDistance(0.0F);
      ParticleManager.spawn(Particle.EXPLOSION_EMITTER, centre, 1, 0.0, 0.0, 0.0, 0.0);
      com.legendaryweapons.managers.AnimationFx.ring(centre, Material.GLOWSTONE, 0.6, radius, 12, true);
      com.legendaryweapons.managers.AnimationFx.shockwave(centre, Material.GOLD_BLOCK, radius * 0.7, 12);
      ParticleManager.spawn(Particle.FLASH, centre, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(255, 230, 150));
      ParticleManager.spawn(Particle.END_ROD, centre.clone().add(0.0, 0.3, 0.0), (int)(24.0 * power) + 8, radius / 2.0, 0.3, radius / 2.0, 0.08);
      this.plugin.getSoundManager().world(centre, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0F, 0.8F);
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_GENERIC_EXPLODE, 0.5F + (float)power * 0.3F, 1.1F);
      if (this.abilities() != null && this.abilities().getBoolean("supernova-break-blocks", false) && this.plugin.getProtectionManager().canBuild(player, centre)) {
         // The player is passed as the source, so protection plugins filter the block list per region.
         centre.getWorld().createExplosion(centre, (float)(this.ability("supernova-block-power", 2.0) * power), false, true, player);
      }

      for (Entity nearby : centre.getWorld().getNearbyEntities(centre, radius, radius / 2.0 + 2.0, radius)) {
         if (nearby instanceof LivingEntity living && this.canAffect(player, nearby)) {
            double distance = nearby.getLocation().distance(centre);
            double falloff = Math.max(0.0, 1.0 - distance / radius);
            double dealt = damage * falloff * falloff;
            AttributeInstance maxHealth = living.getAttribute(Attribute.MAX_HEALTH);
            if (maxHealth != null && cap > 0.0) {
               dealt = Math.min(dealt, maxHealth.getValue() * cap);
            }

            if (dealt > 0.0) {
               living.damage(dealt, player);
               // "the last light a thing ever sees": whoever the blast touched stays lit up for a moment
               int glow = this.abilityInt("supernova-glow-duration", 60);
               if (glow > 0 && living != player) {
                  living.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.GLOWING, glow, 0, false, false, true));
               }
            }

            Vector offset = nearby.getLocation().toVector().subtract(centre.toVector());
            Vector direction = offset.lengthSquared() < 1.0E-4 ? new Vector(0.0, 1.0, 0.0) : offset.normalize();
            nearby.setVelocity(direction.multiply(knockback * falloff).setY(Math.max(0.4, knockback * falloff * 0.6)));
         }
      }

      HudManager.message(player, Component.text("SuperNova ", NamedTextColor.YELLOW).append(Component.text((int)Math.round(power * 100.0) + "%", NamedTextColor.GOLD)));
   }
}
