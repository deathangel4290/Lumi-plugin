package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public class CooldownManager {
   private final LegendaryWeapons plugin;
   private final Map<UUID, Map<String, Long>> expiries = new HashMap<>();

   public CooldownManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   private static long ticksToMillis(long ticks) {
      return ticks * 50L;
   }

   public boolean isOnCooldown(Player player, String abilityKey) {
      return this.getRemainingMillis(player, abilityKey) > 0L;
   }

   public long getRemainingMillis(Player player, String abilityKey) {
      Map<String, Long> forPlayer = this.expiries.get(player.getUniqueId());
      if (forPlayer == null) {
         return 0L;
      } else {
         Long expiry = forPlayer.get(abilityKey);
         if (expiry == null) {
            return 0L;
         } else {
            long remaining = expiry - System.currentTimeMillis();
            if (remaining <= 0L) {
               forPlayer.remove(abilityKey);
               return 0L;
            } else {
               return remaining;
            }
         }
      }
   }

   public double getRemainingSeconds(Player player, String abilityKey) {
      return Math.ceil(this.getRemainingMillis(player, abilityKey) / 100.0) / 10.0;
   }

   public void set(Player player, String abilityKey, long ticks) {
      if (ticks <= 0L) {
         this.clear(player, abilityKey);
      } else {
         this.expiries.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).put(abilityKey, System.currentTimeMillis() + ticksToMillis(ticks));
      }
   }

   public void setWithItemFeedback(Player player, String abilityKey, long ticks, Material material) {
      this.set(player, abilityKey, ticks);
      if (ticks > 0L && material != null && !material.isAir()) {
         player.setCooldown(material, (int)Math.min(2147483647L, ticks));
      }
   }

   public void setWithHeldItemFeedback(Player player, String abilityKey, long ticks) {
      this.setWithItemFeedback(player, abilityKey, ticks, player.getInventory().getItemInMainHand().getType());
   }

   public boolean tryUseWithItemFeedback(Player player, String abilityKey, long ticks) {
      if (this.isOnCooldown(player, abilityKey)) {
         this.notifyOnCooldown(player, abilityKey);
         return false;
      } else {
         this.setWithHeldItemFeedback(player, abilityKey, ticks);
         return true;
      }
   }

   public void clear(Player player, String abilityKey, Material material) {
      this.clear(player, abilityKey);
      if (material != null && !material.isAir()) {
         player.setCooldown(material, 0);
      }
   }

   public void clear(Player player, String abilityKey) {
      Map<String, Long> forPlayer = this.expiries.get(player.getUniqueId());
      if (forPlayer != null) {
         forPlayer.remove(abilityKey);
         if (forPlayer.isEmpty()) {
            this.expiries.remove(player.getUniqueId());
         }
      }
   }

   public void clearAll(Player player) {
      this.expiries.remove(player.getUniqueId());
   }

   public boolean tryUse(Player player, String abilityKey, long ticks) {
      if (this.isOnCooldown(player, abilityKey)) {
         this.notifyOnCooldown(player, abilityKey);
         return false;
      } else {
         this.set(player, abilityKey, ticks);
         return true;
      }
   }

   public void notifyOnCooldown(Player player, String abilityKey) {
      String message = this.plugin.getConfigManager().getMessage("cooldown-message", false);
      double seconds = this.getRemainingSeconds(player, abilityKey);
      player.sendActionBar(
         LegacyComponentSerializer.legacySection()
            .deserialize(message)
            .append(Component.text(" (" + String.format("%.1f", seconds) + "s)", NamedTextColor.GRAY))
      );
   }
}
