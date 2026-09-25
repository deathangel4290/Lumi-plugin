package com.legendaryweapons.managers;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class DisguiseManager {
   private final LegendaryWeapons plugin;
   private final Map<UUID, DisguiseManager.Disguise> active = new HashMap<>();
   private final Map<String, PlayerProfile> profileCache = new HashMap<>();

   public DisguiseManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public boolean isDisguised(Player player) {
      return this.active.containsKey(player.getUniqueId());
   }

   public DisguiseManager.Disguise disguiseOf(Player player) {
      return this.active.get(player.getUniqueId());
   }

   public String fakeNameOf(Player player) {
      DisguiseManager.Disguise disguise = this.active.get(player.getUniqueId());
      return disguise == null ? null : disguise.fakeName();
   }

   public void fetchProfile(final String targetName, final Consumer<PlayerProfile> callback) {
      PlayerProfile cached = this.profileCache.get(targetName.toLowerCase());
      if (cached != null) {
         callback.accept(cached);
      } else {
         (new BukkitRunnable() {
            public void run() {
               PlayerProfile profile = Bukkit.createProfile(targetName);

               boolean ok;
               try {
                  ok = profile.completeFromCache(true, true);
                  if (!ok || !profile.hasTextures()) {
                     ok = profile.complete(true);
                  }
               } catch (Exception var4) {
                  DisguiseManager.this.plugin.getLogger().warning("Profile lookup failed for " + targetName + ": " + var4.getMessage());
                  ok = false;
               }

               final PlayerProfile resolved = ok ? profile : null;
               (new BukkitRunnable() {
                  public void run() {
                     if (resolved != null) {
                        DisguiseManager.this.profileCache.put(targetName.toLowerCase(), resolved);
                     }

                     callback.accept(resolved);
                  }
               }).runTask(DisguiseManager.this.plugin);
            }
         }).runTaskAsynchronously(this.plugin);
      }
   }

   public boolean apply(Player player, String fakeName, PlayerProfile targetProfile) {
      if (this.isDisguised(player)) {
         return false;
      } else if (targetProfile != null && targetProfile.hasTextures()) {
         PlayerProfile current = player.getPlayerProfile();
         DisguiseManager.Disguise record = new DisguiseManager.Disguise(
            player.getName(), fakeName, new HashSet<>(current.getProperties()), player.displayName(), player.playerListName()
         );
         this.active.put(player.getUniqueId(), record);
         PlayerProfile disguised = Bukkit.createProfile(player.getUniqueId(), fakeName);
         disguised.setProperties(targetProfile.getProperties());
         player.setPlayerProfile(disguised);
         player.displayName(Component.text(fakeName));
         player.playerListName(Component.text(fakeName));
         this.refreshFor(player);
         return true;
      } else {
         return false;
      }
   }

   public DisguiseManager.Disguise remove(Player player) {
      DisguiseManager.Disguise record = this.active.remove(player.getUniqueId());
      if (record == null) {
         return null;
      } else {
         PlayerProfile restored = Bukkit.createProfile(player.getUniqueId(), record.realName());
         restored.setProperties(record.realProperties());
         player.setPlayerProfile(restored);
         player.displayName(record.realDisplayName());
         player.playerListName(record.realListName());
         this.refreshFor(player);
         return record;
      }
   }

   private void refreshFor(final Player player) {
      final List<UUID> viewers = new ArrayList<>();

      for (Player viewer : Bukkit.getOnlinePlayers()) {
         if (!viewer.equals(player)) {
            viewers.add(viewer.getUniqueId());
            viewer.hidePlayer(this.plugin, player);
         }
      }

      (new BukkitRunnable() {
         public void run() {
            if (player.isOnline()) {
               for (UUID id : viewers) {
                  Player viewerx = Bukkit.getPlayer(id);
                  if (viewerx != null && viewerx.isOnline()) {
                     viewerx.showPlayer(DisguiseManager.this.plugin, player);
                  }
               }
            }
         }
      }).runTaskLater(this.plugin, 3L);
   }

   public void removeAll() {
      for (UUID id : new ArrayList<>(this.active.keySet())) {
         Player player = Bukkit.getPlayer(id);
         if (player != null) {
            this.remove(player);
         } else {
            this.active.remove(id);
         }
      }
   }

   public record Disguise(String realName, String fakeName, Set<ProfileProperty> realProperties, Component realDisplayName, Component realListName) {
   }
}
