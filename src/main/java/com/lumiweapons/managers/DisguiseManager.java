package com.lumiweapons.managers;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.lumiweapons.LumiWeapons;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display.Billboard;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Disguises using Paper's profile API. The player keeps their own UUID, so bans, permissions,
 * inventories and every UUID-keyed log stay attached to the real account.
 *
 * Veil disguises change only what other players see -- skin, the name over their head, the tab
 * list, chat and death messages -- and keep the real name on the server profile. Two players can
 * therefore look identical while the real owner of the name is online, logs in or logs out; nothing
 * on the server ever sees two players with one name. The Most Wanted is "renamed": its profile name
 * is the alias as well, because that event must not leak the real name anywhere.
 *
 * Fixes over 1.1:
 *   - The disguised profile was built with Bukkit.createProfile(ownUuid, fakeName). For a UUID
 *     that is online, Paper returns THAT player's live profile -- real name and all -- so the
 *     name never changed and only the skin (sometimes) did. createProfileExact is used now.
 *   - Skins came from a by-name Mojang lookup, which fails on rate limits and offline-mode
 *     servers. Textures are now taken from the target if online, then from the skin cached when
 *     they last joined (see PlayerRosterManager), and only then looked up by UUID.
 *   - A floating name tag is shown above the disguised player and the vanilla tag is hidden via a
 *     scoreboard team, so the name above their head is guaranteed to be the disguise.
 */
public class DisguiseManager {
   public static final String TEAM_NAME = "lw_disguise";
   private final LumiWeapons plugin;
   private final Map<UUID, Disguise> active = new HashMap<>();
   private final Map<UUID, TextDisplay> nametags = new HashMap<>();
   private final Map<UUID, Component> tagLooks = new HashMap<>();
   private final Set<UUID> tagged = new HashSet<>();

   public DisguiseManager(LumiWeapons plugin) {
      this.plugin = plugin;
      (new BukkitRunnable() {
         public void run() {
            DisguiseManager.this.followTick();
         }
      }).runTaskTimer(plugin, 1L, 1L);
   }

   /**
    * renamed = the server profile itself carries the fake name (Most Wanted: nothing may leak the
    * real name). Veil disguises are not renamed: the profile keeps the real name and only what
    * others see changes (skin, tag, tab list, chat, death messages), so the real owner of the name
    * can be online, log in or log out without the server ever seeing two players with one name.
    */
   public record Disguise(String realName, String fakeName, Set<ProfileProperty> realProperties, Component realDisplayName, Component realListName, boolean renamed) {
      /** The scoreboard entry that carries the vanilla name tag for this player. */
      public String tagEntry() {
         return this.renamed ? this.fakeName : this.realName;
      }
   }

   public boolean isDisguised(Player player) {
      return this.active.containsKey(player.getUniqueId());
   }

   public Disguise disguiseOf(Player player) {
      return this.active.get(player.getUniqueId());
   }

   public String fakeNameOf(Player player) {
      Disguise disguise = this.active.get(player.getUniqueId());
      return disguise == null ? null : disguise.fakeName();
   }

   public String realNameOf(Player player) {
      Disguise disguise = this.active.get(player.getUniqueId());
      return disguise == null ? player.getName() : disguise.realName();
   }

   public static boolean isValidName(String name) {
      return name != null && name.matches("[A-Za-z0-9_]{1,16}");
   }

   private static Set<ProfileProperty> textures(Collection<ProfileProperty> properties) {
      Set<ProfileProperty> out = new HashSet<>();
      if (properties != null) {
         for (ProfileProperty property : properties) {
            if ("textures".equals(property.getName())) {
               out.add(property);
            }
         }
      }

      return out;
   }

