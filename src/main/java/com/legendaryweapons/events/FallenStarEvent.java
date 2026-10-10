package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.AnimationFx;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Fallen Star. A meteor comes down at a random reachable spot; where it fell is revealed in stages
 * (biome, then rough distance, then coordinates). It leaves a crater with the sealed Star Core in
 * the middle. The first players to arrive wake its guardians, which come in waves; clearing the
 * last wave frees the core, which becomes a carry objective like the Boss Heart.
 *
 * The crater is temporary. Every block it changes is recorded (and saved in events.yml in case
 * the server stops mid-event) and put back afterwards - but only blocks still exactly as the
 * crater left them, so nothing a player built there is erased. A spot is only used if everything
 * the crater would touch is natural terrain, so it never lands in someone's base.
 */
public final class FallenStarEvent implements Listener {
   private enum Phase { FALLING, LANDED, WAVES, CORE, DONE }

   private static final TextColor GOLD = TextColor.color(255, 215, 80);
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final NamespacedKey guardianKey;
   private final List<String[]> crater = new ArrayList<>();         // world, x, y, z, original, placed
   private final Set<UUID> guardians = new HashSet<>();
   private final Set<Long> tickets = new HashSet<>();
   private Phase phase = Phase.DONE;
   private Location centre;
   private World world;
   private long startedAt;
   private long landedAt;
   private int wave;
   private long waveStartedAt;
   private long nextWaveAt;
   private int revealed;
   private Contribution contribution;
   private ItemDisplay core;
   private BlockDisplay seal;
   private TextDisplay label;
   private BukkitRunnable task;

   public FallenStarEvent(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.guardianKey = new NamespacedKey(plugin, "star-guardian");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("events.fallen-star");
   }

   private double setting(String key, double fallback) {
      return this.config() == null ? fallback : this.config().getDouble(key, fallback);
   }

   public boolean isRunning() {
      return this.phase != Phase.DONE;
   }

   // ------------------------------------------------------------------ persistence (crater only)

   void save(ConfigurationSection section) {
      List<String> lines = new ArrayList<>();
      for (String[] entry : this.crater) {
         lines.add(String.join("|", entry));
      }

      section.set("crater", lines.isEmpty() ? null : lines);
   }

