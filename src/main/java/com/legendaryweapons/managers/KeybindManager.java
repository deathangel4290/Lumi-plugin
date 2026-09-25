package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public class KeybindManager {
   private final LegendaryWeapons plugin;
   private final File keybindsFile;
   private final FileConfiguration keybindsConfig;
   private final Map<UUID, String> playerKeybinds = new HashMap<>();

   public KeybindManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.keybindsFile = new File(plugin.getDataFolder(), "keybinds.yml");
      if (!this.keybindsFile.exists()) {
         try {
            this.keybindsFile.getParentFile().mkdirs();
            this.keybindsFile.createNewFile();
         } catch (IOException var3) {
            plugin.getLogger().severe("Could not create keybinds.yml!");
            var3.printStackTrace();
         }
      }

      this.keybindsConfig = YamlConfiguration.loadConfiguration(this.keybindsFile);
      this.loadKeybinds();
   }

   private void loadKeybinds() {
      if (this.keybindsConfig.isConfigurationSection("keybinds")) {
         for (String uuidString : this.keybindsConfig.getConfigurationSection("keybinds").getKeys(false)) {
            try {
               UUID uuid = UUID.fromString(uuidString);
               String action = this.keybindsConfig.getString("keybinds." + uuidString);
               this.playerKeybinds.put(uuid, action);
            } catch (IllegalArgumentException var51) {
               this.plugin.getLogger().warning("Skipping invalid UUID in keybinds.yml: " + uuidString);
            }
         }
      }
   }

   public void saveKeybinds() {
      this.keybindsConfig.set("keybinds", null);

      for (Entry<UUID, String> entry : this.playerKeybinds.entrySet()) {
         this.keybindsConfig.set("keybinds." + entry.getKey().toString(), entry.getValue());
      }

      try {
         this.keybindsConfig.save(this.keybindsFile);
      } catch (IOException var3) {
         this.plugin.getLogger().severe("Could not save keybinds.yml!");
         var3.printStackTrace();
      }
   }

   public void setKeybind(Player player, String action) {
      this.playerKeybinds.put(player.getUniqueId(), action);
      this.saveKeybinds();
   }

   public boolean removeKeybind(Player player) {
      boolean removed = this.playerKeybinds.remove(player.getUniqueId()) != null;
      if (removed) {
         this.saveKeybinds();
      }

      return removed;
   }

   public boolean hasKeybind(Player player) {
      return this.playerKeybinds.containsKey(player.getUniqueId());
   }

   public String getKeybind(Player player) {
      return this.playerKeybinds.getOrDefault(player.getUniqueId(), "NONE");
   }
}
