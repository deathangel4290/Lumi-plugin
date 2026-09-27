package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.WeaponAbility;
import com.legendaryweapons.managers.WeaponManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class LostTridentListener implements Listener, WeaponAbility {
   private static final String WEAPON_KEY = "lost-trident-of-atlantis";
   private static final String WALL_COOLDOWN_KEY = "atlantis-water-wall";
   private final LegendaryWeapons plugin;
   private final WeaponManager weaponManager;
   private final Map<UUID, BukkitRunnable> tridentEffects = new HashMap<>();
   /** Water placed by live walls. It must never flow: flowing water next to two sources makes new sources
    *  the wall doesn't know about, and those were left behind forever after the wall ended. */
   private final Set<Block> wallWater = new HashSet<>();
   private final NamespacedKey tridentKey;

   @Override
   public String weaponKey() {
      return "lost-trident-of-atlantis";
   }

   @Override
   public net.kyori.adventure.text.Component hud(Player player) {
      return com.legendaryweapons.managers.HudManager.cooldown("Water Wall", this.plugin.getCooldownManager().getRemainingSeconds(player, WALL_COOLDOWN_KEY));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      this.triggerAbility(player, secondary);
      return true;
   }

   public LostTridentListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.tridentKey = new NamespacedKey(plugin, "lost-trident");
   }

   public void triggerAbility(Player player) {
      this.triggerAbility(player, player.isSneaking());
   }

   public void triggerAbility(Player player, boolean secondary) {
      if (secondary) {
         this.createWaterWall(player);
      } else {
         com.legendaryweapons.managers.HudManager.message(player, Component.text("Sneak to raise a water wall.", NamedTextColor.AQUA));
      }
   }

   @EventHandler
   public void onProjectileLaunch(ProjectileLaunchEvent event) {
      if (event.getEntity() instanceof Trident && event.getEntity().getShooter() instanceof Player) {
         Player player = (Player)event.getEntity().getShooter();
         ItemStack item = player.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(item) && "lost-trident-of-atlantis".equals(this.weaponManager.getWeaponKey(item))) {
            Trident trident = (Trident)event.getEntity();
            ItemStack tridentItem = this.weaponManager.getWeapon("lost-trident-of-atlantis");
            ItemMeta meta = tridentItem.getItemMeta();
            meta.addEnchant(Enchantment.LOYALTY, 3, true);
            meta.addEnchant(Enchantment.CHANNELING, 1, true);
            tridentItem.setItemMeta(meta);
            trident.setItem(tridentItem);
            trident.getPersistentDataContainer().set(this.tridentKey, PersistentDataType.BYTE, (byte)1);
            this.startTridentEffects(trident, player);
            this.createLaunchEffects(player.getLocation(), trident.getVelocity());
         }
      }
   }

   @EventHandler
   public void onProjectileHit(ProjectileHitEvent event) {
      if (event.getEntity() instanceof Trident) {
         Trident trident = (Trident)event.getEntity();
         if (this.tridentEffects.containsKey(trident.getUniqueId())) {
            this.tridentEffects.get(trident.getUniqueId()).cancel();
            this.tridentEffects.remove(trident.getUniqueId());
         }

         if (trident.getPersistentDataContainer().has(this.tridentKey, PersistentDataType.BYTE)) {
            Player shooter = trident.getShooter() instanceof Player p ? p : null;
            this.createEpicWaterPulse(trident.getLocation(), shooter);
         }
      }
   }

   private void startTridentEffects(final Trident trident, Player player) {
      final UUID tridentId = trident.getUniqueId();
      BukkitRunnable effectTask = new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            if (trident != null && trident.isValid() && !trident.isOnGround()) {
               Location tridentLoc = trident.getLocation();
               World world = trident.getWorld();
               ParticleManager.spawn(Particle.DRIPPING_WATER, tridentLoc, 5, 0.1, 0.1, 0.1, 0.02);
               ParticleManager.spawn(Particle.FALLING_WATER, tridentLoc, 3, 0.05, 0.05, 0.05, 0.01);
               ParticleManager.spawn(Particle.NAUTILUS, tridentLoc, 2, 0.2, 0.2, 0.2, 0.1);
               if (this.ticks % 3 == 0) {
                  ParticleManager.spawn(Particle.BUBBLE_POP, tridentLoc, 2, 0.3, 0.3, 0.3, 0.05);
               }

               if (this.ticks % 10 == 0) {
                  LostTridentListener.this.createPulsingAura(tridentLoc);
               }

               LostTridentListener.this.createVortexTrail(tridentLoc, this.ticks);
               if (this.ticks % 5 == 0) {
                  LostTridentListener.this.createBubbleStream(tridentLoc, trident.getVelocity());
               }

               if (this.ticks % 20 == 0) {
                  LostTridentListener.this.plugin.getSoundManager().ambient(tridentLoc, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.3F, 1.5F);
               }

               trident.setGlowing(this.ticks % 20 < 10);
               this.ticks++;
            } else {
               LostTridentListener.this.tridentEffects.remove(tridentId);
               this.cancel();
            }
         }
      };
      effectTask.runTaskTimer(this.plugin, 1L, 1L);
      this.tridentEffects.put(tridentId, effectTask);
   }

   private void createLaunchEffects(final Location location, Vector direction) {
      World world = location.getWorld();
      ParticleManager.spawn(Particle.SPLASH, location, 50, 1.0, 1.0, 1.0, 0.3);
      ParticleManager.spawn(Particle.DRIPPING_WATER, location, 30, 0.8, 0.8, 0.8, 0.2);

      for (int i = 0; i < 3; i++) {
         final int layer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 1.0 + layer * 0.5;
               LostTridentListener.this.createEnergyRing(location, radius, Color.AQUA);
            }
         }).runTaskLater(this.plugin, i * 2L);
      }

      this.plugin.getSoundManager().world(location, Sound.ITEM_TRIDENT_THROW, 0.8F, 0.9F);
   }

   private void createPulsingAura(Location center) {
      World world = center.getWorld();

      for (double radius = 0.5; radius <= 1.5; radius += 0.5) {
         for (int i = 0; i < 12; i++) {
            double angle = i * 3.141592653589793 / 6.0;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location auraPoint = center.clone().add(x, 0.0, z);
            ParticleManager.spawn(Particle.DUST, auraPoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(Color.fromRGB(0, 150, 255), 1.5F));
         }
      }

      ParticleManager.spawn(Particle.END_ROD, center, 3, 0.2, 0.2, 0.2, 0.05);
   }

   private void createVortexTrail(Location center, int tick) {
      World world = center.getWorld();
      double time = tick * 0.3;

      for (int i = 0; i < 3; i++) {
         double radius = 0.3 + i * 0.2;

         for (int j = 0; j < 8; j++) {
            double angle = time + j * 3.141592653589793 / 4.0;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            Location vortexPoint = center.clone().add(-x, 0.1, -z);
            ParticleManager.spawn(Particle.DRIPPING_WATER, vortexPoint, 1, 0.0, 0.0, 0.0, 0.0);
            if (j % 2 == 0) {
               ParticleManager.spawn(Particle.BUBBLE_POP, vortexPoint, 1, 0.1, 0.1, 0.1, 0.0);
            }
         }
      }
   }

   private void createBubbleStream(Location center, Vector direction) {
      World world = center.getWorld();
      Vector perpendicular1 = new Vector(-direction.getZ(), 0.0, direction.getX()).normalize().multiply(0.4);
      Vector perpendicular2 = new Vector(direction.getZ(), 0.0, -direction.getX()).normalize().multiply(0.4);

      for (int i = 0; i < 5; i++) {
         double offset = i * 0.3;
         Location streamPoint1 = center.clone().add(perpendicular1).add(direction.clone().multiply(-offset));
         Location streamPoint2 = center.clone().add(perpendicular2).add(direction.clone().multiply(-offset));
         ParticleManager.spawn(Particle.BUBBLE_COLUMN_UP, streamPoint1, 2, 0.1, 0.1, 0.1, 0.05);
         ParticleManager.spawn(Particle.BUBBLE_COLUMN_UP, streamPoint2, 2, 0.1, 0.1, 0.1, 0.05);
      }
   }

   private void createEnergyRing(Location center, double radius, Color color) {
      World world = center.getWorld();

      for (int i = 0; i < 16; i++) {
         double angle = i * 3.141592653589793 / 8.0;
         double x = Math.cos(angle) * radius;
         double z = Math.sin(angle) * radius;
         Location ringPoint = center.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.DUST, ringPoint, 2, 0.1, 0.1, 0.1, 0.0, new DustOptions(color, 2.0F));
      }
   }

   private void createWaterWall(final Player player) {
      if (player.getWorld().getEnvironment() == Environment.NETHER) {
         com.legendaryweapons.managers.HudManager.message(player, Component.text("The water boils away here.", NamedTextColor.RED));
         this.plugin.getSoundManager().self(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.6F, 1.4F);
      } else {
         ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("lost-trident-of-atlantis");
         final int width = abilities != null ? abilities.getInt("water-wall-width", 7) : 7;
         final int height = abilities != null ? abilities.getInt("water-wall-height", 4) : 4;
         long duration = abilities != null ? abilities.getLong("water-wall-duration", 80L) : 80L;
         long cooldown = abilities != null ? abilities.getLong("water-wall-cooldown", 160L) : 160L;
         final double push = abilities != null ? abilities.getDouble("water-wall-push", 0.9) : 0.9;
         if (this.plugin.getCooldownManager().tryUseWithItemFeedback(player, "atlantis-water-wall", cooldown)) {
            final Vector facing = player.getLocation().getDirection().setY(0.0).normalize();
            Vector perpendicular = new Vector(-facing.getZ(), 0.0, facing.getX()).normalize();
            Block targetBlock = player.getTargetBlockExact(12);
            final Location anchor;
            if (targetBlock != null && !targetBlock.getType().isAir()) {
               anchor = targetBlock.getLocation().add(0.5, 1.0, 0.5);
            } else {
               Location ahead = player.getLocation().add(facing.clone().multiply(3.0));
               ahead.setY(player.getLocation().getY());
               anchor = ahead;
            }

            this.createWaterWallEffects(anchor, facing);
            final List<Block> waterBlocks = new ArrayList<>();
            int half = Math.max(0, width / 2);

            for (int w = -half; w <= half; w++) {
               double edge = (double)Math.abs(w) / Math.max(1, half);
               int columnHeight = Math.max(1, (int)Math.round(height - edge * (height / 3.0)));
               Vector bow = facing.clone().multiply(edge * 1.2);

               for (int h = 0; h < columnHeight; h++) {
                  Location blockLoc = anchor.clone().add(perpendicular.clone().multiply(w)).add(bow).add(0.0, h, 0.0);
                  Block block = blockLoc.getBlock();
                  if (block.getType().isAir() && this.plugin.getProtectionManager().canBuild(player, blockLoc)) {
                     LostTridentListener.this.wallWater.add(block);
                     block.setType(Material.WATER);
                     waterBlocks.add(block);
                  }
               }
            }

            this.pushEntitiesFromWall(anchor, facing, width, height, push, player);
            this.createOngoingWallEffects(anchor, width, height, perpendicular, duration);
            long pushInterval = 10L;
            final int pushes = (int)Math.max(1L, duration / 10L);
            (new BukkitRunnable() {
               int done = 0;

               public void run() {
                  if (this.done++ >= pushes) {
                     this.cancel();
                  } else {
                     LostTridentListener.this.pushEntitiesFromWall(anchor, facing, width, height, push * 0.5, player);
                  }
               }
            }).runTaskTimer(this.plugin, 10L, 10L);
            (new BukkitRunnable() {
               public void run() {
                  for (Block block : waterBlocks) {
                     LostTridentListener.this.wallWater.remove(block);
                     if (block.getType() == Material.WATER) {
                        ParticleManager.spawn(Particle.CLOUD, block.getLocation().add(0.5, 0.5, 0.5), 2, 0.2, 0.2, 0.2, 0.05);
                        block.setType(Material.AIR);
                     }
                  }
               }
            }).runTaskLater(this.plugin, duration);
            this.plugin.getSoundManager().world(anchor, Sound.ITEM_BUCKET_EMPTY, 0.8F, 0.8F);
            ParticleManager.spawn(Particle.SWEEP_ATTACK, anchor, 10, 2.0, 2.0, 2.0, 0.1);
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onWallFlow(BlockFromToEvent event) {
      if (this.wallWater.contains(event.getBlock())) {
         event.setCancelled(true);
      }
   }

   /** Plugin disable: the removal tasks will never run, so take every live wall down now. */
   public void cleanup() {
      for (Block block : this.wallWater) {
         if (block.getType() == Material.WATER) {
            block.setType(Material.AIR);
         }
      }

      this.wallWater.clear();
   }

   private void pushEntitiesFromWall(Location anchor, Vector facing, int width, int height, double push, Player owner) {
      double reach = Math.max(2.0, width / 2.0 + 1.5);

      for (Entity entity : anchor.getWorld().getNearbyEntities(anchor, reach, height, reach)) {
         if (entity instanceof LivingEntity && this.plugin.getProtectionManager().canAffect(owner, entity)) {
            Vector offset = entity.getLocation().toVector().subtract(anchor.toVector());
            double side = offset.dot(facing);
            Vector direction = facing.clone().multiply(side >= 0.0 ? 1.0 : -1.0);
            entity.setVelocity(entity.getVelocity().add(direction.multiply(push)).setY(Math.max(entity.getVelocity().getY(), 0.35)));
            ParticleManager.spawn(Particle.SPLASH, entity.getLocation(), 8, 0.4, 0.5, 0.4, 0.1);
         }
      }
   }

   private void createWaterWallEffects(final Location location, Vector direction) {
      final World world = location.getWorld();

      for (int i = 0; i < 3; i++) {
         final int layer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 2.0 + layer * 1.0;
               ParticleManager.spawn(Particle.DRIPPING_WATER, location, 30, radius, 1.0, radius, 0.2);
               LostTridentListener.this.createEnergyRing(location, radius, Color.BLUE);
            }
         }).runTaskLater(this.plugin, i * 5L);
      }

      this.plugin.getSoundManager().world(location, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7F, 1.1F);
   }

   private void createOngoingWallEffects(final Location location, final int width, final int height, final Vector perpendicular, final long duration) {
      final World world = location.getWorld();
      BukkitRunnable wallEffectTask = new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            if (this.ticks * 2 >= duration) {
               this.cancel();
            } else {
               for (int w = -width / 2; w <= width / 2; w++) {
                  for (int h = 0; h < height; h++) {
                     Location effectLoc = location.clone().add(perpendicular.clone().multiply(w)).add(0.0, h, 0.0);
                     ParticleManager.spawn(Particle.DRIPPING_WATER, effectLoc, 1, 0.3, 0.1, 0.3, 0.0);
                     if (this.ticks % 10 == 0) {
                        ParticleManager.spawn(Particle.BUBBLE_POP, effectLoc, 1, 0.2, 0.2, 0.2, 0.0);
                     }
                  }
               }

               if (this.ticks % 5 == 0) {
                  LostTridentListener.this.createWallOutline(location, width, height, perpendicular);
               }

               this.ticks++;
            }
         }
      };
      wallEffectTask.runTaskTimer(this.plugin, 5L, 5L);
   }

   private void createWallOutline(Location location, int width, int height, Vector perpendicular) {
      World world = location.getWorld();

      for (int w = -width / 2; w <= width / 2; w++) {
         Location bottom = location.clone().add(perpendicular.clone().multiply(w));
         Location top = bottom.clone().add(0.0, height, 0.0);
         ParticleManager.spawn(Particle.DUST, bottom, 1, 0.1, 0.1, 0.1, 0.0, new DustOptions(Color.fromRGB(0, 100, 200), 1.0F));
         ParticleManager.spawn(Particle.DUST, top, 1, 0.1, 0.1, 0.1, 0.0, new DustOptions(Color.fromRGB(0, 100, 200), 1.0F));
      }
   }

   private void createEpicWaterPulse(final Location location, final Player shooter) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("lost-trident-of-atlantis");
      final double radius = abilities != null ? abilities.getDouble("pulse-radius", 4.0) : 4.0;
      final double knockback = abilities != null ? abilities.getDouble("water-pulse-knockback", 1.5) : 1.5;
      final double pulseDamage = abilities != null ? abilities.getDouble("pulse-damage", 6.0) : 6.0;
      this.createPrePulseEffects(location);
      (new BukkitRunnable() {
         public void run() {
            ParticleManager.spawn(Particle.SPLASH, location, 200, radius, radius / 2.0, radius, 0.5);
            ParticleManager.spawn(Particle.DRIPPING_WATER, location, 100, radius, radius / 2.0, radius, 0.3);

            for (double r = 1.0; r <= radius; r++) {
               LostTridentListener.this.createEnergyRing(location, r, Color.AQUA);
            }

            ParticleManager.spawn(Particle.BUBBLE_POP, location, 50, radius, radius / 2.0, radius, 0.2);
            ParticleManager.spawn(Particle.BUBBLE_COLUMN_UP, location, 30, radius, radius / 2.0, radius, 0.3);
            LostTridentListener.this.plugin.getSoundManager().world(location, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.9F, 0.8F);

            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
               if (entity instanceof LivingEntity living && !entity.equals(shooter) && (shooter == null || LostTridentListener.this.plugin.getProtectionManager().canAffect(shooter, entity))) {
                  if (pulseDamage > 0.0) {
                     if (shooter != null) {
                        living.damage(pulseDamage, shooter);
                     } else {
                        living.damage(pulseDamage);
                     }
                  }

                  Vector offset = entity.getLocation().toVector().subtract(location.toVector());
                  Vector direction = offset.lengthSquared() < 1.0E-4 ? new Vector(0.0, 1.0, 0.0) : offset.normalize();
                  entity.setVelocity(direction.multiply(knockback).setY(knockback * 0.5));
                  ParticleManager.spawn(Particle.SPLASH, entity.getLocation(), 10, 0.5, 0.5, 0.5, 0.1);
               }
            }

            LostTridentListener.this.createLingeringPulseEffects(location, radius);
         }
      }).runTaskLater(this.plugin, 3L);
   }

   private void createPrePulseEffects(final Location location) {
      final World world = location.getWorld();

      for (int i = 3; i > 0; i--) {
         final int size = i;
         (new BukkitRunnable() {
            public void run() {
               LostTridentListener.this.createEnergyRing(location, (double)size, Color.BLUE);
               LostTridentListener.this.plugin.getSoundManager().ambient(location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5F, 0.5F + size * 0.2F);
            }
         }).runTaskLater(this.plugin, (3 - i) * 2L);
      }

      for (int i = 0; i < 3; i++) {
         final int intensity = i;
         (new BukkitRunnable() {
            public void run() {
               ParticleManager.spawn(Particle.NAUTILUS, location, 12, 0.6, 0.6, 0.6, 0.4);
               ParticleManager.spawn(Particle.END_ROD, location, 5 + intensity * 3, 0.3, 0.3, 0.3, 0.1);
            }
         }).runTaskLater(this.plugin, i * 1L);
      }
   }

   private void createLingeringPulseEffects(final Location location, final double radius) {
      final World world = location.getWorld();

      for (int i = 0; i < 5; i++) {
         (new BukkitRunnable() {
            public void run() {
               for (int j = 0; j < 3; j++) {
                  Vector offset = new Vector((Math.random() - 0.5) * radius * 2.0, Math.random() * radius, (Math.random() - 0.5) * radius * 2.0);
                  Location dripLoc = location.clone().add(offset);
                  ParticleManager.spawn(Particle.DRIPPING_WATER, dripLoc, 1, 0.0, 0.0, 0.0, 0.0);
               }
            }
         }).runTaskLater(this.plugin, 10L + i * 5L);
      }
   }
}
