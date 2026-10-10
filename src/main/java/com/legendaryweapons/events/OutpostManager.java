package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Outposts: permanent capture points that give people a reason to fight when no event is on.
 *
 * Stand in an outpost alone (you and players you mutually trust count as one side) to capture it.
 * Someone else's outpost is first neutralised, then captured. Anyone else inside pauses it. The
 * owner is told when it is under attack.
 *
 * Income is deliberately small so the first owner cannot snowball: every outposts.income-minutes
 * that the owner is online (outposts.income-requires-online), the outpost's type pays its income
 * table into their reward chest, plus Renown and Outpost Time on the season board. A player can
 * hold at most outposts.max-per-player.
 */
public final class OutpostManager {
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final File file;
   private final Map<String, Outpost> outposts = new LinkedHashMap<>();

   private static final class Outpost {
      final String name;
      final String type;
      final Location centre;
      final double radius;
      UUID owner;
      String ownerName;
      double progress;                 // 0..100 toward the capturer, or the owner's hold
      UUID capturer;
      int onlineMinutes;
      long lastAttackNotice;
      long secondCounter;
      final List<BlockDisplay> ring = new ArrayList<>();
      TextDisplay label;
      Material shown;
      String state = "";

      Outpost(String name, String type, Location centre, double radius) {
         this.name = name;
         this.type = type;
         this.centre = centre;
         this.radius = radius;
      }
   }

   public OutpostManager(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.file = new File(plugin.getDataFolder(), "outposts.yml");
      this.load();
      (new BukkitRunnable() {
         public void run() {
            try {
               OutpostManager.this.tick();
            } catch (RuntimeException var2) {
               OutpostManager.this.plugin.getLogger().warning("Outpost tick failed: " + var2);
            }
         }
      }).runTaskTimer(plugin, 40L, 20L);
      plugin.getHudManager().register(this::hud);
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("outposts");
   }

   private double setting(String key, double fallback) {
      return this.config() == null ? fallback : this.config().getDouble(key, fallback);
   }

   private String displayName(Outpost outpost) {
      String configured = this.config() == null ? null : this.config().getString("types." + outpost.type + ".display");
      return configured != null ? configured : outpost.name;
   }

   // ------------------------------------------------------------------ persistence

