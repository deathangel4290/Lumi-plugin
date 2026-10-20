package com.lumiweapons.listeners;

import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.WeaponAbility;
import com.lumiweapons.managers.WeaponManager;
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
import org.bukkit.Particle.DustTransition;
import com.lumiweapons.managers.AnimationFx;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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
   private static final String THROW_COOLDOWN_KEY = "atlantis-throw";
   private final LumiWeapons plugin;
   private final WeaponManager weaponManager;
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
      return com.lumiweapons.managers.HudManager.cooldown(player, "Throw", THROW_COOLDOWN_KEY)
         .append(net.kyori.adventure.text.Component.text("   "))
         .append(com.lumiweapons.managers.HudManager.cooldown(player, "Water Wall", WALL_COOLDOWN_KEY));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      this.triggerAbility(player, secondary);
      return true;
   }

   public LostTridentListener(LumiWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.tridentKey = LumiWeapons.key("lost-trident");
   }

   public void triggerAbility(Player player) {
      this.triggerAbility(player, player.isSneaking());
   }

   public void triggerAbility(Player player, boolean secondary) {
      if (secondary) {
         this.createWaterWall(player);
      } else {
         com.lumiweapons.managers.HudManager.message(player, Component.text("Sneak to raise a water wall.", NamedTextColor.AQUA));
      }
   }

   @EventHandler
   public void onProjectileLaunch(ProjectileLaunchEvent event) {
      if (event.getEntity() instanceof Trident && event.getEntity().getShooter() instanceof Player) {
         Player player = (Player)event.getEntity().getShooter();
         ItemStack item = player.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(item) && "lost-trident-of-atlantis".equals(this.weaponManager.getWeaponKey(item))) {
            var cooldowns = this.plugin.getCooldownManager();
            if (cooldowns.isOnCooldown(player, THROW_COOLDOWN_KEY)) {
               // the item cooldown normally stops the throw before it starts; this catches the rest
               event.setCancelled(true);
               cooldowns.notifyOnCooldown(player, THROW_COOLDOWN_KEY);
               return;
            }

            ConfigurationSection settings = this.plugin.getConfigManager().getWeaponAbilities(WEAPON_KEY);
            long throwCooldown = settings == null ? 80L : settings.getLong("throw-cooldown", 80L);
            // greys the trident out until it can be thrown again (it comes back on its own)
            cooldowns.setWithItemFeedback(player, THROW_COOLDOWN_KEY, throwCooldown, item);
            Trident trident = (Trident)event.getEntity();
            // Keep the player's own trident (their enchantments, their durability); only make sure it
            // comes back. It used to be swapped for a fresh template, wiping anything added to it.
            ItemStack tridentItem = trident.getItemStack().clone();
            ItemMeta meta = tridentItem.getItemMeta();
            if (meta != null && meta.getEnchantLevel(Enchantment.LOYALTY) < 3) {
               meta.addEnchant(Enchantment.LOYALTY, 3, true);
               tridentItem.setItemMeta(meta);
               trident.setItemStack(tridentItem);
            }

            trident.getPersistentDataContainer().set(this.tridentKey, PersistentDataType.BYTE, (byte)1);
            this.flyModel(trident, tridentItem);
            this.throwBurst(player, trident.getVelocity());
         }
      }
   }

   @EventHandler
   public void onProjectileHit(ProjectileHitEvent event) {
      if (event.getEntity() instanceof Trident trident && trident.getPersistentDataContainer().has(this.tridentKey, PersistentDataType.BYTE)) {
         Player shooter = trident.getShooter() instanceof Player p ? p : null;
         this.createEpicWaterPulse(trident.getLocation(), shooter);
      }
   }

   /**
    * A thrown trident is drawn with vanilla's fixed trident model whatever the item is, so hide the
    * entity and fly the Atlantis model on it (out, stuck, and all the way home on loyalty). The model
    * points along its flight, rolls about its shaft while it moves, and leaves a thin twin-strand
    * wake of water: readable from a distance, never a wall of particles in anyone's face.
    */
   private void flyModel(final Trident trident, ItemStack look) {
      for (Player viewer : trident.getWorld().getPlayers()) {
         viewer.hideEntity(this.plugin, trident);
      }

      final org.bukkit.entity.ItemDisplay display = trident.getWorld().spawn(trident.getLocation(), org.bukkit.entity.ItemDisplay.class, d -> {
         d.setPersistent(false);
         d.setItemStack(look.clone());
         d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
         d.setTeleportDuration(1);
         d.setShadowRadius(0.0F);
      });
      final DustTransition foam = new DustTransition(Color.fromRGB(120, 235, 255), Color.fromRGB(235, 255, 255), 0.7F);
      final DustOptions deep = new DustOptions(Color.fromRGB(20, 120, 210), 0.7F);
      (new BukkitRunnable() {
         int life = 0;
         float roll = 0.0F;
         org.joml.Quaternionf pointing = new org.joml.Quaternionf();

         public void run() {
            if (!trident.isValid() || !display.isValid() || this.life++ > 1200) {
               if (display.isValid()) {
                  display.remove();
               }

               if (trident.isValid()) {
                  for (Player viewer : trident.getWorld().getPlayers()) {
                     viewer.showEntity(LostTridentListener.this.plugin, trident);
                  }
               }

               this.cancel();
               return;
            }

            Location at = trident.getLocation();
            // the projectile's own yaw/pitch would turn the model a second time on top of `pointing`
            at.setYaw(0.0F);
            at.setPitch(0.0F);
            display.teleport(at);
            Vector v = trident.getVelocity();
            double speed = v.length();
            if (speed > 0.05) {
               this.pointing = new org.joml.Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float)v.getX(), (float)v.getY(), (float)v.getZ());
               this.roll += (float)Math.min(0.6, speed * 0.25);
               org.bukkit.util.Transformation now = display.getTransformation();
               display.setInterpolationDelay(0);
               display.setInterpolationDuration(1);
               display.setTransformation(new org.bukkit.util.Transformation(now.getTranslation(), new org.joml.Quaternionf(this.pointing).rotateY(this.roll), now.getScale(), now.getRightRotation()));
            }

            if (speed > 0.25) {
               LostTridentListener.this.wake(at, v, this.life, foam, deep);
            }
         }
      }).runTaskTimer(this.plugin, 1L, 1L);
   }

   /** Two strands spiralling round the flight path just behind the trident, with the odd drip. */
   private void wake(Location at, Vector velocity, int tick, DustTransition foam, DustOptions deep) {
      Vector dir = velocity.clone().normalize();
      Vector side = Math.abs(dir.getY()) > 0.95 ? new Vector(1, 0, 0) : dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
      Vector up = side.clone().crossProduct(dir).normalize();
      double angle = tick * 0.9;
      double radius = 0.22;

      for (int strand = 0; strand < 2; strand++) {
         double a = angle + strand * Math.PI;
         Vector ring = side.clone().multiply(Math.cos(a) * radius).add(up.clone().multiply(Math.sin(a) * radius));
         Location point = at.clone().subtract(dir.clone().multiply(0.45)).add(ring);
         if (strand == 0) {
            ParticleManager.spawn(Particle.DUST_COLOR_TRANSITION, point, 1, 0.0, 0.0, 0.0, 0.0, foam);
         } else {
            ParticleManager.spawn(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, deep);
         }
      }

      if (tick % 2 == 0) {
         ParticleManager.spawn(Particle.FALLING_WATER, at.clone().subtract(dir.clone().multiply(0.8)), 1, 0.05, 0.05, 0.05, 0.0);
      }

      if (tick % 5 == 0) {
         ParticleManager.spawn(Particle.BUBBLE_POP, at.clone().subtract(dir.clone().multiply(0.6)), 1, 0.08, 0.08, 0.08, 0.01);
      }
   }

   /** The release: a small ring of water snaps off the hand and spray follows the throw. */
   private void throwBurst(Player player, Vector velocity) {
      Vector dir = velocity.clone().normalize();
      Location hand = player.getEyeLocation().subtract(0.0, 0.35, 0.0).add(dir.clone().multiply(1.1));
      Vector side = Math.abs(dir.getY()) > 0.95 ? new Vector(1, 0, 0) : dir.clone().crossProduct(new Vector(0, 1, 0)).normalize();
      Vector up = side.clone().crossProduct(dir).normalize();
      DustOptions ring = new DustOptions(Color.fromRGB(110, 225, 255), 0.8F);
      com.lumiweapons.managers.SpriteFx.burst(hand, org.bukkit.Color.fromRGB(90, 215, 255), 1.2, 1, 0, 4);

      for (int i = 0; i < 14; i++) {
         double a = Math.PI * 2 * i / 14;
         Location point = hand.clone().add(side.clone().multiply(Math.cos(a) * 0.45)).add(up.clone().multiply(Math.sin(a) * 0.45));
         ParticleManager.spawn(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, ring);
      }

      for (int i = 0; i < 6; i++) {
         ParticleManager.spawn(Particle.SPLASH, hand.clone().add(dir.clone().multiply(i * 0.35)), 2, 0.08, 0.08, 0.08, 0.05);
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8F, 0.9F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.25F, 1.6F);
   }

   private void createEnergyRing(Location center, double radius, Color color) {
      int points = (int)Math.max(12, Math.min(28, radius * 7));

      for (int i = 0; i < points; i++) {
         double angle = Math.PI * 2 * i / points;
         Location ringPoint = center.clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius);
         ParticleManager.spawn(Particle.DUST, ringPoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(color, 1.2F));
      }
   }

   /**
    * Sneak + ability: a curved wall of water rises in front of you, row by row. While it stands it
    * shoves people clear, slows enemies wading through it, and stops enemy arrows, tridents and
    * pearls dead. You and trusted players shoot straight through, and you get Dolphin's Grace near it.
    * When it ends it crashes forward as a wave.
    */
   private void createWaterWall(final Player player) {
      if (player.getWorld().getEnvironment() == Environment.NETHER) {
         com.lumiweapons.managers.HudManager.message(player, Component.text("The water boils away here.", NamedTextColor.RED));
         this.plugin.getSoundManager().self(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.6F, 1.4F);
      } else {
         ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("lost-trident-of-atlantis");
         final int width = abilities.getInt("water-wall-width", 7);
         final int height = abilities.getInt("water-wall-height", 4);
         final long duration = abilities.getLong("water-wall-duration", 100L);
         long cooldown = abilities.getLong("water-wall-cooldown", 160L);
         final double push = abilities.getDouble("water-wall-push", 0.9);
         final boolean blockProjectiles = abilities.getBoolean("water-wall-blocks-projectiles", true);
         final int slowTicks = abilities.getInt("water-wall-slowness", 30);
         final double crashDamage = abilities.getDouble("water-wall-crash-damage", 4.0);
         final double crashRange = abilities.getDouble("water-wall-crash-range", 5.0);
         // no item cooldown here: greying the trident out would stop it being thrown during the wall
         if (this.plugin.getCooldownManager().tryUse(player, "atlantis-water-wall", cooldown)) {
            Vector flat = player.getLocation().getDirection().setY(0.0);
            if (flat.lengthSquared() < 1.0E-4) {
               double yaw = Math.toRadians(player.getLocation().getYaw());       // looking straight down
               flat = new Vector(-Math.sin(yaw), 0.0, Math.cos(yaw));
            }

            final Vector facing = flat.normalize();
            final Vector perpendicular = new Vector(-facing.getZ(), 0.0, facing.getX()).normalize();
            Block targetBlock = player.getTargetBlockExact(12);
            final Location anchor;
            if (targetBlock != null && !targetBlock.getType().isAir()) {
               anchor = targetBlock.getLocation().add(0.5, 1.0, 0.5);
            } else {
               Location ahead = player.getLocation().add(facing.clone().multiply(3.0));
               ahead.setY(player.getLocation().getY());
               anchor = ahead;
            }

            final Set<Block> wall = new HashSet<>();
            final int half = Math.max(0, width / 2);
            // rise: one row every 2 ticks, bottom first
            for (int h = 0; h < height; h++) {
               final int row = h;
               this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
                  for (int w = -half; w <= half; w++) {
                     double edge = (double)Math.abs(w) / Math.max(1, half);
                     int columnHeight = Math.max(1, (int)Math.round(height - edge * (height / 3.0)));
                     if (row >= columnHeight) {
                        continue;
                     }

                     Location blockLoc = anchor.clone().add(perpendicular.clone().multiply(w)).add(facing.clone().multiply(edge * 1.2)).add(0.0, row, 0.0);
                     Block block = blockLoc.getBlock();
                     if (block.getType().isAir() && this.plugin.getProtectionManager().canBuild(player, blockLoc)) {
                        this.wallWater.add(block);
                        wall.add(block);
                        block.setType(Material.WATER);
                        if (w % 2 == 0) {
                           ParticleManager.spawn(Particle.SPLASH, blockLoc.clone().add(0.5, 1.0, 0.5), 3, 0.3, 0.05, 0.3, 0.05);
                        }
                     }
                  }

                  this.plugin.getSoundManager().world(anchor, Sound.ITEM_BUCKET_EMPTY, 0.5F, 0.8F + row * 0.15F);
               }, row * 2L);
            }

            this.createWaterWallEffects(anchor);
            this.pushEntitiesFromWall(anchor, facing, width, height, push, player);
            (new BukkitRunnable() {
               int tick = 0;

               public void run() {
                  if (this.tick++ >= duration) {
                     this.cancel();
                     LostTridentListener.this.crash(player, wall, anchor, facing, perpendicular, width, crashRange, crashDamage);
                     return;
                  }

                  if (blockProjectiles) {
                     LostTridentListener.this.stopProjectiles(player, wall, anchor, width, height);
                  }

                  if (this.tick % 10 == 0) {
                     LostTridentListener.this.pushEntitiesFromWall(anchor, facing, width, height, push * 0.5, player);
                     LostTridentListener.this.wade(player, wall, anchor, width, height, slowTicks);
                  }

                  if (this.tick % 5 == 0) {
                     LostTridentListener.this.shimmer(wall);
                  }
               }
            }).runTaskTimer(this.plugin, 1L, 1L);
         }
      }
   }

   /** Enemy projectiles that would pass through the wall splash to a stop in it. */
   private void stopProjectiles(Player owner, Set<Block> wall, Location anchor, int width, int height) {
      if (wall.isEmpty()) {
         return;
      }

      double reach = width / 2.0 + 6.0;
      for (Entity entity : anchor.getWorld().getNearbyEntities(anchor.clone().add(0.0, height / 2.0, 0.0), reach, height + 4.0, reach)) {
         if (!(entity instanceof org.bukkit.entity.Projectile projectile) || entity.isOnGround()) {
            continue;
         }

         if (projectile.getShooter() instanceof Entity source && (source.equals(owner) || !this.plugin.getProtectionManager().canAffect(owner, source))) {
            continue;                                                          // yours or a trusted player's
         }

         Vector v = projectile.getVelocity();
         double length = v.length();
         if (length < 0.05) {
            continue;
         }

         Location from = projectile.getLocation();
         Vector step = v.clone().multiply(0.25 / length);
         int steps = (int)Math.ceil(length / 0.25) + 1;
         for (int i = 0; i <= steps; i++) {
            Location probe = from.clone().add(step.clone().multiply(i));
            if (wall.contains(probe.getBlock())) {
               projectile.teleport(probe.clone().subtract(step));
               projectile.setVelocity(v.clone().multiply(-0.05).setY(-0.1));
               ParticleManager.spawn(Particle.SPLASH, probe, 12, 0.25, 0.25, 0.25, 0.1);
               this.plugin.getSoundManager().world(probe, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.6F, 1.3F);
               break;
            }
         }
      }
   }

   /** Enemies inside the wall are slowed; the owner swims fast near it. */
   private void wade(Player owner, Set<Block> wall, Location anchor, int width, int height, int slowTicks) {
      double reach = width / 2.0 + 2.0;
      for (Entity entity : anchor.getWorld().getNearbyEntities(anchor, reach, height + 1.0, reach)) {
         if (!(entity instanceof LivingEntity living)) {
            continue;
         }

         if (entity.equals(owner)) {
            owner.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 30, 0, true, false, true));
         } else if (slowTicks > 0 && this.plugin.getProtectionManager().canAffect(owner, entity)
               && (wall.contains(living.getLocation().getBlock()) || wall.contains(living.getEyeLocation().getBlock()))) {
            living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 1, true, true, true));
         }
      }
   }

   private void shimmer(Set<Block> wall) {
      int n = 0;
      for (Block block : wall) {
         if (n++ % 3 == 0) {
            ParticleManager.spawn(Particle.BUBBLE_POP, block.getLocation().add(0.5, 0.5, 0.5), 1, 0.3, 0.3, 0.3, 0.0);
         }
      }
   }

   /** The wall's end: the water drains and a low wave rolls forward, away from the caster. */
   private void crash(final Player owner, Set<Block> wall, final Location anchor, final Vector facing, final Vector perpendicular, int width, final double range, final double damage) {
      for (Block block : wall) {
         this.wallWater.remove(block);
         if (block.getType() == Material.WATER) {
            block.setType(Material.AIR);
         }
      }

      if (!owner.isOnline()) {
         return;
      }

      final double halfWidth = width / 2.0 + 0.5;
      final Set<UUID> hit = new HashSet<>();
      this.plugin.getSoundManager().world(anchor, Sound.ITEM_TRIDENT_RIPTIDE_2, 0.7F, 0.9F);
      com.lumiweapons.managers.SpriteFx.burst(anchor.clone().add(facing.clone().multiply(1.0)).add(0.0, 1.2, 0.0), org.bukkit.Color.fromRGB(90, 215, 255), 2.8, 2, 1, 6);
      (new BukkitRunnable() {
         int step = 0;

         public void run() {
            if (this.step >= 5) {
               this.cancel();
               return;
            }

            double front = range * (this.step + 1) / 5.0;
            for (double w = -halfWidth; w <= halfWidth; w += 0.7) {
               Location crest = anchor.clone().add(facing.clone().multiply(front)).add(perpendicular.clone().multiply(w));
               ParticleManager.spawn(Particle.SPLASH, crest.clone().add(0.0, 0.4, 0.0), 2, 0.2, 0.2, 0.2, 0.05);
               if (this.step % 2 == 0) {
                  ParticleManager.spawn(Particle.FALLING_WATER, crest.clone().add(0.0, 0.9, 0.0), 1, 0.2, 0.1, 0.2, 0.0);
               }
            }

            for (Entity entity : anchor.getWorld().getNearbyEntities(anchor, range + halfWidth, 3.0, range + halfWidth)) {
               if (!(entity instanceof LivingEntity living) || entity.equals(owner) || hit.contains(entity.getUniqueId())
                     || !LostTridentListener.this.plugin.getProtectionManager().canAffect(owner, entity)) {
                  continue;
               }

               Vector offset = entity.getLocation().toVector().subtract(anchor.toVector());
               double along = offset.dot(facing);
               if (along < -0.5 || along > front + 0.8 || Math.abs(offset.dot(perpendicular)) > halfWidth) {
                  continue;
               }

               hit.add(entity.getUniqueId());
               if (damage > 0.0) {
                  com.lumiweapons.abilities.AbilityDamage.hit(owner, living, damage, 0.0, anchor, "{victim} was swept away by {killer}'s Water Wall");
               }

               entity.setVelocity(facing.clone().multiply(1.1).setY(0.35));
               ParticleManager.spawn(Particle.SPLASH, entity.getLocation().add(0.0, 1.0, 0.0), 10, 0.3, 0.4, 0.3, 0.1);
            }

            this.step++;
         }
      }).runTaskTimer(this.plugin, 0L, 1L);
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

   private void createWaterWallEffects(final Location location) {
      if (!com.lumiweapons.managers.SpriteFx.wave(location, org.bukkit.Color.fromRGB(90, 215, 255), 3.2, 7)) {
         AnimationFx.ring(location, Material.LIGHT_BLUE_STAINED_GLASS, 0.6, 3.0, 8, false);
      }

      this.plugin.getSoundManager().world(location, Sound.ITEM_TRIDENT_RIPTIDE_1, 0.7F, 1.1F);
   }

   /** Impact: a tight splash crown and a ring of water running out along the ground. */
   private void createEpicWaterPulse(final Location location, final Player shooter) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("lost-trident-of-atlantis");
      final double radius = abilities.getDouble("pulse-radius", 4.0);
      final double knockback = abilities.getDouble("water-pulse-knockback", 1.5);
      final double pulseDamage = abilities.getDouble("pulse-damage", 6.0);
      ParticleManager.spawn(Particle.NAUTILUS, location, 10, 0.4, 0.4, 0.4, 0.3);
      this.plugin.getSoundManager().ambient(location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5F, 1.2F);
      (new BukkitRunnable() {
         public void run() {
            Location ground = location.clone();
            if (!com.lumiweapons.managers.SpriteFx.wave(ground, org.bukkit.Color.fromRGB(90, 215, 255), radius, 7)) {
               AnimationFx.ring(ground, Material.LIGHT_BLUE_STAINED_GLASS, 0.5, radius, 8, false);
            }

            for (int i = 0; i < 16; i++) {                                     // crown
               double a = Math.PI * 2 * i / 16;
               Location lip = location.clone().add(Math.cos(a) * 0.7, 0.2, Math.sin(a) * 0.7);
               ParticleManager.spawn(Particle.SPLASH, lip, 3, 0.05, 0.3, 0.05, 0.15);
            }

            LostTridentListener.this.createEnergyRing(location, radius * 0.5, Color.fromRGB(90, 210, 255));
            LostTridentListener.this.createEnergyRing(location, radius, Color.fromRGB(30, 130, 220));
            ParticleManager.spawn(Particle.BUBBLE_COLUMN_UP, location, 12, 0.6, 0.3, 0.6, 0.2);
            LostTridentListener.this.plugin.getSoundManager().world(location, Sound.ENTITY_FISHING_BOBBER_SPLASH, 0.9F, 0.8F);

            for (Entity entity : location.getWorld().getNearbyEntities(location, radius, radius, radius)) {
               if (entity instanceof LivingEntity living && !entity.equals(shooter) && (shooter == null || LostTridentListener.this.plugin.getProtectionManager().canAffect(shooter, entity))) {
                  if (entity.getLocation().distanceSquared(location) > radius * radius) {
                     continue;
                  }

                  if (pulseDamage > 0.0) {
                     com.lumiweapons.abilities.AbilityDamage.hit(shooter, living, pulseDamage, 0.0, location, "{victim} was dragged under by {killer}'s Lost Trident");
                  }

                  Vector offset = entity.getLocation().toVector().subtract(location.toVector());
                  Vector direction = offset.lengthSquared() < 1.0E-4 ? new Vector(0.0, 1.0, 0.0) : offset.normalize();
                  entity.setVelocity(direction.multiply(knockback).setY(knockback * 0.5));
                  ParticleManager.spawn(Particle.SPLASH, entity.getLocation(), 10, 0.5, 0.5, 0.5, 0.1);
               }
            }
         }
      }).runTaskLater(this.plugin, 2L);
   }
}
