package com.legendaryweapons.teams;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * A captain draft. Each captain gets a team; then they take turns picking from the pool in snake
 * order (1 2 3 3 2 1 1 2 3 ...), each with teams.draft.pick-seconds to choose on the draft board
 * (/draft). Run out of time and a random player is picked for you (teams.draft.auto-pick). It ends
 * when the pool is empty or every team is full (teams.draft.max-team-size).
 */
public final class Draft {
   private final LegendaryWeapons plugin;
   private final TeamManager teams;
   private final List<TeamManager.Team> order = new ArrayList<>();
   private final List<UUID> pool = new ArrayList<>();
   private final Map<UUID, String> names = new LinkedHashMap<>();
   private final BossBar bar = BossBar.bossBar(Component.text("Team Draft"), 1.0F, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
   private final List<Runnable> watchers = new ArrayList<>();
   private BukkitTask task;
   private int round;
   private int turn;
   private int secondsLeft;
   private boolean running;

   Draft(LegendaryWeapons plugin, TeamManager teams) {
      this.plugin = plugin;
      this.teams = teams;
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("teams.draft");
   }

   private int pickSeconds() {
      return Math.max(5, this.config() == null ? 45 : this.config().getInt("pick-seconds", 45));
   }

   private int maxSize() {
      return this.config() == null ? 0 : this.config().getInt("max-team-size", 0);
   }

   /**
    * Starts a draft. Null on success, else why not. Existing teams of the captains and pool are
    * left (players change team when picked).
    */
   public static String start(LegendaryWeapons plugin, TeamManager teams, List<Player> captains, List<Player> pool) {
      if (teams.draft() != null) {
         return "A draft is already running.";
      }

      if (captains.size() < 2) {
         return "Pick at least two captains.";
      }

      if (captains.size() > TeamManager.COLOURS.size()) {
         return "At most " + TeamManager.COLOURS.size() + " captains.";
      }

      Draft draft = new Draft(plugin, teams);
      for (int i = 0; i < captains.size(); i++) {
         Player captain = captains.get(i);
         NamedTextColor colour = TeamManager.COLOURS.get(i);
         String colourName = NamedTextColor.NAMES.key(colour).replace('_', ' ');
         String name = colourName.substring(0, 1).toUpperCase(Locale.ROOT) + colourName.substring(1) + " Team";
         TeamManager.Team existing = teams.byName(name);
         if (existing != null) {
            teams.disband(existing);                        // an old team of that colour from a previous draft
         }

         draft.order.add(teams.create(name, colour, captain.getUniqueId(), captain.getName()));
      }

      for (Player player : pool) {
         if (!captains.contains(player)) {
            draft.pool.add(player.getUniqueId());
            draft.names.put(player.getUniqueId(), player.getName());
         }
      }

      if (draft.pool.isEmpty()) {
         teams.save();
         return "Nobody is in the pool to pick from. The captains' teams were made.";
      }

      teams.setDraft(draft);
      draft.running = true;
      draft.secondsLeft = draft.pickSeconds();
      Component announce = Component.text("⚑ The Team Draft has begun!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD);
      Bukkit.broadcast(announce);
      for (TeamManager.Team team : draft.order) {
         Bukkit.broadcast(Component.text("  " + team.name + ": ", team.colour).append(Component.text("captain " + team.nameOf(team.captain), NamedTextColor.WHITE)));
      }

      Bukkit.broadcast(Component.text("  " + draft.pool.size() + " players in the pool. Watch it with /draft.", NamedTextColor.GRAY));
      for (Player online : Bukkit.getOnlinePlayers()) {
         online.showBossBar(draft.bar);
         plugin.getSoundManager().self(online, Sound.EVENT_RAID_HORN, 0.5F, 1.4F);
      }

      draft.announceTurn();
      draft.task = plugin.getServer().getScheduler().runTaskTimer(plugin, draft::tick, 20L, 20L);
      return null;
   }

   // ------------------------------------------------------------------ state for the board

   public boolean running() {
      return this.running;
   }

   public List<UUID> pool() {
      return List.copyOf(this.pool);
   }

   public String nameOf(UUID id) {
      return this.names.getOrDefault(id, "?");
   }

   public List<TeamManager.Team> order() {
      return List.copyOf(this.order);
   }

   public TeamManager.Team current() {
      return this.running && !this.order.isEmpty() ? this.order.get(this.slot()) : null;
   }

   public int secondsLeft() {
      return this.secondsLeft;
   }

   public int round() {
      return this.round + 1;
   }

   public boolean isPicking(Player player) {
      TeamManager.Team team = this.current();
      return team != null && player.getUniqueId().equals(team.captain);
   }

   /** A menu to redraw whenever the draft changes or the clock ticks. */
   public void watch(Runnable refresh) {
      this.watchers.add(refresh);
   }

   public void unwatch(Runnable refresh) {
      this.watchers.remove(refresh);
   }

   private void changed() {
      for (Runnable watcher : new ArrayList<>(this.watchers)) {
         watcher.run();
      }
   }

   // ------------------------------------------------------------------ turns

   /** Snake order: even rounds go 0..n-1, odd rounds come back n-1..0. */
   private int slot() {
      return this.round % 2 == 0 ? this.turn : this.order.size() - 1 - this.turn;
   }

   private boolean full(TeamManager.Team team) {
      return this.maxSize() > 0 && team.members.size() >= this.maxSize();
   }

   private void tick() {
      if (!this.running) {
         return;
      }

      TeamManager.Team team = this.current();
      Player captain = team == null || team.captain == null ? null : Bukkit.getPlayer(team.captain);
      if (captain == null) {
         this.secondsLeft = Math.min(this.secondsLeft, 5);      // an absent captain does not hold everyone up
      }

      this.secondsLeft--;
      if (this.secondsLeft <= 0) {
         boolean auto = this.config() == null || this.config().getBoolean("auto-pick", true);
         if (auto && !this.pool.isEmpty()) {
            UUID random = this.pool.get(ThreadLocalRandom.current().nextInt(this.pool.size()));
            this.take(team, random, true);
         } else {
            Bukkit.broadcast(Component.text("⚑ " + team.name + " ran out of time and skips this pick.", NamedTextColor.GRAY));
            this.advance();
         }

         return;
      }

      if (this.secondsLeft <= 5 && captain != null) {
         this.plugin.getSoundManager().self(captain, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8F, 1.6F);
      }

      this.updateBar();
      this.changed();
   }

   /** The captain on the clock picks `target`. Null on success, else why not. */
   public String pick(Player captain, UUID target) {
      if (!this.running) {
         return "The draft is over.";
      }

      if (!this.isPicking(captain)) {
         return "It isn't your pick.";
      }

      if (!this.pool.contains(target)) {
         return "That player has already been picked.";
      }

      this.take(this.current(), target, false);
      return null;
   }

   private void take(TeamManager.Team team, UUID target, boolean random) {
      this.pool.remove(target);
      String name = this.nameOf(target);
      this.teams.add(team, target, name);
      Bukkit.broadcast(Component.text("⚑ ", team.colour).append(Component.text(team.name, team.colour, TextDecoration.BOLD))
         .append(Component.text(random ? " were given " : " pick ", NamedTextColor.GRAY)).append(Component.text(name, NamedTextColor.WHITE))
         .append(Component.text(random ? " (time ran out)" : "", NamedTextColor.DARK_GRAY)));
      Player picked = Bukkit.getPlayer(target);
      if (picked != null) {
         picked.showTitle(Title.title(Component.text(team.name, team.colour, TextDecoration.BOLD), Component.text("picked you!", NamedTextColor.GRAY)));
         this.plugin.getSoundManager().self(picked, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 1.2F);
      }

      for (Player online : Bukkit.getOnlinePlayers()) {
         if (online != picked) {
            this.plugin.getSoundManager().self(online, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5F, 1.2F);
         }
      }

      this.advance();
   }

   private void advance() {
      boolean anyRoom = false;
      for (TeamManager.Team team : this.order) {
         anyRoom |= !this.full(team);
      }

      if (this.pool.isEmpty() || !anyRoom) {
         this.finish();
         return;
      }

      // next captain in snake order, skipping full teams
      for (int guard = 0; guard < this.order.size() * 2 + 2; guard++) {
         this.turn++;
         if (this.turn >= this.order.size()) {
            this.turn = 0;
            this.round++;
         }

         if (!this.full(this.current())) {
            break;
         }
      }

      this.secondsLeft = this.pickSeconds();
      this.announceTurn();
      this.changed();
   }

   private void announceTurn() {
      TeamManager.Team team = this.current();
      this.updateBar();
      Player captain = team.captain == null ? null : Bukkit.getPlayer(team.captain);
      if (captain != null) {
         captain.showTitle(Title.title(Component.text("Your pick!", team.colour, TextDecoration.BOLD), Component.text("Open /draft to choose", NamedTextColor.GRAY)));
         this.plugin.getSoundManager().self(captain, Sound.BLOCK_BELL_USE, 1.0F, 1.2F);
         captain.sendMessage(Component.text("⚑ Your pick, " + this.secondsLeft + " seconds. ", team.colour)
            .append(Component.text("[Open the draft board]", NamedTextColor.YELLOW).clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/draft"))));
      }
   }

   private void updateBar() {
      TeamManager.Team team = this.current();
      if (team == null) {
         return;
      }

      this.bar.name(Component.text("⚑ Draft · ", NamedTextColor.WHITE).append(Component.text(team.name, team.colour, TextDecoration.BOLD))
         .append(Component.text(" (" + team.nameOf(team.captain) + ") picking · " + this.secondsLeft + "s · " + this.pool.size() + " left", NamedTextColor.WHITE)));
      this.bar.progress(Math.max(0.0F, Math.min(1.0F, this.secondsLeft / (float)this.pickSeconds())));
      this.bar.color(switch (NamedTextColor.NAMES.key(team.colour)) {
         case "red" -> BossBar.Color.RED;
         case "blue", "aqua" -> BossBar.Color.BLUE;
         case "green" -> BossBar.Color.GREEN;
         case "yellow", "gold" -> BossBar.Color.YELLOW;
         case "white" -> BossBar.Color.WHITE;
         default -> BossBar.Color.PURPLE;
      });
   }

   public void showTo(Player player) {
      if (this.running) {
         player.showBossBar(this.bar);
      }
   }

   private void stopTicking() {
      this.running = false;
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      for (Player online : Bukkit.getOnlinePlayers()) {
         online.hideBossBar(this.bar);
      }

      this.teams.setDraft(null);
      this.teams.save();
   }

   private void finish() {
      this.stopTicking();
      Bukkit.broadcast(Component.text("⚑ The Team Draft is over!", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));
      for (TeamManager.Team team : this.order) {
         List<String> members = new ArrayList<>();
         for (UUID id : team.members) {
            members.add(team.nameOf(id));
         }

         Bukkit.broadcast(Component.text("  " + team.name + ": ", team.colour).append(Component.text(String.join(", ", members), NamedTextColor.WHITE)));
      }

      if (!this.pool.isEmpty()) {
         Bukkit.broadcast(Component.text("  Not picked (every team is full): " + this.pool.size(), NamedTextColor.GRAY));
      }

      for (Player online : Bukkit.getOnlinePlayers()) {
         TeamManager.Team team = this.teams.teamOf(online);
         if (team != null) {
            online.showTitle(Title.title(Component.text(team.name, team.colour, TextDecoration.BOLD), Component.text("Your teammates are your allies now. /team", NamedTextColor.GRAY)));
         }

         this.plugin.getSoundManager().self(online, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6F, 1.0F);
      }

      this.changed();
   }

   /** Stops it early and undoes the teams it made. */
   public void cancel(String reason) {
      if (!this.running) {
         return;
      }

      this.stopTicking();
      for (TeamManager.Team team : this.order) {
         this.teams.disband(team);
      }

      Bukkit.broadcast(Component.text("⚑ The Team Draft was cancelled" + (reason == null ? "." : " (" + reason + ")."), NamedTextColor.RED));
      this.changed();
   }
}
