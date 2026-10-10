package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Season Renown: one score every event feeds, plus side leaderboards for the different kinds of
 * player. Stored in season.yml; a season reset archives it to season-N.yml, pays the top three and
 * starts again.
 *
 * Renown amounts live in season.renown.* so they can be tuned without touching code. PvP kills
 * only count once per killer/victim pair every season.kill-repeat-minutes, so two friends cannot
 * farm each other.
 */
public final class SeasonManager {
   public enum Stat {
      RENOWN("renown", "Renown", "🏆"),
      KILLS("kills", "Kills", "⚔"),
      DEATHS("deaths", "Deaths", "💀"),
      BOSS_DAMAGE("boss", "Boss Damage", "👑"),
      BOUNTIES("bounties", "Bounties", "💰"),
      OUTPOST_MINUTES("outposts", "Outpost Time", "🏰"),
      HEARTS("hearts", "Hearts Claimed", "💜"),
      STARS("stars", "Stars Claimed", "☄");

      public final String key;
      public final String label;
      public final String icon;

      Stat(String key, String label, String icon) {
         this.key = key;
         this.label = label;
         this.icon = icon;
      }

      public static Stat byKey(String key) {
         for (Stat stat : values()) {
            if (stat.key.equalsIgnoreCase(key) || stat.name().equalsIgnoreCase(key)) {
               return stat;
            }
         }

         return null;
      }
   }

   public record Standing(UUID id, String name, double value) {
   }

   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final File file;
   private final Map<UUID, EnumMap<Stat, Double>> stats = new HashMap<>();
   private final Map<UUID, String> names = new HashMap<>();
   private final Map<String, Long> recentKills = new HashMap<>();
   private int season = 1;
   private long startedAt = System.currentTimeMillis();
   private boolean dirty;

   public SeasonManager(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.file = new File(plugin.getDataFolder(), "season.yml");
      this.load();
      (new BukkitRunnable() {
         public void run() {
            if (SeasonManager.this.dirty) {
               SeasonManager.this.save();
            }
         }
      }).runTaskTimer(plugin, 6000L, 6000L);
   }

   public int season() {
      return this.season;
   }