   /** On startup: put back a crater left behind by a crash or restart. */
   void recover(ConfigurationSection section) {
      if (section != null) {
         for (String line : section.getStringList("crater")) {
            String[] parts = line.split("\\|", -1);
            if (parts.length == 6) {
               this.crater.add(parts);
            }
         }

         if (!this.crater.isEmpty()) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
               int restored = this.restoreCrater();
               this.plugin.getLogger().info("[Fallen Star] Restored " + restored + " crater blocks left from the last run.");
            }, 40L);
         }
      }
   }

   // ------------------------------------------------------------------ start

   public String start(World target) {
      if (this.isRunning()) {
         return "A Fallen Star is already active.";
      }

      if (!this.crater.isEmpty()) {
         this.restoreCrater();
      }

      double radius = this.setting("crater-radius", 6.0);
      Location spot = null;
      for (int attempt = 0; attempt < 8 && spot == null; attempt++) {
         Location candidate = SafeSpots.find(target, this.setting("border-margin", 64.0), this.setting("max-radius", 1500.0));
         if (candidate != null && this.allNatural(candidate, radius)) {
            spot = candidate;
         }
      }

      if (spot == null) {
         return "Could not find open, natural ground for the star in " + target.getName() + ".";
      }

      this.world = target;
      this.centre = spot.getBlock().getLocation();
      this.contribution = new Contribution();
      this.guardians.clear();
      this.wave = 0;
      this.revealed = 0;
      this.startedAt = System.currentTimeMillis();
      this.phase = Phase.FALLING;
      int cx = this.centre.getBlockX() >> 4;
      int cz = this.centre.getBlockZ() >> 4;
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            this.world.addPluginChunkTicket(cx + dx, cz + dz, this.plugin);
            this.tickets.add(((long)(cx + dx) << 32) ^ ((cz + dz) & 0xffffffffL));
         }
      }

      Bukkit.broadcast(Component.text("☄ Something is burning through the sky...", GOLD));
      for (Player player : Bukkit.getOnlinePlayers()) {
         this.plugin.getSoundManager().self(player, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.8F, 0.5F);
      }

      this.meteor();
      this.task = new BukkitRunnable() {
         public void run() {
            try {
               FallenStarEvent.this.tick();
            } catch (RuntimeException var2) {
               FallenStarEvent.this.plugin.getLogger().warning("Fallen Star tick failed: " + var2);
            }
         }
      };
      this.task.runTaskTimer(this.plugin, 20L, 20L);
      return null;
   }

   private static boolean natural(Material type) {
      if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
         return true;
      }

      if (Tag.BASE_STONE_OVERWORLD.isTagged(type) || Tag.DIRT.isTagged(type) || Tag.SAND.isTagged(type) || Tag.LEAVES.isTagged(type)
         || Tag.LOGS.isTagged(type) || Tag.FLOWERS.isTagged(type) || Tag.REPLACEABLE.isTagged(type) || Tag.ICE.isTagged(type)
         || Tag.TERRACOTTA.isTagged(type) || Tag.SNOW.isTagged(type) || Tag.SAPLINGS.isTagged(type) || Tag.BASE_STONE_NETHER.isTagged(type)) {
         return true;
      }

      return switch (type.name()) {
         case "GRAVEL", "CLAY", "SANDSTONE", "RED_SANDSTONE", "SNOW_BLOCK", "POWDER_SNOW", "MOSS_BLOCK", "MOSS_CARPET", "CALCITE",
            "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE", "CACTUS", "SUGAR_CANE", "BAMBOO", "PUMPKIN", "MELON", "SWEET_BERRY_BUSH", "KELP",
            "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS", "LILY_PAD", "BROWN_MUSHROOM", "RED_MUSHROOM", "BROWN_MUSHROOM_BLOCK",
            "RED_MUSHROOM_BLOCK", "MUSHROOM_STEM", "COAL_ORE", "IRON_ORE", "COPPER_ORE", "SOUL_SAND", "SOUL_SOIL", "CRIMSON_NYLIUM",
            "WARPED_NYLIUM", "MAGMA_BLOCK", "OBSIDIAN", "COCOA", "BEE_NEST", "SMOOTH_BASALT", "AMETHYST_BLOCK", "BUDDING_AMETHYST",
            "PALE_MOSS_BLOCK", "PALE_MOSS_CARPET", "PALE_HANGING_MOSS", "LEAF_LITTER", "FIREFLY_BUSH", "BUSH", "DEAD_BUSH" -> true;
         default -> false;
      };
   }

   private int surface(int x, int z, int aroundY) {
      for (int y = aroundY + 4; y >= aroundY - 6; y--) {
         Block block = this.worldOr(this.centre).getBlockAt(x, y, z);
         if (!block.isPassable() && !block.isLiquid() && !Tag.LEAVES.isTagged(block.getType()) && !Tag.LOGS.isTagged(block.getType())) {
            return y;                                   // the ground, not a tree canopy above it
         }
      }

      return Integer.MIN_VALUE;
   }

   private World worldOr(Location fallback) {
      return this.world != null ? this.world : fallback.getWorld();
   }

   private boolean allNatural(Location at, double radius) {
      World w = at.getWorld();
      int r = (int)Math.ceil(radius) + 1;
      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dz * dz > r * r) {
               continue;
            }

            for (int dy = -4; dy <= 4; dy++) {
               Block block = w.getBlockAt(at.getBlockX() + dx, at.getBlockY() + dy, at.getBlockZ() + dz);
               // no water (it would flood the crater for good) and no tree trunks in the blast
               if (!natural(block.getType()) || block.isLiquid() || Tag.LOGS.isTagged(block.getType()) || block.getState() instanceof TileState) {
                  return false;
               }
            }
         }
      }

      return true;
   }

   // ------------------------------------------------------------------ the fall

   private void meteor() {
      final double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
      final Location target = this.centre.clone().add(0.5, 0.5, 0.5);
      final Vector offset = new Vector(Math.cos(angle) * 45.0, 75.0, Math.sin(angle) * 45.0);
      final int fall = 50;
      final BlockDisplay rock = this.world.spawn(target.clone().add(offset), BlockDisplay.class, display -> {
         display.setBlock(Material.MAGMA_BLOCK.createBlockData());
         display.setPersistent(false);
         display.setTeleportDuration(2);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setTransformation(new Transformation(new Vector3f(-1.25F, -1.25F, -1.25F), new Quaternionf(), new Vector3f(2.5F, 2.5F, 2.5F), new Quaternionf()));
         display.setViewRange(8.0F);
      });
      (new BukkitRunnable() {
         int t = 0;

         public void run() {
            this.t += 2;
            if (!FallenStarEvent.this.isRunning()) {
               rock.remove();
               this.cancel();
               return;
            }

            double k = Math.min(1.0, (double)this.t / fall);
            Location at = target.clone().add(offset.clone().multiply(1.0 - k * k));
            rock.teleport(at);
            ParticleManager.showcase(Particle.FLAME, at, 12, 0.6, 0.6, 0.6, 0.04, null);
            ParticleManager.showcase(Particle.LARGE_SMOKE, at, 6, 0.5, 0.5, 0.5, 0.02, null);
            ParticleManager.showcase(Particle.LAVA, at, 2, 0.4, 0.4, 0.4, 0.0, null);
            if (k >= 1.0) {
               rock.remove();
               this.cancel();
               FallenStarEvent.this.impact();
            }
         }
      }).runTaskTimer(this.plugin, 2L, 2L);
   }

   private void impact() {
      Location at = this.centre.clone().add(0.5, 0.5, 0.5);
      ParticleManager.showcase(Particle.EXPLOSION_EMITTER, at, 2, 1.0, 0.5, 1.0, 0.0, null);
      ParticleManager.showcase(Particle.LAVA, at, 40, 2.5, 1.0, 2.5, 0.0, null);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.5F);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0F, 0.6F);
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6F, 0.4F);
      }

      // Anyone standing exactly where it lands is thrown clear, not buried.
      for (Entity nearby : this.world.getNearbyEntities(at, 8.0, 6.0, 8.0)) {
         if (nearby instanceof LivingEntity) {
            Vector push = nearby.getLocation().toVector().subtract(at.toVector()).setY(0.0);
            nearby.setVelocity((push.lengthSquared() < 0.01 ? new Vector(1, 0, 0) : push.normalize()).multiply(1.2).setY(0.6));
         }
      }

      this.carve();
      AnimationFx.shockwave(at, Material.BLACKSTONE, this.setting("crater-radius", 6.0) + 2.0, 14);
      this.landedAt = System.currentTimeMillis();
      this.phase = Phase.LANDED;
      this.spawnCore();
      this.manager.save();
      String biome = prettify(this.world.getBiome(this.centre.getBlockX(), this.centre.getBlockY(), this.centre.getBlockZ()).getKey().getKey());
      Bukkit.broadcast(Component.text("☄ A FALLEN STAR HAS CRASHED", GOLD, TextDecoration.BOLD));
      Bukkit.broadcast(Component.text("It came down somewhere in a " + biome + " (" + com.legendaryweapons.rituals.Ritual.dimensionName(this.world) + ").", NamedTextColor.GRAY));
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.showTitle(Title.title(Component.text("☄ Fallen Star", GOLD, TextDecoration.BOLD), Component.text("somewhere in a " + biome, NamedTextColor.GRAY)));
      }
   }

   private static String prettify(String key) {
      String[] words = key.toLowerCase(Locale.ROOT).split("_");
      StringBuilder out = new StringBuilder();
      for (String word : words) {
         if (!word.isEmpty()) {
            out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
         }
      }

      return out.toString();
   }

   private void record(Block block, Material placed) {
      String original = block.getBlockData().getAsString();
      block.setType(placed, false);
      this.crater.add(new String[]{block.getWorld().getName(), String.valueOf(block.getX()), String.valueOf(block.getY()), String.valueOf(block.getZ()), original, block.getBlockData().getAsString()});
   }

   private void carve() {
      double radius = this.setting("crater-radius", 6.0);
      int maxDepth = (int)Math.max(1.0, this.setting("crater-depth", 3.0));
      int r = (int)Math.ceil(radius);
      ThreadLocalRandom random = ThreadLocalRandom.current();
      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            double d2 = (dx * dx + dz * dz) / (radius * radius);
            if (d2 > 1.0) {
               continue;
            }

            int x = this.centre.getBlockX() + dx;
            int z = this.centre.getBlockZ() + dz;
            int top = this.surface(x, z, this.centre.getBlockY() - 1);
            if (top == Integer.MIN_VALUE) {
               continue;
            }

            // plants and snow layers on the surface go first
            for (int y = top + 1; y <= top + 2; y++) {
               Block above = this.world.getBlockAt(x, y, z);
               if (!above.getType().isAir() && above.isPassable() && !above.isLiquid()) {
                  this.record(above, Material.AIR);
               }
            }

            int depth = (int)Math.round(maxDepth * (1.0 - d2));
            for (int y = top; y > top - depth; y--) {
               Block block = this.world.getBlockAt(x, y, z);
               if (!block.getType().isAir()) {
                  this.record(block, Material.AIR);
               }
            }

            Block floor = this.world.getBlockAt(x, top - depth, z);
            double d = Math.sqrt(d2);
            Material lining = d < 0.3 ? (random.nextDouble() < 0.3 ? Material.CRYING_OBSIDIAN : Material.MAGMA_BLOCK)
               : (d < 0.7 ? (random.nextDouble() < 0.5 ? Material.BLACKSTONE : Material.BASALT) : (random.nextDouble() < 0.5 ? Material.TUFF : Material.COARSE_DIRT));
            if (!floor.getType().isAir() && floor.getState() instanceof TileState == false) {
               this.record(floor, lining);
            }

            if (d > 0.85 && random.nextDouble() < 0.45) {
               Block rim = this.world.getBlockAt(x, top + 1, z);
               if (rim.getType().isAir()) {
                  this.record(rim, random.nextBoolean() ? Material.BLACKSTONE : Material.COARSE_DIRT);
               }
            }
         }
      }

      // the centre of the core sits just above the crater floor
      int floorY = this.surface(this.centre.getBlockX(), this.centre.getBlockZ(), this.centre.getBlockY() - 1);
      if (floorY != Integer.MIN_VALUE) {
         this.centre = new Location(this.world, this.centre.getBlockX(), floorY + 1, this.centre.getBlockZ());
      }
   }

   /** Put back every crater block that is still exactly as the crater left it. */
   private int restoreCrater() {
      int restored = 0;
      for (int i = this.crater.size() - 1; i >= 0; i--) {
         String[] entry = this.crater.get(i);
         World w = Bukkit.getWorld(entry[0]);
         if (w == null) {
            continue;
         }

         try {
            Block block = w.getBlockAt(Integer.parseInt(entry[1]), Integer.parseInt(entry[2]), Integer.parseInt(entry[3]));
            if (block.getBlockData().getAsString().equals(entry[5])) {
               block.setBlockData(Bukkit.createBlockData(entry[4]), false);
               restored++;
            }
         } catch (RuntimeException var6) {
            this.plugin.getLogger().warning("[Fallen Star] Could not restore a crater block: " + var6.getMessage());
         }
      }

      this.crater.clear();
      this.manager.save();
      return restored;
   }

   // ------------------------------------------------------------------ the core and its guardians

   private Location coreSpot() {
      return this.centre.clone().add(0.5, 1.4, 0.5);
   }

   private void spawnCore() {
      Location at = this.coreSpot();
      this.core = this.world.spawn(at, ItemDisplay.class, display -> {
         display.setItemStack(CustomItems.get().create(CustomItems.STAR_CORE, 1));
         display.setPersistent(false);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setGlowing(true);
         display.setGlowColorOverride(Color.fromRGB(255, 215, 80));
         display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2F, 1.2F, 1.2F), new Quaternionf()));
         display.setViewRange(6.0F);
      });
      this.seal = this.world.spawn(at, BlockDisplay.class, display -> {
         display.setBlock(Material.PURPLE_STAINED_GLASS.createBlockData());
         display.setPersistent(false);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setTransformation(new Transformation(new Vector3f(-0.85F, -0.85F, -0.85F), new Quaternionf(), new Vector3f(1.7F, 1.7F, 1.7F), new Quaternionf()));
      });
      this.label = this.world.spawn(at.clone().add(0.0, 1.6, 0.0), TextDisplay.class, display -> {
         display.setBillboard(Display.Billboard.CENTER);
         display.setPersistent(false);
         display.setShadowed(true);
         display.setDefaultBackground(false);
         display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
      });
   }

   private List<Player> playersNear(double radius) {
      List<Player> near = new ArrayList<>();
      Location at = this.coreSpot();
      for (Player player : this.world.getPlayers()) {
         if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
            && player.getLocation().distanceSquared(at) <= radius * radius) {
            near.add(player);
         }
      }

      return near;
   }

   private List<String> waveSpecs(int index) {
      List<?> waves = this.config() == null ? List.of() : this.config().getList("waves", List.of());
      if (index < 0 || index >= waves.size() || !(waves.get(index) instanceof List<?> list)) {
         return List.of();
      }

      List<String> out = new ArrayList<>();
      for (Object entry : list) {
         out.add(String.valueOf(entry));
      }

      return out;
   }

   private int waveCount() {
      return this.config() == null ? 0 : this.config().getList("waves", List.of()).size();
   }

   private void spawnWave() {
      this.wave++;
      this.waveStartedAt = System.currentTimeMillis();
      double radius = this.setting("crater-radius", 6.0);
      double healthMultiplier = this.setting("guardian-health-multiplier", 1.5) * (1.0 + 0.15 * Math.max(0, this.playersNear(48.0).size() - 2));
      ThreadLocalRandom random = ThreadLocalRandom.current();
      for (String spec : this.waveSpecs(this.wave - 1)) {
         String[] parts = spec.trim().split("\\s+");
         EntityType type;
         try {
            type = EntityType.valueOf(parts[0].toUpperCase(Locale.ROOT));
         } catch (IllegalArgumentException var18) {
            this.plugin.getLogger().warning("[Fallen Star] Unknown guardian type: " + parts[0]);
            continue;
         }

         int count = 1;
         try {
            count = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1;
         } catch (NumberFormatException ignored) {
         }

         for (int i = 0; i < count; i++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            int x = this.centre.getBlockX() + (int)Math.round(Math.cos(angle) * (radius - 1.0));
            int z = this.centre.getBlockZ() + (int)Math.round(Math.sin(angle) * (radius - 1.0));
            int y = this.surface(x, z, this.centre.getBlockY());
            Location at = new Location(this.world, x + 0.5, (y == Integer.MIN_VALUE ? this.centre.getBlockY() : y) + 1.0, z + 0.5);
            Entity spawned = this.world.spawnEntity(at, type);
            if (!(spawned instanceof LivingEntity living)) {
               spawned.remove();
               continue;
            }

            living.getPersistentDataContainer().set(this.guardianKey, PersistentDataType.BYTE, (byte)1);
            living.customName(Component.text("☄ Star-Touched " + prettify(type.name()), GOLD));
            living.setCustomNameVisible(false);
            living.setRemoveWhenFarAway(false);
            living.setPersistent(false);                // never saved: a restart cannot leave guardians behind
            AttributeInstance health = living.getAttribute(Attribute.MAX_HEALTH);
            if (health != null) {
               health.setBaseValue(health.getBaseValue() * healthMultiplier);
               living.setHealth(health.getValue());
            }

            if (living instanceof Mob mob) {
               Player target = this.nearest(at);
               if (target != null) {
                  mob.setTarget(target);
               }
            }

            this.guardians.add(living.getUniqueId());
            ParticleManager.showcase(Particle.REVERSE_PORTAL, at.clone().add(0.0, 1.0, 0.0), 30, 0.4, 0.8, 0.4, 0.05, null);
         }
      }

      this.plugin.getSoundManager().world(this.coreSpot(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.0F, 0.7F);
      for (Player player : this.playersNear(64.0)) {
         player.showTitle(Title.title(Component.empty(), Component.text("☄ Guardians - wave " + this.wave + "/" + this.waveCount(), GOLD)));
      }
   }

   private Player nearest(Location at) {
      Player best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Player player : this.playersNear(40.0)) {
         double d = player.getLocation().distanceSquared(at);
         if (d < bestDistance) {
            bestDistance = d;
            best = player;
         }
      }

      return best;
   }

   private int aliveGuardians() {
      this.guardians.removeIf(id -> {
         Entity entity = Bukkit.getEntity(id);
         return entity == null || !entity.isValid() || entity.isDead();
      });
      return this.guardians.size();
   }

   private void free() {
      this.phase = Phase.CORE;
      if (this.seal != null && this.seal.isValid()) {
         this.seal.remove();
      }

      if (this.label != null && this.label.isValid()) {
         this.label.remove();
      }

      Location at = this.coreSpot();
      ParticleManager.showcase(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(255, 230, 150));
      ParticleManager.showcase(Particle.END_ROD, at, 40, 0.4, 0.6, 0.4, 0.15, null);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_GLASS_BREAK, 1.0F, 0.6F);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 1.4F);
      if (this.core != null && this.core.isValid()) {
         this.core.remove();
      }

      if (!this.contribution.isEmpty()) {
         this.contribution.payOut(this.plugin, "Fallen Star", this.config() == null ? null : this.config().getConfigurationSection("rewards"), "fallen-star");
      }

      this.manager.relics().spawn(RelicManager.Type.STAR_CORE, at, "Fallen Star", claimer -> {
      });
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.finish(null), 20L);
   }

   // ------------------------------------------------------------------ main loop

   private void tick() {
      long now = System.currentTimeMillis();
      if (this.phase == Phase.FALLING || this.phase == Phase.DONE || this.phase == Phase.CORE) {
         return;
      }

      double maxMinutes = this.setting("max-minutes", 25.0);
      if (now - this.startedAt > maxMinutes * 60000.0) {
         this.finish("☄ The Fallen Star burns out before anyone can take its core.");
         return;
      }

      // staged reveal
      long since = (now - this.landedAt) / 1000L;
      if (this.revealed < 1 && since >= (long)this.setting("reveal-distance-seconds", 30.0)) {
         this.revealed = 1;
         Location spawn = this.world.getSpawnLocation();
         double dx = this.centre.getX() - spawn.getX();
         double dz = this.centre.getZ() - spawn.getZ();
         int distance = (int)(Math.round(Math.sqrt(dx * dx + dz * dz) / 50.0) * 50L);
         Bukkit.broadcast(Component.text("☄ The star fell about " + distance + " blocks " + compass(dx, dz) + " of spawn.", GOLD));
      }

      if (this.revealed < 2 && since >= (long)this.setting("reveal-coords-seconds", 60.0)) {
         this.revealed = 2;
         Bukkit.broadcast(Component.text("☄ Fallen Star: " + this.centre.getBlockX() + ", " + this.centre.getBlockY() + ", " + this.centre.getBlockZ(), GOLD, TextDecoration.BOLD));
      }

      // beam + spin
      Location at = this.coreSpot();
      for (int i = 0; i < 6; i++) {
         ParticleManager.showcase(Particle.END_ROD, at.clone().add(0.0, 1.0 + Math.random() * 40.0, 0.0), 1, 0.05, 0.0, 0.05, 0.0, null);
      }

      if (this.core != null && this.core.isValid()) {
         Transformation t = this.core.getTransformation();
         this.core.setInterpolationDelay(0);
         this.core.setInterpolationDuration(20);
         this.core.setTransformation(new Transformation(t.getTranslation(), new Quaternionf().rotationY((float)(now % 6000L / 6000.0 * Math.PI * 2.0)), t.getScale(), t.getRightRotation()));
      }

      if (this.phase == Phase.LANDED) {
         if (!this.playersNear(this.setting("trigger-radius", 20.0)).isEmpty()) {
            this.phase = Phase.WAVES;
            Bukkit.broadcast(Component.text("☄ The Star's guardians have awoken!", GOLD));
            if (this.revealed < 2) {
               this.revealed = 2;
               Bukkit.broadcast(Component.text("☄ Fallen Star: " + this.centre.getBlockX() + ", " + this.centre.getBlockY() + ", " + this.centre.getBlockZ(), GOLD, TextDecoration.BOLD));
            }

            this.spawnWave();
         }

         this.updateLabel("Sealed - waiting for challengers");
         return;
      }

      // WAVES
      for (Player player : this.playersNear(this.setting("crater-radius", 6.0) + 2.0)) {
         this.contribution.add(player, Contribution.Kind.SUPPORT, 1.0, this.manager.publicName(player));    // holding the crater
      }

      int alive = this.aliveGuardians();
      long waveTimeout = (long)(this.setting("wave-timeout-seconds", 120.0) * 1000.0);
      if (alive == 0 || now - this.waveStartedAt > waveTimeout) {
         if (this.wave >= this.waveCount()) {
            if (alive == 0) {
               this.free();
               return;
            }
         } else if (this.nextWaveAt == 0L) {
            this.nextWaveAt = now + 4000L;
         } else if (now >= this.nextWaveAt) {
            this.nextWaveAt = 0L;
            this.spawnWave();
         }
      }

      for (UUID id : this.guardians) {
         if (Bukkit.getEntity(id) instanceof Mob mob && (mob.getTarget() == null || mob.getTarget().isDead())) {
            Player target = this.nearest(mob.getLocation());
            if (target != null) {
               mob.setTarget(target);
            }
         }
      }

      this.updateLabel("Guardians: wave " + this.wave + "/" + this.waveCount() + " - " + alive + " left");
   }

   private static String compass(double dx, double dz) {
      String[] names = new String[]{"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
      double yaw = Math.toDegrees(Math.atan2(-dx, dz));
      return names[(int)Math.round(((yaw % 360.0) + 360.0) % 360.0 / 45.0) % 8];
   }

   private void updateLabel(String state) {
      if (this.label != null && this.label.isValid()) {
         this.label.text(Component.text("☄ FALLEN STAR ☄", GOLD, TextDecoration.BOLD).append(Component.newline()).append(Component.text(state, NamedTextColor.WHITE)));
      }
   }

   /** End the event (the core, if freed, lives on as a relic). The crater stays a while for the story, then heals. */
   private void finish(String message) {
      if (this.phase == Phase.DONE) {
         return;
      }

      boolean freed = this.phase == Phase.CORE;
      this.phase = Phase.DONE;
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      if (message != null) {
         Bukkit.broadcast(Component.text(message, NamedTextColor.GRAY));
      }

      for (UUID id : this.guardians) {
         Entity entity = Bukkit.getEntity(id);
         if (entity != null) {
            entity.remove();
         }
      }

      this.guardians.clear();
      for (Entity display : new Entity[]{this.core, this.seal, this.label}) {
         if (display != null && display.isValid()) {
            display.remove();
         }
      }

      for (long key : this.tickets) {
         this.world.removePluginChunkTicket((int)(key >> 32), (int)key, this.plugin);
      }

      this.tickets.clear();
      long delay = (long)(this.setting(freed ? "restore-minutes" : "restore-minutes-failed", freed ? 5.0 : 1.0) * 1200.0);
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (!this.isRunning() && !this.crater.isEmpty()) {
            this.restoreCrater();
         }
      }, Math.max(20L, delay));
   }

   public void stop() {
      this.finish("☄ The Fallen Star fades away.");
   }

   public void shutdown() {
      if (this.isRunning()) {
         this.finish(null);
      }

      if (!this.crater.isEmpty()) {
         this.restoreCrater();
      }
   }

   public String status() {
      if (!this.isRunning()) {
         return "Fallen Star: not active." + (this.crater.isEmpty() ? "" : " (crater still healing)");
      }

      return "Fallen Star: " + this.phase.name().toLowerCase(Locale.ROOT) + " at " + this.centre.getBlockX() + ", " + this.centre.getBlockY() + ", " + this.centre.getBlockZ()
         + (this.phase == Phase.WAVES ? " - wave " + this.wave + "/" + this.waveCount() + ", " + this.guardians.size() + " guardians" : "");
   }

   // ------------------------------------------------------------------ guardian bookkeeping

   private boolean isGuardian(Entity entity) {
      return entity.getPersistentDataContainer().has(this.guardianKey, PersistentDataType.BYTE);
   }

   private static Player attacker(Entity damager) {
      if (damager instanceof Player player) {
         return player;
      }

      return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onDamage(EntityDamageByEntityEvent event) {
      if (this.phase == Phase.WAVES && this.isGuardian(event.getEntity()) && event.getEntity() instanceof LivingEntity living) {
         Player player = attacker(event.getDamager());
         if (player != null) {
            this.contribution.add(player, Contribution.Kind.DAMAGE, Math.min(event.getFinalDamage(), living.getHealth()), this.manager.publicName(player));
         }
      }
   }

   @EventHandler
   public void onGuardianDeath(EntityDeathEvent event) {
      if (this.isGuardian(event.getEntity())) {
         event.setDroppedExp(event.getDroppedExp() * 2);
         Player killer = event.getEntity().getKiller();
         if (killer != null && this.contribution != null) {
            this.contribution.add(killer, Contribution.Kind.MECHANIC, 1.0, this.manager.publicName(killer));
            this.manager.season().add(killer, SeasonManager.Stat.RENOWN, this.manager.season().renownFor("fallen-star-guardian"));
         }

         ParticleManager.spawn(Particle.END_ROD, event.getEntity().getLocation().add(0.0, 1.0, 0.0), 10, 0.3, 0.5, 0.3, 0.05);
      }
   }
}
