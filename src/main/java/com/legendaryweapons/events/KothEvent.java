package com.legendaryweapons.events;

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
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * King of the Hill. An admin places the Hill Marker (or runs /lwevent koth setcenter) to fix the
 * centre, then starts the event.
 *
 * Points (all in events.koth.points):
 *   - holding the hill alone: per-second each second,
 *   - holding it uninterrupted: streak-bonus every streak-every seconds,
 *   - taking it from the previous king: capture-bonus,
 *   - a kill where the killer or the victim is on the hill: kill-bonus,
 *   - everyone on a contested hill: contested-per-second (0 by default).
 * First to win-points wins on the spot; otherwise the top scorer when the clock runs out.
 *
 * The hill is outlined on the ground by a ring of glowing display blocks (gold, red while
 * contested) that follows the terrain, plus a light column over the centre, so it can be found
 * from a distance whatever a player's particle setting is.
 */
public class KothEvent {
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final Map<UUID, Integer> points = new HashMap<>();
   private final Map<UUID, String> names = new HashMap<>();
   private final BossBar bar = BossBar.bossBar(Component.text("King of the Hill"), 1.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
   private final List<BlockDisplay> ring = new ArrayList<>();
   private Location center;
   private TextDisplay hologram;
   private UUID king;
   private int held;
   private Boolean ringContested;
   private BukkitTask task;
   private long endsAt;
   private long totalMillis;

   public KothEvent(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
   }

   public boolean isRunning() {
      return this.task != null;
   }

   public Location getCenter() {
      return this.center;
   }

   void setCenterSilently(Location center) {
      this.center = center == null ? null : center.getBlock().getLocation();
   }

   public void setCenter(Location center) {
      this.center = center == null ? null : center.getBlock().getLocation();
      this.manager.save();
   }

   private double radius() {
      return Math.max(1.0, this.plugin.getConfig().getDouble("events.koth.radius", 5.0));
   }

   public String start(int minutes) {
      if (this.isRunning()) {
         return "King of the Hill is already running.";
      } else if (this.center == null || this.center.getWorld() == null) {
         return "No hill set. Place a Hill Marker (/lwevent koth marker) or use /lwevent koth setcenter.";
      } else {
         this.points.clear();
         this.names.clear();
         this.king = null;
         this.held = 0;
         this.buildRing();
         this.totalMillis = Math.max(1, minutes) * 60000L;
         this.endsAt = System.currentTimeMillis() + this.totalMillis;
         this.removeHologram();
         this.hologram = this.center.getWorld().spawn(this.center.clone().add(0.5, this.plugin.getConfig().getDouble("events.koth.hologram-height", 3.5), 0.5), TextDisplay.class, display -> {
            display.setBillboard(Billboard.CENTER);
            display.setPersistent(false);
            display.setShadowed(true);
            display.setDefaultBackground(false);
            display.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            display.setLineWidth(220);
         });

         for (Player player : Bukkit.getOnlinePlayers()) {
            player.showBossBar(this.bar);
            player.showTitle(Title.title(Component.text("King of the Hill", NamedTextColor.GOLD), Component.text(this.where(), NamedTextColor.GRAY)));
         }

         int goal = this.winPoints();
         Bukkit.broadcast(Component.text("♔ King of the Hill has begun at " + this.where() + "! Hold the hill alone to score points"
            + (goal > 0 ? " - first to " + goal + " wins." : ".") + " " + minutes + " minutes.", NamedTextColor.GOLD));
         this.task = (new BukkitRunnable() {
            public void run() {
               KothEvent.this.tick();
            }
         }).runTaskTimer(this.plugin, 20L, 20L);
         return null;
      }
   }

   private String where() {
      return com.legendaryweapons.rituals.Ritual.dimensionName(this.center.getWorld()) + " " + this.center.getBlockX() + ", " + this.center.getBlockY() + ", " + this.center.getBlockZ();
   }

   private boolean inside(Location loc) {
      if (loc.getWorld() == null || !loc.getWorld().equals(this.center.getWorld())) {
         return false;
      }

      double radius = this.radius();
      double vertical = this.plugin.getConfig().getDouble("events.koth.vertical-range", 4.0);
      double dx = loc.getX() - (this.center.getX() + 0.5);
      double dz = loc.getZ() - (this.center.getZ() + 0.5);
      return dx * dx + dz * dz <= radius * radius && Math.abs(loc.getY() - this.center.getY()) <= vertical;
   }

   private List<Player> onHill() {
      List<Player> on = new ArrayList<>();

      for (Player player : this.center.getWorld().getPlayers()) {
         if (!player.isDead() && player.getGameMode() != GameMode.SPECTATOR && this.inside(player.getLocation())) {
            on.add(player);
         }
      }

      return on;
   }

   private int setting(String key, int def) {
      return this.plugin.getConfig().getInt("events.koth.points." + key, def);
   }

   private int winPoints() {
      return Math.max(0, this.plugin.getConfig().getInt("events.koth.win-points", 300));
   }

   private void award(Player player, int amount, String why) {
      if (amount > 0) {
         int total = this.points.merge(player.getUniqueId(), amount, Integer::sum);
         this.names.put(player.getUniqueId(), this.manager.publicName(player));
         if (why != null) {
            player.sendMessage(Component.text("+" + amount + " ", NamedTextColor.GOLD).append(Component.text(why + " (" + total + " points)", NamedTextColor.YELLOW)));
         }
      }
   }

   /** Called from EventManager on every player death. */
   public void onKill(Player victim, Player killer) {
      if (this.isRunning() && killer != null && killer != victim) {
         // the victim is already dead here, so test positions rather than onHill()
         if (this.inside(killer.getLocation()) || this.inside(victim.getLocation())) {
            this.award(killer, this.setting("kill-bonus", 15), "Hill kill");
            this.plugin.getSoundManager().self(killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8F, 1.2F);
         }
      }
   }

   private void tick() {
      long left = this.endsAt - System.currentTimeMillis();
      if (left <= 0L) {
         this.stop(true);
      } else {
         List<Player> on = this.onHill();
         Component state;
         if (on.size() == 1) {
            Player current = on.get(0);
            if (!current.getUniqueId().equals(this.king)) {
               if (this.king != null) {
                  this.award(current, this.setting("capture-bonus", 5), "Captured the hill");
               }

               this.king = current.getUniqueId();
               this.held = 0;
               this.plugin.getSoundManager().self(current, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8F, 1.4F);
            }

            this.held++;
            this.award(current, this.setting("per-second", 1), null);
            int every = this.setting("streak-every", 30);
            if (every > 0 && this.held % every == 0) {
               this.award(current, this.setting("streak-bonus", 10), "Held the hill for " + this.held + "s");
            }

            state = Component.text("King: ", NamedTextColor.GOLD).append(Component.text(this.manager.publicName(current), NamedTextColor.WHITE));
         } else if (on.isEmpty()) {
            this.held = 0;
            state = Component.text("The hill is empty", NamedTextColor.GRAY);
         } else {
            this.held = 0;
            for (Player player : on) {
               this.award(player, this.setting("contested-per-second", 0), null);
            }

            state = Component.text("Contested! (" + on.size() + " on the hill)", NamedTextColor.RED);
         }

         for (Player player : on) {
            int mine = this.points.getOrDefault(player.getUniqueId(), 0);
            com.legendaryweapons.managers.HudManager.message(player, Component.text("♔ ", NamedTextColor.GOLD)
               .append(Component.text(mine + " points", NamedTextColor.YELLOW))
               .append(Component.text("  #" + this.rankOf(player.getUniqueId()), NamedTextColor.GRAY)));
         }

         long seconds = left / 1000L;
         this.bar.name(Component.text("♔ King of the Hill  ", NamedTextColor.GOLD).append(state).append(Component.text("  " + String.format("%02d:%02d", seconds / 60L, seconds % 60L), NamedTextColor.WHITE)));
         this.bar.progress((float)Math.max(0.0, Math.min(1.0, (double)left / this.totalMillis)));
         this.refreshHologram(state, seconds);
         this.drawBoundary(on.size() > 1);
         int goal = this.winPoints();
         if (goal > 0 && !this.points.isEmpty() && this.ranking().get(0).getValue() >= goal) {
            this.stop(true);
         }
      }
   }

   private int rankOf(UUID id) {
      List<Map.Entry<UUID, Integer>> ranked = this.ranking();
      for (int i = 0; i < ranked.size(); i++) {
         if (ranked.get(i).getKey().equals(id)) {
            return i + 1;
         }
      }

      return ranked.size() + 1;
   }

   /** Ground level near (x, z) close to the hill's height, so the ring hugs slopes instead of floating. */
   private double groundY(double x, double z) {
      org.bukkit.World world = this.center.getWorld();
      int bx = (int)Math.floor(x);
      int bz = (int)Math.floor(z);
      int top = this.center.getBlockY() + 3;
      for (int y = top; y >= this.center.getBlockY() - 5; y--) {
         Block block = world.getBlockAt(bx, y, bz);
         if (!block.isPassable() && block.getType() != Material.LODESTONE) {
            return y + 1.0;
         }
      }

      return this.center.getBlockY();
   }

   private void buildRing() {
      this.removeRing();
      if (this.plugin.getConfig().getBoolean("events.koth.ring.enabled", true)) {
         double radius = this.radius();
         int segments = Math.max(24, (int)Math.round(radius * 2.0 * Math.PI / 0.9));
         float length = (float)(radius * 2.0 * Math.PI / segments) + 0.05F;
         float width = (float)this.plugin.getConfig().getDouble("events.koth.ring.width", 0.25);
         Location middle = this.center.clone().add(0.5, 0.0, 0.5);

         for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * (i + 0.5) / segments;
            double x = middle.getX() + Math.cos(angle) * radius;
            double z = middle.getZ() + Math.sin(angle) * radius;
            Location at = new Location(this.center.getWorld(), x, this.groundY(x, z) + 0.01, z);
            // lay the slab along the tangent: rotate about Y, then shift so it is centred on the point
            Quaternionf turn = new Quaternionf().rotationY((float)(-angle - Math.PI / 2.0));
            Vector3f half = new Vector3f(length / 2.0F, 0.0F, width / 2.0F);
            turn.transform(half);
            Transformation shape = new Transformation(half.negate(), turn, new Vector3f(length, 0.06F, width), new Quaternionf());
            this.ring.add(this.center.getWorld().spawn(at, BlockDisplay.class, display -> {
               display.setPersistent(false);
               display.setBlock(Material.GOLD_BLOCK.createBlockData());
               display.setTransformation(shape);
               display.setBrightness(new Display.Brightness(15, 15));
               display.setGlowing(true);
               display.setGlowColorOverride(Color.fromRGB(255, 200, 40));
               display.setViewRange(4.0F);
            }));
         }

         this.ringContested = false;
      }
   }

