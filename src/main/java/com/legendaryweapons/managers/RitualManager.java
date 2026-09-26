package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.rituals.Ritual;
import com.legendaryweapons.rituals.RitualBlock;
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
   private final LegendaryWeapons plugin;
   private final ConfigManager configManager;
   private final Set<Location> altars = new HashSet<>();
   private final List<String> pendingAltars = new ArrayList<>();
   private final List<Ritual> activeRituals = new ArrayList<>();
   private final List<Map<?, ?>> pendingRituals = new ArrayList<>();
   private final List<String> craftedWeapons = new ArrayList<>();
   private final Map<Location, ItemDisplay> altarCores = new HashMap<>();
   private final File dataFile;
   private final FileConfiguration dataConfig;

   public RitualManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.configManager = plugin.getConfigManager();
      this.dataFile = new File(plugin.getDataFolder(), "rituals.yml");
      this.dataConfig = YamlConfiguration.loadConfiguration(this.dataFile);
      this.craftedWeapons.addAll(this.dataConfig.getStringList("crafted-weapons"));
      this.loadAltars();
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
         Location location = parseKey(it.next());
         if (location != null) {
            this.altars.add(location);
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
   }

   // ------------------------------------------------------------------ altar recipe & visuals

   private void registerRitualBlockRecipe() {
      if (!this.configManager.isRitualBlockRecipeEnabled()) {
         this.plugin.getLogger().info("Ritual Block recipe is disabled in the config.");
      } else {
         NamespacedKey recipeKey = new NamespacedKey(this.plugin, "ritual_block_recipe");
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

         ItemDisplay core = this.altarCores.get(altar);
         if (loaded && watched && this.getRitualAtLocation(altar) == null) {
            if (core == null || !core.isValid()) {
               core = world.spawn(altar.clone().add(0.5, 1.45, 0.5), ItemDisplay.class, display -> {
                  display.setItemStack(new ItemStack(Material.AMETHYST_CLUSTER));
                  display.setPersistent(false);
                  display.setBillboard(Billboard.FIXED);
                  display.setInterpolationDuration(20);
                  display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.55F, 0.55F, 0.55F), new Quaternionf()));
               });
               this.altarCores.put(altar, core);
            }

            core.setTransformation(new Transformation(new Vector3f(0.0F, (float)(Math.sin(time * 0.5) * 0.08), 0.0F), new Quaternionf().rotateY((float)(time * 0.6)), new Vector3f(0.55F, 0.55F, 0.55F), new Quaternionf()));
            ParticleManager.spawn(Particle.ENCHANT, altar.clone().add(0.5, 1.6, 0.5), 6, 0.6, 0.3, 0.6, 0.6);
         } else if (core != null) {
            if (core.isValid()) {
               core.remove();
            }

            this.altarCores.remove(altar);
         }
      }
   }

   // ------------------------------------------------------------------ altars & rituals

   public void addRitualBlock(Location location) {
      this.altars.add(blockOf(location));
      this.save();
   }

   public void removeRitualBlock(Location location) {
      Location block = blockOf(location);
      this.altars.remove(block);
      ItemDisplay core = this.altarCores.remove(block);
      if (core != null && core.isValid()) {
         core.remove();
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

   public boolean startRitual(Player player, Location location, String weaponKey, List<ItemStack> consumed) {
      if (!this.configManager.isRitualCraftingEnabled()) {
         player.sendMessage(Component.text("Ritual crafting is disabled on this server.", NamedTextColor.RED));
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
            .append(Component.text(" has begun in the " + Ritual.dimensionName(at.getWorld()) + " at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + "!", NamedTextColor.LIGHT_PURPLE)));
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
