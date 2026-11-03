package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.AnimationFx;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Earthshaker Hammer -- heavy damage and area control. Slow to swing, long cooldowns: terrifying
 * when it lands, punishable when it misses.
 *
 *   Passive, Heavy Impact: a fully charged hit sends a small shockwave through the enemies around
 *     the one you hit.
 *   Ability 1, Groundbreaker: slam the ground; a shockwave throws everyone near you back.
 *   Ability 2, Fissure: a crack races along the ground in a line, damaging and slowing.
 *   Ultimate, Cataclysm: leap up and crash down; a big earthquake launches and damages everyone in
 *     the impact zone.
 */
public class EarthshakerListener extends UltimateWeapon {
   public static final String KEY = "earthshaker-hammer";
   private static final String SLAM_KEY = "earthshaker-groundbreaker";
   private static final String FISSURE_KEY = "earthshaker-fissure";
   private static final Color EARTH = Color.fromRGB(0x8A6A3A);
   private static final Color MAGMA = Color.fromRGB(0xE8642A);
   private final Set<UUID> leaping = new HashSet<>();

   public EarthshakerListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Cataclysm";
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "Groundbreaker", SLAM_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Fissure", FISSURE_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Heavy Impact

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHeavyHit(EntityDamageByEntityEvent event) {
      if (AbilityDamage.active() || !(event.getDamager() instanceof Player attacker) || !this.isHolding(attacker) || !this.isFullSwing(attacker)) {
         return;
      }

      Location at = event.getEntity().getLocation();
      double radius = this.ability("impact-radius", 3.0);
      ParticleManager.spawn(Particle.BLOCK, at, 20, radius / 2.0, 0.1, radius / 2.0, 0.1, at.clone().subtract(0, 0.5, 0).getBlock().getBlockData());
      this.plugin.getSoundManager().world(at, Sound.ITEM_MACE_SMASH_GROUND, 0.8F, 0.9F);
      for (LivingEntity nearby : this.around(attacker, at, radius)) {
         if (!nearby.equals(event.getEntity())) {
            AbilityDamage.hit(attacker, nearby, this.ability("impact-damage", 3.0));
         }
      }
   }