   /**
    * Resolve the skin for a target. The callback always runs on the main thread and receives the
    * textures property (possibly empty when nothing could be found).
    */
   public void fetchTextures(final UUID targetId, final String targetName, final Consumer<Set<ProfileProperty>> callback) {
      Player online = targetId == null ? null : Bukkit.getPlayer(targetId);
      if (online != null) {
         Disguise theirs = this.active.get(online.getUniqueId());
         Set<ProfileProperty> live = textures(theirs != null ? theirs.realProperties() : online.getPlayerProfile().getProperties());
         if (!live.isEmpty()) {
            callback.accept(live);
            return;
         }
      }

      ProfileProperty cached = targetId == null ? null : this.plugin.getPlayerRosterManager().cachedTextures(targetId);
      if (cached != null) {
         callback.accept(Set.of(cached));
      } else {
         (new BukkitRunnable() {
            public void run() {
               Set<ProfileProperty> found = new HashSet<>();

               try {
                  PlayerProfile profile = targetId != null ? Bukkit.createProfileExact(targetId, targetName) : Bukkit.createProfile(targetName);
                  if (profile.complete(true)) {
                     found.addAll(DisguiseManager.textures(profile.getProperties()));
                  }

                  if (found.isEmpty() && targetId != null) {
                     PlayerProfile byName = Bukkit.createProfile(targetName);
                     if (byName.complete(true)) {
                        found.addAll(DisguiseManager.textures(byName.getProperties()));
                     }
                  }
               } catch (Exception var3) {
                  DisguiseManager.this.plugin.getLogger().warning("Skin lookup failed for " + targetName + ": " + var3.getMessage());
               }

               DisguiseManager.this.plugin.getServer().getScheduler().runTask(DisguiseManager.this.plugin, () -> {
                  if (!found.isEmpty() && targetId != null) {
                     DisguiseManager.this.plugin.getPlayerRosterManager().cacheTextures(targetId, found.iterator().next());
                  }

                  callback.accept(found);
               });
            }
         }).runTaskAsynchronously(this.plugin);
      }
   }

   /**
    * Apply a Veil disguise: the target's skin and name as everyone sees them, while the server profile
    * keeps the real name. Works whether or not the target is online.
    */
   public boolean apply(Player player, String fakeName, Collection<ProfileProperty> textures) {
      return this.apply(player, fakeName, textures, false);
   }

   /**
    * Apply a disguise. renamed = also rename the server profile (the Most Wanted, whose real name must
    * not show anywhere). With empty textures the player shows the default skin for their UUID.
    */
   public boolean apply(Player player, String fakeName, Collection<ProfileProperty> textures, boolean renamed) {
      if (this.isDisguised(player) || !isValidName(fakeName)) {
         return false;
      } else {
         PlayerProfile current = player.getPlayerProfile();
         Disguise record = new Disguise(player.getName(), fakeName, new HashSet<>(current.getProperties()), player.displayName(), player.playerListName(), renamed);
         this.active.put(player.getUniqueId(), record);
         PlayerProfile disguised = Bukkit.createProfileExact(player.getUniqueId(), renamed ? fakeName : player.getName());
         disguised.setProperties(textures(textures));
         player.setPlayerProfile(disguised);
         player.displayName(Component.text(fakeName));
         player.playerListName(this.lookOf(fakeName));
         // The vanilla tag would show the real (or, renamed, the shared) name: hide this player's
         // entry and float the disguise name instead. Only this player's entry is hidden, so a real
         // namesake standing next to them keeps their own vanilla tag.
         if (this.customNametags() || !renamed) {
            this.tagged.add(player.getUniqueId());
            this.hideVanillaTag(record.tagEntry(), true);
            this.spawnNametag(player, fakeName);
         }

         return true;
      }
   }

   public Disguise remove(Player player) {
      Disguise record = this.active.remove(player.getUniqueId());
      this.removeNametag(player.getUniqueId());
      if (record == null) {
         return null;
      } else {
         if (this.tagged.remove(player.getUniqueId())) {
            this.hideVanillaTag(record.tagEntry(), false);
         }

         PlayerProfile restored = Bukkit.createProfileExact(player.getUniqueId(), record.realName());
         restored.setProperties(record.realProperties());
         player.setPlayerProfile(restored);
         player.displayName(record.realDisplayName());
         player.playerListName(record.realListName());
         return record;
      }
   }

   public void removeAll() {
      for (UUID id : new ArrayList<>(this.active.keySet())) {
         Player player = Bukkit.getPlayer(id);
         if (player != null) {
            this.remove(player);
         } else {
            this.active.remove(id);
            this.tagged.remove(id);
            this.removeNametag(id);
         }
      }
   }

   // ------------------------------------------------------------------ name tags

   private boolean customNametags() {
      return this.plugin.getConfig().getBoolean("weapons.veil-of-mimicry.abilities.custom-nametag", true);
   }

   private void hideVanillaTag(String entry, boolean hide) {
      {
         this.hideOn(Bukkit.getScoreboardManager().getMainScoreboard(), entry, hide);

         for (Player online : Bukkit.getOnlinePlayers()) {
            Scoreboard board = online.getScoreboard();
            if (board != Bukkit.getScoreboardManager().getMainScoreboard()) {
               this.hideOn(board, entry, hide);
            }
         }
      }
   }

   private void hideOn(Scoreboard board, String entry, boolean hide) {
      Team team = board.getTeam(TEAM_NAME);
      if (team == null) {
         if (!hide) {
            return;
         }

         team = board.registerNewTeam(TEAM_NAME);
         team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
      }

      if (hide) {
         team.addEntry(entry);
      } else {
         team.removeEntry(entry);
      }
   }

