package com.lumiweapons.events;

import com.lumiweapons.LumiWeapons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Who earned what in a shared fight (bosses, Fallen Star guardians).
 *
 * Score = damage share x 60% + mechanic share x 20% + support share x 20% (events.contribution.*).
 * Each part is your fraction of everyone's total in that category, so someone who spent the fight
 * breaking shields or carrying an objective scores as well as someone who only swung at the boss.
 * A category nobody touched hands its weight to the others.
 *
 * Paying out: everyone at or above min-share gets the participant table; the top three get the
 * top3 table on top; the best gets the top1 table on top of that. Everything goes into the
 * reward chest (/rewards).
 */
public final class Contribution {
   public enum Kind { DAMAGE, MECHANIC, SUPPORT }

   public record Share(UUID id, String name, double score, double damage, double mechanic, double support) {
   }

   private final Map<UUID, double[]> raw = new HashMap<>();
   private final Map<UUID, String> names = new HashMap<>();

   public void add(Player player, Kind kind, double amount, String name) {
      if (player != null && amount > 0.0 && !Double.isNaN(amount)) {
         this.raw.computeIfAbsent(player.getUniqueId(), k -> new double[3])[kind.ordinal()] += amount;
         this.names.put(player.getUniqueId(), name);
      }
   }

   public boolean isEmpty() {
      return this.raw.isEmpty();
   }

   public double damage(UUID id) {
      double[] row = this.raw.get(id);
      return row == null ? 0.0 : row[0];
   }

   public List<Share> results(ConfigurationSection weights) {
      double[] w = new double[]{
         weights == null ? 0.6 : weights.getDouble("damage-weight", 0.6),
         weights == null ? 0.2 : weights.getDouble("mechanic-weight", 0.2),
         weights == null ? 0.2 : weights.getDouble("support-weight", 0.2)
      };
      double[] totals = new double[3];
      for (double[] row : this.raw.values()) {
         for (int i = 0; i < 3; i++) {
            totals[i] += row[i];
         }
      }

      double used = 0.0;
      for (int i = 0; i < 3; i++) {
         if (totals[i] > 0.0) {
            used += w[i];
         }
      }

      List<Share> out = new ArrayList<>();
      if (used <= 0.0) {
         return out;
      }

      for (Map.Entry<UUID, double[]> entry : this.raw.entrySet()) {
         double[] row = entry.getValue();
         double score = 0.0;
         for (int i = 0; i < 3; i++) {
            if (totals[i] > 0.0) {
               score += w[i] / used * (row[i] / totals[i]);
            }
         }

         out.add(new Share(entry.getKey(), this.names.getOrDefault(entry.getKey(), "?"), score, row[0], row[1], row[2]));
      }

      out.sort((a, b) -> Double.compare(b.score(), a.score()));
      return out;
   }

   /**
    * Pay everyone out of {@code rewards} (participant / top3 / top1 loot tables)
    * and announce the top contributors. Returns the qualifying shares, best first.
    */
   public List<Share> payOut(LumiWeapons plugin, String eventName, ConfigurationSection rewards) {
      EventManager manager = plugin.getEventManager();
      List<Share> all = this.results(plugin.getConfig().getConfigurationSection("events.contribution"));
      double minShare = plugin.getConfig().getDouble("events.contribution.min-share", 0.03);
      List<Share> qualified = new ArrayList<>();
      for (Share share : all) {
         if (share.score() >= minShare) {
            qualified.add(share);
         }
      }

      for (int i = 0; i < qualified.size(); i++) {
         Share share = qualified.get(i);
         List<org.bukkit.inventory.ItemStack> items = new ArrayList<>();
         if (rewards != null) {
            items.addAll(manager.loot().roll(rewards.getConfigurationSection("participant")));
            if (i < 3) {
               items.addAll(manager.loot().roll(rewards.getConfigurationSection("top3")));
            }

            if (i == 0) {
               items.addAll(manager.loot().roll(rewards.getConfigurationSection("top1")));
            }
         }

         manager.vault().deposit(share.id(), items, eventName + " - #" + (i + 1) + ", " + Math.round(share.score() * 100.0) + "% contribution");
      }

      if (!qualified.isEmpty()) {
         Component line = Component.text("Top contributors: ", NamedTextColor.GOLD);
         for (int i = 0; i < Math.min(3, qualified.size()); i++) {
            Share share = qualified.get(i);
            line = line.append(Component.text((i > 0 ? "  " : "") + (i + 1) + ". " + share.name() + " " + Math.round(share.score() * 100.0) + "%", i == 0 ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
         }

         Bukkit.broadcast(line);
      }

      return qualified;
   }
}