   private void colourRing(boolean contested) {
      if (this.ringContested != null && this.ringContested != contested) {
         this.ringContested = contested;
         for (BlockDisplay display : this.ring) {
            if (display.isValid()) {
               display.setBlock((contested ? Material.REDSTONE_BLOCK : Material.GOLD_BLOCK).createBlockData());
               display.setGlowColorOverride(contested ? Color.RED : Color.fromRGB(255, 200, 40));
            }
         }
      }
   }

   private void removeRing() {
      for (BlockDisplay display : this.ring) {
         if (display.isValid()) {
            display.remove();
         }
      }

      this.ring.clear();
      this.ringContested = null;
   }

   private void drawBoundary(boolean contested) {
      this.colourRing(contested);
      double radius = this.radius();
      Location middle = this.center.clone().add(0.5, 0.0, 0.5);
      Particle.DustOptions dust = new Particle.DustOptions(contested ? Color.RED : Color.fromRGB(255, 200, 40), 1.4F);
      int points = Math.max(24, (int)(radius * 6.0));

      for (int i = 0; i < points; i++) {
         double angle = Math.PI * 2.0 * i / points;
         double x = middle.getX() + Math.cos(angle) * radius;
         double z = middle.getZ() + Math.sin(angle) * radius;
         ParticleManager.showcase(Particle.DUST, new Location(middle.getWorld(), x, this.groundY(x, z) + 0.3, z), 1, 0.0, 0.1, 0.0, 0.0, dust);
      }

      for (double y = 0.5; y < 24.0; y += 1.5) {           // light column so the hill is visible from far off
         ParticleManager.showcase(Particle.END_ROD, middle.clone().add(0.0, y, 0.0), 1, 0.05, 0.3, 0.05, 0.0, null);
      }
   }