   private void load() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.file);
      for (String name : data.getKeys(false)) {
         ConfigurationSection row = data.getConfigurationSection(name);
         World world = row == null ? null : Bukkit.getWorld(row.getString("world", ""));
         if (world == null) {
            this.plugin.getLogger().warning("[Outposts] Skipping '" + name + "': its world is not loaded.");
            continue;
         }

         Outpost outpost = new Outpost(name, row.getString("type", "plains"), new Location(world, row.getInt("x"), row.getInt("y"), row.getInt("z")), row.getDouble("radius", 6.0));
         String owner = row.getString("owner");
         if (owner != null) {
            try {
               outpost.owner = UUID.fromString(owner);
               outpost.ownerName = row.getString("owner-name", "?");
               outpost.progress = 100.0;
            } catch (IllegalArgumentException ignored) {
            }
         }

         outpost.onlineMinutes = row.getInt("minutes", 0);
         this.outposts.put(name.toLowerCase(Locale.ROOT), outpost);
      }
   }

   public void save() {
      YamlConfiguration data = new YamlConfiguration();
      for (Outpost outpost : this.outposts.values()) {
         String base = outpost.name;
         data.set(base + ".world", outpost.centre.getWorld().getName());
         data.set(base + ".x", outpost.centre.getBlockX());
         data.set(base + ".y", outpost.centre.getBlockY());
         data.set(base + ".z", outpost.centre.getBlockZ());
         data.set(base + ".radius", outpost.radius);
         data.set(base + ".type", outpost.type);
         data.set(base + ".owner", outpost.owner == null ? null : outpost.owner.toString());
         data.set(base + ".owner-name", outpost.ownerName);
         data.set(base + ".minutes", outpost.onlineMinutes);
      }

      try {
         this.file.getParentFile().mkdirs();
         data.save(this.file);
      } catch (IOException var5) {
         this.plugin.getLogger().severe("Could not save outposts.yml: " + var5.getMessage());
      }
   }

   // ------------------------------------------------------------------ admin

   public String create(String name, String type, Location at, double radius) {
      String key = name.toLowerCase(Locale.ROOT);
      if (!name.matches("[A-Za-z0-9_-]{1,24}")) {
         return "Outpost names are letters, digits, - and _ (max 24).";
      }

      if (this.outposts.containsKey(key)) {
         return "An outpost called '" + name + "' already exists.";
      }

      if (this.config() == null || !this.config().isConfigurationSection("types." + type)) {
         return "Unknown outpost type '" + type + "'. Types: " + this.types();
      }

      this.outposts.put(key, new Outpost(name, type, at.getBlock().getLocation(), radius > 0.0 ? radius : this.setting("radius", 6.0)));
      this.save();
      return null;
   }

   public String remove(String name) {
      Outpost outpost = this.outposts.remove(name.toLowerCase(Locale.ROOT));
      if (outpost == null) {
         return "No outpost called '" + name + "'.";
      }

      this.clearDisplays(outpost);
      this.save();
      return null;
   }

   public String reset(String name) {
      Outpost outpost = this.outposts.get(name.toLowerCase(Locale.ROOT));
      if (outpost == null) {
         return "No outpost called '" + name + "'.";
      }

      outpost.owner = null;
      outpost.ownerName = null;
      outpost.progress = 0.0;
      outpost.capturer = null;
      outpost.onlineMinutes = 0;
      this.save();
      return null;
   }

   public List<String> types() {
      return this.config() == null || this.config().getConfigurationSection("types") == null ? List.of()
         : new ArrayList<>(this.config().getConfigurationSection("types").getKeys(false));
   }

   public List<String> names() {
      List<String> out = new ArrayList<>();
      for (Outpost outpost : this.outposts.values()) {
         out.add(outpost.name);
      }

      return out;
   }

   /** Chat lines for /outposts. */
   public List<Component> list() {
      List<Component> lines = new ArrayList<>();
      lines.add(Component.text("🏰 Outposts", NamedTextColor.GOLD, TextDecoration.BOLD));
      if (this.outposts.isEmpty()) {
         lines.add(Component.text("  None have been built yet.", NamedTextColor.GRAY));
      }

      for (Outpost outpost : this.outposts.values()) {
         Location c = outpost.centre;
         lines.add(Component.text("  " + this.displayName(outpost) + " ", NamedTextColor.YELLOW)
            .append(Component.text("(" + com.legendaryweapons.rituals.Ritual.dimensionName(c.getWorld()) + " " + c.getBlockX() + ", " + c.getBlockY() + ", " + c.getBlockZ() + ") ", NamedTextColor.GRAY))
            .append(Component.text(outpost.owner == null ? "unclaimed" : "held by " + outpost.ownerName, outpost.owner == null ? NamedTextColor.WHITE : NamedTextColor.GOLD)));
      }

      return lines;
   }

   // ------------------------------------------------------------------ capture loop

   private boolean inside(Outpost outpost, Player player) {
      Location loc = player.getLocation();
      if (!loc.getWorld().equals(outpost.centre.getWorld())) {
         return false;
      }

      double dx = loc.getX() - (outpost.centre.getX() + 0.5);
      double dz = loc.getZ() - (outpost.centre.getZ() + 0.5);
      return dx * dx + dz * dz <= outpost.radius * outpost.radius && Math.abs(loc.getY() - outpost.centre.getY()) <= this.setting("vertical-range", 5.0);
   }

   private int owned(UUID player) {
      int count = 0;
      for (Outpost outpost : this.outposts.values()) {
         if (player.equals(outpost.owner)) {
            count++;
         }
      }

      return count;
   }

   private boolean sameSide(UUID a, UUID b) {
      return a.equals(b) || this.plugin.getTrustManager().isMutuallyTrusted(a, b);
   }

   private void tick() {
      double perSecond = 100.0 / Math.max(5.0, this.setting("capture-seconds", 60.0));
      boolean minute = false;
      for (Outpost outpost : this.outposts.values()) {
         outpost.secondCounter++;
         if (outpost.secondCounter % 60L == 0L) {
            minute = true;
            this.payTick(outpost);
         }

         if (!outpost.centre.getWorld().isChunkLoaded(outpost.centre.getBlockX() >> 4, outpost.centre.getBlockZ() >> 4)) {
            continue;
         }

         List<Player> inside = new ArrayList<>();
         for (Player player : outpost.centre.getWorld().getPlayers()) {
            if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) && this.inside(outpost, player)) {
               inside.add(player);
            }
         }

         // one side = one player plus everyone mutually trusted with them
         Player lead = inside.isEmpty() ? null : inside.get(0);
         boolean contested = false;
         for (Player player : inside) {
            if (!this.sameSide(lead.getUniqueId(), player.getUniqueId())) {
               contested = true;
               break;
            }
         }

         if (inside.isEmpty()) {
            outpost.state = outpost.owner == null ? "Unclaimed" : "Held by " + outpost.ownerName;
            if (outpost.owner == null && outpost.progress > 0.0) {
               outpost.progress = Math.max(0.0, outpost.progress - perSecond * 0.5);
            } else if (outpost.owner != null && outpost.progress < 100.0) {
               outpost.progress = Math.min(100.0, outpost.progress + perSecond * 0.5);
            }
         } else if (contested) {
            outpost.state = "CONTESTED";
         } else if (outpost.owner != null && this.sameSide(outpost.owner, lead.getUniqueId())) {
            outpost.progress = Math.min(100.0, outpost.progress + perSecond);
            outpost.state = "Held by " + outpost.ownerName;
         } else if (outpost.owner != null) {
            outpost.progress -= perSecond;
            outpost.state = "Being taken by " + this.manager.publicName(lead);
            this.warnOwner(outpost, lead);
            if (outpost.progress <= 0.0) {
               Bukkit.broadcast(Component.text("🏰 " + this.manager.publicName(lead) + " has neutralised " + outpost.ownerName + "'s " + this.displayName(outpost) + "!", NamedTextColor.GOLD));
               outpost.owner = null;
               outpost.ownerName = null;
               outpost.progress = 0.0;
               outpost.capturer = lead.getUniqueId();
               outpost.onlineMinutes = 0;
               this.save();
            }
         } else {
            if (outpost.capturer == null || !this.sameSide(outpost.capturer, lead.getUniqueId())) {
               outpost.capturer = lead.getUniqueId();
               outpost.progress = 0.0;
            }

            if (this.owned(lead.getUniqueId()) >= (int)this.setting("max-per-player", 2.0)) {
               outpost.state = this.manager.publicName(lead) + " already holds the most outposts allowed";
               HudManager.message(lead, Component.text("You already hold " + (int)this.setting("max-per-player", 2.0) + " outposts - you cannot take another.", NamedTextColor.RED));
            } else {
               outpost.progress += perSecond;
               outpost.state = "Capturing: " + this.manager.publicName(lead);
               if (outpost.progress >= 100.0) {
                  this.capture(outpost, lead);
               }
            }
         }

         this.render(outpost, contested);
      }

      if (minute) {
         this.save();
      }
   }

   private void capture(Outpost outpost, Player player) {
      outpost.owner = player.getUniqueId();
      outpost.ownerName = this.manager.publicName(player);
      outpost.progress = 100.0;
      outpost.capturer = null;
      outpost.onlineMinutes = 0;
      this.save();
      Bukkit.broadcast(Component.text("🏰 " + outpost.ownerName + " has captured the " + this.displayName(outpost) + "!", NamedTextColor.GOLD, TextDecoration.BOLD));
      this.plugin.getSoundManager().world(outpost.centre, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.2F);
      this.manager.season().renown(player, "outpost-capture", 1.0, "for capturing the " + this.displayName(outpost));
   }

   private void warnOwner(Outpost outpost, Player attacker) {
      long now = System.currentTimeMillis();
      Player owner = outpost.owner == null ? null : Bukkit.getPlayer(outpost.owner);
      if (owner != null && now - outpost.lastAttackNotice > 60000L) {
         outpost.lastAttackNotice = now;
         owner.sendMessage(Component.text("🏰 Your " + this.displayName(outpost) + " is under attack by " + this.manager.publicName(attacker) + "!", NamedTextColor.RED));
         this.plugin.getSoundManager().self(owner, Sound.BLOCK_BELL_USE, 1.0F, 0.8F);
      }
   }

   /** Once a minute per outpost: Outpost Time, and income every income-minutes the owner is online. */
   private void payTick(Outpost outpost) {
      if (outpost.owner == null) {
         return;
      }

      Player owner = Bukkit.getPlayer(outpost.owner);
      this.manager.season().add(outpost.owner, outpost.ownerName, SeasonManager.Stat.OUTPOST_MINUTES, 1.0);
      if (owner == null && this.config() != null && this.config().getBoolean("income-requires-online", true)) {
         return;
      }

      outpost.onlineMinutes++;
      int every = (int)Math.max(1.0, this.setting("income-minutes", 60.0));
      if (outpost.onlineMinutes % every == 0) {
         List<String> income = this.config() == null ? List.of() : this.config().getStringList("types." + outpost.type + ".income");
         this.manager.vault().depositSpecs(outpost.owner, outpost.ownerName, income, this.displayName(outpost) + " income");
         this.manager.season().give(outpost.owner, outpost.ownerName, this.manager.season().renownFor("outpost-hour"));
      }
   }

   // ------------------------------------------------------------------ visuals

   private void clearDisplays(Outpost outpost) {
      for (BlockDisplay display : outpost.ring) {
         if (display.isValid()) {
            display.remove();
         }
      }

      outpost.ring.clear();
      if (outpost.label != null && outpost.label.isValid()) {
         outpost.label.remove();
      }

      outpost.label = null;
   }

   private double groundY(Outpost outpost, double x, double z) {
      World world = outpost.centre.getWorld();
      for (int y = outpost.centre.getBlockY() + 3; y >= outpost.centre.getBlockY() - 5; y--) {
         Block block = world.getBlockAt((int)Math.floor(x), y, (int)Math.floor(z));
         if (!block.isPassable()) {
            return y + 1.0;
         }
      }

      return outpost.centre.getBlockY();
   }

   private void buildRing(Outpost outpost) {
      outpost.shown = Material.WHITE_CONCRETE;
      for (BlockDisplay display : outpost.ring) {
         if (display.isValid()) {
            display.remove();
         }
      }

      outpost.ring.clear();
      int segments = Math.max(20, (int)Math.round(outpost.radius * 2.0 * Math.PI / 1.0));
      float length = (float)(outpost.radius * 2.0 * Math.PI / segments) + 0.05F;
      for (int i = 0; i < segments; i++) {
         double angle = Math.PI * 2.0 * (i + 0.5) / segments;
         double x = outpost.centre.getX() + 0.5 + Math.cos(angle) * outpost.radius;
         double z = outpost.centre.getZ() + 0.5 + Math.sin(angle) * outpost.radius;
         Location at = new Location(outpost.centre.getWorld(), x, this.groundY(outpost, x, z) + 0.01, z);
         Quaternionf turn = new Quaternionf().rotationY((float)(-angle - Math.PI / 2.0));
         Vector3f half = new Vector3f(length / 2.0F, 0.0F, 0.11F);
         turn.transform(half);
         Transformation shape = new Transformation(half.negate(), turn, new Vector3f(length, 0.05F, 0.22F), new Quaternionf());
         outpost.ring.add(outpost.centre.getWorld().spawn(at, BlockDisplay.class, display -> {
            display.setPersistent(false);
            display.setBlock(Material.WHITE_CONCRETE.createBlockData());
            display.setTransformation(shape);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange(3.0F);
         }));
      }
   }

   private void render(Outpost outpost, boolean contested) {
      if (outpost.ring.isEmpty() || !outpost.ring.get(0).isValid()) {
         this.buildRing(outpost);
      }

      Material colour = contested ? Material.RED_CONCRETE : (outpost.owner != null ? Material.GOLD_BLOCK : (outpost.progress > 0.0 ? Material.LIGHT_BLUE_CONCRETE : Material.WHITE_CONCRETE));
      if (outpost.shown != colour) {
         outpost.shown = colour;
         for (BlockDisplay display : outpost.ring) {
            display.setBlock(colour.createBlockData());
         }
      }

      if (outpost.label == null || !outpost.label.isValid()) {
         outpost.label = outpost.centre.getWorld().spawn(outpost.centre.clone().add(0.5, this.setting("label-height", 3.0), 0.5), TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
         });
      }

      int bars = (int)Math.round(Math.max(0.0, Math.min(100.0, outpost.progress)) / 10.0);
      outpost.label.text(Component.text("🏰 " + this.displayName(outpost), NamedTextColor.GOLD, TextDecoration.BOLD)
         .append(Component.newline())
         .append(Component.text(outpost.state, contested ? NamedTextColor.RED : NamedTextColor.WHITE))
         .append(Component.newline())
         .append(Component.text("■".repeat(bars), outpost.owner != null ? NamedTextColor.GOLD : NamedTextColor.AQUA))
         .append(Component.text("■".repeat(10 - bars), NamedTextColor.DARK_GRAY)));
      if (outpost.secondCounter % 2L == 0L) {
         ParticleManager.showcase(Particle.END_ROD, outpost.centre.clone().add(0.5, 1.0 + Math.random() * 6.0, 0.5), 1, 0.05, 0.0, 0.05, 0.0, null);
      }
   }

   private Component hud(Player player) {
      for (Outpost outpost : this.outposts.values()) {
         if (this.inside(outpost, player)) {
            return Component.text("🏰 " + this.displayName(outpost) + " " + (int)Math.round(Math.max(0.0, outpost.progress)) + "%", outpost.owner != null ? NamedTextColor.GOLD : NamedTextColor.AQUA);
         }
      }

      return null;
   }

   public void shutdown() {
      for (Outpost outpost : this.outposts.values()) {
         this.clearDisplays(outpost);
      }

      this.save();
   }
}
