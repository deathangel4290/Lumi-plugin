package com.legendaryweapons.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

public class ChargeMeter {
   private final Map<UUID, Map<String, Double>> values = new HashMap<>();

   public double get(Player player, String key) {
      Map<String, Double> forPlayer = this.values.get(player.getUniqueId());
      return forPlayer == null ? 0.0 : forPlayer.getOrDefault(key, 0.0);
   }

   public double add(Player player, String key, double amount, double max) {
      double updated = Math.max(0.0, Math.min(max, this.get(player, key) + amount));
      this.values.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).put(key, updated);
      return updated;
   }

   public boolean isFull(Player player, String key, double max) {
      return this.get(player, key) >= max;
   }

   public void set(Player player, String key, double value, double max) {
      double clamped = Math.max(0.0, Math.min(max, value));
      this.values.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>()).put(key, clamped);
   }

   public double consume(Player player, String key) {
      double held = this.get(player, key);
      Map<String, Double> forPlayer = this.values.get(player.getUniqueId());
      if (forPlayer != null) {
         forPlayer.remove(key);
         if (forPlayer.isEmpty()) {
            this.values.remove(player.getUniqueId());
         }
      }

      return held;
   }

   public void clear(Player player) {
      this.values.remove(player.getUniqueId());
   }

   public void showActionBar(Player player, String label, String key, double max, NamedTextColor colour) {
      double value = this.get(player, key);
      int segments = 10;
      int filled = max <= 0.0 ? 0 : (int)Math.round(value / max * segments);
      Component bar = Component.text("|".repeat(Math.max(0, filled)), colour)
         .append(Component.text("|".repeat(Math.max(0, segments - filled)), NamedTextColor.DARK_GRAY));
      player.sendActionBar(
         ((TextComponent)Component.text(label + " ", NamedTextColor.GRAY).append(bar))
            .append(Component.text(" " + (int)Math.round(value / max * 100.0) + "%", colour))
      );
   }
}
