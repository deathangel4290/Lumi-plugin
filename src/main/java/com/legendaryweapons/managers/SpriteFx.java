package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Textured ability effects: a sprite from the resource pack (assets/luminara/items/vfx) on an item
 * display that the client animates. Kept for the signature moments only -- one per weapon -- so a
 * fight never turns into a wall of effects. Everything else stays particles and display blocks.
 *
 * Same PvP rules as AnimationFx: short (well under a second unless it is a telegraph), no collision,
 * hidden from players on /lwfx minimal or off. effects.sprites (or effects.animations) turns them
 * off, and every caller falls back to its old effect when a method returns false.
 */
public final class SpriteFx {
   public enum Sprite {
      SLASH_RED,
      SLASH_LIGHT,
      SLASH_DARK,
      FLASH,
      SHOCKWAVE_GOLD,
      SHOCKWAVE_MAGMA,
      RUNE_GOLD,
      ECLIPSE;

      final NamespacedKey model = new NamespacedKey("luminara", "vfx/" + this.name().toLowerCase(Locale.ROOT));
   }

   private static LegendaryWeapons plugin;
   private static final Map<Sprite, ItemStack> ITEMS = new EnumMap<>(Sprite.class);
   private static final Set<ItemDisplay> LIVE = new HashSet<>();

   private SpriteFx() {
   }

   public static void init(LegendaryWeapons owner) {
      plugin = owner;
   }

   /** Removes anything still on screen (plugin disable). The displays are not saved either way. */
   public static void shutdown() {
      for (ItemDisplay display : LIVE) {
         if (display.isValid()) {
            display.remove();
         }
      }

      LIVE.clear();
   }

   public static boolean enabled() {
      return plugin != null
         && plugin.getConfig().getBoolean("effects.animations", true)
         && plugin.getConfig().getBoolean("effects.sprites", true);
   }

   private static ItemStack item(Sprite sprite) {
      return ITEMS.computeIfAbsent(sprite, s -> {
         ItemStack stack = new ItemStack(Material.PAPER);
         ItemMeta meta = stack.getItemMeta();
         meta.setItemModel(s.model);
         stack.setItemMeta(meta);
         return stack;
      }).clone();
   }

   private static Transformation shape(Quaternionf rotation, float width, float height, float depth) {
      return new Transformation(new Vector3f(), rotation, new Vector3f(width, height, depth), new Quaternionf());
   }

   private static Transformation shape(Quaternionf rotation, double size) {
      return shape(rotation, (float)size, (float)size, (float)size);
   }

   private static ItemDisplay spawn(Location at, Sprite sprite, Display.Billboard billboard, Transformation start) {
      return spawn(at, item(sprite), billboard, start);
   }

   private static ItemDisplay spawn(Location at, ItemStack shown, Display.Billboard billboard, Transformation start) {
      World world = at.getWorld();
      Location place = at.clone();
      place.setYaw(0.0F);
      place.setPitch(0.0F);
      ItemDisplay spawned = world.spawn(place, ItemDisplay.class, display -> {
         display.setPersistent(false);
         display.setItemStack(shown);
         display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
         display.setBillboard(billboard);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setShadowRadius(0.0F);
         display.setViewRange(0.75F);
         display.setTransformation(start);
         ParticleManager particles = ParticleManager.get();
         if (particles != null) {
            for (Player viewer : world.getPlayers()) {
               ParticleManager.Level level = particles.levelOf(viewer);
               if (level == ParticleManager.Level.OFF || level == ParticleManager.Level.MINIMAL) {
                  viewer.hideEntity(plugin, display);
               }
            }
         }
      });
      LIVE.add(spawned);
      return spawned;
   }

