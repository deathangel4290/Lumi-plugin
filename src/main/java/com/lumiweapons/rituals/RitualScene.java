package com.lumiweapons.rituals;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * What a running ritual looks like: a few large, slow, readable pieces instead of a cloud of
 * particles. All of it is resource-pack models on item displays, coloured per weapon.
 *
 *   - two rune circles on the ground, turning against each other and speeding up as it builds
 *   - four crystal pylons on the diagonals; their beams to the weapon light one per quarter, so
 *     the progress can be read at a glance from across the area
 *   - a beam into the sky over the weapon that thickens with progress (how people find it)
 *   - a thin stream of motes along each lit beam, and a few glyphs drifting in. That is all the
 *     particles there are.
 *   - the finale: the pylons fly into the weapon, the circles wind in, then a flash and a ring.
 */
public final class RitualScene {
   static final double OUTER_RADIUS = 3.3;
   static final double INNER_RADIUS = 1.75;
   static final double WEAPON_HEIGHT = 2.3;                 // above the altar's base, at its centre
   private static final double PYLON_HEIGHT = 1.7;
   private static final float PYLON_SIZE = 1.1F;
   private static final float BEAM_WIDTH = 0.16F;
   private static final float SKY_LENGTH = 48.0F;
   private static final int INTRO_TICKS = 30;
   /** Displays playing an exit animation after their ritual has ended; removed on shutdown. */
   private static final Set<ItemDisplay> LEAVING = new HashSet<>();

   private final LumiWeapons plugin;
   private final World world;
   private final Location centre;                           // altar base, block centre
   private final Location weapon;
   private final Color tint;
   private final Color light;
   private final List<ItemDisplay> owned = new ArrayList<>();
   private ItemDisplay outer;
   private ItemDisplay inner;
   private ItemDisplay sky;
   private final ItemDisplay[] pylons = new ItemDisplay[4];
   private final ItemDisplay[] beams = new ItemDisplay[4];
   private final boolean[] beamShown = new boolean[4];
   private float outerYaw;
   private float innerYaw;
   private float spin;
   private int steadyFrom = -1;
   private boolean finale;

   RitualScene(LumiWeapons plugin, Location block, Color tint) {
      this.plugin = plugin;
      this.world = block.getWorld();
      this.centre = block.clone().add(0.5, 0.0, 0.5);
      this.weapon = this.centre.clone().add(0.0, WEAPON_HEIGHT, 0.0);
      this.tint = tint;
      this.light = mix(tint, Color.WHITE, 0.35);
   }

   // ------------------------------------------------------------------ models

   public static ItemStack model(String id, Color tint) {
      ItemStack stack = new ItemStack(Material.PAPER);
      ItemMeta meta = stack.getItemMeta();
      meta.setItemModel(new NamespacedKey("luminara", id));
      if (tint != null) {
         CustomModelDataComponent data = meta.getCustomModelDataComponent();
         data.setColors(List.of(tint));
         meta.setCustomModelDataComponent(data);
      }

      stack.setItemMeta(meta);
      return stack;
   }

