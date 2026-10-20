package com.lumiweapons.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

public class ChargePool {
   private final Map<UUID, Map<String, long[]>> slots = new HashMap<>();

   private static long ticksToMillis(long ticks) {
      return ticks * 50L;
   }

   private long[] slotsFor(Player player, String key, int maxCharges) {
      Map<String, long[]> forPlayer = this.slots.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());
      long[] existing = forPlayer.get(key);
      if (existing == null || existing.length != Math.max(1, maxCharges)) {
         existing = new long[Math.max(1, maxCharges)];
         forPlayer.put(key, existing);
      }

      return existing;
   }

   public int available(Player player, String key, int maxCharges) {
      long now = System.currentTimeMillis();
      int ready = 0;

      for (long at : this.slotsFor(player, key, maxCharges)) {
         if (at <= now) {
            ready++;
         }
      }

      return ready;
   }

   public boolean tryConsume(Player player, String key, int maxCharges, long rechargeTicks, long emptyPenaltyTicks) {
      long now = System.currentTimeMillis();
      long[] arr = this.slotsFor(player, key, maxCharges);
      int chosen = -1;

      for (int i = 0; i < arr.length; i++) {
         if (arr[i] <= now) {
            chosen = i;
            break;
         }
      }

      if (chosen < 0) {
         return false;
      } else {
         int remaining = 0;

         for (int ix = 0; ix < arr.length; ix++) {
            if (ix != chosen && arr[ix] <= now) {
               remaining++;
            }
         }

         long wait = ticksToMillis(rechargeTicks) + (remaining == 0 ? ticksToMillis(emptyPenaltyTicks) : 0L);
         arr[chosen] = now + wait;
         return true;
      }
   }

   public long millisUntilNext(Player player, String key, int maxCharges) {
      long now = System.currentTimeMillis();
      long soonest = 9223372036854775807L;

      for (long at : this.slotsFor(player, key, maxCharges)) {
         if (at <= now) {
            return 0L;
         }

         soonest = Math.min(soonest, at);
      }

      return soonest == 9223372036854775807L ? 0L : soonest - now;
   }

   public double secondsUntilNext(Player player, String key, int maxCharges) {
      return Math.ceil(this.millisUntilNext(player, key, maxCharges) / 100.0) / 10.0;
   }

   public void clear(Player player) {
      this.slots.remove(player.getUniqueId());
   }
}
