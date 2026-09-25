package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public class TrustManager {
   private final LegendaryWeapons plugin;
   private File trustDataFile;
   private FileConfiguration trustDataConfig;

   public TrustManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.setupTrustData();
   }

   private void setupTrustData() {
      this.trustDataFile = new File(this.plugin.getDataFolder(), "trustdata.yml");
      if (!this.trustDataFile.exists()) {
         try {
            this.trustDataFile.createNewFile();
         } catch (IOException var2) {
            this.plugin.getLogger().severe("Could not create trustdata.yml!");
            var2.printStackTrace();
         }
      }

      this.trustDataConfig = YamlConfiguration.loadConfiguration(this.trustDataFile);
   }

   public void saveTrustData() {
      try {
         this.trustDataConfig.save(this.trustDataFile);
      } catch (IOException var2) {
         this.plugin.getLogger().severe("Could not save trustdata.yml!");
         var2.printStackTrace();
      }
   }

   public List<String> getTrustedPlayers(Player player) {
      return this.trustDataConfig.getStringList("trusted." + player.getUniqueId().toString());
   }

   public void addTrustedPlayer(Player player, Player trustedPlayer) {
      List<String> trustedList = this.getTrustedPlayers(player);
      if (!trustedList.contains(trustedPlayer.getUniqueId().toString())) {
         trustedList.add(trustedPlayer.getUniqueId().toString());
         this.trustDataConfig.set("trusted." + player.getUniqueId().toString(), trustedList);
         this.saveTrustData();
      }
   }

   public void removeTrustedPlayer(Player player, OfflinePlayer untrustedPlayer) {
      List<String> trustedList = this.getTrustedPlayers(player);
      trustedList.remove(untrustedPlayer.getUniqueId().toString());
      this.trustDataConfig.set("trusted." + player.getUniqueId().toString(), trustedList);
      this.saveTrustData();
   }

   public boolean isTrusted(Player player, Player targetPlayer) {
      List<String> trustedList = this.getTrustedPlayers(player);
      return trustedList != null && trustedList.contains(targetPlayer.getUniqueId().toString());
   }

   public boolean isMutuallyTrusted(Player player1, Player player2) {
      return this.isTrusted(player1, player2) && this.isTrusted(player2, player1);
   }
}
