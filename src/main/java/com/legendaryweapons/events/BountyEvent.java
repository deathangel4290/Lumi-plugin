package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.rituals.Ritual;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

/**
 * Bounty Hunt: every player is given a target. The action bar tracks yours -- distance and
 * direction when you share a dimension, otherwise which dimension they are in. Killing your own
 * target scores a point on the sidebar leaderboard and hands you a new one. When time runs out
 * the top hunter wins (ties all win).
 */
public class BountyEvent {
   private static final String[] ARROWS = new String[]{"⬆", "⬈", "➡", "⬊", "⬇", "⬋", "⬅", "⬉"};
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final Map<UUID, UUID> targets = new HashMap<>();
   private final Map<UUID, Integer> scores = new HashMap<>();
   private final Map<UUID, String> names = new HashMap<>();
   private Scoreboard board;
   private Objective objective;
   private BukkitTask task;
   private long endsAt;

   public BountyEvent(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
   }

   public boolean isRunning() {
      return this.task != null;
   }

   private static boolean eligible(Player player) {
      return player.isOnline() && player.getGameMode() != GameMode.SPECTATOR;
   }

   public String start(int minutes) {
      if (this.isRunning()) {
         return "A bounty hunt is already running.";
      } else {
         List<Player> pool = new ArrayList<>();

         for (Player player : Bukkit.getOnlinePlayers()) {
            if (eligible(player)) {
               pool.add(player);
            }
         }

         if (pool.size() < 2) {
            return "A bounty hunt needs at least 2 players online.";
         } else {
            this.targets.clear();
            this.scores.clear();
            Collections.shuffle(pool);

            // A single shuffled cycle: everyone hunts someone and is hunted by exactly one person.
            for (int i = 0; i < pool.size(); i++) {
               this.targets.put(pool.get(i).getUniqueId(), pool.get((i + 1) % pool.size()).getUniqueId());
               this.scores.put(pool.get(i).getUniqueId(), 0);
               this.names.put(pool.get(i).getUniqueId(), this.manager.publicName(pool.get(i)));
            }

            for (Player hunter : pool) {
               UUID target = this.targets.get(hunter.getUniqueId());
               if (target != null && this.plugin.getTeamManager().sameTeam(hunter.getUniqueId(), target)) {
                  this.assignNewTarget(hunter, null);          // never hunt a teammate
               }
            }

            this.endsAt = System.currentTimeMillis() + Math.max(1, minutes) * 60000L;
            this.board = Bukkit.getScoreboardManager().getNewScoreboard();
            this.objective = this.board.registerNewObjective("lwbounty", Criteria.DUMMY, Component.text("☠ Bounty Hunt", NamedTextColor.RED, TextDecoration.BOLD));
            this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            this.plugin.getDisguiseManager().syncTeamTo(this.board);

            for (Player player : pool) {
               player.setScoreboard(this.board);
               player.showTitle(Title.title(Component.text("Bounty Hunt", NamedTextColor.RED), Component.text("Your target is on your action bar", NamedTextColor.GRAY)));
               this.plugin.getSoundManager().self(player, Sound.EVENT_RAID_HORN, 0.6F, 1.2F);
            }

            Bukkit.broadcast(Component.text("☠ A Bounty Hunt has begun! Everyone has a target. Top hunter after " + minutes + " minutes wins a Luminosity Fragment.", NamedTextColor.RED));
            this.task = (new BukkitRunnable() {
               public void run() {
                  BountyEvent.this.tick();
               }
            }).runTaskTimer(this.plugin, 20L, 20L);
            this.tick();
            return null;
         }
      }
   }

