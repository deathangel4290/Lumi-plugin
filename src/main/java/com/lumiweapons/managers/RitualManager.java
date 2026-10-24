package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.rituals.Ritual;
import com.lumiweapons.rituals.RitualBlock;
import com.lumiweapons.rituals.RitualScene;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Ritual altars and running rituals, persisted in rituals.yml.
 *
 * Fixes over 1.1: altar positions were never written to disk (saveLocations() was never called
 * and saved the wrong list), so every placed altar stopped working after a restart. A ritual in a
 * world that is not loaded yet (Multiverse and friends load worlds after plugins) used to crash
 * the plugin on startup; it now waits for that world to load.
 */
public class RitualManager implements Listener {
   private final LumiWeapons plugin;
   private final ConfigManager configManager;
   private final Set<Location> altars = new HashSet<>();
   private final List<String> pendingAltars = new ArrayList<>();
   private final List<String> pendingAncient = new ArrayList<>();
   private final List<Ritual> activeRituals = new ArrayList<>();
   private final List<Map<?, ?>> pendingRituals = new ArrayList<>();
   private final List<String> craftedWeapons = new ArrayList<>();
   private final Map<Location, ItemDisplay> altarCores = new HashMap<>();
   private final Map<Location, ItemDisplay> altarShells = new HashMap<>();
   /** Ancient Altars: found around the map, never crafted, unbreakable, at most ritual.ancient-altars.max. */
   private final Set<Location> ancient = new HashSet<>();
   private final Set<Location> discovered = new HashSet<>();
   private final Map<Location, org.bukkit.entity.TextDisplay> labels = new HashMap<>();
   private final com.lumiweapons.rituals.RitualClock clock;
   private final File dataFile;
   private final FileConfiguration dataConfig;

   public RitualManager(LumiWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.dataFile = new File(plugin.getDataFolder(), "rituals.yml");
      this.dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
      this.clock = new com.lumiweapons.rituals.RitualClock(plugin);
      this.craftedWeapons.addAll(this.dataConfig.getStringList("crafted-weapons"));
      this.loadAltars();
      for (String key : this.dataConfig.getStringList("ancient-altars")) {
         try {
            Location location = parseKey(key);
            if (location != null) {
               this.ancient.add(location);
               this.altars.add(location);
            } else {
               this.pendingAltars.add(key);
               this.pendingAncient.add(key);
            }
         } catch (NumberFormatException ignored) {
         }
      }

      for (String key : this.dataConfig.getStringList("discovered-altars")) {
         try {
            Location location = parseKey(key);
            if (location != null) {
               this.discovered.add(location);
            }
         } catch (NumberFormatException ignored) {
         }
      }

      this.loadRituals();
      this.registerRitualBlockRecipe();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      (new BukkitRunnable() {
         double time = 0.0;

         public void run() {
            this.time += 1.0;
            RitualManager.this.idleAltars(this.time);
         }
      }).runTaskTimer(plugin, 40L, 20L);
   }

   // ------------------------------------------------------------------ persistence

   /** Block-aligned copy of a location. Never touches the world, so it cannot load a chunk. */
   private static Location blockOf(Location location) {
      return new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
   }

   private static String key(Location location) {
      return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
   }

   private static Location parseKey(String key) {
      String[] parts = key.split(";");
      if (parts.length != 4) {
         return null;
      } else {
         World world = Bukkit.getWorld(parts[0]);
         return world == null ? null : new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
      }
   }

   /** Location.serialize() maps from 1.1: {world: name, x, y, z, ...}. */
   private static String legacyKey(Map<?, ?> map) {
      Object world = map.get("world");
      Object x = map.get("x");
      Object y = map.get("y");
      Object z = map.get("z");
      return world != null && x instanceof Number nx && y instanceof Number ny && z instanceof Number nz
         ? world + ";" + (int)Math.floor(nx.doubleValue()) + ";" + (int)Math.floor(ny.doubleValue()) + ";" + (int)Math.floor(nz.doubleValue())
         : null;
   }

