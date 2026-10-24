package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public class TrustManager {
   private final LumiWeapons plugin;
   private File trustDataFile;
   private FileConfiguration trustDataConfig;

   public TrustManager(LumiWeapons plugin) {
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

   /** Teammates count as trusting each other for whatever teams.allies.<rule> allows. */
   private boolean teammates(java.util.UUID a, java.util.UUID b, String rule, boolean fallback) {
      var teams = this.plugin.getTeamManager();
      return teams != null && this.plugin.getConfig().getBoolean("teams.allies." + rule, fallback) && teams.sameTeam(a, b);
   }

   /** Trust from /trust alone (teams not counted). */
   public boolean personallyTrusts(java.util.UUID truster, java.util.UUID trusted) {
      return this.trustDataConfig.getStringList("trusted." + truster).contains(trusted.toString());
   }

   /** Abilities skip whoever this player trusts, and their teammates (teams.allies.abilities-skip). */
   public boolean isTrusted(Player player, Player targetPlayer) {
      return this.teammates(player.getUniqueId(), targetPlayer.getUniqueId(), "abilities-skip", true)
         || this.personallyTrusts(player.getUniqueId(), targetPlayer.getUniqueId());
   }

   /** No friendly fire: trust both ways, or teammates unless teams.allies.friendly-fire is on. */
   public boolean isMutuallyTrusted(Player player1, Player player2) {
      var teams = this.plugin.getTeamManager();
      boolean teammatesSafe = teams != null && !this.plugin.getConfig().getBoolean("teams.allies.friendly-fire", false)
         && teams.sameTeam(player1.getUniqueId(), player2.getUniqueId());
      return teammatesSafe || this.personallyTrusts(player1.getUniqueId(), player2.getUniqueId()) && this.personallyTrusts(player2.getUniqueId(), player1.getUniqueId());
   }

   /** Outposts: one side when trusting both ways, or teammates (teams.allies.share-outposts). */
   public boolean isMutuallyTrusted(java.util.UUID a, java.util.UUID b) {
      return this.teammates(a, b, "share-outposts", true) || this.personallyTrusts(a, b) && this.personallyTrusts(b, a);
   }
}
