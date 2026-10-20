package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * The action-bar HUD: cooldowns, charges and meters for whatever the player is holding, plus
 * anything else that wants a permanent slot (Veil disguise state, the bounty tracker).
 *
 * Ability feedback ("Frozen!", "Not ready") still goes to the action bar, but through
 * {@link #message}, which pauses the HUD for that player long enough to read it. Without that
 * the 4-times-a-second refresh would overwrite every message instantly.
 */
public class HudManager implements Listener {
   private static HudManager instance;
   private static final Component SEPARATOR = Component.text("  |  ", NamedTextColor.DARK_GRAY);
   private final LumiWeapons plugin;
   private final Map<UUID, Long> holdUntil = new HashMap<>();
   private final Set<UUID> showing = new HashSet<>();
   private final List<Function<Player, Component>> providers = new ArrayList<>();

   public HudManager(LumiWeapons plugin) {
      this.plugin = plugin;
      instance = this;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      (new BukkitRunnable() {
         public void run() {
            HudManager.this.tick();
         }
      }).runTaskTimer(plugin, 20L, 4L);
   }

   public static HudManager get() {
      return instance;
   }

   /** Send an ability message and keep the HUD from painting over it. */
   public static void message(Player player, Component message) {
      if (player != null && message != null) {
         player.sendActionBar(message);
         if (instance != null) {
            instance.holdUntil.put(player.getUniqueId(), System.currentTimeMillis() + instance.holdMillis());
         }
      }
   }

   /** Providers return null when they have nothing to show for this player. Order is display order. */
   public void register(Function<Player, Component> provider) {
      this.providers.add(provider);
   }

   private long holdMillis() {
      return Math.max(250L, this.plugin.getConfig().getLong("hud.message-hold-millis", 1600L));
   }

   private boolean enabled() {
      return this.plugin.getConfig().getBoolean("hud.enabled", true);
   }

   private void tick() {
      if (this.enabled()) {
         long now = System.currentTimeMillis();

         for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Long hold = this.holdUntil.get(id);
            if (hold == null || hold <= now) {
               Component line = null;

               for (Function<Player, Component> provider : this.providers) {
                  Component part;
                  try {
                     part = provider.apply(player);
                  } catch (RuntimeException var11) {
                     part = null;
                  }

                  if (part != null) {
                     line = line == null ? part : line.append(SEPARATOR).append(part);
                  }
               }

               if (line != null) {
                  player.sendActionBar(line);
                  this.showing.add(id);
               } else if (this.showing.remove(id)) {
                  player.sendActionBar(Component.empty());
               }
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.holdUntil.remove(event.getPlayer().getUniqueId());
      this.showing.remove(event.getPlayer().getUniqueId());
   }

   // ------------------------------------------------------------------ formatting helpers

   public static String seconds(double seconds) {
      return seconds >= 10.0 ? String.valueOf((int)Math.ceil(seconds)) + "s" : String.format("%.1fs", seconds);
   }

   private static final net.kyori.adventure.text.format.TextColor READY = net.kyori.adventure.text.format.TextColor.color(0x6BE07A);
   private static final net.kyori.adventure.text.format.TextColor FILL = net.kyori.adventure.text.format.TextColor.color(0xB98CFF);

   /** "Label 3.2s" while cooling down, "✔ Label" when ready (no progress bar: the total is unknown). */
   public static Component cooldown(String label, double secondsLeft) {
      return secondsLeft > 0.0
         ? Component.text(label + " ", NamedTextColor.GRAY).append(Component.text(seconds(secondsLeft), secondsLeft <= 3.0 ? NamedTextColor.YELLOW : NamedTextColor.RED))
         : Component.text("✔ ", READY).append(Component.text(label, NamedTextColor.WHITE));
   }

   /**
    * A cooldown with its progress: "Label ▮▮▮▮▮▯▯▯ 3.2s" while it recharges, "✔ Label" when it's
    * ready. The bar fills as the cooldown runs out.
    */
   public static Component cooldown(Player player, String label, String cooldownKey) {
      CooldownManager cooldowns = instance == null ? null : instance.plugin.getCooldownManager();
      double left = cooldowns == null ? 0.0 : cooldowns.getRemainingSeconds(player, cooldownKey);
      if (left <= 0.0) {
         return Component.text("✔ ", READY).append(Component.text(label, NamedTextColor.WHITE));
      }

      int filled = (int)Math.round((1.0 - cooldowns.fractionLeft(player, cooldownKey)) * 8.0);
      return Component.text(label + " ", NamedTextColor.GRAY)
         .append(Component.text("▮".repeat(filled), FILL))
         .append(Component.text("▯".repeat(8 - filled), NamedTextColor.DARK_GRAY))
         .append(Component.text(" " + seconds(left), left <= 3.0 ? NamedTextColor.YELLOW : NamedTextColor.WHITE));
   }

   /** A 10-segment meter: "Label ▮▮▮▮▯▯▯▯▯▯ 40%". */
   public static Component meter(String label, double fraction, NamedTextColor colour) {
      double clamped = Math.max(0.0, Math.min(1.0, fraction));
      int filled = (int)Math.round(clamped * 10.0);
      TextComponent bar = Component.text("▮".repeat(filled), colour).append(Component.text("▯".repeat(10 - filled), NamedTextColor.DARK_GRAY));
      return Component.text(label + " ", NamedTextColor.GRAY)
         .append(bar)
         .append(Component.text(" " + (int)Math.round(clamped * 100.0) + "%", clamped >= 1.0 ? NamedTextColor.GREEN : colour));
   }

   /** "Label ●●○" for charge pools. */
   public static Component pips(String label, int ready, int max, NamedTextColor colour) {
      return Component.text(label + " ", NamedTextColor.GRAY)
         .append(Component.text("●".repeat(Math.max(0, ready)), colour))
         .append(Component.text("○".repeat(Math.max(0, max - ready)), NamedTextColor.DARK_GRAY));
   }
}