   // ------------------------------------------------------------------ Groundbreaker

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, SLAM_KEY, this.abilityLong("groundbreaker-cooldown", 400L))) {
         return;
      }

      Location centre = player.getLocation();
      double radius = this.ability("groundbreaker-radius", 5.0);
      this.shockwave(player, centre, radius, this.ability("groundbreaker-damage", 6.0), this.ability("groundbreaker-knockback", 1.3), 0.45);
      this.chargeFromAbility(player);
   }

   private void shockwave(Player player, Location centre, double radius, double damage, double knockback, double lift) {
      ParticleManager.spawn(Particle.EXPLOSION, centre, 2, 0.5, 0.1, 0.5, 0.0);
      ParticleManager.spawn(Particle.BLOCK, centre, 50, radius / 2.0, 0.2, radius / 2.0, 0.2, centre.clone().subtract(0, 0.5, 0).getBlock().getBlockData());
      this.plugin.getSoundManager().world(centre, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.2F, 0.6F);
      AnimationFx.shockwave(centre, Material.COARSE_DIRT, radius, 12);
      SpriteFx.wave(centre, EARTH, radius, 8);
      for (LivingEntity target : this.around(player, centre, radius)) {
         AbilityDamage.hit(player, target, damage, 0.0, centre, "{victim} was flattened by {killer}'s Earthshaker");
         push(target, centre, knockback, lift);
      }
   }

   // ------------------------------------------------------------------ Fissure

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, FISSURE_KEY, this.abilityLong("fissure-cooldown", 360L))) {
         return;
      }

      Vector step = facing(player);
      double length = this.ability("fissure-length", 12.0);
      Location start = player.getLocation();
      Set<UUID> struck = new HashSet<>();
      this.plugin.getSoundManager().world(start, Sound.BLOCK_DEEPSLATE_BREAK, 1.2F, 0.5F);
      for (int i = 1; i <= (int)length; i++) {
         final int at = i;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            Location crack = start.clone().add(step.clone().multiply(at));
            Block ground = crack.clone().subtract(0, 0.5, 0).getBlock();
            ParticleManager.spawn(Particle.BLOCK, crack.clone().add(0, 0.1, 0), 12, 0.4, 0.1, 0.4, 0.1, ground.getType().isAir() ? Material.DIRT.createBlockData() : ground.getBlockData());
            ParticleManager.spawn(Particle.DUST, crack.clone().add(0, 0.2, 0), 3, 0.3, 0.05, 0.3, 0.0, new Particle.DustOptions(MAGMA, 1.0F));
            if (at % 3 == 0) {
               this.plugin.getSoundManager().world(crack, Sound.BLOCK_ROOTED_DIRT_BREAK, 0.8F, 0.6F);
            }

            if (!player.isOnline()) {
               return;
            }

            for (LivingEntity target : this.around(player, crack, this.ability("fissure-width", 1.5))) {
               if (struck.add(target.getUniqueId())) {
                  AbilityDamage.hit(player, target, this.ability("fissure-damage", 5.0), 0.0, crack, "{victim} fell into {killer}'s Fissure");
                  target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, this.abilityInt("fissure-slow-duration", 40), 1, true, false, true));
                  target.setVelocity(target.getVelocity().setY(0.3));
               }
            }
         }, i / 2L);
      }

      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Cataclysm

   @Override
   protected String ultimateBlocked(Player player) {
      return this.leaping.contains(player.getUniqueId()) ? "Already in the air." : null;
   }

   @Override
   protected void ultimate(Player player) {
      UUID id = player.getUniqueId();
      this.leaping.add(id);
      this.ulting.add(id);
      this.protectFall(player, 200L);
      player.setVelocity(facing(player).multiply(this.ability("cataclysm-forward", 0.6)).setY(this.ability("cataclysm-lift", 1.3)));
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0F, 0.7F);
      HudManager.message(player, Component.text("Cataclysm!", NamedTextColor.GOLD));
      int[] airborne = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         if (!player.isOnline() || player.isDead()) {
            this.leaping.remove(id);
            this.ulting.remove(id);
            task.cancel();
            return;
         }

         // after the peak, drive down; on landing (or after 3s) the earthquake
         if (player.getVelocity().getY() < 0.0) {
            player.setVelocity(player.getVelocity().setY(Math.min(player.getVelocity().getY(), -1.2)));
         }

         ParticleManager.spawn(Particle.FALLING_DUST, player.getLocation(), 2, 0.2, 0.2, 0.2, 0.0, Material.DIRT.createBlockData());
         if (++airborne[0] > 60 || airborne[0] > 4 && player.isOnGround()) {
            this.leaping.remove(id);
            this.ulting.remove(id);
            this.earthquake(player);
            task.cancel();
         }
      }, 2L, 1L);
   }

   private void earthquake(Player player) {
      Location centre = player.getLocation();
      double radius = this.ability("cataclysm-radius", 8.0);
      double damage = this.ability("cataclysm-damage", 14.0);
      double edge = Math.max(0.0, Math.min(1.0, this.ability("cataclysm-edge-damage", 0.4)));
      ParticleManager.showcase(Particle.EXPLOSION_EMITTER, centre, 1, 0.0, 0.0, 0.0, 0.0, null);
      ParticleManager.showcase(Particle.BLOCK, centre, 120, radius / 2.0, 0.3, radius / 2.0, 0.3, centre.clone().subtract(0, 0.5, 0).getBlock().getBlockData());
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_GENERIC_EXPLODE, 1.5F, 0.6F);
      this.plugin.getSoundManager().world(centre, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.5F, 0.5F);
      AnimationFx.shockwave(centre, Material.CRACKED_DEEPSLATE_BRICKS, radius, 16);
      SpriteFx.wave(centre, MAGMA, radius, 10);
      for (LivingEntity target : this.around(player, centre, radius)) {
         double distance = horizontal(target.getLocation().toVector().subtract(centre.toVector())).length();
         double falloff = 1.0 - (1.0 - edge) * Math.min(1.0, distance / radius);
         AbilityDamage.hit(player, target, damage * falloff, this.ability("cataclysm-armour-pierce", 0.2), centre, "{victim} was buried by {killer}'s Cataclysm");
         push(target, centre, 0.6 * falloff, this.ability("cataclysm-launch", 1.0) * falloff + 0.3);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.leaping.remove(event.getPlayer().getUniqueId());
      this.ulting.remove(event.getPlayer().getUniqueId());
   }
}
