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
import net.kyori.adventure.text.format.TextColor;
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
      if (this.config() != null && this.config().getBoolean("chat.default-on", false) && this.chatEnabled()) {
         this.teamChat.add(id);
      }

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
      this.unregisterBoards(team);

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

   public boolean allowLeave() {
      return this.config() != null && this.config().getBoolean("allow-leave", false);
   }

   public boolean captainsCanRename() {
      return this.config() == null || this.config().getBoolean("captains.can-rename", true);
   }

   public boolean captainsCanKick() {
      return this.config() != null && this.config().getBoolean("captains.can-kick", false);
   }

   public int maxNameLength() {
      return Math.max(3, Math.min(32, this.config() == null ? 20 : this.config().getInt("captains.max-name-length", 20)));
   }

   /** Makes someone on the team its captain. */
   public void setCaptain(Team team, UUID id) {
      if (team.members.contains(id)) {
         team.captain = id;
         this.save();
         Player online = Bukkit.getPlayer(id);
         if (online != null) {
            this.syncPlayer(online);
         }

         this.syncBoards();
      }
   }

   public void recolour(Team team, NamedTextColor colour) {
      team.colour = colour;
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

   public boolean chatEnabled() {
      return this.config() == null || this.config().getBoolean("chat.enabled", true);
   }

   /** Sends `text` to `from`'s team. False if they have none (or team chat is off). */
   public boolean say(Player from, String text) {
      Team team = this.teamOf(from);
      if (team == null || !this.chatEnabled()) {
         return false;
      }

      String format = this.config() == null ? "[{team}]" : this.config().getString("chat.prefix", "[{team}]");
      String prefix = (format == null ? "[{team}]" : format)
         .replace("{team}", team.name)
         .replace("{symbol}", this.symbol(from.getUniqueId().equals(team.captain)));
      Component line = Component.text(prefix.isBlank() ? "" : prefix.trim() + " ", team.colour).append(Component.text(from.getName() + ": ", NamedTextColor.WHITE))
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
      if (!this.teamChat.contains(player.getUniqueId()) || this.teamOf(player) == null || !this.chatEnabled()) {
         return;
      }

      event.setCancelled(true);
      String text = PlainTextComponentSerializer.plainText().serialize(event.message());
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.say(player, text));
   }

   // ------------------------------------------------------------------ name tags and tab colours

   /** teams.nametags.*: the team colour on names over heads and in tab, with a captain / member mark. */
   private ConfigurationSection tags() {
      return this.config() == null ? null : this.config().getConfigurationSection("nametags");
   }

   private boolean nametags() {
      ConfigurationSection tags = this.tags();
      return tags == null || tags.getBoolean("enabled", true);           // the old coloured-names is migrated by ConfigManager
   }

   private String symbol(boolean captain) {
      ConfigurationSection tags = this.tags();
      String symbol = tags == null ? (captain ? "♛" : "✦") : tags.getString(captain ? "captain-symbol" : "member-symbol", captain ? "♛" : "✦");
      return symbol == null ? "" : symbol.trim();
   }

   private TextColor symbolColour(Team team, boolean captain) {
      ConfigurationSection tags = this.tags();
      String raw = tags == null ? "" : tags.getString(captain ? "captain-symbol-colour" : "member-symbol-colour", captain ? "GOLD" : "TEAM");
      NamedTextColor named = raw == null || raw.equalsIgnoreCase("TEAM") ? null : NamedTextColor.NAMES.value(raw.trim().toLowerCase(Locale.ROOT));
      return named == null ? team.colour : named;
   }

   private boolean showTeamName() {
      ConfigurationSection tags = this.tags();
      return tags != null && tags.getBoolean("show-team-name", false);
   }

   /** What goes in front of a name: "♛ " / "✦ " (and "[Red Team] " when show-team-name is on). */
   public Component prefix(Team team, boolean captain) {
      Component out = Component.empty();
      String symbol = this.symbol(captain);
      if (!symbol.isEmpty()) {
         out = out.append(Component.text(symbol + " ", this.symbolColour(team, captain)));
      }

      if (this.showTeamName()) {
         out = out.append(Component.text("[" + team.name + "] ", team.colour));
      }

      return out;
   }

   /**
    * A name as it looks over this player's head: prefix plus the name in the team colour, or the
    * plain white name for someone without a team. The Veil's fake tags use it to look real.
    */
   public Component decorated(UUID id, String name) {
      Team team = this.teamOf(id);
      if (team == null || !this.enabled() || !this.nametags()) {
         return Component.text(name, NamedTextColor.WHITE);
      }

      return this.prefix(team, id.equals(team.captain)).append(Component.text(name, team.colour));
   }

   /** What each board's colour team was last set to, so unchanged teams send no update packets. */
   private final Map<String, String> applied = new java.util.HashMap<>();

   private static String boardName(Team team, boolean captain) {
      return BOARD_PREFIX + team.id + (captain ? "_c" : "");
   }

   /** The scoreboard team for a team's captain or its members (the prefix differs). */
   private org.bukkit.scoreboard.Team boardTeam(Scoreboard board, Team team, boolean captain) {
      String name = boardName(team, captain);
      org.bukkit.scoreboard.Team entry = board.getTeam(name);
      String slot = System.identityHashCode(board) + ":" + name;
      if (entry == null) {
         entry = board.registerNewTeam(name);
         this.applied.remove(slot);
      }

      Component prefix = this.prefix(team, captain);
      String look = NamedTextColor.NAMES.key(team.colour) + "|" + team.name + "|" + this.symbol(captain) + "|" + this.symbolColour(team, captain) + "|" + this.showTeamName();
      if (!look.equals(this.applied.get(slot))) {
         entry.color(team.colour);
         entry.prefix(prefix);
         this.applied.put(slot, look);
      }

      return entry;
   }

   private void unregisterBoards(Team team) {
      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      for (boolean captain : new boolean[]{false, true}) {
         org.bukkit.scoreboard.Team board = main.getTeam(boardName(team, captain));
         if (board != null) {
            board.unregister();
         }
      }
   }

   private void clearEntry(String entry) {
      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      org.bukkit.scoreboard.Team current = main.getEntryTeam(entry);
      if (current != null && current.getName().startsWith(BOARD_PREFIX)) {
         current.removeEntry(entry);
      }
   }

   /** Puts an online player in their colour team on the main board (not while disguised). */
   public void syncPlayer(Player player) {
      Team team = this.teamOf(player);
      boolean disguised = this.plugin.getDisguiseManager() != null && this.plugin.getDisguiseManager().isDisguised(player);
      if (team == null || !this.enabled() || !this.nametags()) {
         this.clearEntry(player.getName());
         return;
      }

      if (disguised) {
         return;                                          // the Veil hides their tag and draws the disguise's own
      }

      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      String wanted = boardName(team, player.getUniqueId().equals(team.captain));
      org.bukkit.scoreboard.Team current = main.getEntryTeam(player.getName());
      if (current == null || current.getName().startsWith(BOARD_PREFIX) && !current.getName().equals(wanted)) {
         this.boardTeam(main, team, player.getUniqueId().equals(team.captain)).addEntry(player.getName());
      }
   }

   /** Every few seconds: everyone in the right colour, including on event scoreboards. */
   public void syncBoards() {
      Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
      if (this.nametags() && this.enabled()) {
         for (Team team : this.teams.values()) {
            this.boardTeam(main, team, false);
            this.boardTeam(main, team, true);
         }
      }

      for (Player player : Bukkit.getOnlinePlayers()) {
         this.syncPlayer(player);
         Scoreboard seen = player.getScoreboard();
         if (seen != main && this.nametags() && this.enabled()) {
            for (Team team : this.teams.values()) {
               for (UUID id : team.members) {
                  Player member = Bukkit.getPlayer(id);
                  if (member != null && !this.plugin.getDisguiseManager().isDisguised(member) && seen.getEntryTeam(member.getName()) == null) {
                     this.boardTeam(seen, team, id.equals(team.captain)).addEntry(member.getName());
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