   private List<Map.Entry<UUID, Integer>> ranking() {
      List<Map.Entry<UUID, Integer>> ranked = new ArrayList<>(this.points.entrySet());
      ranked.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
      return ranked;
   }

   private void refreshHologram(Component state, long secondsLeft) {
      if (this.hologram != null && this.hologram.isValid()) {
         Component text = Component.text("♔ KING OF THE HILL ♔", NamedTextColor.GOLD, TextDecoration.BOLD);
         text = text.append(Component.newline()).append(secondsLeft >= 0L ? Component.text(String.format("%02d:%02d left", secondsLeft / 60L, secondsLeft % 60L), NamedTextColor.WHITE) : Component.text("Final standings", NamedTextColor.WHITE));
         if (state != null) {
            text = text.append(Component.newline()).append(state);
         }

         text = text.append(Component.newline());
         List<Map.Entry<UUID, Integer>> ranked = this.ranking();
         int lines = Math.max(1, this.plugin.getConfig().getInt("events.koth.hologram-lines", 5));

         for (int i = 0; i < Math.min(lines, ranked.size()); i++) {
            Map.Entry<UUID, Integer> entry = ranked.get(i);
            NamedTextColor colour = i == 0 ? NamedTextColor.YELLOW : (i == 1 ? NamedTextColor.WHITE : NamedTextColor.GRAY);
            text = text.append(Component.newline()).append(Component.text("#" + (i + 1) + " " + this.names.getOrDefault(entry.getKey(), "?") + " - " + entry.getValue() + " pts", colour));
         }

         if (ranked.isEmpty()) {
            text = text.append(Component.newline()).append(Component.text("Nobody has scored yet", NamedTextColor.DARK_GRAY));
         }

         this.hologram.text(text);
      }
   }

