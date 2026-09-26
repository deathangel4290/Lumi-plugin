package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Short ability animations made of block displays. The client interpolates them, so they move
 * smoothly for the cost of two packets each.
 *
 * PvP rules: everything happens at ground level or around the caster's body (never at eye height in
 * front of anyone), lasts well under a second, has no collision, and is hidden from players whose
 * /lwfx level is OFF. effects.animations turns the lot off.
 */
public final class AnimationFx {
   private static LegendaryWeapons plugin;

   private AnimationFx() {
   }

   public static void init(LegendaryWeapons owner) {
      plugin = owner;
   }

   private static boolean enabled() {
      return plugin != null && plugin.getConfig().getBoolean("effects.animations", true);
   }

   private static Transformation shape(Vector3f translation, float yaw, float tilt, Vector3f scale) {
      Quaternionf rotation = new Quaternionf().rotationY(yaw).rotateZ(tilt);
      // centre the unit block on its origin before rotating, so shards spin about their middle
      Vector3f half = new Vector3f(scale).mul(0.5F);
      rotation.transform(half);
      return new Transformation(new Vector3f(translation).sub(half), rotation, scale, new Quaternionf());
   }

   private static BlockDisplay spawn(Location at, BlockData block, Transformation start, boolean glow) {
      World world = at.getWorld();
      return world.spawn(at, BlockDisplay.class, display -> {
         display.setPersistent(false);
         display.setBlock(block);
         display.setTransformation(start);
         display.setShadowRadius(0.0F);
         display.setViewRange(0.75F);
         if (glow) {
            display.setBrightness(new Display.Brightness(15, 15));
         }

         ParticleManager particles = ParticleManager.get();
         for (Player viewer : world.getPlayers()) {
            if (particles != null && particles.levelOf(viewer) == ParticleManager.Level.OFF) {
               viewer.hideEntity(plugin, display);
            }
         }
      });
   }

