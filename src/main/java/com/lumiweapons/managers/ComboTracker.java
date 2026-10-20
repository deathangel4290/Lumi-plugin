package com.lumiweapons.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

public class ComboTracker {
   private final Map<UUID, Map<String, ComboTracker.Streak>> streaks = new HashMap<>();

   public int hit(Player attacker, String comboKey, long windowMillis) {
      Map<String, ComboTracker.Streak> forPlayer = this.streaks.computeIfAbsent(attacker.getUniqueId(), uuid -> new HashMap<>());
      ComboTracker.Streak streak = forPlayer.computeIfAbsent(comboKey, k -> new ComboTracker.Streak());
      long now = System.currentTimeMillis();
      if (streak.lastHitAt > 0L && now - streak.lastHitAt > windowMillis) {
         streak.count = 0;
      }

      streak.lastHitAt = now;
      streak.count++;
      return streak.count;
   }

   public int get(Player player, String comboKey) {
      Map<String, ComboTracker.Streak> forPlayer = this.streaks.get(player.getUniqueId());
      if (forPlayer == null) {
         return 0;
      } else {
         ComboTracker.Streak streak = forPlayer.get(comboKey);
         return streak == null ? 0 : streak.count;
      }
   }

   public void reset(Player player, String comboKey) {
      Map<String, ComboTracker.Streak> forPlayer = this.streaks.get(player.getUniqueId());
      if (forPlayer != null) {
         forPlayer.remove(comboKey);
      }
   }

   public void resetAll(Player player) {
      this.streaks.remove(player.getUniqueId());
   }

   private static final class Streak {
      int count;
      long lastHitAt;
   }
}
