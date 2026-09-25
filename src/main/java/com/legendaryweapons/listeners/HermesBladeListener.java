package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.abilities.KillTrackedWeapon;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class HermesBladeListener extends AbstractWeaponListener implements KillTrackedWeapon {
   private static final String WEAPON_KEY = "hermes-blade";
   private static final String DASH_COOLDOWN_KEY = "hermes-dash";
   private final Map<UUID, Integer> kills = new HashMap<>();

   public HermesBladeListener(LegendaryWeapons plugin) {
      super(plugin);
      this.startSpeedRefresher();
   }

   @Override
   public String weaponKey() {
      return "hermes-blade";
   }

   @Override
   public void setKillCount(Player player, int count) {
      this.kills.put(player.getUniqueId(), Math.max(0, count));
   }

   @Override
   public int getKillCount(Player player) {
      return this.kills.getOrDefault(player.getUniqueId(), 0);
   }

   private void startSpeedRefresher() {
      (new BukkitRunnable() {
         public void run() {
            int refresh = HermesBladeListener.this.abilityInt("speed-refresh-ticks", 40);
            int forOne = HermesBladeListener.this.abilityInt("kills-for-speed-1", 1);
            int forTwo = HermesBladeListener.this.abilityInt("kills-for-speed-2", 2);

            for (Player player : HermesBladeListener.this.plugin.getServer().getOnlinePlayers()) {
               if (HermesBladeListener.this.isHolding(player)) {
                  int count = HermesBladeListener.this.kills.getOrDefault(player.getUniqueId(), 0);
                  int amplifier = count >= forTwo ? 1 : (count >= forOne ? 0 : -1);
                  if (amplifier >= 0) {
                     player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, refresh, amplifier, true, false, true));
                  }
               }
            }
         }
      }).runTaskTimer(this.plugin, 20L, 20L);
   }

   @EventHandler
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player killer = event.getEntity().getKiller();
      if (killer != null && this.isHolding(killer)) {
         int count = this.kills.merge(killer.getUniqueId(), 1, Integer::sum);
         int forOne = this.abilityInt("kills-for-speed-1", 1);
         int forTwo = this.abilityInt("kills-for-speed-2", 2);
         if (count == forOne || count == forTwo) {
            int tier = count >= forTwo ? 2 : 1;
            killer.sendActionBar(Component.text("Swiftness " + tier, NamedTextColor.WHITE));
            this.plugin.getSoundManager().self(killer, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7F, 1.7F);
         }
      }

      this.kills.remove(event.getEntity().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.kills.remove(event.getPlayer().getUniqueId());
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (!this.tryCooldown(player, "hermes-dash", this.abilityLong("dash-cooldown", 100L))) {
         return true;
      } else {
         this.dash(player);
         return true;
      }
   }

   private void dash(final Player player) {
      double power = this.ability("dash-power", 1.4);
      double lift = this.ability("dash-lift", 0.25);
      final int duration = this.abilityInt("dash-duration", 8);
      final double radius = this.ability("dash-hit-radius", 1.6);
      final int slowDuration = this.abilityInt("dash-slowness-duration", 60);
      final int slowAmplifier = this.abilityInt("dash-slowness-amplifier", 1);
      Vector direction = player.getLocation().getDirection().setY(0.0).normalize().multiply(power);
      direction.setY(lift);
      player.setVelocity(direction);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.6F, 1.6F);
      final Set<UUID> alreadyHit = new HashSet<>();
      (new BukkitRunnable() {
            int ticks = 0;

            public void run() {
               if (this.ticks++ < duration && player.isOnline()) {
                  Location loc = player.getLocation();
                  loc.getWorld().spawnParticle(Particle.CLOUD, loc, 4, 0.2, 0.1, 0.2, 0.01);

                  for (Entity nearby : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
                     if (nearby instanceof Player target
                        && !target.equals(player)
                        && alreadyHit.add(target.getUniqueId())
                        && (
                           !HermesBladeListener.this.plugin.getTrustManager().isTrusted(player, target)
                              || !HermesBladeListener.this.plugin.getConfigManager().isPreventAbilityDamage()
                        )) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowAmplifier, true, false));
                        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0.0, 1.0, 0.0), 10, 0.3, 0.4, 0.3, 0.05);
                        HermesBladeListener.this.plugin.getSoundManager().world(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5F, 1.5F);
                     }
                  }
               } else {
                  this.cancel();
               }
            }
         })
         .runTaskTimer(this.plugin, 1L, 1L);
   }
}
