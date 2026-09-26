package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Every particle the plugin spawns goes through here.
 *
 * Particles are sent per viewer rather than to the whole world, which is what makes the PvP
 * readability rules possible:
 *   - the global density and each player's own /lwfx level thin the count,
 *   - a particle that would land right in front of a viewer's eyes is not sent to that viewer,
 *     so nobody's own effects (or an enemy's aura in melee range) bury their screen,
 *   - particles that need data (FLASH needs a Color since 1.21.9, DUST needs DustOptions, ...)
 *     are given a safe default instead of throwing. A throw inside an ability's repeating task
 *     used to leave the task running forever -- that was the "SuperNova particles never stop" bug.
 */
public class ParticleManager {
   private static ParticleManager instance;
   private final LegendaryWeapons plugin;
   private final NamespacedKey preferenceKey;
   private final Set<Particle> warned = EnumSet.noneOf(Particle.class);

   public enum Level {
      FULL(1.0),
      REDUCED(0.5),
      MINIMAL(0.2),
      OFF(0.0);

      public final double factor;

      Level(double factor) {
         this.factor = factor;
      }

      public static Level parse(String raw) {
         if (raw == null) {
            return null;
         }

         try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var2) {
            return null;
         }
      }
   }

   public ParticleManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.preferenceKey = new NamespacedKey(plugin, "fx-level");
      instance = this;
   }

   public static ParticleManager get() {
      return instance;
   }

   /** Same argument order as World#spawnParticle so call sites convert one-to-one. */
   public static void spawn(Particle particle, Location location, int count, double dx, double dy, double dz, double speed) {
      if (instance != null) {
         instance.emit(particle, location, count, dx, dy, dz, speed, null, false);
      }
   }

   public static void spawn(Particle particle, Location location, int count, double dx, double dy, double dz, double speed, Object data) {
      if (instance != null) {
         instance.emit(particle, location, count, dx, dy, dz, speed, data, false);
      }
   }

   /** For persistent, decorative effects (auras, idle altar sparkle). Off unless effects.passive-auras is on. */
   public static void ambient(Particle particle, Location location, int count, double dx, double dy, double dz, double speed, Object data) {
      if (instance != null && instance.passiveEnabled()) {
         instance.emit(particle, location, count, dx, dy, dz, speed, data, false);
      }
   }

   private boolean enabled() {
      return this.plugin.getConfig().getBoolean("effects.particles", true);
   }

   public double density() {
      return Math.max(0.0, Math.min(1.0, this.plugin.getConfig().getDouble("effects.density", 0.4)));
   }

   public boolean passiveEnabled() {
      return this.enabled() && this.plugin.getConfig().getBoolean("effects.passive-auras", false);
   }

   public boolean trailEnabled() {
      return this.enabled() && this.plugin.getConfig().getBoolean("effects.trails", true);
   }

   private double viewRange() {
      return Math.max(8.0, this.plugin.getConfig().getDouble("effects.view-range", 48.0));
   }

   private double eyeClearance() {
      return Math.max(0.0, this.plugin.getConfig().getDouble("effects.eye-clearance", 1.3));
   }

   public Level levelOf(Player player) {
      String stored = player.getPersistentDataContainer().get(this.preferenceKey, PersistentDataType.STRING);
      Level parsed = Level.parse(stored);
      if (parsed != null) {
         return parsed;
      } else {
         Level configured = Level.parse(this.plugin.getConfig().getString("effects.default-player-level", "REDUCED"));
         return configured != null ? configured : Level.REDUCED;
      }
   }

   public void setLevel(Player player, Level level) {
      player.getPersistentDataContainer().set(this.preferenceKey, PersistentDataType.STRING, level.name());
   }

   /** Kept for existing call sites; a burst is just a normal spawn. */
   public void burst(Location location, Particle particle, int count, double dx, double dy, double dz, double speed) {
      this.emit(particle, location, count, dx, dy, dz, speed, null, false);
   }

   public <T> void burst(Location location, Particle particle, int count, double dx, double dy, double dz, double speed, T data) {
      this.emit(particle, location, count, dx, dy, dz, speed, data, false);
   }

   public void trail(Location location, Particle particle, int count, double dx, double dy, double dz, double speed) {
      if (this.trailEnabled()) {
         this.emit(particle, location, count, dx, dy, dz, speed, null, false);
      }
   }

   public int scale(int count) {
      return !this.enabled() ? 0 : thin(count * this.density());
   }

   /** Probabilistic rounding: a ring of single particles thins evenly instead of vanishing at low density. */
   private static int thin(double expected) {
      if (expected <= 0.0) {
         return 0;
      } else {
         int whole = (int)Math.floor(expected);
         return ThreadLocalRandom.current().nextDouble() < expected - whole ? whole + 1 : whole;
      }
   }

   private void emit(Particle particle, Location location, int count, double dx, double dy, double dz, double speed, Object data, boolean ignored) {
      if (this.enabled() && location != null && particle != null) {
         World world = location.getWorld();
         if (world != null) {
            Object resolved = this.resolveData(particle, data);
            if (resolved != MISSING) {
               double range = this.viewRange();
               double rangeSq = range * range;
               double clearance = this.eyeClearance();
               double clearanceSq = clearance * clearance;
               double spread = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
               double density = this.density();

               for (Player viewer : world.getPlayers()) {
                  Location viewerLoc = viewer.getLocation();
                  if (!(viewerLoc.distanceSquared(location) > rangeSq)) {
                     Level level = this.levelOf(viewer);
                     if (level != Level.OFF) {
                        // A tight effect sitting on the viewer's face is the thing that blinds them; a wide
                        // burst centred near them is fine because most of it lands elsewhere.
                        if (clearanceSq > 0.0 && spread < 0.6 && viewer.getEyeLocation().distanceSquared(location) < clearanceSq) {
                           continue;
                        }

                        int amount = count <= 0 ? 0 : thin(count * density * level.factor);
                        if (count <= 0 || amount > 0) {
                           try {
                              if (resolved == null) {
                                 viewer.spawnParticle(particle, location, amount, dx, dy, dz, speed);
                              } else {
                                 viewer.spawnParticle(particle, location, amount, dx, dy, dz, speed, resolved);
                              }
                           } catch (RuntimeException var29) {
                              if (this.warned.add(particle)) {
                                 this.plugin.getLogger().warning("Particle " + particle + " could not be spawned (" + var29.getMessage() + "); skipping it from now on.");
                              }

                              return;
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static final Object MISSING = new Object();

   private Object resolveData(Particle particle, Object data) {
      if (this.warned.contains(particle)) {
         return MISSING;
      } else {
         Class<?> type;
         try {
            type = particle.getDataType();
         } catch (RuntimeException var5) {
            return data;
         }

         if (type == null || type == Void.class) {
            return null;
         } else if (data != null && type.isInstance(data)) {
            return data;
         } else if (type == Color.class) {
            return data instanceof Particle.DustOptions dust ? dust.getColor() : Color.WHITE;
         } else if (type == Particle.DustOptions.class) {
            return new Particle.DustOptions(data instanceof Color c ? c : Color.WHITE, 1.0F);
         } else if (type == BlockData.class) {
            return Material.STONE.createBlockData();
         } else if (type == ItemStack.class) {
            return new ItemStack(Material.STONE);
         } else if (type == Float.class) {
            return 0.0F;
         } else if (type == Integer.class) {
            return 0;
         } else {
            if (this.warned.add(particle)) {
               this.plugin.getLogger().warning("Particle " + particle + " needs " + type.getSimpleName() + " data the plugin does not provide; skipping it.");
            }

            return MISSING;
         }
      }
   }
}