   /** Move every display to its end shape over `ticks`, then remove it. */
   private static void play(List<BlockDisplay> displays, List<Transformation> ends, int ticks) {
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         for (int i = 0; i < displays.size(); i++) {
            BlockDisplay display = displays.get(i);
            if (display.isValid()) {
               display.setInterpolationDelay(0);
               display.setInterpolationDuration(ticks);
               display.setTransformation(ends.get(i));
            }
         }
      }, 2L);
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         for (BlockDisplay display : displays) {
            if (display.isValid()) {
               display.remove();
            }
         }
      }, ticks + 3L);
   }

   /** Flat shards on the ground moving from r0 to r1 (grow or shrink) while flattening away. */
   public static void ring(Location center, Material material, double r0, double r1, int ticks, boolean glow) {
      if (!enabled() || center.getWorld() == null) return;
      Location at = center.clone();
      at.setPitch(0.0F);
      at.setYaw(0.0F);
      int count = (int)Math.max(10, Math.min(28, Math.max(r0, r1) * 5));
      BlockData data = material.createBlockData();
      List<BlockDisplay> displays = new ArrayList<>();
      List<Transformation> ends = new ArrayList<>();
      float arc = (float)(Math.PI * 2 / count);

      for (int i = 0; i < count; i++) {
         float angle = arc * i;
         float yaw = -angle - (float)Math.PI / 2;                     // lie along the tangent
         float len0 = (float)Math.max(0.15, r0 * arc * 0.8);
         float len1 = (float)Math.max(0.15, r1 * arc * 0.8);
         Vector3f p0 = new Vector3f((float)(Math.cos(angle) * r0), 0.05F, (float)(Math.sin(angle) * r0));
         Vector3f p1 = new Vector3f((float)(Math.cos(angle) * r1), 0.02F, (float)(Math.sin(angle) * r1));
         displays.add(spawn(at, data, shape(p0, yaw, 0.0F, new Vector3f(len0, 0.12F, 0.22F)), glow));
         ends.add(shape(p1, yaw, 0.0F, new Vector3f(len1, 0.01F, 0.08F)));
      }

      play(displays, ends, ticks);
   }

   /** Chunks of ground kicked outward from an impact, tumbling and sinking as they go. */
   public static void shockwave(Location center, Material material, double radius, int ticks) {
      if (!enabled() || center.getWorld() == null) return;
      Location at = center.clone();
      at.setPitch(0.0F);
      at.setYaw(0.0F);
      ThreadLocalRandom random = ThreadLocalRandom.current();
      int count = (int)Math.max(10, Math.min(24, radius * 4));
      BlockData data = material.createBlockData();
      List<BlockDisplay> displays = new ArrayList<>();
      List<Transformation> ends = new ArrayList<>();

      for (int i = 0; i < count; i++) {
         double angle = Math.PI * 2 * i / count + random.nextDouble(-0.15, 0.15);
         float size = (float)random.nextDouble(0.25, 0.45);
         float yaw = (float)random.nextDouble(Math.PI * 2);
         Vector3f p0 = new Vector3f((float)(Math.cos(angle) * 0.6), size * 0.3F, (float)(Math.sin(angle) * 0.6));
         double r = radius * random.nextDouble(0.75, 1.0);
         Vector3f p1 = new Vector3f((float)(Math.cos(angle) * r), -size * 0.2F, (float)(Math.sin(angle) * r));
         displays.add(spawn(at, data, shape(p0, yaw, 0.3F, new Vector3f(size, size * 0.6F, size)), false));
         ends.add(shape(p1, yaw + 1.2F, -0.4F, new Vector3f(size * 0.3F, 0.02F, size * 0.3F)));
      }

      play(displays, ends, ticks);
   }

   /** Shards bursting up out of the ground and sinking back. */
   public static void spikes(Location base, Material material, int count, double spread, double height, int ticks, boolean glow) {
      if (!enabled() || base.getWorld() == null) return;
      Location at = base.clone();
      at.setPitch(0.0F);
      at.setYaw(0.0F);
      ThreadLocalRandom random = ThreadLocalRandom.current();
      BlockData data = material.createBlockData();
      List<BlockDisplay> displays = new ArrayList<>();
      List<Transformation> ups = new ArrayList<>();
      List<Transformation> downs = new ArrayList<>();

      for (int i = 0; i < count; i++) {
         float x = (float)random.nextDouble(-spread, spread);
         float z = (float)random.nextDouble(-spread, spread);
         float yaw = (float)random.nextDouble(Math.PI * 2);
         float tilt = (float)random.nextDouble(-0.35, 0.35);
         float h = (float)(height * random.nextDouble(0.6, 1.0));
         float w = (float)random.nextDouble(0.18, 0.3);
         displays.add(spawn(at, data, shape(new Vector3f(x, -h / 2, z), yaw, tilt, new Vector3f(w, h, w)), glow));
         ups.add(shape(new Vector3f(x, h * 0.35F, z), yaw, tilt, new Vector3f(w, h, w)));
         downs.add(shape(new Vector3f(x, -h / 2, z), yaw, tilt, new Vector3f(w * 0.5F, h * 0.4F, w * 0.5F)));
      }

      int rise = Math.max(2, ticks / 3);
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         for (int i = 0; i < displays.size(); i++) {
            BlockDisplay d = displays.get(i);
            if (d.isValid()) {
               d.setInterpolationDelay(0);
               d.setInterpolationDuration(rise);
               d.setTransformation(ups.get(i));
            }
         }
      }, 2L);
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> play(displays, downs, ticks - rise), 2L + rise + Math.max(1, ticks / 3));
   }

   /** A row of spikes that erupts along a direction, one step after another. */
   public static void spikeLine(Location start, Vector direction, double length, double blocksPerTick, Material material, boolean glow) {
      if (!enabled() || start.getWorld() == null) return;
      Vector step = direction.clone().setY(0.0);
      if (step.lengthSquared() < 1.0E-4) return;
      step.normalize().multiply(1.5);
      int steps = (int)Math.max(1, length / 1.5);

      for (int i = 1; i <= steps; i++) {
         Location at = start.clone().add(step.clone().multiply(i));
         plugin.getServer().getScheduler().runTaskLater(plugin, () -> spikes(at, material, 3, 0.5, 1.1, 12, glow), Math.max(1L, Math.round(i * 1.5 / Math.max(0.1, blocksPerTick))));
      }
   }

   /** A thin crescent of dust sweeping in front of the attacker's chest, never at eye level. */
   public static void slash(Player player, Color from, Color to) {
      if (!enabled()) return;
      Location eye = player.getLocation().add(0.0, 1.1, 0.0);
      Vector forward = player.getLocation().getDirection().setY(0.0);
      if (forward.lengthSquared() < 1.0E-4) return;
      forward.normalize();
      Vector side = new Vector(-forward.getZ(), 0.0, forward.getX());
      Particle.DustTransition dust = new Particle.DustTransition(from, to, 0.9F);

      for (int i = 0; i <= 14; i++) {
         double t = -1.0 + 2.0 * i / 14.0;
         double reach = 1.5 - 0.35 * t * t;
         Location point = eye.clone().add(forward.clone().multiply(reach)).add(side.clone().multiply(t * 1.2)).add(0.0, -t * 0.35, 0.0);
         ParticleManager.spawn(Particle.DUST_COLOR_TRANSITION, point, 1, 0.0, 0.0, 0.0, 0.0, dust);
      }
   }
}
