package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.abilities.ChargedWeapon;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class LuminosityListener extends AbstractWeaponListener implements ChargedWeapon {
   private static final String WEAPON_KEY = "luminosity";
   private static final String DASH_POOL_KEY = "luminosity-dash";
   private static final String ULTIMATE_KEY = "luminosity-supernova";
   private final Map<UUID, Long> lastChargeFromVictim = new HashMap<>();
   private final Map<UUID, Double> slamming = new HashMap<>();

   public LuminosityListener(LegendaryWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return "luminosity";
   }

   @Override
   public void setChargeFraction(Player player, double fraction) {
      double max = this.ability("ultimate-charge-max", 100.0);
      this.plugin.getChargeMeter().set(player, "luminosity-supernova", fraction * max, max);
   }

   @Override
   public double getChargeFraction(Player player) {
      double max = this.ability("ultimate-charge-max", 100.0);
      return max <= 0.0 ? 0.0 : this.plugin.getChargeMeter().get(player, "luminosity-supernova") / max;
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.slamming.remove(event.getEntity().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.slamming.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker)) {
         if (event.getEntity() instanceof Player victim && !(event.getFinalDamage() <= 0.0)) {
            if (!this.plugin.getTrustManager().isTrusted(attacker, victim)) {
               long now = System.currentTimeMillis();
               long victimCooldown = this.abilityLong("ultimate-victim-cooldown", 60L) * 50L;
               UUID pairKey = victim.getUniqueId();
               Long last = this.lastChargeFromVictim.get(pairKey);
               if (last == null || now - last >= victimCooldown) {
                  this.lastChargeFromVictim.put(pairKey, now);
                  double max = this.ability("ultimate-charge-max", 100.0);
                  double gain = this.ability("ultimate-charge-per-hit", 20.0);
                  double charge = this.plugin.getChargeMeter().add(attacker, "luminosity-supernova", gain, max);
                  this.plugin.getChargeMeter().showActionBar(attacker, "SuperNova", "luminosity-supernova", max, NamedTextColor.YELLOW);
                  if (charge >= max) {
                     this.plugin.getSoundManager().self(attacker, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 2.0F);
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
      long recharge = this.abilityLong("dash-recharge", 160L);
      long penalty = this.abilityLong("dash-empty-penalty", 200L);
      if (!this.plugin.getChargePool().tryConsume(player, "luminosity-dash", charges, recharge, penalty)) {
         player.sendActionBar(
            Component.text("Dash recharging ", NamedTextColor.GRAY)
               .append(Component.text(this.plugin.getChargePool().secondsUntilNext(player, "luminosity-dash", charges) + "s", NamedTextColor.YELLOW))
         );
      } else {
         Vector direction = player.getLocation().getDirection().setY(0.0).normalize().multiply(this.ability("dash-power", 1.5));
         direction.setY(this.ability("dash-lift", 0.35));
         player.setVelocity(direction);
         int remaining = this.plugin.getChargePool().available(player, "luminosity-dash", charges);
         player.sendActionBar(
            Component.text("Dash ", NamedTextColor.GRAY)
               .append(Component.text(remaining + "/" + charges, remaining > 0 ? NamedTextColor.YELLOW : NamedTextColor.DARK_GRAY))
         );
         Location loc = player.getLocation();
         this.plugin.getParticleManager().burst(loc, Particle.END_ROD, 10, 0.3, 0.2, 0.3, 0.04);
         this.plugin.getSoundManager().world(loc, Sound.ENTITY_BREEZE_SHOOT, 0.6F, 1.3F);
      }
   }

   private void superNova(final Player player) {
      double max = this.ability("ultimate-charge-max", 100.0);
      double minimum = this.ability("supernova-min-charge", 20.0);
      double held = this.plugin.getChargeMeter().get(player, "luminosity-supernova");
      if (held < minimum) {
         player.sendActionBar(
            ((TextComponent)Component.text("SuperNova ", NamedTextColor.GRAY)
                  .append(Component.text((int)Math.round(held / max * 100.0) + "%", NamedTextColor.YELLOW)))
               .append(Component.text(" - needs " + (int)Math.round(minimum / max * 100.0) + "%", NamedTextColor.GRAY))
         );
      } else if (!this.slamming.containsKey(player.getUniqueId())) {
         final double power = Math.max(0.0, Math.min(1.0, this.plugin.getChargeMeter().consume(player, "luminosity-supernova") / max));
         if (player.isOnGround()) {
            player.setVelocity(new Vector(0.0, this.ability("supernova-launch", 0.9), 0.0));
         }

         final UUID id = player.getUniqueId();
         this.slamming.put(id, power);
         this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_WARDEN_SONIC_CHARGE, 0.7F, 1.4F);
         final int maxTicks = this.abilityInt("supernova-max-fall-ticks", 60);
         final double slamVelocity = this.ability("supernova-slam-velocity", -2.2);
         (new BukkitRunnable() {
            int ticks = 0;
            boolean driving = false;

            private void finish(boolean detonate) {
               if (detonate && player.isOnline()) {
                  LuminosityListener.this.detonate(player, power);
               }

               LuminosityListener.this.slamming.remove(id);
               this.cancel();
            }

            public void run() {
               if (player.isOnline() && LuminosityListener.this.isHolding(player)) {
                  this.ticks++;
                  if (!this.driving && (player.getVelocity().getY() <= 0.0 || this.ticks > 6)) {
                     this.driving = true;
                     Vector v = player.getVelocity();
                     player.setVelocity(new Vector(v.getX(), slamVelocity, v.getZ()));
                  }

                  LuminosityListener.this.plugin.getParticleManager().trail(player.getLocation(), Particle.ELECTRIC_SPARK, 4, 0.3, 0.3, 0.3, 0.05);
                  if (this.driving && (player.isOnGround() || this.ticks >= maxTicks)) {
                     this.finish(true);
                  }
               } else {
                  this.finish(false);
               }
            }
         }).runTaskTimer(this.plugin, 1L, 1L);
      }
   }

   private void detonate(Player player, double power) {
      double radius = this.ability("supernova-radius", 8.0) * (0.45 + power * 0.55);
      double damage = this.ability("supernova-damage", 24.0) * power;
      double knockback = this.ability("supernova-knockback", 2.0) * (0.5 + power * 0.5);
      Location centre = player.getLocation();
      player.setFallDistance(0.0F);
      this.plugin.getParticleManager().burst(centre, Particle.EXPLOSION_EMITTER, 1 + (int)(power * 2.0), 1.0, 0.2, 1.0, 0.0);
      this.plugin.getParticleManager().burst(centre, Particle.END_ROD, (int)(30.0 * power) + 10, radius / 2.0, 0.4, radius / 2.0, 0.12);
      centre.getWorld().spawnParticle(Particle.FLASH, centre, 1, 0.0, 0.0, 0.0, 0.0);
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_GENERIC_EXPLODE, 0.6F + (float)power * 0.4F, 0.6F);
      if (this.abilities() != null && this.abilities().getBoolean("supernova-break-blocks", false)) {
         centre.getWorld().createExplosion(centre, (float)(this.ability("supernova-block-power", 2.0) * power), false, true, player);
      }

      for (Entity nearby : centre.getWorld().getNearbyEntities(centre, radius, radius / 2.0 + 2.0, radius)) {
         if (nearby instanceof LivingEntity living
            && !nearby.equals(player)
            && !(
               nearby instanceof Player target
                  && this.plugin.getTrustManager().isTrusted(player, target)
                  && this.plugin.getConfigManager().isPreventAbilityDamage()
            )) {
            double distance = nearby.getLocation().distance(centre);
            double falloff = Math.max(0.0, 1.0 - distance / radius);
            double dealt = damage * falloff * falloff;
            if (dealt > 0.0) {
               living.damage(dealt, player);
            }

            Vector offset = nearby.getLocation().toVector().subtract(centre.toVector());
            Vector direction = offset.lengthSquared() < 1.0E-4 ? new Vector(0.0, 1.0, 0.0) : offset.normalize();
            nearby.setVelocity(direction.multiply(knockback * falloff).setY(Math.max(0.5, knockback * falloff)));
         }
      }

      player.sendActionBar(
         Component.text("SuperNova ", NamedTextColor.YELLOW).append(Component.text((int)Math.round(power * 100.0) + "%", NamedTextColor.GOLD))
      );
   }
}
