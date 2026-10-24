package com.lumiweapons.teams;

import com.lumiweapons.LumiWeapons;
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
 * A captain draft. Each captain gets a team; then they take turns picking from the pool, in snake
 * order (1 2 3 3 2 1 1 2 3 ...) or round order (teams.draft.order), each with
 * teams.draft.pick-seconds to choose on the draft board (/draft). Run out of time and a random
 * player is picked for you (teams.draft.auto-pick). It ends when the pool is empty or every team is
 * full (teams.draft.max-team-size). Everything else is in teams.draft.* too.
 */
public final class Draft {
   private final LumiWeapons plugin;
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

   Draft(LumiWeapons plugin, TeamManager teams) {
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

   private boolean flag(String key, boolean fallback) {
      return this.config() == null ? fallback : this.config().getBoolean(key, fallback);
   }

   private boolean snake() {
      return this.config() == null || !"ROUND".equalsIgnoreCase(this.config().getString("order", "SNAKE"));
   }

   private void sound(Player player, Sound sound, float volume, float pitch) {
      if (this.flag("sounds", true)) {
         this.plugin.getSoundManager().self(player, sound, volume, pitch);
      }
   }

   private void title(Player player, Title title) {
      if (this.flag("titles", true)) {
         player.showTitle(title);
      }
   }

   /** The draft's colours (teams.draft.colours), in captain order. */
   public static List<NamedTextColor> colours(LumiWeapons plugin) {
      List<NamedTextColor> out = new ArrayList<>();
      for (String raw : plugin.getConfig().getStringList("teams.draft.colours")) {
         NamedTextColor colour = NamedTextColor.NAMES.value(raw.trim().toLowerCase(Locale.ROOT));
         if (colour != null && !out.contains(colour)) {
            out.add(colour);
         }
      }

      return out.isEmpty() ? TeamManager.COLOURS : out;
   }

   public static int minCaptains(LumiWeapons plugin) {
      return Math.max(2, plugin.getConfig().getInt("teams.draft.min-captains", 2));
   }

   static String colourName(NamedTextColor colour) {
      String raw = NamedTextColor.NAMES.key(colour).replace('_', ' ');
      StringBuilder out = new StringBuilder();
      for (String word : raw.split(" ")) {
         out.append(out.length() == 0 ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
      }

      return out.toString();
   }

   /**
    * Starts a draft. Null on success, else why not. Existing teams of the captains and pool are
    * left (players change team when picked).
    */
   public static String start(LumiWeapons plugin, TeamManager teams, List<Player> captains, List<Player> pool) {
      if (teams.draft() != null) {
         return "A draft is already running.";
      }

      List<NamedTextColor> colours = colours(plugin);
      if (captains.size() < minCaptains(plugin)) {
         return "Pick at least " + minCaptains(plugin) + " captains.";
      }

      if (captains.size() > colours.size()) {
         return "At most " + colours.size() + " captains (one per colour in teams.draft.colours).";
      }

      List<Player> order = new ArrayList<>(captains);
      if ("RANDOM".equalsIgnoreCase(plugin.getConfig().getString("teams.draft.captain-order", "CHOSEN"))) {
         java.util.Collections.shuffle(order);
      }

      Draft draft = new Draft(plugin, teams);
      String format = plugin.getConfig().getString("teams.draft.team-name", "{colour} Team");
      for (int i = 0; i < order.size(); i++) {
         Player captain = order.get(i);
         NamedTextColor colour = colours.get(i);
         String name = (format == null || format.isBlank() ? "{colour} Team" : format)
            .replace("{colour}", colourName(colour)).replace("{color}", colourName(colour)).replace("{captain}", captain.getName());
         TeamManager.Team existing = teams.byName(name);
         if (existing != null && plugin.getConfig().getBoolean("teams.draft.replace-old-teams", true)) {
            teams.disband(existing);                        // an old team of that name from a previous draft
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
         if (draft.flag("boss-bar", true)) {
            online.showBossBar(draft.bar);
         }

         draft.sound(online, Sound.EVENT_RAID_HORN, 0.5F, 1.4F);
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

   /** Snake order: even rounds go 0..n-1, odd rounds come back n-1..0. Round order always goes 0..n-1. */
   private int slot() {
      return !this.snake() || this.round % 2 == 0 ? this.turn : this.order.size() - 1 - this.turn;
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
         // an absent captain does not hold everyone up
         this.secondsLeft = Math.min(this.secondsLeft, Math.max(1, this.config() == null ? 5 : this.config().getInt("absent-captain-seconds", 5)));
      }

      this.secondsLeft--;
      if (this.secondsLeft <= 0) {
         boolean auto = this.config() == null || this.config().getBoolean("auto-pick", true);
         if (auto && !this.pool.isEmpty()) {
            UUID random = this.pool.get(ThreadLocalRandom.current().nextInt(this.pool.size()));
            this.take(team, random, true);
         } else {
            if (this.flag("announce-picks", true)) {
               Bukkit.broadcast(Component.text("⚑ " + team.name + " ran out of time and skips this pick.", NamedTextColor.GRAY));
            }

            this.advance();
         }

         return;
      }

      int warn = this.config() == null ? 5 : this.config().getInt("warn-seconds", 5);
      if (this.secondsLeft <= warn && captain != null) {
         this.sound(captain, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8F, 1.6F);
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
      if (this.flag("announce-picks", true)) {
         Bukkit.broadcast(Component.text("⚑ ", team.colour).append(Component.text(team.name, team.colour, TextDecoration.BOLD))
            .append(Component.text(random ? " were given " : " pick ", NamedTextColor.GRAY)).append(Component.text(name, NamedTextColor.WHITE))
            .append(Component.text(random ? " (time ran out)" : "", NamedTextColor.DARK_GRAY)));
      }

      Player picked = Bukkit.getPlayer(target);
      if (picked != null) {
         this.title(picked, Title.title(Component.text(team.name, team.colour, TextDecoration.BOLD), Component.text("picked you!", NamedTextColor.GRAY)));
         this.sound(picked, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7F, 1.2F);
         if (!this.flag("announce-picks", true)) {
            picked.sendMessage(Component.text("⚑ You're on " + team.name + ".", team.colour));
         }
      }

      for (Player online : Bukkit.getOnlinePlayers()) {
         if (online != picked) {
            this.sound(online, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5F, 1.2F);
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
         this.title(captain, Title.title(Component.text("Your pick!", team.colour, TextDecoration.BOLD), Component.text("Open /draft to choose", NamedTextColor.GRAY)));
         this.sound(captain, Sound.BLOCK_BELL_USE, 1.0F, 1.2F);
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
      if (this.running && this.flag("boss-bar", true)) {
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
            this.title(online, Title.title(Component.text(team.name, team.colour, TextDecoration.BOLD), Component.text("Your teammates are your allies now. /team", NamedTextColor.GRAY)));
         }

         this.sound(online, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6F, 1.0F);
      }

      this.changed();
   }

   /** Stops it early and undoes the teams it made. */
   public void cancel(String reason) {
      if (!this.running) {
         return;
      }

      this.stopTicking();
      if (this.flag("cancel-removes-teams", true)) {
         for (TeamManager.Team team : this.order) {
            this.teams.disband(team);
         }
      }

      Bukkit.broadcast(Component.text("⚑ The Team Draft was cancelled" + (reason == null ? "." : " (" + reason + ")."), NamedTextColor.RED));
      this.changed();
   }
}
