package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.WeaponManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.entity.AbstractArrow.PickupStatus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class HellstormCrossbowListener implements Listener {
   private static final String WEAPON_KEY = "hellstorm";
   private final LegendaryWeapons plugin;
   private final WeaponManager weaponManager;
   private final Map<UUID, BukkitRunnable> arrowEffects = new HashMap<>();
   private final NamespacedKey explosiveKey;

   public HellstormCrossbowListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.explosiveKey = new NamespacedKey(plugin, "hellstorm-explosive");
   }

   private HellstormCrossbowListener.Settings settings() {
      return HellstormCrossbowListener.Settings.from(this.plugin.getConfigManager().getWeaponAbilities("hellstorm"));
   }

   @EventHandler
   public void onEntityShootBow(EntityShootBowEvent event) {
      if (event.getEntity() instanceof Player) {
         Player player = (Player)event.getEntity();
         ItemStack bow = event.getBow();
         if (this.weaponManager.isLegendaryWeapon(bow) && "hellstorm".equals(this.weaponManager.getWeaponKey(bow))) {
            event.setCancelled(true);
            HellstormCrossbowListener.Settings settings = this.settings();
            if (this.plugin.getCooldownManager().tryUse(player, "hellstorm", settings.cooldownTicks())) {
               this.fireHellstorm(player, settings);
            }
         }
      }
   }

   private void fireHellstorm(final Player player, final HellstormCrossbowListener.Settings settings) {
      Location eyeLocation = player.getEyeLocation();
      final Vector direction = eyeLocation.getDirection();

      for (int i = 0; i < settings.arrowCount(); i++) {
         final int arrowIndex = i;
         (new BukkitRunnable() {
            public void run() {
               HellstormCrossbowListener.this.fireSingleArrow(player, direction, arrowIndex, settings);
            }
         }).runTaskLater(this.plugin, i * 5L);
      }
   }

   private void fireSingleArrow(Player player, Vector direction, int arrowIndex, HellstormCrossbowListener.Settings settings) {
      boolean isExplosive = arrowIndex == settings.arrowCount() - 1;
      Location spawnLocation = player.getEyeLocation().clone();
      Arrow arrow = player.getWorld().spawnArrow(spawnLocation, direction, 2.5F, 0.0F);
      arrow.setShooter(player);
      arrow.setFireTicks(settings.fireTicks());
      arrow.setCritical(true);
      arrow.setPickupStatus(PickupStatus.DISALLOWED);
      arrow.setGravity(false);
      arrow.setDamage(isExplosive ? settings.explosiveDamage() : settings.normalDamage());
      if (isExplosive) {
         arrow.setVisualFire(true);
         arrow.setPierceLevel(2);
         arrow.getPersistentDataContainer().set(this.explosiveKey, PersistentDataType.FLOAT, settings.explosivePower());
      }

      this.startArrowEffects(arrow, isExplosive);
   }

   private void startArrowEffects(final Arrow arrow, final boolean isExplosive) {
      final UUID arrowId = arrow.getUniqueId();
      BukkitRunnable effectTask = new BukkitRunnable() {
         public void run() {
            if (arrow != null && arrow.isValid() && !arrow.isOnGround()) {
               Location arrowLoc = arrow.getLocation();
               World world = arrow.getWorld();
               world.spawnParticle(Particle.SMOKE, arrowLoc, 1, 0.1, 0.1, 0.1, 0.01);
               if (isExplosive) {
                  world.spawnParticle(Particle.DUST, arrowLoc, 6, 0.35, 0.35, 0.35, 0.0, new DustOptions(Color.RED, 3.0F));
                  world.spawnParticle(Particle.FLAME, arrowLoc, 4, 0.2, 0.2, 0.2, 0.01);
               }
            } else {
               HellstormCrossbowListener.this.arrowEffects.remove(arrowId);
               this.cancel();
            }
         }
      };
      effectTask.runTaskTimer(this.plugin, 1L, 1L);
      this.arrowEffects.put(arrowId, effectTask);
   }

   @EventHandler
   public void onProjectileHit(ProjectileHitEvent event) {
      if (event.getEntity() instanceof Arrow) {
         final Arrow arrow = (Arrow)event.getEntity();
         if (this.arrowEffects.containsKey(arrow.getUniqueId())) {
            this.arrowEffects.get(arrow.getUniqueId()).cancel();
            this.arrowEffects.remove(arrow.getUniqueId());
         }

         if (arrow.getPersistentDataContainer().has(this.explosiveKey, PersistentDataType.FLOAT)) {
            final float power = (Float)arrow.getPersistentDataContainer().get(this.explosiveKey, PersistentDataType.FLOAT);
            final Location explosionLoc = arrow.getLocation();
            (new BukkitRunnable() {
               public void run() {
                  arrow.getWorld().createExplosion(explosionLoc, power, true, true);
                  arrow.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, explosionLoc, 2, 0.0, 0.0, 0.0, 0.0);
                  HellstormCrossbowListener.this.plugin.getSoundManager().world(explosionLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.8F);
                  arrow.remove();
               }
            }).runTaskLater(this.plugin, 3L);
         }
      }
   }

   private record Settings(int arrowCount, int fireTicks, float explosivePower, double normalDamage, double explosiveDamage, long cooldownTicks) {
      static HellstormCrossbowListener.Settings from(ConfigurationSection section) {
         return section == null
            ? new HellstormCrossbowListener.Settings(5, 200, 3.0F, 6.0, 18.0, 600L)
            : new HellstormCrossbowListener.Settings(
               Math.max(1, section.getInt("arrow-count", 5)),
               section.getInt("fire-ticks", 200),
               (float)section.getDouble("explosive-arrow-power", 3.0),
               section.getDouble("normal-arrow-damage", 6.0),
               section.getDouble("explosive-arrow-damage", 18.0),
               section.getLong("cooldown", 600L)
            );
      }
   }
}
