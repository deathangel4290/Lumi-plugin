package com.lumiweapons.teams;

import com.lumiweapons.LumiWeapons;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Player teams, normally made by a captain draft ({@link Draft}). Teammates are allies: the trust
 * checks treat them as trusting each other both ways, so abilities skip them, they can't hurt each
 * other (teams.friendly-fire), and they share outposts. Each team has a colour, shown on name tags
 * and in the tab list, and its own chat (/tc, or /team chat to talk there by default).
 *
 * Saved in teams.yml.
 */
public final class TeamManager implements Listener {
   /** The colours drafted teams get, in order. */
   public static final List<NamedTextColor> COLOURS = List.of(
      NamedTextColor.RED, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.YELLOW,
      NamedTextColor.AQUA, NamedTextColor.LIGHT_PURPLE, NamedTextColor.GOLD, NamedTextColor.WHITE
   );
   private static final String BOARD_PREFIX = "lumi_";
   private final LumiWeapons plugin;
   private final File file;
   private final Map<String, Team> teams = new LinkedHashMap<>();
   private final Map<UUID, String> memberOf = new ConcurrentHashMap<>();
   private final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
   private Draft draft;

   public static final class Team {
      public final String id;
      public String name;
      public NamedTextColor colour;
      public UUID captain;
      public final Set<UUID> members = new LinkedHashSet<>();
      final Map<UUID, String> names = new LinkedHashMap<>();

      Team(String id, String name, NamedTextColor colour) {
         this.id = id;
         this.name = name;
         this.colour = colour;
      }

      public String nameOf(UUID id) {
         String known = this.names.get(id);
         if (known != null) {
            return known;
         }

         OfflinePlayer player = Bukkit.getOfflinePlayer(id);
         return player.getName() == null ? id.toString().substring(0, 8) : player.getName();
      }

      public Component label() {
         return Component.text(this.name, this.colour);
      }
   }