   private void load() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.file);
      this.season = Math.max(1, data.getInt("season", 1));
      this.startedAt = data.getLong("started", System.currentTimeMillis());
      ConfigurationSection players = data.getConfigurationSection("players");
      if (players != null) {
         for (String key : players.getKeys(false)) {
            UUID id;
            try {
               id = UUID.fromString(key);
            } catch (IllegalArgumentException var9) {
               continue;
            }

            ConfigurationSection row = players.getConfigurationSection(key);
            if (row != null) {
               EnumMap<Stat, Double> values = new EnumMap<>(Stat.class);
               for (Stat stat : Stat.values()) {
                  double value = row.getDouble(stat.key, 0.0);
                  if (value != 0.0) {
                     values.put(stat, value);
                  }
               }

               this.stats.put(id, values);
               this.names.put(id, row.getString("name", "?"));
            }
         }
      }
   }

   public void save() {
      YamlConfiguration data = new YamlConfiguration();
      data.set("season", this.season);
      data.set("started", this.startedAt);
      for (Map.Entry<UUID, EnumMap<Stat, Double>> entry : this.stats.entrySet()) {
         String base = "players." + entry.getKey();
         data.set(base + ".name", this.names.getOrDefault(entry.getKey(), "?"));
         for (Map.Entry<Stat, Double> value : entry.getValue().entrySet()) {
            data.set(base + "." + value.getKey().key, Math.round(value.getValue() * 100.0) / 100.0);
         }
      }

      try {
         this.file.getParentFile().mkdirs();
         data.save(this.file);
         this.dirty = false;
      } catch (IOException var5) {
         this.plugin.getLogger().severe("Could not save season.yml: " + var5.getMessage());
      }
   }

   public double get(UUID id, Stat stat) {
      EnumMap<Stat, Double> row = this.stats.get(id);
      return row == null ? 0.0 : row.getOrDefault(stat, 0.0);
   }

   public void add(UUID id, String name, Stat stat, double amount) {
      if (amount != 0.0 && id != null) {
         this.stats.computeIfAbsent(id, k -> new EnumMap<>(Stat.class)).merge(stat, amount, Double::sum);
         if (name != null) {
            this.names.put(id, name);
         }

         this.dirty = true;
      }
   }

   public void add(Player player, Stat stat, double amount) {
      this.add(player.getUniqueId(), this.manager.publicName(player), stat, amount);
   }

   /** Configured renown for a source, e.g. "pvp-kill". */
   public double renownFor(String source) {
      return this.plugin.getConfig().getDouble("season.renown." + source, 0.0);
   }

   /** Award the configured renown for {@code source}, times {@code multiplier}, with a chat note. */
   public double renown(Player player, String source, double multiplier, String reason) {
      double amount = this.renownFor(source) * multiplier;
      this.give(player, amount, reason);
      return amount;
   }

   public void give(Player player, double amount, String reason) {
      if (player != null && amount > 0.0) {
         this.add(player, Stat.RENOWN, amount);
         if (reason != null && this.plugin.getConfig().getBoolean("season.notify", true)) {
            player.sendMessage(Component.text("+" + format(amount) + " Renown ", NamedTextColor.GOLD).append(Component.text(reason, NamedTextColor.GRAY)));
         }
      }
   }

   /** Renown for someone who may be offline (event results computed after they left). */
   public void give(UUID id, String name, double amount) {
      Player online = Bukkit.getPlayer(id);
      if (online != null) {
         this.give(online, amount, null);
      } else {
         this.add(id, name, Stat.RENOWN, amount);
      }
   }

   /** PvP bookkeeping, called for every player death. */
   public void onDeath(Player victim, Player killer) {
      this.add(victim, Stat.DEATHS, 1.0);
      if (killer != null && !killer.equals(victim)) {
         long now = System.currentTimeMillis();
         long window = Math.max(0L, this.plugin.getConfig().getLong("season.kill-repeat-minutes", 10L)) * 60000L;
         String pair = killer.getUniqueId() + ">" + victim.getUniqueId();
         Long last = this.recentKills.get(pair);
         if (last != null && now - last < window) {
            return;                                     // same pair again too soon: no farm
         }

         this.recentKills.put(pair, now);
         this.recentKills.values().removeIf(time -> now - time > window);
         this.add(killer, Stat.KILLS, 1.0);
         this.renown(killer, "pvp-kill", 1.0, "for killing " + this.manager.publicName(victim));
      }
   }

   public List<Standing> top(Stat stat, int limit) {
      List<Standing> all = new ArrayList<>();
      for (Map.Entry<UUID, EnumMap<Stat, Double>> entry : this.stats.entrySet()) {
         double value = entry.getValue().getOrDefault(stat, 0.0);
         if (value > 0.0) {
            all.add(new Standing(entry.getKey(), this.names.getOrDefault(entry.getKey(), "?"), value));
         }
      }

      all.sort((a, b) -> Double.compare(b.value(), a.value()));
      return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
   }

   public int rank(UUID id, Stat stat) {
      double mine = this.get(id, stat);
      if (mine <= 0.0) {
         return 0;
      }

      int better = 0;
      for (EnumMap<Stat, Double> row : this.stats.values()) {
         if (row.getOrDefault(stat, 0.0) > mine) {
            better++;
         }
      }

      return better + 1;
   }

   public static String format(double value) {
      return value == Math.rint(value) ? String.valueOf((long)value) : String.format(Locale.ROOT, "%.1f", value);
   }

   public static String formatStat(Stat stat, double value) {
      return stat == Stat.OUTPOST_MINUTES ? (long)value / 60L + "h " + (long)value % 60L + "m" : format(value);
   }

   /** Chat lines for /renown top. */
   public List<Component> leaderboard(Stat stat, int limit) {
      List<Component> lines = new ArrayList<>();
      lines.add(Component.text(stat.icon + " " + stat.label + " - Season " + this.season, NamedTextColor.GOLD, TextDecoration.BOLD));
      List<Standing> top = this.top(stat, limit);
      if (top.isEmpty()) {
         lines.add(Component.text("  Nobody yet.", NamedTextColor.GRAY));
      }

      for (int i = 0; i < top.size(); i++) {
         Standing standing = top.get(i);
         NamedTextColor colour = i == 0 ? NamedTextColor.YELLOW : (i == 1 ? NamedTextColor.WHITE : (i == 2 ? NamedTextColor.GOLD : NamedTextColor.GRAY));
         lines.add(Component.text("  " + (i + 1) + ". ", colour).append(Component.text(standing.name(), NamedTextColor.WHITE))
            .append(Component.text(" - " + formatStat(stat, standing.value()), colour)));
      }

      return lines;
   }

   /** Chat lines for /renown [player]. */
   public List<Component> summary(UUID id, String name) {
      List<Component> lines = new ArrayList<>();
      lines.add(Component.text("🏆 " + name + " - Season " + this.season, NamedTextColor.GOLD, TextDecoration.BOLD));
      for (Stat stat : Stat.values()) {
         double value = this.get(id, stat);
         int rank = this.rank(id, stat);
         lines.add(Component.text("  " + stat.icon + " " + stat.label + ": ", NamedTextColor.GRAY)
            .append(Component.text(formatStat(stat, value), NamedTextColor.WHITE))
            .append(Component.text(rank > 0 ? "  #" + rank : "", NamedTextColor.DARK_GRAY)));
      }

      return lines;
   }

   public String status() {
      long days = (System.currentTimeMillis() - this.startedAt) / 86400000L;
      List<Standing> top = this.top(Stat.RENOWN, 1);
      return "Season " + this.season + ", running " + days + " day(s), " + this.stats.size() + " players. Leader: "
         + (top.isEmpty() ? "nobody" : top.get(0).name() + " (" + format(top.get(0).value()) + ")");
   }

   /** End the season: archive, pay the top three by Renown, start the next one. */
   public void reset() {
      this.save();
      File archive = new File(this.plugin.getDataFolder(), "season-" + this.season + ".yml");
      try {
         java.nio.file.Files.copy(this.file.toPath(), archive.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException var8) {
         this.plugin.getLogger().warning("Could not archive the season: " + var8.getMessage());
      }

      List<Standing> top = this.top(Stat.RENOWN, 3);
      String[] places = new String[]{"first", "second", "third"};
      Bukkit.broadcast(Component.text("🏆 Season " + this.season + " is over!", NamedTextColor.GOLD, TextDecoration.BOLD));
      for (int i = 0; i < top.size(); i++) {
         Standing standing = top.get(i);
         Bukkit.broadcast(Component.text("  " + (i + 1) + ". " + standing.name() + " - " + format(standing.value()) + " Renown", i == 0 ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
         List<String> rewards = this.plugin.getConfig().getStringList("season.rewards." + places[i]);
         if (!rewards.isEmpty()) {
            this.manager.vault().depositSpecs(standing.id(), standing.name(), rewards, "Season " + this.season + " - place " + (i + 1));
         }
      }

      for (Player player : Bukkit.getOnlinePlayers()) {
         this.plugin.getSoundManager().self(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
      }

      this.stats.clear();
      this.names.clear();
      this.recentKills.clear();
      this.season++;
      this.startedAt = System.currentTimeMillis();
      this.save();
      Bukkit.broadcast(Component.text("Season " + this.season + " has begun. Every event, fight and objective earns Renown: /renown", NamedTextColor.GOLD));
   }
}
