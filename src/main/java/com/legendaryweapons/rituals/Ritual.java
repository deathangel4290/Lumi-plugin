package com.legendaryweapons.rituals;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One running ritual.
 *
 * Everyone online gets their own boss bar, so it can speak to where THEY are:
 *   - same dimension: distance and a direction arrow relative to where they are facing,
 *   - Overworld <-> Nether: the ritual's coordinates converted into their dimension (x/8 or x*8),
 *     so you can walk (or portal) straight to it,
 *   - anywhere else: which dimension it is in.
 * Bars are added for players who join mid-ritual and follow them between dimensions.
 */
public class Ritual {
   private static final String[] ARROWS = new String[]{"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};
   private final LegendaryWeapons plugin;
   private final UUID playerUUID;
   private final Location location;
   private final String weaponKey;
   private final long startTime;
   private final int duration;
   private final List<ItemStack> consumed;
   private final World world;
   private final Map<UUID, BossBar> bars = new HashMap<>();
   private final BukkitTask task;
   private final BukkitTask particleTask;
   private int secondsRemaining;
   private ItemDisplay floatingItem;
   private int lastMilestone = 0;
   private boolean finished = false;

   public Ritual(LegendaryWeapons plugin, UUID playerUUID, Location location, String weaponKey, long startTime, List<ItemStack> consumed) {
      this.plugin = plugin;
      this.playerUUID = playerUUID;
      this.location = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
      this.weaponKey = weaponKey;
      this.startTime = startTime;
      this.consumed = consumed == null ? new ArrayList<>() : new ArrayList<>(consumed);
      this.duration = Math.max(1, plugin.getConfigManager().getRitualTimer());
      this.world = this.location.getWorld();
      this.secondsRemaining = this.duration - (int)((System.currentTimeMillis() - startTime) / 1000L);
      this.createFloatingItem();
      this.particleTask = (new BukkitRunnable() {
         double time = 0.0;
         int frame = 0;

         public void run() {
            this.time += 0.15;
            Ritual.this.drawEffects(this.time, this.frame++);
         }
      }).runTaskTimer(plugin, 0L, 2L);
      this.task = (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            Ritual.this.secondsRemaining = Ritual.this.duration - (int)((System.currentTimeMillis() - Ritual.this.startTime) / 1000L);
            if (Ritual.this.secondsRemaining <= 0) {
               Ritual.this.complete();
            } else {
               Ritual.this.updateBars();
               if (this.ticks++ % 2 == 0) {
                  Ritual.this.milestones();
               }
            }
         }
      }).runTaskTimer(plugin, 1L, 10L);
      this.updateBars();
   }

   // ------------------------------------------------------------------ boss bars

   private void updateBars() {
      float progress = (float)Math.max(0.0, Math.min(1.0, (double)this.secondsRemaining / this.duration));

      for (Player viewer : Bukkit.getOnlinePlayers()) {
         BossBar bar = this.bars.get(viewer.getUniqueId());
         Component title = this.titleFor(viewer);
         if (bar == null) {
            bar = BossBar.bossBar(title, progress, BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
            this.bars.put(viewer.getUniqueId(), bar);
            viewer.showBossBar(bar);
         } else {
            bar.name(title);
            bar.progress(progress);
         }
      }

      this.bars.entrySet().removeIf(entry -> Bukkit.getPlayer(entry.getKey()) == null);
   }

   /** Called on join so the bar appears immediately rather than on the next refresh. */
   public void showTo(Player player) {
      if (!this.finished) {
         this.updateBars();
      }
   }

   public void hideFrom(Player player) {
      BossBar bar = this.bars.remove(player.getUniqueId());
      if (bar != null) {
         player.hideBossBar(bar);
      }
   }

   private Component titleFor(Player viewer) {
      Component title = Component.text("✦ ", NamedTextColor.LIGHT_PURPLE)
         .append(LegacyComponentSerializer.legacySection().deserialize(this.displayName()).decoration(TextDecoration.BOLD, true))
         .append(Component.text("  " + this.getFormattedTimeRemaining(), NamedTextColor.WHITE))
         .append(Component.text("  |  ", NamedTextColor.DARK_PURPLE))
         .append(Component.text(dimensionName(this.world) + " ", dimensionColour(this.world)))
         .append(Component.text(this.location.getBlockX() + ", " + this.location.getBlockY() + ", " + this.location.getBlockZ(), NamedTextColor.WHITE));
      World viewerWorld = viewer.getWorld();
      if (viewerWorld.equals(this.world)) {
         Location centre = this.location.clone().add(0.5, 0.0, 0.5);
         double distance = viewer.getLocation().distance(centre);
         return title.append(Component.text("  |  ", NamedTextColor.DARK_PURPLE))
            .append(Component.text((int)Math.round(distance) + "m " + arrowTowards(viewer.getLocation(), centre), NamedTextColor.YELLOW));
      } else {
         double scale = portalScale(this.world, viewerWorld);
         if (scale > 0.0) {
            int x = (int)Math.floor(this.location.getBlockX() * scale);
            int z = (int)Math.floor(this.location.getBlockZ() * scale);
            return title.append(Component.text("  |  ", NamedTextColor.DARK_PURPLE))
               .append(Component.text("your " + dimensionName(viewerWorld) + ": ", NamedTextColor.GRAY))
               .append(Component.text(x + ", " + z, NamedTextColor.YELLOW));
         } else {
            return title;
         }
      }
   }

   /** Overworld to Nether divides by 8, Nether to Overworld multiplies. 0 when there is no mapping. */
   private static double portalScale(World from, World to) {
      Environment a = from.getEnvironment();
      Environment b = to.getEnvironment();
      if (a == Environment.NORMAL && b == Environment.NETHER) {
         return 0.125;
      } else {
         return a == Environment.NETHER && b == Environment.NORMAL ? 8.0 : 0.0;
      }
   }

   private static String arrowTowards(Location from, Location to) {
      Vector delta = to.toVector().subtract(from.toVector());
      if (delta.getX() * delta.getX() + delta.getZ() * delta.getZ() < 4.0) {
         return "●";
      } else {
         double targetYaw = Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
         double relative = ((targetYaw - from.getYaw()) % 360.0 + 360.0) % 360.0;
         return ARROWS[(int)Math.round(relative / 45.0) % 8];
      }
   }

   public static String dimensionName(World world) {
      if (world == null) {
         return "Unknown";
      } else {
         return switch (world.getEnvironment()) {
            case NETHER -> "Nether";
            case THE_END -> "The End";
            case NORMAL -> "Overworld";
            default -> world.getName();
         };
      }
   }

   public static NamedTextColor dimensionColour(World world) {
      if (world == null) {
         return NamedTextColor.GRAY;
      } else {
         return switch (world.getEnvironment()) {
            case NETHER -> NamedTextColor.RED;
            case THE_END -> NamedTextColor.LIGHT_PURPLE;
            case NORMAL -> NamedTextColor.GREEN;
            default -> NamedTextColor.GRAY;
         };
      }
   }

   // ------------------------------------------------------------------ visuals

   private void createFloatingItem() {
      Location spawnLocation = this.location.clone().add(0.5, 1.6, 0.5);
      ItemStack weaponStack = this.plugin.getWeaponManager().getWeapon(this.weaponKey);
      if (weaponStack == null || weaponStack.getType().isAir()) {
         weaponStack = new ItemStack(Material.BARRIER);
      }

      final ItemStack shown = weaponStack;
      final Color glow = this.theme()[0];
      this.floatingItem = this.world.spawn(spawnLocation, ItemDisplay.class, display -> {
         display.setItemStack(shown);
         display.setPersistent(false);
         display.setInvulnerable(true);
         display.setBillboard(Billboard.FIXED);
         display.setInterpolationDuration(3);
         display.setCustomNameVisible(true);
         display.customName(LegacyComponentSerializer.legacySection().deserialize(this.displayName()));
         display.setGlowing(true);
         display.setGlowColorOverride(glow);
         display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.3F, 1.3F, 1.3F), new Quaternionf()));
      });
   }

   private Color[] theme() {
      return switch (this.weaponKey) {
         case "scythe-of-diablo" -> new Color[]{Color.fromRGB(220, 20, 60), Color.fromRGB(90, 0, 12)};
         case "ender-glaive" -> new Color[]{Color.fromRGB(150, 60, 235), Color.fromRGB(60, 0, 95)};
         case "axe-of-zeus" -> new Color[]{Color.fromRGB(120, 230, 255), Color.fromRGB(255, 255, 255)};
         case "sword-of-eclipse" -> new Color[]{Color.fromRGB(255, 120, 230), Color.fromRGB(28, 28, 40)};
         case "hellstorm" -> new Color[]{Color.fromRGB(255, 165, 40), Color.fromRGB(190, 45, 10)};
         case "lost-trident-of-atlantis" -> new Color[]{Color.fromRGB(60, 220, 220), Color.fromRGB(20, 90, 145)};
         case "chiones-blade" -> new Color[]{Color.fromRGB(170, 230, 255), Color.fromRGB(60, 130, 220)};
         case "luminosity" -> new Color[]{Color.fromRGB(255, 220, 90), Color.fromRGB(255, 255, 220)};
         case "titans-axe" -> new Color[]{Color.fromRGB(200, 170, 110), Color.fromRGB(90, 70, 40)};
         default -> new Color[]{Color.fromRGB(138, 43, 226), Color.fromRGB(75, 0, 130)};
      };
   }

   private double progress() {
      return Math.max(0.0, Math.min(1.0, 1.0 - (double)this.secondsRemaining / this.duration));
   }

   private static void fx(Particle particle, Location at, int count, double spread, double speed, Object data) {
      ParticleManager.showcase(particle, at, count, spread, spread, spread, speed, data);
   }

   private static Particle.DustOptions dust(Color colour, double size) {
      return new Particle.DustOptions(colour, (float)size);
   }

   /**
    * The ritual is a landmark, so it is drawn at full strength (effects.ritual-density) rather
    * than through the PvP thinning. Everything tightens and speeds up as it nears completion.
    *
    *   - inner rune ring on the floor, shrinking and brightening
    *   - outer sigil turning the other way, with enchanting glyphs drawn in along four spokes
    *   - a double helix climbing to the weapon
    *   - motes in the weapon's colours spiralling inward
    *   - a beacon column that grows denser (this is how players find it from afar)
    *   - sparks, and a firework crackle over the final fifth
    */
   private void drawEffects(double time, int frame) {
      if (this.world != null && this.world.isChunkLoaded(this.location.getBlockX() >> 4, this.location.getBlockZ() >> 4)) {
         double progress = this.progress();
         Color[] theme = this.theme();
         Location centre = this.location.clone().add(0.5, 0.1, 0.5);

         // Inner rune ring.
         double inner = 1.7 - progress * 0.5;
         double runeSize = 1.0 + progress * 0.8;

         for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2.0 / 16.0 * i + time * 0.5;
            fx(Particle.DUST, centre.clone().add(Math.cos(angle) * inner, 0.05, Math.sin(angle) * inner), 1, 0.0, 0.0, dust(i % 4 == 0 ? theme[1] : theme[0], runeSize));
         }

         // Outer sigil and glyph spokes (every other frame keeps the density sensible).
         if (frame % 2 == 0) {
            double outer = 2.6;

            for (int i = 0; i < 24; i++) {
               double angle = Math.PI * 2.0 / 24.0 * i - time * 0.3;
               fx(Particle.DUST, centre.clone().add(Math.cos(angle) * outer, 0.02, Math.sin(angle) * outer), 1, 0.0, 0.0, dust(theme[1], 0.8));
            }

            for (int spoke = 0; spoke < 4; spoke++) {
               double angle = Math.PI / 2.0 * spoke - time * 0.3;
               Location from = centre.clone().add(Math.cos(angle) * outer, 0.3, Math.sin(angle) * outer);
               ParticleManager.showcase(Particle.ENCHANT, from, 3, 0.1, 0.1, 0.1, 1.0, null);
            }
         }

         // Double helix.
         double frequency = 1.5 + progress * 1.5;
         double helixSize = 0.8 + progress * 0.5;

         for (double y = 0.0; y < 3.5; y += 0.25) {
            double r = 0.9 * (1.0 - y / 3.5) + 0.15;
            double angle = y * frequency + time;
            fx(Particle.DUST, centre.clone().add(Math.cos(angle) * r, y, Math.sin(angle) * r), 1, 0.0, 0.0, dust(theme[0], helixSize));
            fx(Particle.DUST, centre.clone().add(-Math.cos(angle) * r, y, -Math.sin(angle) * r), 1, 0.0, 0.0, dust(theme[1], helixSize));
         }

         // Motes spiralling into the weapon.
         for (int m = 0; m < 3; m++) {
            double phase = (time * 0.35 + m / 3.0) % 1.0;
            double r = 2.2 * (1.0 - phase);
            double angle = time * 2.0 + m * (Math.PI * 2.0 / 3.0);
            Location mote = centre.clone().add(Math.cos(angle) * r, 0.4 + phase * 1.2, Math.sin(angle) * r);
            fx(Particle.DUST, mote, 1, 0.0, 0.0, dust(m % 2 == 0 ? theme[0] : Color.WHITE, 1.1));
         }

         // Beacon column.
         int motes = 2 + (int)(progress * 4.0);

         for (int i = 0; i < motes; i++) {
            fx(Particle.END_ROD, centre.clone().add((Math.random() - 0.5) * 0.3, 2.0 + Math.random() * 20.0, (Math.random() - 0.5) * 0.3), 1, 0.0, 0.0, null);
         }

         // Sparks, more of them as it builds.
         if (Math.random() < 0.15 + progress * 0.35) {
            double spread = 6.0 - progress * 2.5;
            Location spark = centre.clone().add((Math.random() - 0.5) * spread, Math.random() * 4.0, (Math.random() - 0.5) * spread);
            fx(Particle.ELECTRIC_SPARK, spark, 2, 0.1, 0.0, null);
            fx(Particle.GLOW, spark, 1, 0.0, 0.0, null);
         }

         // Final fifth: firework crackle around the weapon.
         if (progress > 0.8 && frame % 10 == 0) {
            fx(Particle.FIREWORK, centre.clone().add(0.0, 1.6, 0.0), 8, 0.5, 0.08, null);
            fx(Particle.GLOW_SQUID_INK, centre.clone().add(0.0, 1.0, 0.0), 3, 0.3, 0.03, null);
         }

         // The weapon: bobbing, spinning faster as it nears completion. Respawn it if the chunk was
         // unloaded (non-persistent displays are removed with the chunk).
         if (this.floatingItem == null || !this.floatingItem.isValid()) {
            this.createFloatingItem();
         }

         float spin = (float)(time * (1.0 + progress * 2.0));
         this.floatingItem.setTransformation(new Transformation(new Vector3f(0.0F, (float)(Math.sin(time * 1.6) * 0.12), 0.0F), new Quaternionf().rotateY(spin), new Vector3f(1.3F, 1.3F, 1.3F), new Quaternionf()));
      }
   }

   private void milestones() {
      int milestone = (int)(this.progress() * 4.0);
      if (milestone > this.lastMilestone && milestone < 4) {
         this.lastMilestone = milestone;
         this.pulse(0.7F + milestone * 0.25F);
      }

      // The last minute: a slow heartbeat so people nearby know it is about to finish.
      if (this.secondsRemaining <= 60 && this.secondsRemaining % 4 == 0) {
         this.plugin.getSoundManager().world(this.location.clone().add(0.5, 0.5, 0.5), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0F, 0.5F);
      }
   }

   /** An expanding ring of the theme colour rolling out across the ground. */
   private void pulse(float pitch) {
      final Location centre = this.location.clone().add(0.5, 0.3, 0.5);
      final Color colour = this.theme()[0];
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, pitch);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, pitch);
      (new BukkitRunnable() {
         double radius = 0.4;

         public void run() {
            if (this.radius > 4.5 || Ritual.this.finished) {
               this.cancel();
            } else {
               for (int i = 0; i < 24; i++) {
                  double angle = Math.PI * 2.0 / 24.0 * i;
                  Ritual.fx(Particle.DUST, centre.clone().add(Math.cos(angle) * this.radius, 0.0, Math.sin(angle) * this.radius), 1, 0.0, 0.0, Ritual.dust(colour, 1.3));
               }

               this.radius += 0.45;
            }
         }
      }).runTaskTimer(this.plugin, 0L, 1L);
   }

   // ------------------------------------------------------------------ lifecycle

   public void complete() {
      if (!this.finished) {
         this.finished = true;
         Location centre = this.location.clone().add(0.5, 1.0, 0.5);
         Player player = this.getPlayer();
         if (player != null && player.isOnline()) {
            player.sendMessage(Component.text("The ritual for ", NamedTextColor.GREEN)
               .append(LegacyComponentSerializer.legacySection().deserialize(this.displayName()))
               .append(Component.text(" is complete!", NamedTextColor.GREEN)));
         }

         Bukkit.broadcast(LegacyComponentSerializer.legacySection().deserialize(this.displayName())
            .append(Component.text(" has been forged in the " + dimensionName(this.world) + ".", NamedTextColor.LIGHT_PURPLE)));
         this.plugin.getSoundManager().world(centre, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
         Color[] theme = this.theme();

         for (int i = 0; i < 90; i++) {
            double angle = Math.random() * Math.PI * 2.0;
            double radius = Math.random() * 2.2;
            fx(Particle.DUST, centre.clone().add(radius * Math.cos(angle), Math.random() * 3.0, radius * Math.sin(angle)), 1, 0.0, 0.0, dust(Math.random() < 0.5 ? theme[0] : theme[1], 2.0));
         }

         ParticleManager.showcase(Particle.FIREWORK, centre, 120, 2.0, 2.0, 2.0, 0.4, null);
         ParticleManager.showcase(Particle.GLOW, centre, 60, 1.5, 1.5, 1.5, 0.3, null);
         ParticleManager.showcase(Particle.TOTEM_OF_UNDYING, centre, 50, 1.0, 1.0, 1.0, 0.3, null);
         ParticleManager.showcase(Particle.FLASH, centre, 1, 0.0, 0.0, 0.0, 0.0, theme[0]);
         this.world.dropItemNaturally(this.location.clone().add(0.5, 1.2, 0.5), this.plugin.getWeaponManager().getWeapon(this.weaponKey));
         this.plugin.getRitualManager().removeRitual(this, true);
         this.cleanup();
      }
   }

   /** The altar was broken mid-ritual: the ingredients spill out and the weapon may be forged again. */
   public void cancel() {
      if (!this.finished) {
         this.finished = true;
         Location centre = this.location.clone().add(0.5, 1.0, 0.5);
         this.plugin.getSoundManager().world(centre, Sound.BLOCK_FIRE_EXTINGUISH, 0.8F, 1.0F);
         ParticleManager.spawn(Particle.LARGE_SMOKE, centre, 20, 0.5, 0.5, 0.5, 0.05);

         for (ItemStack item : this.consumed) {
            if (item != null && !item.getType().isAir()) {
               this.world.dropItemNaturally(centre, item);
            }
         }

         Bukkit.broadcast(Component.text("The ritual of ", NamedTextColor.RED)
            .append(LegacyComponentSerializer.legacySection().deserialize(this.displayName()))
            .append(Component.text(" was broken!", NamedTextColor.RED)));
         this.plugin.getRitualManager().removeRitual(this, false);
         this.cleanup();
      }
   }

   /** Server shutdown: stop quietly, the ritual resumes from rituals.yml on the next start. */
   public void suspend() {
      this.finished = true;
      this.cleanup();
   }

   private void cleanup() {
      this.task.cancel();
      this.particleTask.cancel();

      for (Map.Entry<UUID, BossBar> entry : this.bars.entrySet()) {
         Player viewer = Bukkit.getPlayer(entry.getKey());
         if (viewer != null) {
            viewer.hideBossBar(entry.getValue());
         }
      }

      this.bars.clear();
      if (this.floatingItem != null && this.floatingItem.isValid()) {
         this.floatingItem.remove();
      }
   }

   // ------------------------------------------------------------------ accessors

   public String displayName() {
      String configured = this.plugin.getConfigManager().getWeaponDisplayName(this.weaponKey);
      return configured != null && !configured.isBlank() ? configured : RitualRecipes.prettify(this.weaponKey.replace('-', '_'));
   }

   public String getDisplayName() {
      return this.displayName();
   }

   public int getSecondsRemaining() {
      return this.secondsRemaining;
   }

   public String getFormattedTimeRemaining() {
      int seconds = Math.max(0, this.secondsRemaining);
      return String.format("%02d:%02d", seconds / 60, seconds % 60);
   }

   public Player getPlayer() {
      return Bukkit.getPlayer(this.playerUUID);
   }

   public UUID getPlayerUUID() {
      return this.playerUUID;
   }

   public long getStartTime() {
      return this.startTime;
   }

   public Location getLocation() {
      return this.location;
   }

   public String getWeaponKey() {
      return this.weaponKey;
   }

   public List<ItemStack> getConsumed() {
      return this.consumed;
   }

   public boolean isRitualLocation(Location check) {
      return check.getWorld() != null && check.getWorld().equals(this.world) && check.distance(this.location) <= 3.0;
   }
}
