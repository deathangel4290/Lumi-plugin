package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * The Event Director: the server starts events, players react.
 *
 * director.windows lists time windows (in director.timezone). Once per day per window, the
 * Director picks one event from the window's weighted list and a random minute inside the window,
 * then counts down to it in chat (director.announce-minutes). So "8-10 PM" means something WILL
 * happen in that window, but nobody knows exactly what or when until it is announced.
 *
 * Event ids: supplydrop[:early|mid|late], bounty, koth, fallenstar, bloodmoon, boss[:id] (a boss
 * needs its arena set; plain "boss" picks one of the bosses that have one).
 * Windows cannot cross midnight; split one into two windows instead.
 * If fewer than director.min-players are online when it is time, it waits 5 minutes and tries
 * again until the window closes.
 */
public final class EventDirector {
   private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   /** window name -> date it already ran (or was planned) */
   private final Map<String, LocalDate> handled = new HashMap<>();
   private Plan plan;
   private String lastEvent;

   private static final class Plan {
      final String window;
      final String event;
      ZonedDateTime at;
      final ZonedDateTime windowEnd;
      final List<Integer> announced = new ArrayList<>();

      Plan(String window, String event, ZonedDateTime at, ZonedDateTime windowEnd) {
         this.window = window;
         this.event = event;
         this.at = at;
         this.windowEnd = windowEnd;
      }
   }

   public EventDirector(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      (new BukkitRunnable() {
         public void run() {
            try {
               EventDirector.this.tick();
            } catch (RuntimeException var2) {
               EventDirector.this.plugin.getLogger().warning("Event Director tick failed: " + var2);
            }
         }
      }).runTaskTimer(plugin, 200L, 200L);
   }

