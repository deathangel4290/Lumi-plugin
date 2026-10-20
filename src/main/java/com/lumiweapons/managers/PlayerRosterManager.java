package com.lumiweapons.managers;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.lumiweapons.LumiWeapons;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Everyone who has joined, for the Veil of Mimicry menu. Also caches each player's signed skin
 * texture from the moment they join, so a disguise never depends on a live Mojang lookup (which
 * rate-limits and does not work at all in offline mode) -- that was why skins "sometimes" failed.
 *
 * seen-players.yml: players.<uuid>.name / .textures / .signature. The 1.1 format
 * (players.<uuid>: name) is still read.
 */
public class PlayerRosterManager {
   private final LumiWeapons plugin;
   private final File file;
   private final FileConfiguration config;
   private final Map<UUID, String> seen = new LinkedHashMap<>();
   private final Map<UUID, ProfileProperty> textures = new HashMap<>();

   public PlayerRosterManager(LumiWeapons plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "seen-players.yml");
      if (!this.file.exists()) {
         try {
            this.file.getParentFile().mkdirs();
            this.file.createNewFile();
         } catch (IOException var3) {
            plugin.getLogger().severe("Could not create seen-players.yml: " + var3.getMessage());
         }
      }

      this.config = YamlConfiguration.loadConfiguration(this.file);
      this.load();
      this.seedFromOfflinePlayers();

      for (Player online : Bukkit.getOnlinePlayers()) {
         this.record(online);
      }

      plugin.getLogger().info("Veil roster: " + this.seen.size() + " known player(s), " + this.textures.size() + " cached skin(s).");
   }

   private void load() {
      ConfigurationSection players = this.config.getConfigurationSection("players");
      if (players != null) {
         for (String key : players.getKeys(false)) {
            try {
               UUID id = UUID.fromString(key);
               if (players.isConfigurationSection(key)) {
                  ConfigurationSection entry = players.getConfigurationSection(key);
                  String name = entry.getString("name");
                  if (name != null && !name.isBlank()) {
                     this.seen.put(id, name);
                  }

                  String value = entry.getString("textures");
                  if (value != null && !value.isBlank()) {
                     this.textures.put(id, new ProfileProperty("textures", value, entry.getString("signature")));
                  }
               } else {
                  String name = players.getString(key);
                  if (name != null && !name.isBlank()) {
                     this.seen.put(id, name);
                  }
               }
            } catch (IllegalArgumentException var8) {
               this.plugin.getLogger().warning("Skipping invalid UUID in seen-players.yml: " + key);
            }
         }
      }
   }

   private void seedFromOfflinePlayers() {
      int added = 0;

      for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
         String name = offline.getName();
         if (name != null && !name.isBlank() && !this.seen.containsKey(offline.getUniqueId())) {
            this.seen.put(offline.getUniqueId(), name);
            added++;
         }
      }

      if (added > 0) {
         this.save();
      }
   }

   /** Called on join, before any disguise can apply, so it records the REAL name and skin. */
   public void record(Player player) {
      if (this.plugin.getDisguiseManager() == null || !this.plugin.getDisguiseManager().isDisguised(player)) {
         boolean changed = !player.getName().equals(this.seen.put(player.getUniqueId(), player.getName()));

         for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
               ProfileProperty previous = this.textures.put(player.getUniqueId(), property);
               changed |= previous == null || !previous.getValue().equals(property.getValue());
            }
         }

         if (changed) {
            this.save();
         }
      }
   }

   public ProfileProperty cachedTextures(UUID id) {
      return this.textures.get(id);
   }

   public void cacheTextures(UUID id, ProfileProperty property) {
      this.textures.put(id, property);
      this.save();
   }

   public UUID idOf(String name) {
      for (Entry<UUID, String> entry : this.seen.entrySet()) {
         if (entry.getValue().equalsIgnoreCase(name)) {
            return entry.getKey();
         }
      }

      return null;
   }

   public List<Entry<UUID, String>> entries() {
      return new ArrayList<>(this.seen.entrySet());
   }

   public int size() {
      return this.seen.size();
   }

   public void save() {
      this.config.set("players", null);

      for (Entry<UUID, String> entry : this.seen.entrySet()) {
         String path = "players." + entry.getKey();
         this.config.set(path + ".name", entry.getValue());
         ProfileProperty skin = this.textures.get(entry.getKey());
         if (skin != null) {
            this.config.set(path + ".textures", skin.getValue());
            this.config.set(path + ".signature", skin.getSignature());
         }
      }

      try {
         this.config.save(this.file);
      } catch (IOException var4) {
         this.plugin.getLogger().severe("Could not save seen-players.yml: " + var4.getMessage());
      }
   }
}