   /** Event scoreboards call this so disguised names stay hidden for players looking at them. */
   public void syncTeamTo(Scoreboard board) {
      for (Map.Entry<UUID, Disguise> entry : this.active.entrySet()) {
         if (this.tagged.contains(entry.getKey())) {
            this.hideOn(board, entry.getValue().tagEntry(), true);
         }
      }
   }

   /**
    * How the disguise's name looks: the impersonated player's team colour and captain/member mark
    * when they are on a team (a plain white tag on someone with a team was a giveaway).
    */
   private Component lookOf(String fakeName) {
      java.util.UUID target = this.plugin.getPlayerRosterManager().idOf(fakeName);
      var teams = this.plugin.getTeamManager();
      return target == null || teams == null ? Component.text(fakeName, NamedTextColor.WHITE) : teams.decorated(target, fakeName);
   }

   /**
    * The fake tag is a text display made to pass for a real name tag: same height, size, background,
    * full brightness, no shadow, seen through walls unless the player sneaks, out to 64 blocks
    * (32 sneaking). It follows by teleport with the same 3-tick smoothing the client gives other
    * players' movement, so the two glide together instead of the tag trailing behind.
    */
   private static final int TAG_SMOOTHING = 3;

   private void spawnNametag(Player player, String name) {
      this.removeNametag(player.getUniqueId());
      Location at = this.tagLocation(player);
      Component look = this.lookOf(name);
      TextDisplay tag = at.getWorld().spawn(at, TextDisplay.class, display -> {
         display.text(look);
         display.setBillboard(Billboard.CENTER);
         display.setSeeThrough(true);
         display.setShadowed(false);
         display.setDefaultBackground(true);
         display.setPersistent(false);
         display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
         display.setTeleportDuration(TAG_SMOOTHING);
         display.setViewRange(1.0F);
         display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.0F, 1.0F, 1.0F), new Quaternionf()));
      });
      player.hideEntity(this.plugin, tag);
      this.nametags.put(player.getUniqueId(), tag);
      this.tagLooks.put(player.getUniqueId(), look);
   }

   /** Vanilla draws a player's name tag half a block above their head (lower while sneaking). */
   private Location tagLocation(Player player) {
      Location loc = player.getLocation();
      loc.setYaw(0.0F);
      loc.setPitch(0.0F);
      return loc.add(0.0, player.getHeight() + (player.isSneaking() ? 0.3 : 0.48), 0.0);
   }

   private void removeNametag(UUID id) {
      TextDisplay tag = this.nametags.remove(id);
      this.tagLooks.remove(id);
      if (tag != null && tag.isValid()) {
         tag.remove();
      }
   }

   /**
    * Tags follow by teleport (smoothed client-side by teleport interpolation) rather than riding
    * the player as a passenger: players carrying passengers cannot be teleported by other plugins.
    */
   private void followTick() {
      for (Map.Entry<UUID, Disguise> entry : this.active.entrySet()) {
         Player player = Bukkit.getPlayer(entry.getKey());
         TextDisplay tag = this.nametags.get(entry.getKey());
         if (player != null && player.isOnline() && this.tagged.contains(entry.getKey())) {
            if (tag == null || !tag.isValid() || !tag.getWorld().equals(player.getWorld())) {
               this.spawnNametag(player, entry.getValue().fakeName());
            } else if (player.isDead() || player.isInvisible() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
               tag.setViewRange(0.0F);                   // a real tag would not show either
            } else {
               boolean sneaking = player.isSneaking();
               float range = sneaking ? 0.5F : 1.0F;
               if (tag.getViewRange() != range) {
                  tag.setViewRange(range);
               }

               if (tag.isSeeThrough() == sneaking) {
                  tag.setSeeThrough(!sneaking);
               }

               Component look = this.lookOf(entry.getValue().fakeName());
               if (!look.equals(this.tagLooks.get(entry.getKey()))) {
                  tag.text(look);                        // their team changed
                  this.tagLooks.put(entry.getKey(), look);
               }

               Location to = this.tagLocation(player);
               // a teleport or a long fall: jump there at once rather than gliding across
               boolean far = tag.getLocation().distanceSquared(to) > 9.0;
               int smoothing = far ? 0 : TAG_SMOOTHING;
               if (tag.getTeleportDuration() != smoothing) {
                  tag.setTeleportDuration(smoothing);
               }

               tag.teleport(to);
            }
         }
      }
   }

   public boolean isNametag(Entity entity) {
      return entity instanceof TextDisplay && this.nametags.containsValue(entity);
   }
}