   private void loadAltars() {
      List<String> keys = new ArrayList<>(this.dataConfig.getStringList("altars"));

      for (Map<?, ?> legacy : this.dataConfig.getMapList("ritual-locations")) {
         String converted = legacyKey(legacy);
         if (converted != null) {
            keys.add(converted);
         }
      }

      for (String key : keys) {
         try {
            Location location = parseKey(key);
            if (location != null) {
               this.altars.add(location);
            } else {
               this.pendingAltars.add(key);
            }
         } catch (NumberFormatException var5) {
            this.plugin.getLogger().warning("Skipping malformed altar entry in rituals.yml: " + key);
         }
      }
   }

   private void loadRituals() {
      this.pendingRituals.addAll(this.dataConfig.getMapList("rituals"));

      for (Map<?, ?> legacy : this.dataConfig.getMapList("active-rituals")) {
         if (legacy.get("location") instanceof Map<?, ?> loc) {
            Map<String, Object> converted = new HashMap<>();
            converted.put("player", legacy.get("player"));
            converted.put("altar", legacyKey(loc));
            converted.put("weapon", legacy.get("weaponKey"));
            converted.put("start", legacy.get("startTime"));
            this.pendingRituals.add(converted);
         }
      }

      this.startPending();
   }

   /** Start every stored ritual whose world is loaded; leave the rest waiting. */
   private void startPending() {
      Iterator<Map<?, ?>> it = this.pendingRituals.iterator();

      while (it.hasNext()) {
         Map<?, ?> map = it.next();

         try {
            Location location = parseKey(String.valueOf(map.get("altar")));
            if (location != null) {
               it.remove();
               UUID player = UUID.fromString(String.valueOf(map.get("player")));
               String weapon = String.valueOf(map.get("weapon"));
               long start = ((Number)map.get("start")).longValue();
               List<ItemStack> items = new ArrayList<>();
               if (map.get("items") instanceof List<?> list) {
                  for (Object entry : list) {
                     if (entry instanceof ItemStack item) {
                        items.add(item);
                     }
                  }
               }

               this.altars.add(location);
               this.activeRituals.add(new Ritual(this.plugin, player, location, weapon, start, items));
            }
         } catch (RuntimeException var10) {
            this.plugin.getLogger().warning("Dropping unreadable ritual from rituals.yml: " + map + " (" + var10 + ")");
            it.remove();
         }
      }
   }

   @EventHandler
   public void onWorldLoad(WorldLoadEvent event) {
      Iterator<String> it = this.pendingAltars.iterator();

      while (it.hasNext()) {
         String key = it.next();
         Location location = parseKey(key);
         if (location != null) {
            this.altars.add(location);
            if (this.pendingAncient.remove(key)) {
               this.ancient.add(location);
            }

            it.remove();
         }
      }

      this.startPending();
   }

   public void save() {
      List<String> altarKeys = new ArrayList<>(this.pendingAltars);

      for (Location location : this.altars) {
         altarKeys.add(key(location));
      }

      List<Map<?, ?>> rituals = new ArrayList<>(this.pendingRituals);

      for (Ritual ritual : this.activeRituals) {
         Map<String, Object> map = new HashMap<>();
         map.put("player", ritual.getPlayerUUID().toString());
         map.put("altar", key(ritual.getLocation()));
         map.put("weapon", ritual.getWeaponKey());
         map.put("start", ritual.getStartTime());
         map.put("items", new ArrayList<>(ritual.getConsumed()));
         rituals.add(map);
      }

      List<String> ancientKeys = new ArrayList<>(this.pendingAncient);
      for (Location location : this.ancient) {
         ancientKeys.add(key(location));
      }

      List<String> discoveredKeys = new ArrayList<>();
      for (Location location : this.discovered) {
         discoveredKeys.add(key(location));
      }

      this.dataConfig.set("ancient-altars", ancientKeys);
      this.dataConfig.set("discovered-altars", discoveredKeys);
      this.dataConfig.set("altars", altarKeys);
      this.dataConfig.set("rituals", rituals);
      this.dataConfig.set("crafted-weapons", this.craftedWeapons);
      this.dataConfig.set("ritual-locations", null);
      this.dataConfig.set("active-rituals", null);

      try {
         this.dataFile.getParentFile().mkdirs();
         this.dataConfig.save(this.dataFile);
      } catch (IOException var6) {
         this.plugin.getLogger().severe("Could not save rituals.yml: " + var6.getMessage());
      }
   }

