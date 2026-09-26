package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Tether Chain -- a lead-style weapon that hooks a target and drags them to you.
 *
 * It is the answer to mobility: a target caught mid-dash (Hermes's Blade, Luminosity) is also
 * "chained down" and cannot dash again for a few seconds.
 */
public class TetherChainListener extends AbstractWeaponListener {
   private static final String COOLDOWN_KEY = "tether-hook";

   public TetherChainListener(LegendaryWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return "tether-chain";
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown("Hook", this.cooldownLeft(player, COOLDOWN_KEY));
   }

   /** It is a lead underneath; do not let it leash mobs. */
   @EventHandler(ignoreCancelled = true)
   public void onLeash(PlayerLeashEntityEvent event) {
      if (this.isHolding(event.getPlayer())) {
         event.setCancelled(true);
      }
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else {
         if (this.tryCooldown(player, COOLDOWN_KEY, this.abilityLong("hook-cooldown", 240L))) {
            this.throwHook(player);
         }

         return true;
      }
   }

   private void throwHook(final Player player) {
      final double range = this.ability("hook-range", 18.0);
      final Location eye = player.getEyeLocation();
      final Vector direction = eye.getDirection().normalize();
      RayTraceResult result = player.getWorld().rayTraceEntities(eye, direction, range, 0.6, entity -> entity instanceof LivingEntity && this.canAffect(player, entity));
      RayTraceResult blocks = player.getWorld().rayTraceBlocks(eye, direction, range);
      double blockDistance = blocks != null && blocks.getHitPosition() != null ? blocks.getHitPosition().distance(eye.toVector()) : Double.MAX_VALUE;
      final LivingEntity target;
      final double reach;
      if (result != null && result.getHitEntity() instanceof LivingEntity hit && hit.getLocation().distance(eye) <= blockDistance + 0.5) {
         target = hit;
         reach = Math.min(range, hit.getEyeLocation().distance(eye));
      } else {
         target = null;
         reach = Math.min(range, blockDistance);
      }

      this.plugin.getSoundManager().world(eye, Sound.ENTITY_FISHING_BOBBER_THROW, 1.0F, 0.6F);
      final Particle.DustOptions chain = new Particle.DustOptions(Color.fromRGB(120, 120, 130), 0.9F);
      (new BukkitRunnable() {
         double travelled = 0.0;

         public void run() {
            if (!player.isOnline()) {
               this.cancel();
               return;
            }

            // The hook flies out over a few ticks so the target has a moment to see it coming.
            double step = Math.max(1.0, range / 4.0);
            double from = this.travelled;
            this.travelled = Math.min(reach, this.travelled + step);

            for (double d = from; d <= this.travelled; d += 0.6) {
               ParticleManager.spawn(Particle.DUST, eye.clone().add(direction.clone().multiply(d)), 1, 0.0, 0.0, 0.0, 0.0, chain);
            }

            if (this.travelled >= reach) {
               this.cancel();
               if (target != null && target.isValid() && !target.isDead()) {
                  TetherChainListener.this.hooked(player, target);
               } else {
                  TetherChainListener.this.plugin.getSoundManager().world(eye, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 0.8F, 0.8F);
               }
            }
         }
      }).runTaskTimer(this.plugin, 0L, 1L);
   }

   private void hooked(Player owner, LivingEntity target) {
      Vector pull = owner.getLocation().toVector().subtract(target.getLocation().toVector());
      double distance = pull.length();
      if (distance > 0.5) {
         double strength = Math.min(this.ability("pull-strength", 1.3), 0.35 + distance * 0.09);
         target.setVelocity(pull.normalize().multiply(strength).setY(this.ability("pull-lift", 0.35)));
      }

      target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, this.abilityInt("hook-slowness-duration", 20), 0, true, false));
      Location at = target.getLocation().add(0.0, 1.0, 0.0);
      ParticleManager.spawn(Particle.CRIT, at, 10, 0.3, 0.4, 0.3, 0.1);
      this.plugin.getSoundManager().world(at, Sound.ITEM_ARMOR_EQUIP_CHAIN, 1.0F, 0.7F);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.0F, 1.2F);
      if (target instanceof Player victim) {
         if (this.plugin.getDashTracker().isDashing(victim)) {
            long lock = this.abilityLong("dash-lock-duration", 60L);
            this.plugin.getDashTracker().lock(victim, lock);
            HudManager.message(victim, Component.text("Chained! You cannot dash for " + HudManager.seconds(lock / 20.0), NamedTextColor.RED));
            HudManager.message(owner, Component.text("Caught " + victim.getName() + " mid-dash!", NamedTextColor.GOLD));
         } else {
            HudManager.message(victim, Component.text("You have been hooked!", NamedTextColor.GRAY));
            HudManager.message(owner, Component.text("Hooked " + victim.getName(), NamedTextColor.GRAY));
         }
      }
   }
}
