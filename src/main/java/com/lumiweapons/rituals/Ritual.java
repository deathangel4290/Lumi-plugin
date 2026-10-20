package com.lumiweapons.rituals;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.ParticleManager;
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
   private final LumiWeapons plugin;
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
   private final RitualScene scene;
   private int lastMilestone = 0;
   private boolean finished = false;

   public Ritual(LumiWeapons plugin, UUID playerUUID, Location location, String weaponKey, long startTime, List<ItemStack> consumed) {
      this.plugin = plugin;
      this.playerUUID = playerUUID;
      this.location = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
      this.weaponKey = weaponKey;
      this.startTime = startTime;
      this.consumed = consumed == null ? new ArrayList<>() : new ArrayList<>(consumed);
      // weapons.<key>.ritual-time, else ritual.timer (read here: rituals resume before the manager exists)
      this.duration = Math.max(1, plugin.getConfig().getInt("weapons." + weaponKey + ".ritual-time", plugin.getConfigManager().getRitualTimer()));
      this.world = this.location.getWorld();
      this.secondsRemaining = this.duration - (int)((System.currentTimeMillis() - startTime) / 1000L);
      this.scene = new RitualScene(plugin, this.location, this.theme()[0]);
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
               if (Ritual.this.secondsRemaining <= 2) {
                  Ritual.this.scene.finale();
               }

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
      Location spawnLocation = this.location.clone().add(0.5, RitualScene.WEAPON_HEIGHT, 0.5);
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

   /**
    * The ritual is drawn by RitualScene (rune circles, pylons, beams, sky beam); this keeps it built
    * -- it is rebuilt if its chunks were unloaded -- and turns the weapon over the altar.
    */
   private void drawEffects(double time, int frame) {
      if (this.world != null && this.world.isChunkLoaded(this.location.getBlockX() >> 4, this.location.getBlockZ() >> 4)) {
         double progress = this.progress();
         boolean fresh = System.currentTimeMillis() - this.startTime < 4000L;
         if (this.scene.ensure(fresh, progress, frame)) {
            this.scene.tick(progress, frame);
         }

         // The weapon: bobbing, spinning faster as it nears completion. Respawn it if the chunk was
         // unloaded (non-persistent displays are removed with the chunk).
         if (this.floatingItem == null || !this.floatingItem.isValid()) {
            this.createFloatingItem();
         }

         float spin = (float)(time * (1.0 + progress * 2.0));
         this.floatingItem.setInterpolationDelay(0);
         this.floatingItem.setInterpolationDuration(3);
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

   /** A ring of the theme colour rolling out across the ground, with a chime. */
   private void pulse(float pitch) {
      Location centre = this.location.clone().add(0.5, 0.3, 0.5);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, pitch);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, pitch);
      this.scene.pulse(4.5);
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
         this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 1.2F);
         this.plugin.getSoundManager().world(centre, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6F, 1.6F);
         this.scene.complete();
         Location forged = this.location.clone().add(0.5, RitualScene.WEAPON_HEIGHT, 0.5);
         ParticleManager.showcase(Particle.END_ROD, forged, 40, 0.1, 0.1, 0.1, 0.25, null);
         ParticleManager.showcase(Particle.TOTEM_OF_UNDYING, forged, 30, 0.4, 0.4, 0.4, 0.35, null);
         // dropped where it floated, straight down onto the altar
         org.bukkit.entity.Item drop = this.world.dropItem(forged, this.plugin.getWeaponManager().getWeapon(this.weaponKey));
         drop.setVelocity(new Vector(0.0, 0.0, 0.0));
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
         this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_DEACTIVATE, 1.0F, 0.8F);
         this.scene.cancel();
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
      this.scene.remove();
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
