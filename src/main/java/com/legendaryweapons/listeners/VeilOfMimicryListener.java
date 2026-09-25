package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.guis.VeilGUI;
import com.legendaryweapons.managers.DisguiseManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class VeilOfMimicryListener extends AbstractWeaponListener {
   private static final String WEAPON_KEY = "veil-of-mimicry";
   private static final String COOLDOWN_KEY = "veil-morph";
   private final VeilGUI gui;

   public VeilOfMimicryListener(LegendaryWeapons plugin) {
      super(plugin);
      this.gui = new VeilGUI(plugin, this::morphInto);
   }

   @Override
   public String weaponKey() {
      return "veil-of-mimicry";
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      this.plugin.getPlayerRosterManager().record(event.getPlayer());
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (this.plugin.getDisguiseManager().isDisguised(player)) {
         this.reveal(player, "You drop the disguise.");
         return true;
      } else if (this.gui.candidateCount(player) == 0) {
         this.explainEmptyRoster(player);
         return true;
      } else if (!this.tryCooldown(player, "veil-morph", this.abilityLong("morph-cooldown", 300L))) {
         return true;
      } else {
         Player witness = this.findWitness(player);
         if (witness != null) {
            player.sendActionBar(Component.text(witness.getName() + " is watching you.", NamedTextColor.RED));
            this.plugin.getSoundManager().self(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.7F, 1.3F);
            player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.02);
            return true;
         } else {
            this.gui.open(player, 0);
            return true;
         }
      }
   }

   private void explainEmptyRoster(Player player) {
      int roster = this.plugin.getPlayerRosterManager().size();
      int hiddenBecauseOnline = this.gui.excludedOnlineCount(player);
      Component message;
      if (hiddenBecauseOnline > 0) {
         message = Component.text(
            "No faces available - " + hiddenBecauseOnline + " hidden because they are online. Set allow-online-targets: true in config.", NamedTextColor.RED
         );
      } else if (roster <= 1) {
         message = Component.text("No other players have joined this server yet.", NamedTextColor.RED);
      } else {
         message = Component.text("No faces available.", NamedTextColor.RED);
      }

      player.sendActionBar(message);
      player.sendMessage(message);
      this.plugin.getSoundManager().self(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.6F, 1.2F);
   }

   private Player findWitness(Player player) {
      double range = this.ability("witness-range", 24.0);
      double cosLimit = Math.cos(Math.toRadians(this.ability("witness-fov-degrees", 100.0) / 2.0));

      for (Player other : Bukkit.getOnlinePlayers()) {
         if (!other.equals(player)
            && other.getWorld().equals(player.getWorld())
            && !(other.getLocation().distance(player.getLocation()) > range)
            && other.canSee(player)) {
            Vector toPlayer = player.getEyeLocation().toVector().subtract(other.getEyeLocation().toVector());
            if (toPlayer.lengthSquared() < 1.0E-4) {
               return other;
            }

            double facing = other.getEyeLocation().getDirection().normalize().dot(toPlayer.normalize());
            if (facing >= cosLimit && other.hasLineOfSight(player)) {
               return other;
            }
         }
      }

      return null;
   }

   private void morphInto(Player player, String targetName) {
      if (player.isOnline() && !this.plugin.getDisguiseManager().isDisguised(player)) {
         Player witness = this.findWitness(player);
         if (witness != null) {
            player.sendActionBar(Component.text(witness.getName() + " is watching you.", NamedTextColor.RED));
         } else {
            this.plugin
               .getDisguiseManager()
               .fetchProfile(
                  targetName,
                  profile -> {
                     if (player.isOnline()) {
                        if (profile != null && profile.hasTextures()) {
                           if (this.plugin.getDisguiseManager().apply(player, targetName, profile)) {
                              Bukkit.broadcast(
                                 Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, new ComponentLike[]{Component.text(targetName)})
                              );
                              player.sendActionBar(Component.text("You are " + targetName + ".", NamedTextColor.LIGHT_PURPLE));
                              Location loc = player.getLocation();
                              loc.getWorld().spawnParticle(Particle.REVERSE_PORTAL, loc.add(0.0, 1.0, 0.0), 30, 0.3, 0.6, 0.3, 0.05);
                           }
                        } else {
                           player.sendActionBar(Component.text("That face would not hold.", NamedTextColor.RED));
                        }
                     }
                  }
               );
         }
      }
   }

   private void reveal(Player player, String reasonForSelf) {
      DisguiseManager.Disguise disguise = this.plugin.getDisguiseManager().remove(player);
      if (disguise != null) {
         Bukkit.broadcast(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, new ComponentLike[]{Component.text(disguise.fakeName())}));
         if (reasonForSelf != null) {
            player.sendActionBar(Component.text(reasonForSelf, NamedTextColor.GRAY));
         }

         Location loc = player.getLocation();
         loc.getWorld().spawnParticle(Particle.SMOKE, loc.clone().add(0.0, 1.0, 0.0), 40, 0.35, 0.7, 0.35, 0.03);
         this.plugin.getSoundManager().world(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 0.8F);
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onAttack(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.plugin.getDisguiseManager().isDisguised(attacker)) {
         this.reveal(attacker, "Striking broke your disguise.");
      }
   }

   @EventHandler(
      priority = EventPriority.MONITOR,
      ignoreCancelled = true
   )
   public void onDamaged(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && this.plugin.getDisguiseManager().isDisguised(player)) {
         double threshold = this.ability("break-damage-threshold", 4.0);
         if (event.getFinalDamage() >= threshold) {
            this.reveal(player, "The hit broke your disguise.");
         } else {
            if (Math.random() < this.ability("tell-chance", 0.25)) {
               this.showTell(player);
            }
         }
      }
   }

   private void showTell(final Player player) {
      final int duration = this.abilityInt("tell-duration", 30);
      (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            if (this.ticks < duration && player.isOnline()) {
               Location head = player.getLocation().add(0.0, 2.1, 0.0);
               double angle = this.ticks * 0.6;
               Location ring = head.clone().add(Math.cos(angle) * 0.45, Math.sin(this.ticks * 0.3) * 0.15, Math.sin(angle) * 0.45);
               player.getWorld().spawnParticle(Particle.WITCH, ring, 1, 0.0, 0.0, 0.0, 0.0);
               player.getWorld().spawnParticle(Particle.SMOKE, head, 1, 0.15, 0.1, 0.15, 0.01);
               this.ticks += 2;
            } else {
               this.cancel();
            }
         }
      }).runTaskTimer(this.plugin, 0L, 2L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.plugin.getDisguiseManager().remove(event.getPlayer());
   }
}
