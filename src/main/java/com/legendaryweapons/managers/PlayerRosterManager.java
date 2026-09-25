package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public class PlayerRosterManager {
   private final LegendaryWeapons plugin;
   private final File file;
   private final FileConfiguration config;
   private final Map<UUID, String> seen = new LinkedHashMap<>();

   public PlayerRosterManager(LegendaryWeapons plugin) {
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
      this.seedFromOnlinePlayers();
      plugin.getLogger().info("Veil roster: " + this.seen.size() + " known player(s).");
   }

   private void seedFromOnlinePlayers() {
      boolean changed = false;

      for (Player online : Bukkit.getOnlinePlayers()) {
         if (!this.seen.containsKey(online.getUniqueId())) {
            this.seen.put(online.getUniqueId(), online.getName());
            changed = true;
         }
      }

      if (changed) {
         this.save();
      }
   }

   private void load() {
      if (this.config.isConfigurationSection("players")) {
         for (String key : this.config.getConfigurationSection("players").getKeys(false)) {
            try {
               String name = this.config.getString("players." + key);
               if (name != null && !name.isBlank()) {
                  this.seen.put(UUID.fromString(key), name);
               }
            } catch (IllegalArgumentException var4) {
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

   public void record(Player player) {
      if (this.plugin.getDisguiseManager() == null || !this.plugin.getDisguiseManager().isDisguised(player)) {
         String previous = this.seen.put(player.getUniqueId(), player.getName());
         if (!player.getName().equals(previous)) {
            this.save();
         }
      }
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
         this.config.set("players." + entry.getKey(), entry.getValue());
      }

      try {
         this.config.save(this.file);
      } catch (IOException var3) {
         this.plugin.getLogger().severe("Could not save seen-players.yml: " + var3.getMessage());
      }
   }
}