   public void onJoin(Player player) {
      if (this.isRunning()) {
         player.showBossBar(this.bar);
      }
   }

   public String status() {
      if (!this.isRunning()) {
         return this.center == null ? "King of the Hill: no hill set." : "King of the Hill: not running. Hill at " + this.where() + ".";
      } else {
         long left = Math.max(0L, (this.endsAt - System.currentTimeMillis()) / 1000L);
         List<Map.Entry<UUID, Integer>> ranked = this.ranking();
         return "King of the Hill: " + left / 60L + "m " + left % 60L + "s left. Leader: "
            + (ranked.isEmpty() ? "nobody" : this.names.get(ranked.get(0).getKey()) + " (" + ranked.get(0).getValue() + " points)");
      }
   }

   public void stop(boolean award) {
      if (this.isRunning()) {
         this.task.cancel();
         this.task = null;
         this.removeRing();

         for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(this.bar);
         }

         List<Map.Entry<UUID, Integer>> ranked = this.ranking();
         if (award && !ranked.isEmpty()) {
            int best = ranked.get(0).getValue();
            List<String> rewards = this.plugin.getConfig().getStringList("events.koth.reward");
            List<String> winners = new ArrayList<>();

            for (Map.Entry<UUID, Integer> entry : ranked) {
               if (entry.getValue() == best) {
                  winners.add(this.names.getOrDefault(entry.getKey(), "?"));
                  Player winner = Bukkit.getPlayer(entry.getKey());
                  if (winner != null) {
                     this.manager.loot().give(winner, rewards);
                     winner.showTitle(Title.title(Component.text("King of the Hill!", NamedTextColor.GOLD), Component.text(best + " points", NamedTextColor.GRAY)));
                     this.plugin.getSoundManager().self(winner, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
                  } else {
                     this.manager.queueReward(entry.getKey(), rewards);
                  }
               }
            }

            Bukkit.broadcast(Component.text("♔ King of the Hill is over! Winner: " + String.join(", ", winners) + " with " + best + " points", NamedTextColor.GOLD));
         } else {
            Bukkit.broadcast(Component.text("♔ King of the Hill has ended.", NamedTextColor.GRAY));
         }

         this.refreshHologram(null, -1L);
         final TextDisplay finalBoard = this.hologram;
         this.hologram = null;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (finalBoard != null && finalBoard.isValid()) {
               finalBoard.remove();
            }
         }, 1200L);
      }
   }

   private void removeHologram() {
      if (this.hologram != null && this.hologram.isValid()) {
         this.hologram.remove();
      }

      this.hologram = null;
   }

   public void shutdown() {
      this.stop(false);
      this.removeRing();
      this.removeHologram();
   }
}
