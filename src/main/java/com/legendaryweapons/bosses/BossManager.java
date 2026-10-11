package com.legendaryweapons.bosses;

import com.legendaryweapons.LegendaryWeapons;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Boss arenas and the one boss fight that can run at a time.
 *
 * Arenas are fixed spots an admin chooses per boss (/lwevent boss arena <id> [radius]): a deep-dark
 * room for the Warden, open sky for the Wither King, open water for the Leviathan, an End island for
 * the Colossus. A fixed arena is what makes the anti-cheese rules possible: damage from outside the
 * arena or from a vehicle does not count, environment damage is ignored, and the boss is leashed.
 */
public final class BossManager implements Listener {
   public static final List<String> IDS = List.of("hollow_warden", "wither_king", "leviathan", "ender_colossus");
   private final LegendaryWeapons plugin;
   private final File file;
   private final Map<String, Arena> arenas = new LinkedHashMap<>();
   private final NamespacedKey bodyKey;
   private final NamespacedKey minionKey;
   private final Map<Entity, Player> pendingAttackers = new HashMap<>();
   private final List<Long> tickets = new ArrayList<>();
   private World ticketWorld;
   private TempBlocks terrain = new TempBlocks();
   private Boss active;

   public record Arena(String id, Location centre, double radius) {
      public boolean contains(Location loc, double margin) {
         if (loc.getWorld() == null || !loc.getWorld().equals(this.centre.getWorld())) {
            return false;
         }

         double dx = loc.getX() - this.centre.getX();
         double dz = loc.getZ() - this.centre.getZ();
         double r = this.radius + margin;
         return dx * dx + dz * dz <= r * r && Math.abs(loc.getY() - this.centre.getY()) <= Math.max(24.0, this.radius);
      }

      /** Standing room at (x, z) near the arena's height. */
      public Location surface(Location at) {
         World world = at.getWorld();
         int x = at.getBlockX();
         int z = at.getBlockZ();
         for (int y = this.centre.getBlockY() + 6; y >= this.centre.getBlockY() - 12; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && block.getRelative(0, 1, 0).isPassable() && block.getRelative(0, 2, 0).isPassable()) {
               return new Location(world, x + 0.5, y + 1.0, z + 0.5);
            }
         }

