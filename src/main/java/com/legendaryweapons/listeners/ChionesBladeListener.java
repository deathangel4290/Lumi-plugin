package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class ChionesBladeListener extends AbstractWeaponListener {
   private static final String WEAPON_KEY = "chiones-blade";
   private static final String COMBO_KEY = "chiones-freeze";
   private static final String WAVE_COOLDOWN_KEY = "chiones-ice-wave";

   public ChionesBladeListener(LegendaryWeapons plugin) {
      super(plugin);
      this.startFrostWalker();
   }

   @Override
   public String weaponKey() {
      return "chiones-blade";
   }

   private void startFrostWalker() {
      (new BukkitRunnable() {
         public void run() {
            int amplifier = ChionesBladeListener.this.abilityInt("cold-ground-speed-amplifier", 1);

            for (Player player : ChionesBladeListener.this.plugin.getServer().getOnlinePlayers()) {
               if (ChionesBladeListener.this.isHolding(player) && ChionesBladeListener.this.isOnCold(player)) {
                  player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, amplifier, true, false, true));
               }
            }
         }
      }).runTaskTimer(this.plugin, 20L, 20L);
   }

   private boolean isOnCold(Player player) {
      Material below = player.getLocation().subtract(0.0, 0.1, 0.0).getBlock().getType();

      return switch (below) {
         case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE, SNOW, SNOW_BLOCK, POWDER_SNOW -> true;
         default -> false;
      };
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker)) {
         if (event.getEntity() instanceof Player victim) {
            if (event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK) {
               if (this.isFullSwing(attacker)) {
                  int required = this.abilityInt("combo-hits-required", 5);
                  long window = this.abilityLong("combo-window-millis", 3000L);
                  int combo = this.plugin.getComboTracker().hit(attacker, "chiones-freeze", window);
                  attacker.sendActionBar(
                     Component.text("Frost ", NamedTextColor.AQUA).append(Component.text(Math.min(combo, required) + "/" + required, NamedTextColor.WHITE))
                  );
                  if (combo >= required) {
                     this.plugin.getComboTracker().reset(attacker, "chiones-freeze");
                     this.freeze(victim, attacker);
                  }
               }
            }
         }
      }
   }

   private void freeze(final LivingEntity target, Player source) {
      final int duration = this.abilityInt("freeze-duration", 30);
      target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, 6, true, false));
      target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(), duration * 2));
      final Location anchor = target.getLocation();
      (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            if (this.ticks++ < duration && target.isValid() && !target.isDead()) {
               if (!target.getWorld().equals(anchor.getWorld())) {
                  target.setFreezeTicks(0);
                  this.cancel();
               } else {
                  Vector v = target.getVelocity();
                  target.setVelocity(new Vector(0.0, Math.min(0.0, v.getY()), 0.0));
                  if (target.getLocation().distanceSquared(anchor) > 4.0) {
                     target.setFreezeTicks(0);
                     this.cancel();
                  }
               }
            } else {
               if (target.isValid()) {
                  target.setFreezeTicks(0);
                  target.setVelocity(new Vector(0.0, target.getVelocity().getY() < 0.0 ? target.getVelocity().getY() : 0.0, 0.0));
               }

               this.cancel();
            }
         }
      }).runTaskTimer(this.plugin, 1L, 1L);
      Location loc = target.getLocation();
      loc.getWorld().spawnParticle(Particle.SNOWFLAKE, loc.clone().add(0.0, 1.0, 0.0), 20, 0.4, 0.8, 0.4, 0.02);
      this.plugin.getSoundManager().world(loc, Sound.BLOCK_GLASS_BREAK, 0.7F, 0.6F);
      if (target instanceof Player frozen) {
         frozen.sendActionBar(Component.text("Frozen!", NamedTextColor.AQUA));
      }

      source.sendActionBar(Component.text("Frozen " + (target instanceof Player p ? p.getName() : "target"), NamedTextColor.AQUA));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (!this.tryCooldown(player, "chiones-ice-wave", this.abilityLong("ice-wave-cooldown", 200L))) {
         return true;
      } else {
         this.iceWave(player);
         return true;
      }
   }

   private void iceWave(final Player player) {
      final double range = this.ability("ice-wave-range", 14.0);
      final double speed = this.ability("ice-wave-speed", 0.9);
      final double width = this.ability("ice-wave-width", 2.5);
      final double damage = this.ability("ice-wave-damage", 0.0);
      final int slowDuration = this.abilityInt("ice-wave-slowness-duration", 100);
      final int slowAmplifier = this.abilityInt("ice-wave-slowness-amplifier", 2);
      final Vector facing = player.getLocation().getDirection().setY(0.0).normalize();
      final Vector across = new Vector(-facing.getZ(), 0.0, facing.getX()).normalize();
      final Location origin = player.getLocation().add(0.0, 0.3, 0.0);
      final Set<UUID> hit = new HashSet<>();
      this.plugin.getSoundManager().world(origin, Sound.BLOCK_POWDER_SNOW_BREAK, 0.8F, 0.7F);
      (new BukkitRunnable() {
            double travelled = 0.0;

            public void run() {
               if (!(this.travelled >= range) && player.isOnline()) {
                  this.travelled = this.travelled + speed;
                  Location front = origin.clone().add(facing.clone().multiply(this.travelled));

                  for (double offset = -width; offset <= width; offset += 0.5) {
                     Location point = front.clone().add(across.clone().multiply(offset));
                     point.getWorld().spawnParticle(Particle.SNOWFLAKE, point, 2, 0.05, 0.25, 0.05, 0.01);
                     point.getWorld().spawnParticle(Particle.ITEM_SNOWBALL, point, 1, 0.1, 0.2, 0.1, 0.02);
                  }

                  for (Entity nearby : front.getWorld().getNearbyEntities(front, width, 2.0, width)) {
                     if (nearby instanceof LivingEntity living
                        && !nearby.equals(player)
                        && hit.add(nearby.getUniqueId())
                        && !(
                           nearby instanceof Player target
                              && ChionesBladeListener.this.plugin.getTrustManager().isTrusted(player, target)
                              && ChionesBladeListener.this.plugin.getConfigManager().isPreventAbilityDamage()
                        )) {
                        living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowAmplifier, true, false));
                        living.setFreezeTicks(Math.min(living.getMaxFreezeTicks(), slowDuration));
                        if (damage > 0.0) {
                           living.damage(damage, player);
                        }

                        living.getWorld().spawnParticle(Particle.SNOWFLAKE, living.getLocation().add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.02);
                     }
                  }
               } else {
                  this.cancel();
               }
            }
         })
         .runTaskTimer(this.plugin, 1L, 1L);
   }
}