   public void shutdown() {
      this.save();

      for (Ritual ritual : new ArrayList<>(this.activeRituals)) {
         ritual.suspend();
      }

      for (ItemDisplay core : this.altarCores.values()) {
         if (core.isValid()) {
            core.remove();
         }
      }

      this.altarCores.clear();
      for (ItemDisplay shell : this.altarShells.values()) {
         if (shell.isValid()) {
            shell.remove();
         }
      }

      this.altarShells.clear();
      for (org.bukkit.entity.TextDisplay label : this.labels.values()) {
         if (label.isValid()) {
            label.remove();
         }
      }

      this.labels.clear();
      RitualScene.shutdown();
   }

   // ------------------------------------------------------------------ altar recipe & visuals

   /** Re-reads the altar recipe (after the altar mode or the recipe changed). */
   public void refreshRecipe() {
      this.registerRitualBlockRecipe();
   }

   private void registerRitualBlockRecipe() {
      if (!this.craftedAltarsAllowed()) {
         Bukkit.removeRecipe(LumiWeapons.key("ritual_block_recipe"));
         this.plugin.getLogger().info("Altar mode ANCIENT: altars can't be crafted, only found.");
      } else if (!this.configManager.isRitualBlockRecipeEnabled()) {
         this.plugin.getLogger().info("Ritual Block recipe is disabled in the config.");
      } else {
         NamespacedKey recipeKey = LumiWeapons.key("ritual_block_recipe");
         Bukkit.removeRecipe(recipeKey);
         ShapedRecipe recipe = new ShapedRecipe(recipeKey, RitualBlock.getRitualBlockItem());
         List<String> shape = this.configManager.getRitualBlockRecipeShape();
         if (shape.size() != 3) {
            this.plugin.getLogger().severe("Ritual Block recipe 'shape' in config.yml must have exactly 3 rows!");
         } else {
            recipe.shape(shape.get(0), shape.get(1), shape.get(2));
            Map<Character, Material> ingredients = this.configManager.getRitualBlockRecipeIngredients();
            if (ingredients.isEmpty()) {
               this.plugin.getLogger().severe("No valid 'ingredients' found for Ritual Block recipe in config.yml!");
            } else {
               for (Entry<Character, Material> entry : ingredients.entrySet()) {
                  recipe.setIngredient(entry.getKey(), entry.getValue());
               }

               try {
                  Bukkit.addRecipe(recipe);
               } catch (Exception var7) {
                  this.plugin.getLogger().severe("Could not register the Ritual Block recipe: " + var7.getMessage());
               }
            }
         }
      }
   }