         return new Location(world, x + 0.5, this.centre.getBlockY() + 1.0, z + 0.5);
      }
   }

   public BossManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "bosses.yml");
      this.bodyKey = new NamespacedKey(plugin, "boss-body");
      this.minionKey = new NamespacedKey(plugin, "boss-minion");
      this.load();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      (new BukkitRunnable() {
         public void run() {
            Boss boss = BossManager.this.active;
            if (boss != null) {
               try {
                  boss.tick();
               } catch (RuntimeException var3) {
                  BossManager.this.plugin.getLogger().warning("Boss tick failed: " + var3);
                  var3.printStackTrace();
               }
            }
         }
      }).runTaskTimer(plugin, 1L, 1L);
      plugin.getHudManager().register(player -> {
         Boss boss = this.active;
         return boss == null || !boss.arena.contains(player.getLocation(), 8.0) ? null : boss.hud(player);
      });
   }

   NamespacedKey bodyKey() {
      return this.bodyKey;
   }

   NamespacedKey minionKey() {
      return this.minionKey;
   }

   // ------------------------------------------------------------------ persistence

   private void load() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.file);
      for (String id : IDS) {
         String raw = data.getString("arenas." + id);
         if (raw != null) {
            String[] parts = raw.split(";");
            World world = parts.length == 5 ? Bukkit.getWorld(parts[0]) : null;
            if (world != null) {
               try {
                  this.arenas.put(id, new Arena(id, new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])), Double.parseDouble(parts[4])));
               } catch (NumberFormatException ignored) {
               }
            }
         }
      }

      // a fight interrupted by a crash: heal the arena
      List<String> leftover = data.getStringList("terrain");
      if (!leftover.isEmpty()) {
         TempBlocks old = new TempBlocks();
         old.load(leftover);
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            int restored = old.restore();
            this.save();
            this.plugin.getLogger().info("[Bosses] Restored " + restored + " arena blocks left by an interrupted fight.");
         }, 40L);
      }
   }

   public void save() {
      YamlConfiguration data = new YamlConfiguration();
      for (Arena arena : this.arenas.values()) {
         Location c = arena.centre();
         data.set("arenas." + arena.id(), c.getWorld().getName() + ";" + c.getX() + ";" + c.getY() + ";" + c.getZ() + ";" + arena.radius());
      }

      data.set("terrain", this.terrain.size() == 0 ? null : this.terrain.serialize());
      try {
         this.file.getParentFile().mkdirs();
         data.save(this.file);
      } catch (IOException var4) {
         this.plugin.getLogger().severe("Could not save bosses.yml: " + var4.getMessage());
      }
   }

   // ------------------------------------------------------------------ control

   public String setArena(String id, Location at, double radius) {
      String key = id.toLowerCase(Locale.ROOT);
      if (!IDS.contains(key)) {
         return "Unknown boss '" + id + "'. Bosses: " + String.join(", ", IDS);
      }

      if (this.active != null && this.active.id.equals(key)) {
         return "That boss is fighting right now.";
      }

      double r = radius > 0.0 ? radius : this.plugin.getConfig().getDouble("bosses." + key + ".arena-radius", 24.0);
      this.arenas.put(key, new Arena(key, at.getBlock().getLocation().add(0.5, 0.0, 0.5), r));
      this.save();
      return null;
   }

   /** "Overworld 120, 64, -300" for announcements, or null when the boss has no arena. */
   public String arenaText(String id) {
      Arena arena = this.arenas.get(id == null ? "" : id.toLowerCase(Locale.ROOT));
      if (arena == null) {
         return null;
      }

      Location c = arena.centre();
      return com.legendaryweapons.rituals.Ritual.dimensionName(c.getWorld()) + " " + c.getBlockX() + ", " + c.getBlockY() + ", " + c.getBlockZ();
   }

   /** A random boss that has an arena and is enabled (the Director's plain "boss"). */
   public String pickBoss() {
      return this.randomWithArena();
   }

   public boolean hasArena(String id) {
      return this.arenas.containsKey(id);
   }

   public List<String> withArenas() {
      return new ArrayList<>(this.arenas.keySet());
   }

   public boolean isRunning() {
      return this.active != null;
   }

   public String start(String id) {
      if (this.active != null) {
         return "The " + this.active.displayName() + " is already fighting.";
      }

      String key = id == null || id.isBlank() || id.equalsIgnoreCase("random") ? this.randomWithArena() : id.toLowerCase(Locale.ROOT);
      if (key == null) {
         return "No boss has an arena yet. Use /lwevent boss arena <boss> [radius].";
      }

      Arena arena = this.arenas.get(key);
      if (arena == null) {
         return "The " + key + " has no arena. Use /lwevent boss arena " + key + " [radius] where it should fight.";
      }

      if (!this.plugin.getConfig().getBoolean("bosses." + key + ".enabled", true)) {
         return "The " + key + " is disabled in the config.";
      }

      this.terrain = new TempBlocks();
      Boss boss = switch (key) {
         case "hollow_warden" -> new HollowWarden(this.plugin, this, arena, this.terrain);
         case "wither_king" -> new WitherKing(this.plugin, this, arena, this.terrain);
         case "leviathan" -> new Leviathan(this.plugin, this, arena, this.terrain);
         case "ender_colossus" -> new EnderColossus(this.plugin, this, arena, this.terrain);
         default -> null;
      };
      if (boss == null) {
         return "Unknown boss '" + key + "'.";
      }

      this.ticket(arena);
      this.active = boss;
      try {
         String error = boss.start();
         if (error != null) {
            this.active = null;
            this.unticket();
         }

         return error;
      } catch (RuntimeException var6) {
         this.plugin.getLogger().warning("Boss failed to start: " + var6);
         var6.printStackTrace();
         boss.fail(null);
         this.active = null;
         return "The boss failed to start: " + var6.getMessage();
      }
   }

   private String randomWithArena() {
      List<String> options = new ArrayList<>();
      for (String id : this.arenas.keySet()) {
         if (this.plugin.getConfig().getBoolean("bosses." + id + ".enabled", true)) {
            options.add(id);
         }
      }

      return options.isEmpty() ? null : options.get(ThreadLocalRandom.current().nextInt(options.size()));
   }

   public void stop() {
      if (this.active != null) {
         this.active.fail("The " + this.active.displayName() + " was dismissed.");
      }
   }

   void ended(Boss boss) {
      if (this.active == boss) {
         this.active = null;
         this.unticket();
         this.save();
      }
   }

   public String status() {
      if (this.active != null) {
         return "Boss: " + this.active.displayName() + " fighting, " + (int)this.active.pool + "/" + (int)this.active.maxPool + " health, phase " + (this.active.phase + 1) + ".";
      }

      List<String> set = new ArrayList<>();
      for (Arena arena : this.arenas.values()) {
         Location c = arena.centre();
         set.add(arena.id() + " @ " + c.getWorld().getName() + " " + c.getBlockX() + "," + c.getBlockY() + "," + c.getBlockZ() + " r" + (int)arena.radius());
      }

      return "Boss: none fighting. Arenas: " + (set.isEmpty() ? "none set" : String.join("; ", set));
   }

   public void shutdown() {
      if (this.active != null) {
         this.active.fail(null);
      }

      this.terrain.restore();
      this.save();
   }

   /** The arena stays loaded for the whole fight, whoever is near it. */
   private void ticket(Arena arena) {
      this.unticket();
      this.ticketWorld = arena.centre().getWorld();
      int r = (int)Math.ceil(arena.radius() / 16.0) + 1;
      int cx = arena.centre().getBlockX() >> 4;
      int cz = arena.centre().getBlockZ() >> 4;
      for (int dx = -r; dx <= r; dx++) {
         for (int dz = -r; dz <= r; dz++) {
            this.ticketWorld.addPluginChunkTicket(cx + dx, cz + dz, this.plugin);
            this.tickets.add(((long)(cx + dx) << 32) ^ ((cz + dz) & 0xffffffffL));
         }
      }
   }

   private void unticket() {
      if (this.ticketWorld != null) {
         for (long key : this.tickets) {
            this.ticketWorld.removePluginChunkTicket((int)(key >> 32), (int)key, this.plugin);
         }
      }

      this.tickets.clear();
      this.ticketWorld = null;
   }

   // ------------------------------------------------------------------ rules

   private static Player attacker(Entity damager) {
      if (damager instanceof Player player) {
         return player;
      }

      return damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
   }

   private boolean isBody(Entity entity) {
      return this.active != null && entity.equals(this.active.body);
   }

   private boolean ownedByFight(Entity entity) {
      return this.active != null && this.active.owns(entity);
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onBossDamaged(EntityDamageEvent event) {
      Boss boss = this.active;
      if (boss == null) {
         return;
      }

      Entity hit = event.getEntity();
      Player attacker = event instanceof EntityDamageByEntityEvent byEntity ? attacker(byEntity.getDamager()) : null;
      if (!this.isBody(hit)) {
         if (boss.extras.contains(hit) && boss.onPartDamaged(hit, attacker, event)) {
            return;
         }

         return;
      }

      boolean explosion = event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
      if (attacker == null && !explosion) {
         event.setCancelled(true);                     // fire, lava, falling, suffocation, its own minions...
         return;
      }

      if (attacker != null) {
         if (!boss.arena.contains(attacker.getLocation(), 6.0)) {
            event.setCancelled(true);
            com.legendaryweapons.managers.HudManager.message(attacker, Component.text("Too far from the arena - your hits do not count.", NamedTextColor.GRAY));
            return;
         }

         if (attacker.isInsideVehicle()) {
            event.setCancelled(true);
            com.legendaryweapons.managers.HudManager.message(attacker, Component.text("Fight it on foot.", NamedTextColor.GRAY));
            return;
         }
      }

      if (boss.shielded) {
         event.setCancelled(true);
         boss.body.getWorld().playSound(boss.body.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_LAND, 0.4F, 1.8F);
         if (attacker != null) {
            com.legendaryweapons.managers.HudManager.message(attacker, Component.text("Shielded - break it first!", NamedTextColor.AQUA));
         }

         return;
      }

      double damage = boss.incoming(attacker, event, event.getDamage());
      if (damage <= 0.0) {
         event.setCancelled(true);
         return;
      }

      event.setDamage(damage);
      this.pendingAttackers.put(hit, attacker);
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBossDamageLanded(EntityDamageEvent event) {
      Boss boss = this.active;
      if (boss != null && this.isBody(event.getEntity())) {
         Player attacker = this.pendingAttackers.remove(event.getEntity());
         boss.damage(attacker, event.getFinalDamage());
      }
   }

   /** The boss and its minions hit harder as configured, and harder still after feeding. */
   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onBossHits(EntityDamageByEntityEvent event) {
      Boss boss = this.active;
      if (boss == null || !(event.getEntity() instanceof Player)) {
         return;
      }

      Entity source = event.getDamager();
      if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
         source = shooter;
      }

      if (this.ownedByFight(source) && event.getCause() != EntityDamageEvent.DamageCause.SONIC_BOOM) {
         event.setDamage(event.getDamage() * boss.outgoing());
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent event) {
      Boss boss = this.active;
      if (boss != null && boss.arena.contains(event.getEntity().getLocation(), 4.0)) {
         boss.feed(event.getEntity());
      }
   }

   @EventHandler
   public void onDeath(EntityDeathEvent event) {
      LivingEntity dead = event.getEntity();
      if (dead.getPersistentDataContainer().has(this.minionKey, PersistentDataType.STRING)) {
         event.getDrops().clear();
         event.setDroppedExp(Math.min(event.getDroppedExp(), 5));
         Boss boss = this.active;
         if (boss != null && boss.minions.contains(dead.getUniqueId())) {
            boss.onMinionDeath(dead, dead.getKiller());
         }
      } else if (dead.getPersistentDataContainer().has(this.bodyKey, PersistentDataType.STRING)) {
         event.getDrops().clear();
         event.setDroppedExp(0);
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onExplode(EntityExplodeEvent event) {
      Entity source = event.getEntity();
      if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
         source = shooter;
      }

      if (this.ownedByFight(source) || source.getPersistentDataContainer().has(this.bodyKey, PersistentDataType.STRING)) {
         event.blockList().clear();                      // bosses never grief the arena
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onChangeBlock(EntityChangeBlockEvent event) {
      if (this.ownedByFight(event.getEntity())) {
         event.setCancelled(true);                       // withers breaking blocks, endermen picking them up
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onTeleport(EntityTeleportEvent event) {
      Boss boss = this.active;
      if (boss != null && this.ownedByFight(event.getEntity()) && !boss.allowTeleport) {
         event.setCancelled(true);                       // endermen and shulkers only move when the fight says so
      }
   }

   /** The Leviathan's elder guardian body must not hand out Mining Fatigue. */
   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onEffect(EntityPotionEffectEvent event) {
      Boss boss = this.active;
      if (boss != null && event.getModifiedType().equals(PotionEffectType.MINING_FATIGUE) && event.getNewEffect() != null
         && boss.arena.contains(event.getEntity().getLocation(), 16.0)) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onInteract(PlayerInteractEntityEvent event) {
      Boss boss = this.active;
      if (boss != null && boss.extras.contains(event.getRightClicked())) {
         event.setCancelled(true);
         boss.onInteract(event.getPlayer(), event.getRightClicked());
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onFlow(BlockFromToEvent event) {
      if (this.terrain.isOurWater(event.getBlock())) {
         event.setCancelled(true);
      }
   }
}
