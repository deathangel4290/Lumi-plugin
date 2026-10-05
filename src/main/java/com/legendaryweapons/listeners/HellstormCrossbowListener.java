package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.WeaponManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
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
      plugin.getHudManager().register(player -> {
         ItemStack held = player.getInventory().getItemInMainHand();
         return this.weaponManager.isLegendaryWeapon(held) && WEAPON_KEY.equals(this.weaponManager.getWeaponKey(held))
            ? com.legendaryweapons.managers.HudManager.cooldown("Volley", plugin.getCooldownManager().getRemainingSeconds(player, WEAPON_KEY))
            : null;
      });
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
            HellstormCrossbowListener.Settings settings = this.settings();
            // Between volleys it is still a crossbow: let the normal shot through instead of eating it,
            // which made the weapon do nothing at all for the whole cooldown.
            if (this.plugin.getCooldownManager().isOnCooldown(player, WEAPON_KEY)) {
               return;
            }

            event.setCancelled(true);
            this.plugin.getCooldownManager().set(player, WEAPON_KEY, settings.cooldownTicks());
            this.fireHellstorm(player, settings);
         }
      }
   }

   private void fireHellstorm(final Player player, final HellstormCrossbowListener.Settings settings) {
      com.legendaryweapons.managers.AnimationFx.ring(player.getLocation(), Material.ORANGE_STAINED_GLASS, 0.3, 1.5, 6, true);
      // each arrow leaves along wherever the player is aiming at that moment, so a volley can be walked onto a target
      for (int i = 0; i < settings.arrowCount(); i++) {
         final int arrowIndex = i;
         (new BukkitRunnable() {
            public void run() {
               if (player.isOnline() && !player.isDead()) {
                  HellstormCrossbowListener.this.fireSingleArrow(player, player.getEyeLocation().getDirection(), arrowIndex, settings);
               }
            }
         }).runTaskLater(this.plugin, i * settings.intervalTicks());
      }
   }

   private void fireSingleArrow(Player player, Vector direction, int arrowIndex, HellstormCrossbowListener.Settings settings) {
      boolean isExplosive = arrowIndex == settings.arrowCount() - 1;
      Location spawnLocation = player.getEyeLocation().clone();
      Arrow arrow = player.getWorld().spawnArrow(spawnLocation, direction, settings.arrowSpeed(), 0.0F);
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

      float pitch = 0.8F + 0.12F * arrowIndex;                     // the volley climbs in pitch
      this.plugin.getSoundManager().world(spawnLocation, Sound.ITEM_CROSSBOW_SHOOT, 0.8F, pitch);
      this.plugin.getSoundManager().world(spawnLocation, isExplosive ? Sound.ITEM_FIRECHARGE_USE : Sound.ENTITY_BLAZE_SHOOT, isExplosive ? 0.8F : 0.35F, pitch);
      this.startArrowEffects(arrow, isExplosive);
   }

   private void startArrowEffects(final Arrow arrow, final boolean isExplosive) {
      final UUID arrowId = arrow.getUniqueId();
      BukkitRunnable effectTask = new BukkitRunnable() {
         int life = 0;

         public void run() {
            // No gravity: a volley fired at the sky would fly (and tick) forever. 5s is ~300 blocks.
            if (arrow.isValid() && !arrow.isOnGround() && this.life++ >= 100) {
               arrow.remove();
            }

            if (arrow.isValid() && !arrow.isOnGround()) {
               Location arrowLoc = arrow.getLocation();
               World world = arrow.getWorld();
               ParticleManager.spawn(Particle.SMOKE, arrowLoc, 1, 0.1, 0.1, 0.1, 0.01);
               ParticleManager.spawn(Particle.FLAME, arrowLoc, 1, 0.03, 0.03, 0.03, 0.0);
               if (isExplosive) {
                  ParticleManager.spawn(Particle.DUST, arrowLoc, 6, 0.35, 0.35, 0.35, 0.0, new DustOptions(Color.RED, 3.0F));
                  ParticleManager.spawn(Particle.FLAME, arrowLoc, 4, 0.2, 0.2, 0.2, 0.01);
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
                  Entity source = arrow.getShooter() instanceof Entity shooterEntity ? shooterEntity : arrow;
                  boolean breakBlocks = !(arrow.getShooter() instanceof Player shooter)
                     || HellstormCrossbowListener.this.plugin.getProtectionManager().canBuild(shooter, explosionLoc);
                  // With a source entity, protection plugins filter the explosion per region.
                  // no fire where the shooter may not build either (claims, spawn, regions)
                  arrow.getWorld().createExplosion(explosionLoc, power, breakBlocks, breakBlocks, source);
                  ParticleManager.spawn(Particle.EXPLOSION_EMITTER, explosionLoc, 2, 0.0, 0.0, 0.0, 0.0);
                  HellstormCrossbowListener.this.plugin.getSoundManager().world(explosionLoc, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.8F);
                  arrow.remove();
               }
            }).runTaskLater(this.plugin, 3L);
         }
      }
   }

   private record Settings(int arrowCount, int fireTicks, float explosivePower, double normalDamage, double explosiveDamage, long cooldownTicks,
                           long intervalTicks, float arrowSpeed) {
      static HellstormCrossbowListener.Settings from(ConfigurationSection section) {
         return section == null
            ? new HellstormCrossbowListener.Settings(5, 200, 3.0F, 6.0, 18.0, 600L, 3L, 3.2F)
            : new HellstormCrossbowListener.Settings(
               Math.max(1, section.getInt("arrow-count", 5)),
               section.getInt("fire-ticks", 200),
               (float)section.getDouble("explosive-arrow-power", 3.0),
               section.getDouble("normal-arrow-damage", 6.0),
               section.getDouble("explosive-arrow-damage", 18.0),
               section.getLong("cooldown", 600L),
               Math.max(1L, section.getLong("volley-interval", 3L)),
               (float)Math.max(0.5, section.getDouble("arrow-speed", 3.2))
            );
      }
   }
}