   /**
    * An idle altar gets a slowly turning crystal above it and a few enchant glyphs drifting in,
    * so it reads as something other than a plain enchanting table. Only near players.
    */
   private void idleAltars(double time) {
      for (Location altar : this.altars) {
         World world = altar.getWorld();
         boolean loaded = world != null && world.isChunkLoaded(altar.getBlockX() >> 4, altar.getBlockZ() >> 4);
         boolean watched = false;
         if (loaded) {
            for (Player player : world.getPlayers()) {
               if (player.getLocation().distanceSquared(altar) < 1024.0) {
                  watched = true;
                  break;
               }
            }
         }

         if (loaded) {
            this.keepAltarBlock(altar);
         }

         this.label(altar, loaded && watched && this.ancient.contains(altar));

         boolean custom = this.customAltar();
         ItemDisplay core = this.altarCores.get(altar);
         boolean decorate = this.plugin.getConfig().getBoolean("ritual.altar-decoration", true);
         if (loaded && watched && this.getRitualAtLocation(altar) == null) {
            ParticleManager.spawn(Particle.ENCHANT, altar.clone().add(0.5, 1.4, 0.5), 6, 0.6, 0.3, 0.6, 0.6);
         }

         if (decorate && loaded && watched && this.getRitualAtLocation(altar) == null) {
            float size = custom ? 0.45F : 0.35F;
            if (core == null || !core.isValid()) {
               // the custom altar gets the pack's crystal hovering over its basin; the plain one an amethyst cluster
               ItemStack shown = custom ? RitualScene.model("ritual_crystal", null) : new ItemStack(Material.AMETHYST_CLUSTER);
               core = world.spawn(altar.clone().add(0.5, custom ? 1.5 : 1.15, 0.5), ItemDisplay.class, display -> {
                  display.setItemStack(shown);
                  display.setPersistent(false);
                  display.setBillboard(Billboard.FIXED);
                  display.setInterpolationDuration(20);
                  if (custom) {
                     display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                     display.setBrightness(new Display.Brightness(15, 15));
                  }

                  display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
               });
               this.altarCores.put(altar, core);
            }

            core.setTransformation(new Transformation(new Vector3f(0.0F, (float)(Math.sin(time * 0.5) * 0.06), 0.0F), new Quaternionf().rotateY((float)(time * 0.6)), new Vector3f(size, size, size), new Quaternionf()));
         } else if (core != null) {
            if (core.isValid()) {
               core.remove();
            }

            this.altarCores.remove(altar);
         }
      }
   }

   private boolean customAltar() {
      return this.plugin.getConfig().getBoolean("ritual.custom-altar", true);
   }