   private void tick() {
      long left = this.endsAt - System.currentTimeMillis();
      if (left <= 0L) {
         this.stop(true);
      } else {
         for (Map.Entry<UUID, UUID> entry : new ArrayList<>(this.targets.entrySet())) {
            Player hunter = Bukkit.getPlayer(entry.getKey());
            Player target = Bukkit.getPlayer(entry.getValue());
            if (hunter != null && (target == null || !eligible(target))) {
               this.assignNewTarget(hunter, null);
            }
         }

         long seconds = left / 1000L;
         this.objective.displayName(Component.text("☠ Bounty Hunt ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text(String.format("%02d:%02d", seconds / 60L, seconds % 60L), NamedTextColor.WHITE)));

         for (Map.Entry<UUID, Integer> score : this.scores.entrySet()) {
            String name = this.names.getOrDefault(score.getKey(), "?");
            this.objective.getScore(name).setScore(score.getValue());
         }
      }
   }

   private void assignNewTarget(Player hunter, UUID avoid) {
      List<Player> options = new ArrayList<>();

      for (Player player : Bukkit.getOnlinePlayers()) {
         if (!player.equals(hunter) && eligible(player) && !player.getUniqueId().equals(avoid)
            && !this.plugin.getTeamManager().sameTeam(hunter.getUniqueId(), player.getUniqueId())) {
            options.add(player);
         }
      }

      if (options.isEmpty() && avoid != null) {
         Player fallback = Bukkit.getPlayer(avoid);
         if (fallback != null && eligible(fallback)) {
            options.add(fallback);
         }
      }

      if (options.isEmpty()) {
         this.targets.remove(hunter.getUniqueId());
      } else {
         Player next = options.get(ThreadLocalRandom.current().nextInt(options.size()));
         this.targets.put(hunter.getUniqueId(), next.getUniqueId());
         this.plugin.getSoundManager().self(hunter, Sound.ITEM_LODESTONE_COMPASS_LOCK, 0.8F, 1.0F);
      }
   }

   public void onJoin(Player player) {
      if (this.isRunning() && eligible(player)) {
         player.setScoreboard(this.board);
         this.scores.putIfAbsent(player.getUniqueId(), 0);
         this.names.put(player.getUniqueId(), this.manager.publicName(player));
         if (!this.targets.containsKey(player.getUniqueId())) {
            this.assignNewTarget(player, null);
         }
      }
   }

   public void onKill(Player victim, Player killer) {
      if (this.isRunning() && killer != null && !killer.equals(victim)) {
         UUID target = this.targets.get(killer.getUniqueId());
         if (victim.getUniqueId().equals(target)) {
            int score = this.scores.merge(killer.getUniqueId(), 1, Integer::sum);
            Bukkit.broadcast(Component.text("☠ ", NamedTextColor.RED)
               .append(Component.text(this.manager.publicName(killer), NamedTextColor.GOLD))
               .append(Component.text(" claimed the bounty on ", NamedTextColor.GRAY))
               .append(Component.text(this.manager.publicName(victim), NamedTextColor.WHITE))
               .append(Component.text(" (" + score + ")", NamedTextColor.GRAY)));
            this.plugin.getSoundManager().self(killer, Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.4F);
            this.assignNewTarget(killer, victim.getUniqueId());
         }
      }
   }

   /** The HUD line for a hunter. */
   public Component hud(Player hunter) {
      if (!this.isRunning()) {
         return null;
      } else {
         UUID id = this.targets.get(hunter.getUniqueId());
         Player target = id == null ? null : Bukkit.getPlayer(id);
         if (target == null) {
            return Component.text("🎯 Waiting for a target...", NamedTextColor.GRAY);
         } else {
            Component line = Component.text("🎯 ", NamedTextColor.RED).append(Component.text(this.manager.publicName(target), NamedTextColor.WHITE));
            if (target.getWorld().equals(hunter.getWorld())) {
               Location from = hunter.getLocation();
               Location to = target.getLocation();
               double distance = from.distance(to);
               return line.append(Component.text("  " + (int)Math.round(distance) + "m " + arrow(from, to), NamedTextColor.YELLOW));
            } else {
               return line.append(Component.text("  in the ", NamedTextColor.GRAY)).append(Component.text(Ritual.dimensionName(target.getWorld()), Ritual.dimensionColour(target.getWorld())));
            }
         }
      }
   }

   private static String arrow(Location from, Location to) {
      Vector delta = to.toVector().subtract(from.toVector());
      if (delta.getX() * delta.getX() + delta.getZ() * delta.getZ() < 9.0) {
         return delta.getY() > 3.0 ? "above" : (delta.getY() < -3.0 ? "below" : "●");
      } else {
         double targetYaw = Math.toDegrees(Math.atan2(-delta.getX(), delta.getZ()));
         double relative = ((targetYaw - from.getYaw()) % 360.0 + 360.0) % 360.0;
         return ARROWS[(int)Math.round(relative / 45.0) % 8];
      }
   }

   public String status() {
      if (!this.isRunning()) {
         return "No bounty hunt is running.";
      } else {
         long left = Math.max(0L, (this.endsAt - System.currentTimeMillis()) / 1000L);
         return "Bounty hunt: " + this.targets.size() + " hunters, " + left / 60L + "m " + left % 60L + "s left. Leader: " + this.leaderText();
      }
   }

   private String leaderText() {
      int best = this.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
      if (best <= 0) {
         return "nobody yet";
      } else {
         List<String> leaders = new ArrayList<>();
         this.scores.forEach((id, score) -> {
            if (score == best) {
               leaders.add(this.names.getOrDefault(id, "?"));
            }
         });
         return String.join(", ", leaders) + " (" + best + ")";
      }
   }

   public void stop(boolean award) {
      if (this.isRunning()) {
         this.task.cancel();
         this.task = null;
         int best = this.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
         if (award && best > 0) {
            List<String> rewards = this.plugin.getConfig().getStringList("events.bounty.reward");

            for (Map.Entry<UUID, Integer> entry : this.scores.entrySet()) {
               if (entry.getValue() == best) {
                  Player winner = Bukkit.getPlayer(entry.getKey());
                  if (winner != null) {
                     this.manager.loot().give(winner, rewards);
                     winner.showTitle(Title.title(Component.text("Top Hunter!", NamedTextColor.GOLD), Component.text("You won the Bounty Hunt", NamedTextColor.GRAY)));
                  } else {
                     this.manager.queueReward(entry.getKey(), rewards);
                  }
               }
            }

            Bukkit.broadcast(Component.text("☠ The Bounty Hunt is over! Winner: " + this.leaderText(), NamedTextColor.GOLD));
         } else {
            Bukkit.broadcast(Component.text("☠ The Bounty Hunt has ended" + (award ? " with no bounties claimed." : "."), NamedTextColor.GRAY));
         }

         for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getScoreboard() == this.board) {
               player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
         }

         this.targets.clear();
         this.scores.clear();
         this.names.clear();
         this.board = null;
         this.objective = null;
      }
   }
}
