package com.lumiweapons.rituals;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.ParticleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The weapon being forged, hovering over the altar.
 *
 *   - drawn in its item-frame pose (the models' "fixed" display), so the 3D models stand upright,
 *     centred on their own axis, at a sensible size instead of their raw 3-block geometry
 *   - rises out of the altar when the ritual starts, then turns slowly with a gentle bob and a
 *     slight sway, turning faster as the ritual builds
 *   - a small rune ring turns under it, and two motes in the weapon's colours circle it
 *   - its name and a progress bar float over it (ritual.floating-weapon.label)
 *   - the last two seconds: it lifts, grows and spins up while the pylons fly in
 *
 * Every piece is redrawn every 2 ticks with a 2-tick interpolation, so the motion is continuous.
 */
final class RitualWeapon {
   private static final int INTRO_TICKS = 30;
   private static final double RING_DROP = 1.1;           // the rune ring sits just over the altar top (17.5px)
   private static final double LABEL_RISE = 1.35;

   private final LumiWeapons plugin;
   private final World world;
   private final Location at;                               // the weapon's centre
   private final ItemStack item;
   private final Component name;
   private final Color tint;
   private final Color accent;
   private final float scale;
   private final boolean label;
   private final boolean outline;
   private ItemDisplay weapon;
   private ItemDisplay ring;
   private TextDisplay tag;
   private String tagShown;
   private int steadyFrom = -1;
   private float spin;
   private float ringYaw;
   private boolean finale;

   RitualWeapon(LumiWeapons plugin, Location block, ItemStack item, String legacyName, Color tint, Color accent) {
      this.plugin = plugin;
      this.world = block.getWorld();
      this.at = block.clone().add(0.5, RitualScene.WEAPON_HEIGHT, 0.5);
      this.item = item == null || item.getType().isAir() ? new ItemStack(Material.BARRIER) : item;
      this.name = LegacyComponentSerializer.legacySection().deserialize(legacyName);
      this.tint = tint;
      this.accent = accent;
      this.scale = (float)Math.max(0.3, Math.min(4.0, plugin.getConfig().getDouble("ritual.floating-weapon.scale", 1.5)));
      this.label = plugin.getConfig().getBoolean("ritual.floating-weapon.label", true);
      this.outline = plugin.getConfig().getBoolean("ritual.floating-weapon.glow-outline", false);
   }

   private boolean intact() {
      return this.weapon != null && this.weapon.isValid() && this.ring != null && this.ring.isValid()
         && (!this.label || this.tag != null && this.tag.isValid());
   }

   /** (Re)builds the pieces if they are missing. `intro` makes the weapon rise out of the altar. */
   private void ensure(boolean intro, int frame) {
      if (this.intact()) {
         return;
      }

      this.remove();
      Location place = this.at.clone();
      place.setYaw(0.0F);
      place.setPitch(0.0F);
      this.weapon = this.world.spawn(place, ItemDisplay.class, display -> {
         display.setPersistent(false);
         display.setItemStack(this.item);
         display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
         display.setBillboard(Display.Billboard.FIXED);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setShadowRadius(0.0F);
         display.setViewRange(2.0F);
         if (this.outline) {
            display.setGlowing(true);
            display.setGlowColorOverride(this.tint);
         }

         display.setTransformation(intro ? this.pose(-0.8, 0.0F, 0.04F, 0.0F, 0.0F) : this.pose(0.0, 0.0F, this.scale, 0.0F, 0.0F));
      });
      this.ring = this.world.spawn(place.clone().subtract(0.0, RING_DROP, 0.0), ItemDisplay.class, display -> {
         display.setPersistent(false);
         display.setItemStack(RitualScene.model("vfx/ritual_circle_inner", this.accent));
         display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
         display.setBillboard(Display.Billboard.FIXED);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setShadowRadius(0.0F);
         display.setViewRange(1.5F);
         display.setTransformation(this.ringShape(intro ? 0.05 : 1.0));
      });
      if (this.label) {
         this.tag = this.world.spawn(place.clone().add(0.0, LABEL_RISE * this.scale / 1.5, 0.0), TextDisplay.class, display -> {
            display.setPersistent(false);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            display.setShadowed(true);
            display.setSeeThrough(false);
            display.setViewRange(0.6F);
            display.text(this.name);
         });
         this.tagShown = null;
      }

      if (intro) {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            move(this.weapon, INTRO_TICKS, this.pose(0.0, 0.0F, this.scale, 0.0F, 0.0F));
            move(this.ring, INTRO_TICKS, this.ringShape(1.0));
         }, 2L);
         this.steadyFrom = frame + (INTRO_TICKS + 4) / 2;
      } else {
         this.steadyFrom = frame + 1;
      }
   }

   /** Rise above the centre, turned by `yaw` and tipped by the two sway angles. */
   private Transformation pose(double rise, float yaw, float size, float swayX, float swayZ) {
      Quaternionf turn = new Quaternionf().rotateX(swayX).rotateZ(swayZ).rotateY(yaw);
      return new Transformation(new Vector3f(0.0F, (float)rise, 0.0F), turn, new Vector3f(size), new Quaternionf());
   }

   private Transformation ringShape(double grow) {
      float d = (float)(2.0 * 0.5 * (this.scale / 1.5) * grow / 0.95);
      return new Transformation(new Vector3f(), new Quaternionf().rotationY(this.ringYaw), new Vector3f(d, 1.0F, d), new Quaternionf());
   }

   private static void move(Display display, int ticks, Transformation to) {
      if (display != null && display.isValid()) {
         display.setInterpolationDelay(0);
         display.setInterpolationDuration(Math.max(1, ticks));
         display.setTransformation(to);
      }
   }

   /** Called every 2 ticks while the altar's chunk is loaded. */
   void tick(boolean fresh, double progress, int frame, int secondsLeft) {
      this.ensure(fresh, frame);
      this.updateTag(progress, secondsLeft);
      if (this.steadyFrom < 0 || frame < this.steadyFrom) {
         return;
      }

      double seconds = frame / 10.0;
      double speed = this.finale ? 5.0 : 1.0 + 2.0 * progress * progress;
      this.spin = (float)((this.spin + 0.11 * speed) % (Math.PI * 2.0));
      this.ringYaw = (float)((this.ringYaw - 0.06 * (1.0 + progress)) % (Math.PI * 2.0));
      double rise = Math.sin(seconds * 1.6) * 0.07 + (this.finale ? 0.35 : 0.0);
      float size = this.scale * (this.finale ? 1.2F : 1.0F);
      float swayX = (float)(Math.sin(seconds * 0.9) * 0.07);
      float swayZ = (float)(Math.cos(seconds * 0.7) * 0.07);
      move(this.weapon, 2, this.pose(rise, this.spin, size, swayX, swayZ));
      move(this.ring, 2, this.ringShape(this.finale ? 1.6 : 1.0));

      // two motes, one in each of the weapon's colours, circling it on a slow spiral
      double radius = 0.55 * this.scale / 1.5;
      for (int k = 0; k < 2; k++) {
         double angle = seconds * 2.4 + Math.PI * k;
         Location mote = this.at.clone().add(Math.cos(angle) * radius, Math.sin(seconds * 1.3 + k * 2.0) * 0.45 * this.scale / 1.5, Math.sin(angle) * radius);
         ParticleManager.showcase(Particle.DUST, mote, 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(k == 0 ? this.tint : this.accent, 0.8F));
      }
   }

   private void updateTag(double progress, int secondsLeft) {
      if (this.tag == null || !this.tag.isValid()) {
         return;
      }

      int filled = (int)Math.round(progress * 10.0);
      String time = Math.max(0, secondsLeft) / 60 + ":" + String.format("%02d", Math.max(0, secondsLeft) % 60);
      String key = filled + "|" + time;
      if (key.equals(this.tagShown)) {
         return;
      }

      this.tagShown = key;
      TextColor bright = TextColor.color(this.accent.asRGB());
      this.tag.text(this.name.decoration(TextDecoration.BOLD, true)
         .appendNewline()
         .append(Component.text("▰".repeat(filled), bright))
         .append(Component.text("▱".repeat(10 - filled), NamedTextColor.DARK_GRAY))
         .append(Component.text("  " + time, NamedTextColor.GRAY)));
   }

   /** The last two seconds: lift, grow, spin up. */
   void finale() {
      this.finale = true;
   }

   void remove() {
      for (Display display : new Display[]{this.weapon, this.ring, this.tag}) {
         if (display != null && display.isValid()) {
            display.remove();
         }
      }

      this.weapon = null;
      this.ring = null;
      this.tag = null;
   }
}