   /**
    * The custom altar is the resource pack's model on an item display over an EMPTY SPAWNER: a full
    * block with the enchanting table's mining time that pistons cannot move, with no book poking
    * through and no texture of its own showing (it is see-through, so the model is lit properly).
    * Old enchanting-table altars are converted when their chunk is loaded; with custom-altar off
    * they are turned back. Anything else in the spot (WorldEdit, a missing block) gets no shell.
    */
   private void keepAltarBlock(Location altar) {
      Block block = altar.getBlock();
      boolean custom = this.customAltar();
      if (custom && block.getType() == Material.ENCHANTING_TABLE) {
         block.setType(Material.SPAWNER);
      } else if (!custom && block.getType() == Material.SPAWNER) {
         block.setType(Material.ENCHANTING_TABLE);
      }

      ItemDisplay shell = this.altarShells.get(altar);
      if (custom && block.getType() == Material.SPAWNER) {
         if (shell == null || !shell.isValid()) {
            shell = altar.getWorld().spawn(altar.clone().add(0.5, 0.5, 0.5), ItemDisplay.class, display -> {
               display.setPersistent(false);
               display.setItemStack(this.ancient.contains(altar) ? this.ancientModel() : RitualScene.model("ritual_altar", null));
               display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
               display.setBillboard(Billboard.FIXED);
               display.setViewRange(2.0F);
               // a hair bigger than the block so the spawner's own faces never show through
               display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.002F, 1.002F, 1.002F), new Quaternionf()));
            });
            this.altarShells.put(altar, shell);
         }
      } else if (shell != null) {
         if (shell.isValid()) {
            shell.remove();
         }

         this.altarShells.remove(altar);
      }
   }

   // ------------------------------------------------------------------ altar kinds & crafting times

   /** CRAFTED (players craft and place altars), ANCIENT (only the Ancient Altars around the map), or BOTH. */
   public String altarMode() {
      String mode = this.plugin.getConfig().getString("ritual.altar-mode", "BOTH");
      mode = mode == null ? "BOTH" : mode.trim().toUpperCase(java.util.Locale.ROOT);
      return mode.equals("CRAFTED") || mode.equals("ANCIENT") ? mode : "BOTH";
   }

   public boolean craftedAltarsAllowed() {
      return !this.altarMode().equals("ANCIENT");
   }

   public boolean isAncient(Location location) {
      return this.ancient.contains(blockOf(location));
   }

   /** How many Ancient Altars someone has found (and used) so far. */
   public int discoveredCount() {
      int found = 0;
      for (Location altar : this.discovered) {
         found += this.ancient.contains(altar) ? 1 : 0;
      }

      return found;
   }

   public Set<Location> ancientAltars() {
      return new HashSet<>(this.ancient);
   }

   public int ancientMax() {
      return Math.max(0, this.plugin.getConfig().getInt("ritual.ancient-altars.max", 3));
   }

   public com.lumiweapons.rituals.RitualClock clock() {
      return this.clock;
   }

   /** This weapon's ritual length in seconds: weapons.<key>.ritual-time, else ritual.timer. */
   public int ritualSeconds(String weaponKey) {
      int fallback = this.configManager.getRitualTimer();
      return Math.max(1, this.plugin.getConfig().getInt("weapons." + weaponKey + ".ritual-time", fallback));
   }

   private ItemStack ancientModel() {
      String model = this.plugin.getConfig().getString("ritual.ancient-altars.model", "luminara:ritual_altar");
      ItemStack item = new ItemStack(Material.PAPER);
      NamespacedKey key = model == null ? null : NamespacedKey.fromString(model.trim().toLowerCase(java.util.Locale.ROOT));
      if (key != null) {
         item.editMeta(meta -> meta.setItemModel(key));
      }

      return item;
   }

   /** Null when a ritual may start at this altar now; otherwise why not, for the player. */
   public String whyClosed(Player player, Location location) {
      boolean ancientAltar = this.isAncient(location);
      if (!ancientAltar && this.altarMode().equals("ANCIENT")) {
         return "This altar has gone dormant. Only the Ancient Altars hidden around the map work now.";
      }

      if (ancientAltar && this.altarMode().equals("CRAFTED")) {
         return "The Ancient Altars are asleep on this server.";
      }

      if (!player.hasPermission("lumiweapons.admin") && !this.clock.openNow()) {
         String next = this.clock.nextOpening();
         return "Rituals can only be started " + this.clock.describe() + "." + (next == null ? "" : " Next: " + next + ".");
      }

      return null;
   }

   /** First use of an Ancient Altar: tell the server it has been found (not where). */
   public void discover(Player player, Location location) {
      Location block = blockOf(location);
      if (this.ancient.contains(block) && this.discovered.add(block)) {
         this.save();
         if (this.plugin.getConfig().getBoolean("ritual.ancient-altars.announce-discovery", true)) {
            Bukkit.broadcast(Component.text("✦ " + this.plugin.getEventManager().publicName(player) + " has found an Ancient Altar! ", NamedTextColor.LIGHT_PURPLE)
               .append(Component.text("(" + this.discovered.size() + " of " + this.ancient.size() + " found)", NamedTextColor.GRAY)));
         }

         this.plugin.getSoundManager().world(block, org.bukkit.Sound.BLOCK_END_PORTAL_SPAWN, 0.6F, 1.4F);
      }
   }

   /** Places an Ancient Altar on this block. Null on success, else why not. */
   public String placeAncient(Location location) {
      Location block = blockOf(location);
      if (this.ancient.size() >= this.ancientMax()) {
         return "There are already " + this.ancient.size() + " Ancient Altars (ritual.ancient-altars.max is " + this.ancientMax() + ").";
      }

      if (this.altars.contains(block)) {
         return "There's already an altar there.";
      }

      block.getBlock().setType(this.customAltar() ? Material.SPAWNER : Material.ENCHANTING_TABLE);
      this.altars.add(block);
      this.ancient.add(block);
      this.save();
      this.keepAltarBlock(block);
      return null;
   }

   /**
    * Ancient Altars at random spots within `radius` of the world spawn, on the surface, at least a
    * fifth of the radius apart, up to the cap. Returns how many were placed.
    */
   public int scatterAncient(World world, int count, int radius) {
      java.util.Random random = new java.util.Random();
      Location spawn = world.getSpawnLocation();
      int placed = 0;
      for (int attempt = 0; attempt < count * 40 && placed < count && this.ancient.size() < this.ancientMax(); attempt++) {
         double angle = random.nextDouble() * Math.PI * 2.0;
         double distance = radius * (0.25 + 0.75 * Math.sqrt(random.nextDouble()));
         int x = spawn.getBlockX() + (int)(Math.cos(angle) * distance);
         int z = spawn.getBlockZ() + (int)(Math.sin(angle) * distance);
         org.bukkit.block.Block ground = world.getHighestBlockAt(x, z);
         if (ground.isLiquid() || !ground.getType().isSolid() || ground.getType().name().contains("LEAVES")) {
            continue;
         }

         Location spot = ground.getLocation().add(0.0, 1.0, 0.0);
         boolean crowded = false;
         for (Location other : this.ancient) {
            if (other.getWorld().equals(world) && other.distanceSquared(spot) < Math.pow(radius / 5.0, 2.0)) {
               crowded = true;
               break;
            }
         }

         if (!crowded && this.placeAncient(spot) == null) {
            placed++;
            this.plugin.getLogger().info("Ancient Altar placed at " + world.getName() + " " + spot.getBlockX() + ", " + spot.getBlockY() + ", " + spot.getBlockZ());
         }
      }

      return placed;
   }

   /** The "Ancient Altar" name over an ancient altar while someone is near. */
   private void label(Location altar, boolean show) {
      org.bukkit.entity.TextDisplay label = this.labels.get(altar);
      boolean wanted = show && this.plugin.getConfig().getBoolean("ritual.ancient-altars.label", true);
      if (!wanted) {
         if (label != null) {
            if (label.isValid()) {
               label.remove();
            }

            this.labels.remove(altar);
         }

         return;
      }

      if (label == null || !label.isValid()) {
         label = altar.getWorld().spawn(altar.clone().add(0.5, 2.1, 0.5), org.bukkit.entity.TextDisplay.class, display -> {
            display.setPersistent(false);
            display.text(Component.text("✦ Ancient Altar ✦", NamedTextColor.LIGHT_PURPLE));
            display.setBillboard(Billboard.CENTER);
            display.setViewRange(0.25F);                  // only up close: they are meant to be found
            display.setSeeThrough(false);
            display.setShadowed(true);
         });
         this.labels.put(altar, label);
      }
   }

   // ------------------------------------------------------------------ altars & rituals

   public void addRitualBlock(Location location) {
      Location block = blockOf(location);
      this.altars.add(block);
      this.save();
   }

   /**
    * The placed enchanting table becomes the altar's spawner a tick later: during BlockPlaceEvent the
    * server still has the table's block entity captured and would put it back over a changed block.
    */
   public void convertPlaced(Location location) {
      Location block = blockOf(location);
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         if (this.altars.contains(block)) {
            this.keepAltarBlock(block);
         }
      });
   }

   public void removeRitualBlock(Location location) {
      Location block = blockOf(location);
      this.altars.remove(block);
      this.ancient.remove(block);
      this.discovered.remove(block);
      this.label(block, false);
      ItemDisplay core = this.altarCores.remove(block);
      if (core != null && core.isValid()) {
         core.remove();
      }

      ItemDisplay shell = this.altarShells.remove(block);
      if (shell != null && shell.isValid()) {
         shell.remove();
      }

      Ritual ritual = this.getRitualAtLocation(block);
      if (ritual != null) {
         ritual.cancel();
      }

      this.save();
   }

   public boolean isRitualBlock(Location location) {
      return this.altars.contains(blockOf(location));
   }

   public Set<Location> getAltars() {
      return new HashSet<>(this.altars);
   }

   public boolean isCrafted(String weaponKey) {
      return this.craftedWeapons.contains(weaponKey);
   }

   public List<String> craftedWeapons() {
      return new ArrayList<>(this.craftedWeapons);
   }

   /**
    * Frees a legendary to be forged again (one-per-server mode). Copies players already have are
    * kept. A weapon whose ritual is still running stays taken: break that altar to cancel it.
    */
   public boolean uncraft(String weaponKey) {
      for (Ritual ritual : this.activeRituals) {
         if (ritual.getWeaponKey().equals(weaponKey)) {
            return false;
         }
      }

      boolean removed = this.craftedWeapons.remove(weaponKey);
      if (removed) {
         this.save();
      }

      return removed;
   }

   public boolean startRitual(Player player, Location location, String weaponKey, List<ItemStack> consumed) {
      String closed = this.whyClosed(player, location);
      if (!this.configManager.isRitualCraftingEnabled()) {
         player.sendMessage(Component.text("Ritual crafting is disabled on this server.", NamedTextColor.RED));
         return false;
      } else if (closed != null) {
         player.sendMessage(Component.text(closed, NamedTextColor.RED));
         return false;
      } else if (this.getRitualAtLocation(location) != null) {
         player.sendMessage(Component.text("This altar is already busy.", NamedTextColor.RED));
         return false;
      } else if (this.configManager.isSingleCraft() && this.craftedWeapons.contains(weaponKey)) {
         player.sendMessage(Component.text("That legendary has already been forged on this server.", NamedTextColor.RED));
         return false;
      } else {
         Ritual ritual = new Ritual(this.plugin, player.getUniqueId(), location, weaponKey, System.currentTimeMillis(), consumed);
         this.activeRituals.add(ritual);
         this.craftedWeapons.add(weaponKey);
         this.save();
         Location at = ritual.getLocation();
         Bukkit.broadcast(Component.text("A ritual for ", NamedTextColor.LIGHT_PURPLE)
            .append(LegacyComponentSerializer.legacySection().deserialize(ritual.displayName()))
            .append(Component.text(" has begun in the " + Ritual.dimensionName(at.getWorld()) + " at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + "!", NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(" It completes at " + this.clock.clockIn(this.ritualSeconds(weaponKey) * 1000L) + ".", NamedTextColor.GRAY)));
         return true;
      }
   }

   /** completed=false (altar broken) frees the weapon to be forged again. */
   public void removeRitual(Ritual ritual, boolean completed) {
      this.activeRituals.remove(ritual);
      if (!completed) {
         this.craftedWeapons.remove(ritual.getWeaponKey());
      }

      this.save();
   }

   public Ritual getRitualAtLocation(Location location) {
      Location block = blockOf(location);

      for (Ritual ritual : this.activeRituals) {
         if (ritual.getLocation().equals(block)) {
            return ritual;
         }
      }

      return null;
   }

   public boolean isRitualLocation(Location location) {
      return this.activeRituals.stream().anyMatch(ritual -> ritual.isRitualLocation(location));
   }

   public List<Ritual> getActiveRituals() {
      return new ArrayList<>(this.activeRituals);
   }

   /** Testing: finish every running ritual now. */
   public int completeAll() {
      List<Ritual> running = new ArrayList<>(this.activeRituals);
      running.forEach(Ritual::complete);
      return running.size();
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (player.isOnline()) {
            for (Ritual ritual : this.activeRituals) {
               ritual.showTo(player);
            }
         }
      }, 10L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      for (Ritual ritual : this.activeRituals) {
         ritual.hideFrom(event.getPlayer());
      }
   }
}
