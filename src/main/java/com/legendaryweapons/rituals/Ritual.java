package com.legendaryweapons.rituals;

import com.legendaryweapons.LegendaryWeapons;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class Ritual {
   private final LegendaryWeapons plugin;
   private final UUID playerUUID;
   private final Location location;
   private final String weaponKey;
   private final BukkitTask task;
   private final BukkitTask particleTask;
   private final BossBar bossBar;
   private final int duration;
   private final long startTime;
   private int secondsRemaining;
   private ItemDisplay floatingItem;
   private final World world;
   private static final Color COLOR_PRIMARY = Color.fromRGB(138, 43, 226);
   private static final Color COLOR_SECONDARY = Color.fromRGB(75, 0, 130);
   private static final Color COLOR_HELIX_1 = Color.fromRGB(173, 216, 230);
   private static final Color COLOR_HELIX_2 = Color.fromRGB(221, 160, 221);
   private static final int BEAM_HEIGHT = 20;
   private int lastMilestone = 0;

   public Ritual(LegendaryWeapons plugin, UUID playerUUID, Location location, String weaponKey, final long startTime) {
      this.plugin = plugin;
      this.playerUUID = playerUUID;
      this.location = location;
      this.weaponKey = weaponKey;
      this.startTime = startTime;
      this.duration = plugin.getConfigManager().getRitualTimer();
      this.world = location.getWorld();
      long elapsedSeconds = (System.currentTimeMillis() - this.startTime) / 1000L;
      this.secondsRemaining = this.duration - (int)elapsedSeconds;
      this.createFloatingItem();
      this.bossBar = plugin.getServer().createBossBar(this.buildBossBarTitle(), BarColor.PURPLE, BarStyle.SOLID, new BarFlag[0]);

      for (Player online : plugin.getServer().getOnlinePlayers()) {
         this.bossBar.addPlayer(online);
      }

      this.particleTask = this.startParticleEffects();
      this.task = (new BukkitRunnable() {
         public void run() {
            long elapsedSecondsx = (System.currentTimeMillis() - startTime) / 1000L;
            Ritual.this.secondsRemaining = Ritual.this.duration - (int)elapsedSecondsx;
            if (Ritual.this.secondsRemaining <= 0) {
               Ritual.this.complete();
               this.cancel();
            } else {
               Ritual.this.updateBossBar();
            }
         }
      }).runTaskTimer(plugin, 20L, 20L);
   }

   private void createFloatingItem() {
      Location spawnLocation = this.location.clone().add(0.5, 1.4, 0.5);
      ItemStack weaponStack = this.plugin.getWeaponManager().getWeapon(this.weaponKey);
      if (weaponStack == null || weaponStack.getType() == Material.AIR) {
         weaponStack = new ItemStack(Material.BARRIER);
         this.plugin.getLogger().warning("Invalid weaponKey for ritual: " + this.weaponKey);
      }

      this.floatingItem = (ItemDisplay)this.world.spawnEntity(spawnLocation, EntityType.ITEM_DISPLAY);
      this.floatingItem.setItemStack(weaponStack);
      this.floatingItem.setPersistent(false);
      this.floatingItem.setInvulnerable(true);
      this.floatingItem.setBillboard(Billboard.FIXED);
      this.floatingItem.setInterpolationDuration(2);
      this.floatingItem.setCustomNameVisible(true);
      this.floatingItem.customName(Component.text(this.displayName(), NamedTextColor.GOLD));
      Transformation transformation = this.floatingItem.getTransformation();
      this.floatingItem
         .setTransformation(
            new Transformation(
               transformation.getTranslation(), transformation.getLeftRotation(), new Vector3f(1.35F, 1.35F, 1.35F), transformation.getRightRotation()
            )
         );
   }

   public String displayName() {
      String configured = this.plugin.getConfigManager().getWeaponDisplayName(this.weaponKey);
      return configured != null && !configured.isBlank() ? configured : this.formatWeaponKey(this.weaponKey);
   }

   public String getDisplayName() {
      return this.displayName();
   }

   public int getSecondsRemaining() {
      return this.secondsRemaining;
   }

   public String getFormattedTimeRemaining() {
      return this.formatTime(Math.max(0, this.secondsRemaining));
   }

   private double progress() {
      return this.duration <= 0 ? 1.0 : Math.max(0.0, Math.min(1.0, 1.0 - (double)this.secondsRemaining / this.duration));
   }

   private Color[] themeColours() {
      String var1 = this.weaponKey;

      return switch (var1) {
         case "scythe-of-diablo" -> new Color[]{Color.fromRGB(220, 20, 60), Color.fromRGB(90, 0, 12)};
         case "ender-glaive" -> new Color[]{Color.fromRGB(150, 60, 235), Color.fromRGB(60, 0, 95)};
         case "axe-of-zeus" -> new Color[]{Color.fromRGB(120, 230, 255), Color.fromRGB(255, 255, 255)};
         case "sword-of-eclipse" -> new Color[]{Color.fromRGB(255, 120, 230), Color.fromRGB(28, 28, 40)};
         case "hellstorm" -> new Color[]{Color.fromRGB(255, 165, 40), Color.fromRGB(190, 45, 10)};
         case "lost-trident-of-atlantis" -> new Color[]{Color.fromRGB(60, 220, 220), Color.fromRGB(20, 90, 145)};
         default -> new Color[]{COLOR_PRIMARY, COLOR_SECONDARY};
      };
   }

   private BukkitTask startParticleEffects() {
      return (new BukkitRunnable() {
         double time = 0.0;
         final Location center = Ritual.this.location.clone().add(0.5, 0.1, 0.5);

         public void run() {
            this.time += 0.15;
            double progress = Ritual.this.progress();
            Ritual.this.updateFloatingItem(this.time, progress);
            Ritual.this.drawRuneRing(this.center, this.time, progress);
            Ritual.this.drawHelixCoil(this.center, this.time, progress);
            Ritual.this.drawBeacon(this.center, progress);
            if (Math.random() < 0.1 + progress * 0.25) {
               Ritual.this.spawnAmbientSparks(this.center, progress);
            }
         }
      }).runTaskTimer(this.plugin, 0L, 2L);
   }

   private void drawRuneRing(Location center, double time, double progress) {
      Color[] theme = this.themeColours();
      int points = 16;
      double radius = 1.7 - progress * 0.5;
      float size = (float)(1.0 + progress * 0.8);

      for (int i = 0; i < points; i++) {
         double angle = 6.283185307179586 / points * i + time * 0.5;
         Location loc = center.clone().add(Math.cos(angle) * radius, 0.05, Math.sin(angle) * radius);
         this.world.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(i % 4 == 0 ? theme[1] : theme[0], size));
      }
   }

   private void drawHelixCoil(Location center, double time, double progress) {
      Color[] theme = this.themeColours();
      double height = 3.5;
      double yStep = 0.25;
      double frequency = 1.5 + progress * 1.5;
      float size = (float)(0.8 + progress * 0.5);

      for (double y = 0.0; y < height; y += yStep) {
         double radius = 0.9 * (1.0 - y / height) + 0.15;
         double angle = y * frequency + time;
         this.world
            .spawnParticle(
               Particle.DUST, center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius), 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(theme[0], size)
            );
         double opposite = angle + 3.141592653589793;
         this.world
            .spawnParticle(
               Particle.DUST,
               center.clone().add(Math.cos(opposite) * radius, y, Math.sin(opposite) * radius),
               1,
               0.0,
               0.0,
               0.0,
               0.0,
               new DustOptions(theme[1], size)
            );
      }
   }

   private void drawBeacon(Location center, double progress) {
      int motes = 2 + (int)(progress * 4.0);

      for (int i = 0; i < motes; i++) {
         Location loc = center.clone().add((Math.random() - 0.5) * 0.3, Math.random() * 20.0, (Math.random() - 0.5) * 0.3);
         this.world.spawnParticle(Particle.END_ROD, loc, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   private void spawnAmbientSparks(Location center, double progress) {
      int sparks = 2 + (int)(progress * 3.0);

      for (int i = 0; i < sparks; i++) {
         double spread = 6.0 - progress * 2.5;
         Location sparkLoc = center.clone().add((Math.random() - 0.5) * spread, Math.random() * 4.0, (Math.random() - 0.5) * spread);
         this.world.spawnParticle(Particle.ELECTRIC_SPARK, sparkLoc, 1, 0.0, 0.0, 0.0, 0.0);
         if (Math.random() < 0.5) {
            this.world.spawnParticle(Particle.GLOW, sparkLoc, 1, 0.0, 0.0, 0.0, 0.0);
         }
      }
   }

   private void playMilestonePulse() {
      final Location center = this.location.clone().add(0.5, 0.3, 0.5);
      final Color[] theme = this.themeColours();
      this.plugin.getSoundManager().world(center, Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 0.7F + (float)this.progress() * 0.7F);
      (new BukkitRunnable() {
         double radius = 0.4;

         public void run() {
            if (this.radius > 4.5) {
               this.cancel();
            } else {
               for (int i = 0; i < 24; i++) {
                  double angle = 0.2617993877991494 * i;
                  Location loc = center.clone().add(Math.cos(angle) * this.radius, 0.0, Math.sin(angle) * this.radius);
                  Ritual.this.world.spawnParticle(Particle.DUST, loc, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(theme[0], 1.3F));
               }

               this.radius += 0.45;
            }
         }
      }).runTaskTimer(this.plugin, 0L, 1L);
   }

   private void updateFloatingItem(double time, double progress) {
      if (this.floatingItem != null && this.floatingItem.isValid()) {
         double bob = Math.sin(time * 1.6) * 0.12;
         float spin = (float)(time * (1.0 + progress * 2.0));
         Transformation transformation = this.floatingItem.getTransformation();
         this.floatingItem
            .setTransformation(
               new Transformation(
                  new Vector3f(0.0F, (float)bob, 0.0F), new Quaternionf().rotateY(spin), transformation.getScale(), transformation.getRightRotation()
               )
            );
         if (Math.random() < 0.1) {
            this.world.spawnParticle(Particle.GLOW, this.floatingItem.getLocation().clone().add(0.0, 0.2, 0.0), 3, 0.2, 0.2, 0.2, 0.0);
         }
      }
   }

   private String buildBossBarTitle() {
      int x = this.location.getBlockX();
      int y = this.location.getBlockY();
      int z = this.location.getBlockZ();
      return "§d§lRitual of "
         + this.formatWeaponKey(this.weaponKey)
         + " §5| §f"
         + this.formatTime(Math.max(0, this.secondsRemaining))
         + " §5| "
         + this.dimensionLabel()
         + " §f"
         + x
         + ", "
         + y
         + ", "
         + z;
   }

   private String dimensionLabel() {
      if (this.world == null) {
         return "§7Unknown";
      } else {
         return switch (this.world.getEnvironment()) {
            case NETHER -> "§cNether";
            case THE_END -> "§5The End";
            case NORMAL -> "§aOverworld";
            default -> "§7" + this.world.getName();
         };
      }
   }

   public void showTo(Player player) {
      if (player != null && !this.bossBar.getPlayers().contains(player)) {
         this.bossBar.addPlayer(player);
      }
   }

   private void updateBossBar() {
      this.bossBar.setTitle(this.buildBossBarTitle());
      this.bossBar.setProgress(Math.max(0.0, Math.min(1.0, (double)this.secondsRemaining / this.duration)));

      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (!this.bossBar.getPlayers().contains(player)) {
            this.bossBar.addPlayer(player);
         }
      }

      int milestone = (int)(this.progress() * 4.0);
      if (milestone > this.lastMilestone && milestone < 4) {
         this.lastMilestone = milestone;
         this.playMilestonePulse();
      }

      double remaining = (double)this.secondsRemaining / this.duration;
      if (remaining < 0.2) {
         Location center = this.location.clone().add(0.5, 1.0, 0.5);
         this.world.spawnParticle(Particle.FIREWORK, center, 10, 0.5, 0.5, 0.5, 0.1);
         this.world.spawnParticle(Particle.GLOW_SQUID_INK, center, 5, 0.3, 0.3, 0.3, 0.05);
      }
   }

   public void complete() {
      Location center = this.location.clone().add(0.5, 1.0, 0.5);
      Color[] theme = this.themeColours();
      Player player = this.getPlayer();
      if (player != null && player.isOnline()) {
         player.sendMessage("§aThe ritual for the " + this.formatWeaponKey(this.weaponKey) + " is complete!");
      }

      this.plugin.getSoundManager().world(center, Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 0.9F);

      for (int i = 0; i < 90; i++) {
         double angle = Math.random() * 3.141592653589793 * 2.0;
         double radius = Math.random() * 2.2;
         Location burst = center.clone().add(radius * Math.cos(angle), Math.random() * 3.0, radius * Math.sin(angle));
         this.world.spawnParticle(Particle.DUST, burst, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(Math.random() < 0.5 ? theme[0] : theme[1], 2.0F));
      }

      this.world.spawnParticle(Particle.FIREWORK, center, 160, 2.0, 2.0, 2.0, 0.5);
      this.world.spawnParticle(Particle.GLOW, center, 80, 1.5, 1.5, 1.5, 0.3);
      this.world.spawnParticle(Particle.TOTEM_OF_UNDYING, center, 50, 1.0, 1.0, 1.0, 0.2);
      ItemStack weapon = this.plugin.getWeaponManager().getWeapon(this.weaponKey);
      this.world.dropItemNaturally(this.location.clone().add(0.0, 1.0, 0.0), weapon);
      this.plugin.getRitualManager().removeRitual(this);
      this.cleanup();
   }

   public void cancel() {
      this.plugin.getRitualManager().removeRitual(this);
      Location center = this.location.clone().add(0.5, 1.0, 0.5);
      this.plugin.getSoundManager().world(center, Sound.BLOCK_FIRE_EXTINGUISH, 0.8F, 1.0F);
      this.world.spawnParticle(Particle.LARGE_SMOKE, center, 30, 0.5, 0.5, 0.5, 0.1);
      this.world.spawnParticle(Particle.ASH, center, 20, 1.0, 1.0, 1.0, 0.05);
      this.cleanup();
   }

   private void cleanup() {
      if (this.task != null) {
         this.task.cancel();
      }

      if (this.particleTask != null) {
         this.particleTask.cancel();
      }

      this.bossBar.removeAll();
      if (this.floatingItem != null && this.floatingItem.isValid()) {
         this.floatingItem.remove();
      }
   }

   private String formatWeaponKey(String key) {
      if (key != null && !key.isEmpty()) {
         String[] words = key.split("-");
         StringBuilder formattedName = new StringBuilder();

         for (String word : words) {
            if (!word.isEmpty()) {
               formattedName.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
            }
         }

         return formattedName.toString().trim();
      } else {
         return "Unknown Weapon";
      }
   }

   private String formatTime(int seconds) {
      int minutes = seconds / 60;
      int remainingSeconds = seconds % 60;
      return String.format("%02d:%02d", minutes, remainingSeconds);
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

   public boolean isRitualLocation(Location checkLoc) {
      return checkLoc.getWorld().equals(this.location.getWorld()) && checkLoc.distance(this.location) <= 3.0;
   }
}
