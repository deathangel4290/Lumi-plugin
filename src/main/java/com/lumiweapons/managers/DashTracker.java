package com.lumiweapons.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Who is mid-dash right now, and who has had their dashes locked. Hermes's Blade and Luminosity
 * report their dashes here; the Tether Chain reads it to punish a dash it catches.
 */
public class DashTracker {
   private final Map<UUID, Long> dashingUntil = new HashMap<>();
   private final Map<UUID, Long> lockedUntil = new HashMap<>();

   public void markDashing(Player player, long ticks) {
      this.dashingUntil.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
   }

   public boolean isDashing(Player player) {
      Long until = this.dashingUntil.get(player.getUniqueId());
      return until != null && until > System.currentTimeMillis();
   }

   public void lock(Player player, long ticks) {
      this.dashingUntil.remove(player.getUniqueId());
      this.lockedUntil.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
   }

   public double lockedSeconds(Player player) {
      Long until = this.lockedUntil.get(player.getUniqueId());
      long left = until == null ? 0L : until - System.currentTimeMillis();
      return left <= 0L ? 0.0 : left / 1000.0;
   }

   public boolean isLocked(Player player) {
      return this.lockedSeconds(player) > 0.0;
   }

   public void clear(Player player) {
      this.dashingUntil.remove(player.getUniqueId());
      this.lockedUntil.remove(player.getUniqueId());
   }
}