   static Color mix(Color a, Color b, double t) {
      return Color.fromRGB(
         (int)Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
         (int)Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
         (int)Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t)
      );
   }

   private ItemDisplay spawn(Location at, ItemStack item, Transformation start, Display.Billboard billboard, float viewRange) {
      Location place = at.clone();
      place.setYaw(0.0F);
      place.setPitch(0.0F);
      ItemDisplay display = this.world.spawn(place, ItemDisplay.class, d -> {
         d.setPersistent(false);
         d.setItemStack(item);
         d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
         d.setBillboard(billboard);
         d.setBrightness(new Display.Brightness(15, 15));
         d.setShadowRadius(0.0F);
         d.setViewRange(viewRange);
         d.setTransformation(start);
      });
      this.owned.add(display);
      return display;
   }

   private static void move(ItemDisplay display, int ticks, Transformation to) {
      if (display != null && display.isValid()) {
         display.setInterpolationDelay(0);
         display.setInterpolationDuration(Math.max(1, ticks));
         display.setTransformation(to);
      }
   }

   private static Transformation flat(double radius, double textureRadius, float yaw) {
      float d = (float)(2.0 * radius / textureRadius);
      return new Transformation(new Vector3f(), new Quaternionf().rotationY(yaw), new Vector3f(d, 1.0F, d), new Quaternionf());
   }

   private Transformation outerShape(double scale) {
      return flat(OUTER_RADIUS * scale, 0.965, this.outerYaw);
   }

   private Transformation innerShape(double scale) {
      return flat(INNER_RADIUS * scale, 0.95, this.innerYaw);
   }

   private Transformation pylonShape(float rise, float size) {
      return new Transformation(new Vector3f(0.0F, rise, 0.0F), new Quaternionf().rotationY(this.spin), new Vector3f(size), new Quaternionf());
   }

   /** The sky beam grows up from its base: translation and length interpolate together. */
   private static Transformation skyShape(float length, float width) {
      return new Transformation(new Vector3f(0.0F, length / 2.0F, 0.0F), new Quaternionf(), new Vector3f(width, length, width), new Quaternionf());
   }

   private Location pylonAt(int i) {
      double angle = Math.PI / 4.0 + Math.PI / 2.0 * i;
      return this.centre.clone().add(Math.cos(angle) * OUTER_RADIUS, PYLON_HEIGHT, Math.sin(angle) * OUTER_RADIUS);
   }

   private Location beamStart(int i) {
      return this.pylonAt(i).add(0.0, PYLON_SIZE * 0.42, 0.0);
   }

   private Transformation beamShape(int i, float width) {
      Vector span = this.weapon.toVector().subtract(this.beamStart(i).toVector());
      float length = (float)span.length();
      Vector dir = span.clone().normalize();
      Quaternionf aim = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float)dir.getX(), (float)dir.getY(), (float)dir.getZ());
      return new Transformation(new Vector3f(), aim, new Vector3f(width, length, width), new Quaternionf());
   }

   // ------------------------------------------------------------------ lifecycle

   private boolean areaLoaded() {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            int x = (int)Math.floor(this.centre.getX() + dx * (OUTER_RADIUS + 0.5));
            int z = (int)Math.floor(this.centre.getZ() + dz * (OUTER_RADIUS + 0.5));
            if (!this.world.isChunkLoaded(x >> 4, z >> 4)) {
               return false;
            }
         }
      }

      return true;
   }

   boolean intact() {
      if (this.outer == null || !this.outer.isValid() || !this.inner.isValid() || !this.sky.isValid()) {
         return false;
      }

      for (ItemDisplay pylon : this.pylons) {
         if (pylon == null || !pylon.isValid()) {
            return false;
         }
      }

      return true;
   }

   /**
    * Builds the scene if it is missing (first start, or its chunks were unloaded). `intro` plays
    * the opening: circles drawing out, pylons rising, the sky beam climbing. False while the area
    * is not loaded.
    */
   boolean ensure(boolean intro, double progress, int frame) {
      if (this.finale || this.intact()) {
         return true;
      } else if (!this.areaLoaded()) {
         return false;
      }

      this.remove();
      Location ground = this.centre.clone().add(0.0, 0.04, 0.0);
      this.outer = this.spawn(ground, model("vfx/ritual_circle_outer", this.tint), this.outerShape(intro ? 0.15 : 1.0), Display.Billboard.FIXED, 1.5F);
      this.inner = this.spawn(ground.clone().add(0.0, 0.02, 0.0), model("vfx/ritual_circle_inner", this.light), this.innerShape(intro ? 0.15 : 1.0), Display.Billboard.FIXED, 1.5F);

      for (int i = 0; i < 4; i++) {
         this.pylons[i] = this.spawn(this.pylonAt(i), model("ritual_crystal", this.tint), intro ? this.pylonShape(-2.2F, 0.3F) : this.pylonShape(0.0F, PYLON_SIZE), Display.Billboard.FIXED, 1.5F);
         this.beams[i] = null;
         this.beamShown[i] = false;
      }

      float width = this.skyWidth(progress, frame);
      this.sky = this.spawn(this.weapon.clone().add(0.0, 0.5, 0.0), model("vfx/ritual_sky", this.tint), skyShape(intro ? 0.0F : SKY_LENGTH, width), Display.Billboard.FIXED, 4.0F);
      if (intro) {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            move(this.outer, INTRO_TICKS, this.outerShape(1.0));
            move(this.inner, INTRO_TICKS, this.innerShape(1.0));
            move(this.sky, INTRO_TICKS + 10, skyShape(SKY_LENGTH, width));
            for (ItemDisplay pylon : this.pylons) {
               move(pylon, INTRO_TICKS, this.pylonShape(0.0F, PYLON_SIZE));
            }
         }, 2L);
         this.plugin.getSoundManager().world(this.centre, Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 0.7F);
         this.plugin.getSoundManager().world(this.centre, Sound.BLOCK_END_PORTAL_SPAWN, 0.5F, 1.6F);
         this.steadyFrom = frame + (INTRO_TICKS + 4) / 2;
      } else {
         // rebuilt after the chunks reloaded: beams already earned come back lit, without a fanfare
         int lit = Math.min(4, 1 + (int)Math.floor(progress * 4.0));
         for (int i = 0; i < lit; i++) {
            this.beams[i] = this.spawn(this.beamStart(i).add(this.weapon).multiply(0.5), model("vfx/ritual_beam", this.tint), this.beamShape(i, BEAM_WIDTH), Display.Billboard.FIXED, 1.5F);
            this.beamShown[i] = true;
         }

         this.steadyFrom = frame + 1;
      }

      return true;
   }

   private float skyWidth(double progress, int frame) {
      return (float)((0.3 + 0.55 * progress) * (1.0 + 0.06 * Math.sin(frame * 0.4)));
   }

   /** Called every 2 ticks. */
   void tick(double progress, int frame) {
      if (this.finale || this.steadyFrom < 0 || frame < this.steadyFrom) {
         return;
      }

      if (frame % 5 == 0) {
         double speed = 1.0 + progress * 1.5;
         this.outerYaw += (float)(0.10 * speed);
         this.innerYaw -= (float)(0.22 * speed);
         this.spin += 0.35F;
         move(this.outer, 10, this.outerShape(1.0));
         move(this.inner, 10, this.innerShape(1.0));
         move(this.sky, 10, skyShape(SKY_LENGTH, this.skyWidth(progress, frame)));
         for (ItemDisplay pylon : this.pylons) {
            move(pylon, 10, this.pylonShape(0.0F, PYLON_SIZE));
         }

         int lit = Math.min(4, 1 + (int)Math.floor(progress * 4.0));
         for (int i = 0; i < 4; i++) {
            if (i < lit && this.beams[i] == null) {
               this.beams[i] = this.spawn(this.beamStart(i).add(this.weapon).multiply(0.5), model("vfx/ritual_beam", this.tint), this.beamShape(i, 0.0F), Display.Billboard.FIXED, 1.5F);
               this.ignite(i);
            } else if (this.beams[i] != null && !this.beamShown[i]) {
               this.beamShown[i] = true;
               move(this.beams[i], 8, this.beamShape(i, BEAM_WIDTH));
            }
         }
      }

      this.particles(progress, frame);
   }

   private void ignite(int i) {
      Location at = this.pylonAt(i);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.2F, 0.8F + i * 0.15F);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_BEACON_POWER_SELECT, 0.6F, 1.4F + i * 0.1F);
      ParticleManager.showcase(Particle.END_ROD, at.clone().add(0.0, 0.4, 0.0), 10, 0.15, 0.3, 0.15, 0.04, null);
   }

   private void particles(double progress, int frame) {
      Particle.DustOptions mote = new Particle.DustOptions(this.light, 0.9F);
      for (int i = 0; i < 4; i++) {
         if (this.beamShown[i]) {
            double phase = ((frame + i * 3) % 10) / 10.0;
            Location from = this.beamStart(i);
            Location at = from.clone().add(this.weapon.toVector().subtract(from.toVector()).multiply(phase));
            ParticleManager.showcase(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, mote);
         }
      }

      ThreadLocalRandom random = ThreadLocalRandom.current();
      if (frame % 5 == 2) {
         Location target = this.centre.clone().add(0.0, 1.2, 0.0);
         for (int k = 0; k < 2; k++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            Vector out = new Vector(Math.cos(angle) * OUTER_RADIUS, -1.1, Math.sin(angle) * OUTER_RADIUS);
            ParticleManager.showcase(Particle.ENCHANT, target, 0, out.getX(), out.getY(), out.getZ(), 1.0, null);
         }
      }

      if (frame % 3 == 0) {
         Location rise = this.weapon.clone().add(random.nextDouble(-0.12, 0.12), 1.5 + random.nextDouble(24.0), random.nextDouble(-0.12, 0.12));
         ParticleManager.showcase(Particle.END_ROD, rise, 0, 0.0, 0.08, 0.0, 1.0, null);
      }

      if (progress > 0.8 && frame % 5 == 0) {
         ParticleManager.showcase(Particle.ELECTRIC_SPARK, this.weapon, 3, 0.35, 0.35, 0.35, 0.05, null);
      }
   }

   /** A ring of the weapon's colour rolling out across the ground. */
   void pulse(double radius) {
      if (!this.world.isChunkLoaded(this.centre.getBlockX() >> 4, this.centre.getBlockZ() >> 4)) {
         return;
      }

      ItemDisplay ring = this.release(this.spawn(this.centre.clone().add(0.0, 0.08, 0.0), model("vfx/ritual_pulse", this.light), flat(0.4, 0.9, 0.0F), Display.Billboard.FIXED, 1.5F));
      this.later(ring, 2L, 14, flat(radius, 0.9, 0.3F));
      this.removeAt(ring, 17L);
   }

   /** The last two seconds: the pylons fly into the weapon, the circles wind in, the beam swells. */
   void finale() {
      if (this.finale || !this.intact()) {
         return;
      }

      this.finale = true;
      int t = 36;
      this.outerYaw += 2.6F;
      this.innerYaw -= 2.6F;
      move(this.outer, t, this.outerShape(0.35));
      move(this.inner, t, this.innerShape(0.3));
      move(this.sky, t, skyShape(SKY_LENGTH, 1.4F));
      for (int i = 0; i < 4; i++) {
         Vector to = this.weapon.toVector().subtract(this.pylonAt(i).toVector());
         Vector3f offset = new Vector3f((float)to.getX(), (float)to.getY(), (float)to.getZ());
         move(this.pylons[i], t, new Transformation(offset, new Quaternionf().rotationY(this.spin + 2.5F), new Vector3f(0.25F), new Quaternionf()));
         if (this.beams[i] != null) {
            move(this.beams[i], t - 8, this.beamShape(i, 0.0F));
         }
      }

      this.plugin.getSoundManager().world(this.centre, Sound.BLOCK_BEACON_POWER_SELECT, 1.5F, 0.6F);
      this.plugin.getSoundManager().world(this.centre, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2F, 0.8F);
      this.plugin.getSoundManager().world(this.centre, Sound.BLOCK_PORTAL_TRIGGER, 0.5F, 1.8F);
   }

   /** Forged: a flash at the weapon, a wide ring, and the sky beam flares out. */
   void complete() {
      if (this.world.isChunkLoaded(this.centre.getBlockX() >> 4, this.centre.getBlockZ() >> 4)) {
         ItemDisplay flash = this.release(this.spawn(this.weapon, model("vfx/flash", null), card(0.6F), Display.Billboard.CENTER, 2.0F));
         this.later(flash, 2L, 3, card(5.0F));
         this.later(flash, 6L, 9, card(0.05F));
         this.removeAt(flash, 16L);
         this.pulse(7.5);
         if (this.sky != null && this.sky.isValid()) {
            ItemDisplay beam = this.release(this.sky);
            this.later(beam, 1L, 3, skyShape(SKY_LENGTH, 2.4F));
            this.later(beam, 4L, 14, skyShape(SKY_LENGTH, 0.0F));
            this.removeAt(beam, 20L);
         }
      }

      this.remove();
   }

   /** Broken: the circles snap shut, the pylons fall, the beams go out. */
   void cancel() {
      this.finale = true;
      for (ItemDisplay circle : new ItemDisplay[]{this.outer, this.inner}) {
         if (circle != null) {
            this.later(this.release(circle), 1L, 8, flat(0.05, 1.0, 0.0F));
            this.removeAt(circle, 11L);
         }
      }

      if (this.sky != null) {
         this.later(this.release(this.sky), 1L, 6, skyShape(SKY_LENGTH, 0.0F));
         this.removeAt(this.sky, 9L);
      }

      for (int i = 0; i < 4; i++) {
         if (this.beams[i] != null) {
            this.later(this.release(this.beams[i]), 1L, 4, this.beamShape(i, 0.0F));
            this.removeAt(this.beams[i], 7L);
         }

         if (this.pylons[i] != null) {
            this.later(this.release(this.pylons[i]), 1L, 10, new Transformation(new Vector3f(0.0F, -2.0F, 0.0F), new Quaternionf().rotationZ(0.8F), new Vector3f(0.3F), new Quaternionf()));
            this.removeAt(this.pylons[i], 13L);
         }
      }

      this.remove();
      this.outer = this.inner = this.sky = null;
   }

   void remove() {
      for (ItemDisplay display : this.owned) {
         if (display.isValid()) {
            display.remove();
         }
      }

      this.owned.clear();
   }

   private static Transformation card(float size) {
      return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size), new Quaternionf());
   }

   /** Hand a display over to an exit animation: the scene no longer owns it, shutdown still cleans it up. */
   private ItemDisplay release(ItemDisplay display) {
      this.owned.remove(display);
      LEAVING.add(display);
      return display;
   }

   private void later(ItemDisplay display, long delay, int ticks, Transformation to) {
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> move(display, ticks, to), delay);
   }

   private void removeAt(ItemDisplay display, long at) {
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         LEAVING.remove(display);
         if (display.isValid()) {
            display.remove();
         }
      }, at);
   }

   public static void shutdown() {
      for (ItemDisplay display : LEAVING) {
         if (display.isValid()) {
            display.remove();
         }
      }

      LEAVING.clear();
   }
}