   void load(ConfigurationSection section) {
      if (section != null) {
         ConfigurationSection days = section.getConfigurationSection("handled");
         if (days != null) {
            for (String window : days.getKeys(false)) {
               try {
                  this.handled.put(window, LocalDate.parse(days.getString(window)));
               } catch (RuntimeException ignored) {
               }
            }
         }

         this.lastEvent = section.getString("last-event");
         // a plan survives a restart, so a restart inside a window never silently skips its event
         if (section.contains("plan.event")) {
            try {
               ZonedDateTime at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(section.getLong("plan.at")), this.zone());
               ZonedDateTime end = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(section.getLong("plan.end")), this.zone());
               if (end.isAfter(ZonedDateTime.now(this.zone()))) {
                  this.plan = new Plan(section.getString("plan.window", "?"), section.getString("plan.event"), at, end);
               }
            } catch (RuntimeException ignored) {
            }
         }
      }
   }

   void save(ConfigurationSection section) {
      section.set("handled", null);
      for (Map.Entry<String, LocalDate> entry : this.handled.entrySet()) {
         section.set("handled." + entry.getKey(), entry.getValue().toString());
      }

      section.set("last-event", this.lastEvent);
      section.set("plan", null);
      if (this.plan != null) {
         section.set("plan.window", this.plan.window);
         section.set("plan.event", this.plan.event);
         section.set("plan.at", this.plan.at.toInstant().toEpochMilli());
         section.set("plan.end", this.plan.windowEnd.toInstant().toEpochMilli());
      }
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("director");
   }

   public boolean enabled() {
      return this.config() != null && this.config().getBoolean("enabled", false);
   }

   public void setEnabled(boolean on) {
      this.plugin.getConfig().set("director.enabled", on);
      this.plugin.saveConfig();
      if (!on) {
         this.plan = null;
      }
   }

   private ZoneId zone() {
      String id = this.config() == null ? "" : this.config().getString("timezone", "");
      try {
         return id == null || id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
      } catch (RuntimeException var3) {
         return ZoneId.systemDefault();
      }
   }

   private static boolean dayMatches(List<String> days, DayOfWeek today) {
      if (days == null || days.isEmpty()) {
         return true;
      }

      for (String day : days) {
         String d = day.trim().toUpperCase(Locale.ROOT);
         if (d.equals("ALL") || d.equals("DAILY") || today.name().startsWith(d)
            || d.equals("WEEKEND") && (today == DayOfWeek.SATURDAY || today == DayOfWeek.SUNDAY)
            || d.equals("WEEKDAYS") && today != DayOfWeek.SATURDAY && today != DayOfWeek.SUNDAY) {
            return true;
         }
      }

      return false;
   }

   private int eligiblePlayers() {
      int count = 0;
      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) {
            count++;
         }
      }

      return count;
   }

   private void tick() {
      if (!this.enabled()) {
         return;
      }

      ZonedDateTime now = ZonedDateTime.now(this.zone());
      if (this.plan == null) {
         this.planNext(now);
      }

      if (this.plan == null) {
         return;
      }

      long secondsLeft = java.time.Duration.between(now, this.plan.at).getSeconds();
      for (int minutes : this.config().getIntegerList("announce-minutes")) {
         if (!this.plan.announced.contains(minutes) && secondsLeft <= minutes * 60L && secondsLeft > (minutes - 1) * 60L) {
            this.plan.announced.add(minutes);
            this.announce(this.plan.event, minutes);
         }
      }

      if (secondsLeft <= 0L) {
         Plan due = this.plan;
         if (this.eligiblePlayers() < Math.max(0, this.config().getInt("min-players", 3))) {
            if (now.plusMinutes(5L).isBefore(due.windowEnd)) {
               due.at = now.plusMinutes(5L);
               due.announced.clear();
               this.plugin.getLogger().info("[Director] Not enough players for " + due.event + "; trying again in 5 minutes.");
            } else {
               this.plan = null;
               this.plugin.getLogger().info("[Director] Skipped " + due.event + " (" + due.window + "): not enough players before the window closed.");
            }

            return;
         }

         this.plan = null;
         this.manager.save();
         String error = this.run(due.event);
         if (error != null) {
            this.plugin.getLogger().warning("[Director] Could not start " + due.event + ": " + error);
         } else {
            this.lastEvent = due.event;
            this.manager.save();
         }
      }
   }

   private void planNext(ZonedDateTime now) {
      ConfigurationSection windows = this.config().getConfigurationSection("windows");
      if (windows == null) {
         return;
      }

      LocalDate today = now.toLocalDate();
      for (String name : windows.getKeys(false)) {
         ConfigurationSection window = windows.getConfigurationSection(name);
         if (window == null || today.equals(this.handled.get(name)) || !dayMatches(window.getStringList("days"), now.getDayOfWeek())) {
            continue;
         }

         LocalTime from;
         LocalTime to;
         try {
            from = LocalTime.parse(window.getString("from", "20:00"));
            to = LocalTime.parse(window.getString("to", "22:00"));
         } catch (RuntimeException var14) {
            this.plugin.getLogger().warning("[Director] Window '" + name + "' has a bad from/to time (use HH:mm).");
            continue;
         }

         ZonedDateTime start = now.with(from);
         ZonedDateTime end = now.with(to);
         if (!end.isAfter(start) || !now.isBefore(end)) {
            continue;                                  // already over today (or malformed)
         }

         String event = this.pick(window.getConfigurationSection("events"));
         if (event == null) {
            continue;
         }

         if (event.equalsIgnoreCase("boss") && this.plugin.getBossManager() != null) {
            String boss = this.plugin.getBossManager().pickBoss();   // decide now, so the countdown can say who and where
            if (boss == null) {
               continue;
            }

            event = "boss:" + boss;
         }

         // Leave room for the countdown: never closer than the first announcement allows.
         int lead = 0;
         for (int minutes : this.config().getIntegerList("announce-minutes")) {
            lead = Math.max(lead, minutes);
         }

         ZonedDateTime earliest = start.isAfter(now.plusMinutes(lead)) ? start : now.plusMinutes(lead);
         if (!earliest.isBefore(end)) {
            this.handled.put(name, today);             // too late in the window for a proper countdown
            continue;
         }

         long span = java.time.Duration.between(earliest, end.minusMinutes(1L)).getSeconds();
         ZonedDateTime at = span <= 0L ? earliest : earliest.plusSeconds(ThreadLocalRandom.current().nextLong(span + 1L));
         this.handled.put(name, today);
         this.plan = new Plan(name, event, at, end);
         this.manager.save();
         this.plugin.getLogger().info("[Director] Planned " + event + " for " + at.format(CLOCK) + " (window " + name + ").");
         return;
      }
   }

   /** Weighted pick, avoiding a repeat of the last event when there is a choice and skipping ones that cannot run. */
   private String pick(ConfigurationSection events) {
      if (events == null) {
         return null;
      }

      Map<String, Integer> pool = new LinkedHashMap<>();
      for (String key : events.getKeys(false)) {
         int weight = events.getInt(key, 0);
         if (weight > 0 && this.canRun(key)) {
            pool.put(key, weight);
         }
      }

      if (pool.size() > 1 && this.lastEvent != null) {
         pool.remove(this.lastEvent);
      }

      int total = pool.values().stream().mapToInt(Integer::intValue).sum();
      if (total <= 0) {
         return null;
      }

      int roll = ThreadLocalRandom.current().nextInt(total);
      for (Map.Entry<String, Integer> entry : pool.entrySet()) {
         roll -= entry.getValue();
         if (roll < 0) {
            return entry.getKey();
         }
      }

      return null;
   }

   private boolean canRun(String event) {
      String base = event.split(":")[0].toLowerCase(Locale.ROOT);
      return switch (base) {
         case "koth" -> this.manager.koth().getCenter() != null;
         case "supplydrop", "bounty", "fallenstar", "bloodmoon" -> true;
         case "boss" -> {
            String[] parts = event.split(":");
            var bosses = this.plugin.getBossManager();
            yield bosses != null && !bosses.isRunning() && (parts.length > 1 ? bosses.hasArena(parts[1].toLowerCase(Locale.ROOT)) : !bosses.withArenas().isEmpty());
         }
         default -> false;
      };
   }

   public static String displayName(String event) {
      String[] parts = event.split(":");
      return switch (parts[0].toLowerCase(Locale.ROOT)) {
         case "supplydrop" -> (parts.length > 1 ? parts[1].substring(0, 1).toUpperCase(Locale.ROOT) + parts[1].substring(1) + "-game " : "") + "Supply Drop";
         case "bounty" -> "Bounty Hunt";
         case "koth" -> "King of the Hill";
         case "fallenstar" -> "Fallen Star";
         case "bloodmoon" -> "Blood Moon";
         case "boss" -> parts.length > 1 ? bossName(parts[1]) : "A Boss";
         default -> event;
      };
   }

   private static String bossName(String id) {
      return switch (id.toLowerCase(Locale.ROOT)) {
         case "hollow_warden" -> "The Hollow Warden";
         case "wither_king" -> "The Wither King";
         case "leviathan" -> "The Leviathan";
         case "ender_colossus" -> "The Ender Colossus";
         default -> "A Boss";
      };
   }

   private void announce(String event, int minutes) {
      String name = displayName(event);
      Component line = Component.text("⚡ ", NamedTextColor.GOLD).append(Component.text(name, NamedTextColor.YELLOW, TextDecoration.BOLD))
         .append(Component.text(" begins in " + minutes + " minute" + (minutes == 1 ? "" : "s") + "!", NamedTextColor.GOLD));
      String[] parts = event.split(":");
      if (parts[0].equalsIgnoreCase("boss") && parts.length > 1 && this.plugin.getBossManager() != null) {
         String where = this.plugin.getBossManager().arenaText(parts[1]);
         if (where != null) {
            line = line.append(Component.text(" Arena: " + where, NamedTextColor.GRAY));
         }
      }
      Bukkit.broadcast(line);
      for (Player player : Bukkit.getOnlinePlayers()) {
         this.plugin.getSoundManager().self(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6F, minutes <= 1 ? 1.6F : 1.0F);
         if (minutes <= 1) {
            player.showTitle(Title.title(Component.text(name, NamedTextColor.YELLOW, TextDecoration.BOLD), Component.text("starts in 1 minute", NamedTextColor.GRAY)));
         }
      }
   }

   private World mainWorld() {
      String name = this.config() == null ? "" : this.config().getString("world", "");
      World world = name == null || name.isBlank() ? null : Bukkit.getWorld(name);
      return world != null ? world : Bukkit.getWorlds().get(0);
   }

   /** Start an event now. Null on success, else why not. */
   public String run(String event) {
      String[] parts = event.toLowerCase(Locale.ROOT).split(":");
      ConfigurationSection events = this.plugin.getConfig().getConfigurationSection("events");
      return switch (parts[0]) {
         case "supplydrop" -> {
            String tier = parts.length > 1 ? parts[1] : this.config().getString("supply-tier", "mid");
            yield this.manager.supplyDrops().spawn(tier, Math.max(1, this.config().getInt("supply-count", 2)), this.mainWorld());
         }
         case "bounty" -> this.manager.bounty().start(events == null ? 30 : events.getInt("bounty.default-minutes", 30));
         case "koth" -> this.manager.koth().start(events == null ? 15 : events.getInt("koth.default-minutes", 15));
         case "fallenstar" -> this.manager.fallenStar().start(this.mainWorld());
         case "bloodmoon" -> this.manager.bloodMoon().arm(this.mainWorld());
         case "boss" -> this.plugin.getBossManager().start(parts.length > 1 ? parts[1] : null);
         default -> "Unknown event '" + event + "'.";
      };
   }

   public void skip() {
      this.plan = null;
      this.manager.save();
   }

   public List<String> status() {
      List<String> lines = new ArrayList<>();
      lines.add("Event Director: " + (this.enabled() ? "ON" : "OFF") + " (" + this.zone() + ", min " + (this.config() == null ? 3 : this.config().getInt("min-players", 3)) + " players)");
      if (this.plan != null) {
         lines.add("Next: " + displayName(this.plan.event) + " at " + this.plan.at.format(CLOCK) + " (window " + this.plan.window + ")");
      } else {
         lines.add("Next: nothing planned right now.");
      }

      ConfigurationSection windows = this.config() == null ? null : this.config().getConfigurationSection("windows");
      if (windows != null) {
         LocalDate today = ZonedDateTime.now(this.zone()).toLocalDate();
         for (String name : windows.getKeys(false)) {
            ConfigurationSection window = windows.getConfigurationSection(name);
            if (window != null) {
               lines.add(" - " + name + ": " + window.getString("from") + "-" + window.getString("to") + " " + window.getStringList("days")
                  + (today.equals(this.handled.get(name)) ? " (done today)" : ""));
            }
         }
      }

      return lines;
   }
}
