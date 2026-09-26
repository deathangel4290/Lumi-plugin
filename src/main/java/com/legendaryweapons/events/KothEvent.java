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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

/**
 * King of the Hill. An admin places the Hill Marker (or runs /lwevent koth setcenter) to fix the
 * centre, then starts the event. Each second, a player alone on the hill scores a point; two or
 * more make it contested and nobody scores. A floating leaderboard sits above the hill and stays
 * up for a minute after the end. The top scorer wins.
 */
public class KothEvent {
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final Map<UUID, Integer> points = new HashMap<>();
   private final Map<UUID, String> names = new HashMap<>();
   private final BossBar bar = BossBar.bossBar(Component.text("King of the Hill"), 1.0F, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
   private Location center;
   private TextDisplay hologram;
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

         Bukkit.broadcast(Component.text("♔ King of the Hill has begun at " + this.where() + "! Hold the hill alone to score. " + minutes + " minutes.", NamedTextColor.GOLD));
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

   private List<Player> onHill() {
      List<Player> on = new ArrayList<>();
      double radius = this.radius();
      double vertical = this.plugin.getConfig().getDouble("events.koth.vertical-range", 4.0);
      Location middle = this.center.clone().add(0.5, 0.0, 0.5);

      for (Player player : this.center.getWorld().getPlayers()) {
         Location loc = player.getLocation();
         double dx = loc.getX() - middle.getX();
         double dz = loc.getZ() - middle.getZ();
         if (!player.isDead() && player.getGameMode() != GameMode.SPECTATOR && dx * dx + dz * dz <= radius * radius && Math.abs(loc.getY() - middle.getY()) <= vertical) {
            on.add(player);
         }
      }

      return on;
   }

   private void tick() {
      long left = this.endsAt - System.currentTimeMillis();
      if (left <= 0L) {
         this.stop(true);
      } else {
         List<Player> on = this.onHill();
         Component state;
         if (on.size() == 1) {
            Player king = on.get(0);
            this.points.merge(king.getUniqueId(), 1, Integer::sum);
            this.names.put(king.getUniqueId(), this.manager.publicName(king));
            state = Component.text("King: ", NamedTextColor.GOLD).append(Component.text(this.manager.publicName(king), NamedTextColor.WHITE));
         } else if (on.isEmpty()) {
            state = Component.text("The hill is empty", NamedTextColor.GRAY);
         } else {
            state = Component.text("Contested! (" + on.size() + " on the hill)", NamedTextColor.RED);
         }

         long seconds = left / 1000L;
         this.bar.name(Component.text("♔ King of the Hill  ", NamedTextColor.GOLD).append(state).append(Component.text("  " + String.format("%02d:%02d", seconds / 60L, seconds % 60L), NamedTextColor.WHITE)));
         this.bar.progress((float)Math.max(0.0, Math.min(1.0, (double)left / this.totalMillis)));
         this.refreshHologram(state, seconds);
         this.drawBoundary(on.size() > 1);
      }
   }

   private void drawBoundary(boolean contested) {
      double radius = this.radius();
      Location middle = this.center.clone().add(0.5, 0.2, 0.5);
      Particle.DustOptions dust = new Particle.DustOptions(contested ? Color.RED : Color.fromRGB(255, 200, 40), 1.2F);

      for (int i = 0; i < 32; i++) {
         double angle = Math.PI * 2.0 * i / 32.0;
         ParticleManager.spawn(Particle.DUST, middle.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius), 1, 0.0, 0.0, 0.0, 0.0, dust);
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
            text = text.append(Component.newline()).append(Component.text("#" + (i + 1) + " " + this.names.getOrDefault(entry.getKey(), "?") + " - " + entry.getValue() + "s", colour));
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
            + (ranked.isEmpty() ? "nobody" : this.names.get(ranked.get(0).getKey()) + " (" + ranked.get(0).getValue() + "s)");
      }
   }

   public void stop(boolean award) {
      if (this.isRunning()) {
         this.task.cancel();
         this.task = null;

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
                     winner.showTitle(Title.title(Component.text("King of the Hill!", NamedTextColor.GOLD), Component.text("You held the hill longest", NamedTextColor.GRAY)));
                     this.plugin.getSoundManager().self(winner, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
                  } else {
                     this.manager.queueReward(entry.getKey(), rewards);
                  }
               }
            }

            Bukkit.broadcast(Component.text("♔ King of the Hill is over! Winner: " + String.join(", ", winners) + " (" + best + "s on the hill)", NamedTextColor.GOLD));
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
      this.removeHologram();
   }
}