   private static void morph(ItemDisplay display, long at, int ticks, Transformation to) {
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         if (display.isValid()) {
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(ticks);
            display.setTransformation(to);
         }
      }, Math.max(1L, at));
   }

   private static void removeAt(ItemDisplay display, long at) {
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         LIVE.remove(display);
         if (display.isValid()) {
            display.remove();
         }
      }, Math.max(1L, at));
   }

   /**
    * Yaw that points the sprite's bulge (+x in the texture) along a horizontal direction. Item
    * displays render their item turned half a circle, so the model's -x is what ends up in front.
    */
   private static float yawToward(Vector direction) {
      float yaw = (float)Math.atan2(direction.getZ(), -direction.getX());
      return plugin.getConfig().getBoolean("effects.sprite-flip", false) ? yaw + (float)Math.PI : yaw;
   }

   /**
    * A crescent swept across the player's front at chest height. `roll` tilts it about the facing
    * direction (positive and negative give forehand and backhand); `size` is its width in blocks.
    */
   public static boolean slash(Player player, Sprite sprite, double size, double roll) {
      if (!enabled() || player.getWorld() == null) {
         return false;
      }

      Vector forward = player.getLocation().getDirection().setY(0.0);
      if (forward.lengthSquared() < 1.0E-4) {
         return false;
      }

      forward.normalize();
      Location centre = player.getLocation().add(0.0, 1.05, 0.0).add(forward.clone().multiply(0.15));
      float yaw = yawToward(forward);
      float sweep = roll >= 0.0 ? 0.6F : -0.6F;
      float tilt = (float)roll;
      ItemDisplay display = spawn(centre, sprite, Display.Billboard.FIXED,
         shape(new Quaternionf().rotationY(yaw + sweep).rotateX(tilt), size * 0.7));
      morph(display, 2L, 3, shape(new Quaternionf().rotationY(yaw - sweep * 0.25F).rotateX(tilt), size));
      morph(display, 5L, 3, shape(new Quaternionf().rotationY(yaw - sweep).rotateX(tilt), size * 0.8));
      removeAt(display, 8L);
      return true;
   }

   /** A flat ring that races out across the ground from the centre to `radius`. */
   public static boolean wave(Location ground, Sprite sprite, double radius, int ticks) {
      if (!enabled() || ground.getWorld() == null) {
         return false;
      }

      Location at = ground.clone().add(0.0, 0.08, 0.0);
      ItemDisplay display = spawn(at, sprite, Display.Billboard.FIXED, shape(new Quaternionf(), 0.8));
      int grow = Math.max(2, ticks);
      morph(display, 2L, grow, shape(new Quaternionf().rotationY(0.4F), radius * 2.0));
      morph(display, 2L + grow, 3, shape(new Quaternionf().rotationY(0.5F), (float)(radius * 2.15), 1.0F, (float)(radius * 2.15)));
      removeAt(display, 5L + grow);
      return true;
   }

   /** A ground ring in any colour (the pack's white pulse ring, tinted), racing out to `radius`. */
   public static boolean wave(Location ground, org.bukkit.Color colour, double radius, int ticks) {
      if (!enabled() || ground.getWorld() == null) {
         return false;
      }

      ItemStack ring = com.legendaryweapons.rituals.RitualScene.model("vfx/ritual_pulse", colour);
      Location at = ground.clone().add(0.0, 0.08, 0.0);
      ItemDisplay display = spawn(at, ring, Display.Billboard.FIXED, shape(new Quaternionf(), 0.8));
      int grow = Math.max(2, ticks);
      // the pulse texture's rim sits at 0.9 of its half-width
      double diameter = radius * 2.0 / 0.9;
      morph(display, 2L, grow, shape(new Quaternionf().rotationY(0.4F), diameter));
      morph(display, 2L + grow, 3, shape(new Quaternionf().rotationY(0.5F), (float)(diameter * 1.07), 1.0F, (float)(diameter * 1.07)));
      removeAt(display, 5L + grow);
      return true;
   }

   /** A camera-facing sprite that blooms to `size`, holds, then shrinks away. */
   public static boolean burst(Location at, Sprite sprite, double size, int growTicks, int holdTicks, int fadeTicks) {
      if (!enabled() || at.getWorld() == null) {
         return false;
      }

      ItemDisplay display = spawn(at, sprite, Display.Billboard.CENTER, shape(new Quaternionf(), size * 0.4));
      int grow = Math.max(1, growTicks);
      morph(display, 2L, grow, shape(new Quaternionf(), size));
      morph(display, 2L + grow + Math.max(0, holdTicks), Math.max(1, fadeTicks), shape(new Quaternionf(), size * 0.05));
      removeAt(display, 3L + grow + Math.max(0, holdTicks) + Math.max(1, fadeTicks));
      return true;
   }

   /** A sprite that stays until removed, for charge-ups and telegraphs. Null when sprites are off. */
   public static Held hold(Location at, Sprite sprite, boolean faceCamera, double size, int maxLifeTicks) {
      if (!enabled() || at.getWorld() == null) {
         return null;
      }

      ItemDisplay display = spawn(at, sprite, faceCamera ? Display.Billboard.CENTER : Display.Billboard.FIXED, shape(new Quaternionf(), size));
      display.setTeleportDuration(2);
      Held held = new Held(display, size);
      removeAt(display, Math.max(20, maxLifeTicks));            // safety net if the caller never lets go
      return held;
   }

   public static final class Held {
      private final ItemDisplay display;
      private double size;
      private float angle;

      private Held(ItemDisplay display, double size) {
         this.display = display;
         this.size = size;
      }

      /** Resize and turn about the vertical axis over `ticks`. Keep each turn under half a circle. */
      public void animate(double size, float angle, int ticks) {
         this.size = size;
         this.angle = angle;
         if (this.display.isValid()) {
            this.display.setInterpolationDelay(0);
            this.display.setInterpolationDuration(Math.max(1, ticks));
            this.display.setTransformation(shape(new Quaternionf().rotationY(angle), size));
         }
      }

      public void animate(double size, int ticks) {
         this.animate(size, this.angle, ticks);
      }

      public double size() {
         return this.size;
      }

      public float angle() {
         return this.angle;
      }

      public void follow(Location at) {
         if (this.display.isValid() && at.getWorld() == this.display.getWorld() && this.display.getLocation().distanceSquared(at) > 0.0025) {
            Location place = at.clone();
            place.setYaw(0.0F);
            place.setPitch(0.0F);
            this.display.teleport(place);
         }
      }

      /** Shrink away over `ticks`, then go. */
      public void release(int ticks) {
         if (this.display.isValid()) {
            this.animate(this.size * 0.05, this.angle, ticks);
            removeAt(this.display, ticks + 1L);
         } else {
            LIVE.remove(this.display);
         }
      }

      public void remove() {
         LIVE.remove(this.display);
         if (this.display.isValid()) {
            this.display.remove();
         }
      }
   }
}
