package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Phoenix Wand -- burst damage and burst mobility, on a budget. Firebolts come in a few charges
 * that refill slowly, so it can't stand in for a bow.
 *
 *   Passive, Embers: ability hits build Ember energy, which is what Rebirth runs on.
 *   Ability 1, Firebolt: a fast fireball that bursts where it lands (one charge).
 *   Ability 2, Phoenix Flight: a fiery burst throws you up and forward.
 *   Ultimate, Rebirth: you erupt in flame, burning everyone around you, and rise with a shield of
 *     absorption hearts.
 */
public class PhoenixWandListener extends UltimateWeapon {
   public static final String KEY = "phoenix-wand";
   private static final String BOLT_POOL = "phoenix-firebolt";
   private static final String FLIGHT_KEY = "phoenix-flight";
   private static final Color FLAME = Color.fromRGB(0xFF6A1A);
   private static final Color GOLD = Color.fromRGB(0xFFC83A);

   public PhoenixWandListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Rebirth";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.GOLD;
   }

   private int charges() {
      return Math.max(1, this.abilityInt("firebolt-charges", 3));
   }

   @Override
   public Component hud(Player player) {
      int ready = this.plugin.getChargePool().available(player, BOLT_POOL, this.charges());
      Component bolts = HudManager.pips("Firebolt", ready, this.charges(), NamedTextColor.GOLD);
      if (ready < this.charges()) {
         bolts = bolts.append(Component.text(" " + HudManager.seconds(this.plugin.getChargePool().secondsUntilNext(player, BOLT_POOL, this.charges())), NamedTextColor.GRAY));
      }

      return bolts.append(Component.text("  ")).append(HudManager.cooldown(player, "Flight", FLIGHT_KEY))
         .append(Component.text("  ")).append(this.ultimateHud(player));
   }

   /** Ability hits are what fill Embers here (ultimate-charge-per-ability-hit). */
   private void ember(Player player) {
      this.chargeUltimate(player, this.ability("ultimate-charge-per-ability-hit", 15.0));
   }

   // ------------------------------------------------------------------ Firebolt

   @Override
   protected void firstAbility(Player player) {
      if (!this.plugin.getChargePool().tryConsume(player, BOLT_POOL, this.charges(), this.abilityLong("firebolt-recharge", 80L), 0L)) {
         HudManager.message(player, Component.text("Out of Firebolts", NamedTextColor.GRAY));
         return;
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.9F, 1.3F);
      Particle.DustOptions flame = new Particle.DustOptions(FLAME, 1.0F);
      this.fly(player, player.getEyeLocation(), player.getEyeLocation().getDirection(), this.ability("firebolt-speed", 2.0), this.ability("firebolt-range", 30.0), 0.5, new Flight() {
         @Override
         protected void draw(Location at, int tick) {
            ParticleManager.spawn(Particle.FLAME, at, 3, 0.05, 0.05, 0.05, 0.01);
            ParticleManager.spawn(Particle.DUST, at, 2, 0.05, 0.05, 0.05, 0.0, flame);
         }

         @Override
         protected void end(Location at) {
            PhoenixWandListener.this.burst(player, at, PhoenixWandListener.this.ability("firebolt-radius", 2.5), PhoenixWandListener.this.ability("firebolt-damage", 5.0));
         }
      });
   }

   /** The fireball bursts: damage falling off to the edge, a short burn. Never breaks blocks. */
   private void burst(Player player, Location at, double radius, double damage) {
      ParticleManager.spawn(Particle.FLAME, at, 30, radius / 3.0, radius / 3.0, radius / 3.0, 0.05);
      ParticleManager.spawn(Particle.LAVA, at, 6, 0.4, 0.4, 0.4, 0.0);
      SpriteFx.burst(at, FLAME, radius * 0.8, 1, 1, 4);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7F, 1.5F);
      if (!player.isOnline()) {
         return;
      }

      boolean any = false;
      for (LivingEntity target : this.around(player, at, radius)) {
         double distance = target.getLocation().distance(at);
         AbilityDamage.hit(player, target, damage * (1.0 - 0.5 * Math.min(1.0, distance / radius)), 0.0, at, "{victim} was burnt to ash by {killer}'s Phoenix Wand");
         target.setFireTicks(Math.max(target.getFireTicks(), this.abilityInt("firebolt-burn", 40)));
         any = true;
      }

      if (any) {
         this.ember(player);
      }
   }

   // ------------------------------------------------------------------ Phoenix Flight

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, FLIGHT_KEY, this.abilityLong("flight-cooldown", 240L))) {
         return;
      }

      Vector launch = facing(player).multiply(this.ability("flight-forward", 1.2)).setY(this.ability("flight-lift", 0.9));
      player.setVelocity(launch);
      this.protectFall(player, 120L);
      Location at = player.getLocation();
      ParticleManager.spawn(Particle.FLAME, at, 30, 0.4, 0.2, 0.4, 0.08);
      SpriteFx.wave(at, FLAME, 2.5, 6);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_BLAZE_SHOOT, 1.0F, 0.8F);
      this.plugin.getSoundManager().world(at, Sound.ITEM_FIRECHARGE_USE, 1.0F, 1.0F);
      for (int tick = 2; tick < 16; tick += 2) {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline()) {
               ParticleManager.spawn(Particle.FLAME, player.getLocation(), 4, 0.2, 0.1, 0.2, 0.02);
               ParticleManager.spawn(Particle.DUST, player.getLocation().add(0, 0.5, 0), 2, 0.3, 0.2, 0.3, 0.0, new Particle.DustOptions(GOLD, 1.0F));
            }
         }, tick);
      }
   }

   // ------------------------------------------------------------------ Rebirth

   @Override
   protected void ultimate(Player player) {
      Location at = player.getLocation().add(0, 1, 0);
      double radius = this.ability("rebirth-radius", 5.0);
      ParticleManager.showcase(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, GOLD);
      ParticleManager.showcase(Particle.FLAME, at, 80, radius / 2.0, 1.0, radius / 2.0, 0.12, null);
      ParticleManager.showcase(Particle.LAVA, at, 12, radius / 3.0, 0.5, radius / 3.0, 0.0, null);
      SpriteFx.wave(player.getLocation(), FLAME, radius, 8);
      SpriteFx.burst(at, GOLD, radius, 2, 2, 6);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 1.2F);
      this.plugin.getSoundManager().world(at, Sound.ITEM_TOTEM_USE, 0.8F, 1.5F);
      for (LivingEntity target : this.around(player, player.getLocation(), radius)) {
         AbilityDamage.hit(player, target, this.ability("rebirth-damage", 8.0), this.ability("rebirth-armour-pierce", 0.2), at, "{victim} burned in {killer}'s Rebirth");
         target.setFireTicks(Math.max(target.getFireTicks(), this.abilityInt("rebirth-burn", 60)));
         push(target, player.getLocation(), this.ability("rebirth-knockback", 1.0), 0.4);
      }

      // the defensive shield: absorption hearts and fire resistance for a moment
      int duration = this.abilityInt("rebirth-shield-duration", 120);
      player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, Math.max(0, this.abilityInt("rebirth-shield-level", 2) - 1), true, false, true));
      player.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, duration, 0, true, false, true));
      player.setFireTicks(0);
      HudManager.message(player, Component.text("Rebirth!", NamedTextColor.GOLD));
   }
}