   public TeamManager(LumiWeapons plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "teams.yml");
      this.load();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::syncBoards, 40L, 100L);
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("teams");
   }

   public boolean enabled() {
      return this.config() == null || this.config().getBoolean("enabled", true);
   }

   // ------------------------------------------------------------------ queries

   public Team teamOf(UUID id) {
      String key = this.memberOf.get(id);
      return key == null ? null : this.teams.get(key);
   }

   public Team teamOf(Player player) {
      return this.teamOf(player.getUniqueId());
   }

   public boolean sameTeam(UUID a, UUID b) {
      if (!this.enabled() || a.equals(b)) {
         return false;
      }

      String team = this.memberOf.get(a);
      return team != null && team.equals(this.memberOf.get(b));
   }

   public Collection<Team> teams() {
      return this.teams.values();
   }

   public Team byName(String name) {
      for (Team team : this.teams.values()) {
         if (team.id.equalsIgnoreCase(name) || team.name.equalsIgnoreCase(name)) {
            return team;
         }
      }

      return null;
   }

   public Draft draft() {
      return this.draft != null && this.draft.running() ? this.draft : null;
   }

   // ------------------------------------------------------------------ changes

   public Team create(String name, NamedTextColor colour, UUID captain, String captainName) {
      String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
      String id = base;
      for (int n = 2; this.teams.containsKey(id); n++) {
         id = base + "_" + n;
      }

      Team team = new Team(id, name, colour);
      this.teams.put(id, team);
      if (captain != null) {
         team.captain = captain;
         this.add(team, captain, captainName);
      }

      this.save();
      return team;
   }

   public void add(Team team, UUID id, String name) {
      Team old = this.teamOf(id);
      if (old != null && old != team) {
         this.removeMember(old, id);
      }

      team.members.add(id);
      if (name != null) {
         team.names.put(id, name);
      }

      this.memberOf.put(id, team.id);
      this.save();
      Player online = Bukkit.getPlayer(id);
      if (online != null) {
         this.syncPlayer(online);
      }
   }

   public void remove(UUID id) {
      Team team = this.teamOf(id);
      if (team != null) {
         this.removeMember(team, id);
         this.save();
      }
   }

   private void removeMember(Team team, UUID id) {
      team.members.remove(id);
      team.names.remove(id);
      this.memberOf.remove(id);
      this.teamChat.remove(id);
      if (id.equals(team.captain)) {
         team.captain = team.members.isEmpty() ? null : team.members.iterator().next();
      }

      OfflinePlayer player = Bukkit.getOfflinePlayer(id);
      if (player.getName() != null) {
         this.clearEntry(player.getName());
      }
   }

   public void disband(Team team) {
      for (UUID id : new ArrayList<>(team.members)) {
         this.removeMember(team, id);
      }

      this.teams.remove(team.id);
      org.bukkit.scoreboard.Team board = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(BOARD_PREFIX + team.id);
      if (board != null) {
         board.unregister();
      }

      this.save();
   }

   public void disbandAll() {
      for (Team team : new ArrayList<>(this.teams.values())) {
         this.disband(team);
      }
   }

   public void rename(Team team, String name) {
      team.name = name;
      this.save();
      this.syncBoards();
   }

   void setDraft(Draft draft) {
      this.draft = draft;
   }

   // ------------------------------------------------------------------ team chat

   public boolean toggleChat(Player player) {
      if (this.teamChat.remove(player.getUniqueId())) {
         return false;
      }

      this.teamChat.add(player.getUniqueId());
      return true;
   }

   public boolean chatOn(Player player) {
      return this.teamChat.contains(player.getUniqueId());
   }

   /** Sends `text` to `from`'s team. False if they have none. */
   public boolean say(Player from, String text) {
      Team team = this.teamOf(from);
      if (team == null) {
         return false;
      }

      Component line = Component.text("[" + team.name + "] ", team.colour).append(Component.text(from.getName() + ": ", NamedTextColor.WHITE))
         .append(Component.text(text, NamedTextColor.GRAY));
      for (UUID id : team.members) {
         Player member = Bukkit.getPlayer(id);
         if (member != null) {
            member.sendMessage(line);
         }
      }

      this.plugin.getLogger().info("[Team " + team.name + "] " + from.getName() + ": " + text);
      return true;
   }

   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onChat(AsyncChatEvent event) {
      Player player = event.getPlayer();
      if (!this.teamChat.contains(player.getUniqueId()) || this.teamOf(player) == null) {
         return;
      }

      event.setCancelled(true);
      String text = PlainTextComponentSerializer.plainText().serialize(event.message());
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.say(player, text));
   }

   // ------------------------------------------------------------------ name tags and tab colours

   private boolean nametags() {
      return this.config() == null || this.config().getBoolean("coloured-names", true);
   }

   /** What each board's colour team was last set to, so unchanged teams send no update packets. */
   private final Map<String, String> applied = new java.util.HashMap<>();

   private org.bukkit.scoreboard.Team boardTeam(Scoreboard board, Team team) {
      String name = BOARD_PREFIX + team.id;
      org.bukkit.scoreboard.Team entry = board.getTeam(name);
      String slot = System.identityHashCode(board) + ":" + name;
      if (entry == null) {
         entry = board.registerNewTeam(name);
         this.applied.remove(slot);
      }

      String look = team.name + "|" + NamedTextColor.NAMES.key(team.colour);
      if (!look.equals(this.applied.get(slot))) {
         entry.color(team.colour);
         entry.prefix(Component.text("[" + team.name + "] ", team.colour));
         this.applied.put(slot, look);
      }

      return entry;
   }

   private void clearEntry(String entry) {
      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      org.bukkit.scoreboard.Team current = main.getEntryTeam(entry);
      if (current != null && current.getName().startsWith(BOARD_PREFIX)) {
         current.removeEntry(entry);
      }
   }

   /** Puts an online player in their colour team on the boards they see (not while disguised). */
   public void syncPlayer(Player player) {
      Team team = this.teamOf(player);
      boolean disguised = this.plugin.getDisguiseManager() != null && this.plugin.getDisguiseManager().isDisguised(player);
      if (team == null || !this.enabled() || !this.nametags()) {
         this.clearEntry(player.getName());
         return;
      }

      if (disguised) {
         return;                                          // the Veil hides their tag; a colour would give them away
      }

      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      org.bukkit.scoreboard.Team current = main.getEntryTeam(player.getName());
      if (current == null || current.getName().startsWith(BOARD_PREFIX) && !current.getName().equals(BOARD_PREFIX + team.id)) {
         this.boardTeam(main, team).addEntry(player.getName());
      }
   }

   /** Every few seconds: everyone in the right colour, including on event scoreboards. */
   public void syncBoards() {
      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      for (Team team : this.teams.values()) {
         this.boardTeam(main, team);
      }

      for (Player player : Bukkit.getOnlinePlayers()) {
         this.syncPlayer(player);
         Scoreboard seen = player.getScoreboard();
         if (seen != main && this.nametags()) {
            for (Team team : this.teams.values()) {
               org.bukkit.scoreboard.Team mirror = this.boardTeam(seen, team);
               for (UUID id : team.members) {
                  Player member = Bukkit.getPlayer(id);
                  if (member != null && !this.plugin.getDisguiseManager().isDisguised(member) && seen.getEntryTeam(member.getName()) == null) {
                     mirror.addEntry(member.getName());
                  }
               }
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Team team = this.teamOf(event.getPlayer());
      if (team != null) {
         team.names.put(event.getPlayer().getUniqueId(), event.getPlayer().getName());
      }

      this.syncPlayer(event.getPlayer());
      if (this.draft() != null) {
         this.draft().showTo(event.getPlayer());
      }
   }

   // ------------------------------------------------------------------ storage

   private void load() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.file);
      ConfigurationSection root = data.getConfigurationSection("teams");
      if (root == null) {
         return;
      }

      for (String id : root.getKeys(false)) {
         ConfigurationSection section = root.getConfigurationSection(id);
         if (section == null) {
            continue;
         }

         NamedTextColor colour = NamedTextColor.NAMES.value(section.getString("colour", "white").toLowerCase(Locale.ROOT));
         Team team = new Team(id, section.getString("name", id), colour == null ? NamedTextColor.WHITE : colour);
         String captain = section.getString("captain", "");
         try {
            team.captain = captain.isEmpty() ? null : UUID.fromString(captain);
         } catch (IllegalArgumentException ignored) {
         }

         ConfigurationSection members = section.getConfigurationSection("members");
         if (members != null) {
            for (String raw : members.getKeys(false)) {
               try {
                  UUID member = UUID.fromString(raw);
                  team.members.add(member);
                  team.names.put(member, members.getString(raw, "?"));
                  this.memberOf.put(member, id);
               } catch (IllegalArgumentException ignored) {
               }
            }
         }

         this.teams.put(id, team);
      }
   }

   public void save() {
      YamlConfiguration data = new YamlConfiguration();
      for (Team team : this.teams.values()) {
         String base = "teams." + team.id;
         data.set(base + ".name", team.name);
         data.set(base + ".colour", NamedTextColor.NAMES.key(team.colour));
         data.set(base + ".captain", team.captain == null ? "" : team.captain.toString());
         for (UUID id : team.members) {
            data.set(base + ".members." + id, team.nameOf(id));
         }
      }

      try {
         this.file.getParentFile().mkdirs();
         data.save(this.file);
      } catch (IOException failure) {
         this.plugin.getLogger().severe("Could not save teams.yml: " + failure.getMessage());
      }
   }

   public void shutdown() {
      if (this.draft != null) {
         this.draft.cancel("the server is stopping");
      }

      this.save();
   }
}
