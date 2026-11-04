package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Glacial Glaive -- crowd control for duelists. It slows; it never freezes anyone solid for long.
 *
 *   Passive, Frostbite: hits put Frost stacks on the target. Three or more slow them; at the
 *     maximum they're slowed hard. Stacks thaw one at a time when you stop.
 *   Ability 1, Ice Lance: a piercing shard of ice through everyone in a line.
 *   Ability 2, Frozen Ground: a patch of ice where you look; enemies on it are slowed and frost over.
 *   Ultimate, Absolute Zero: a freezing burst around you; it fills everyone's Frost, and anyone at
 *     the maximum is rooted for a moment (then can't be rooted again for a while).
 */
public class GlacialGlaiveListener extends UltimateWeapon {
   public static final String KEY = "glacial-glaive";
   private static final String LANCE_KEY = "glacial-ice-lance";
   private static final String GROUND_KEY = "glacial-frozen-ground";
   private static final Color ICE = Color.fromRGB(0xA8E4FF);
   private static final Color DEEP_ICE = Color.fromRGB(0x3F8FD0);

   private record Frost(int stacks, long last) {
   }

   private final Map<UUID, Frost> frost = new HashMap<>();
   /** target -> until when they're rooted (ms). */
   private final Map<UUID, Long> rooted = new HashMap<>();

   public GlacialGlaiveListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::frostTick, 10L, 10L);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::rootTick, 1L, 1L);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Absolute Zero";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.AQUA;
   }

   private int maxFrost() {
      return Math.max(1, this.abilityInt("frost-max-stacks", 5));
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "Ice Lance", LANCE_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Frozen Ground", GROUND_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Frostbite

   private void addFrost(LivingEntity target, int amount) {
      Frost before = this.frost.get(target.getUniqueId());
      int stacks = Math.min(this.maxFrost(), (before == null ? 0 : before.stacks()) + amount);
      this.frost.put(target.getUniqueId(), new Frost(stacks, System.currentTimeMillis()));
      this.applySlow(target, stacks);
      ParticleManager.spawn(Particle.SNOWFLAKE, target.getLocation().add(0, 1, 0), 3 + stacks * 2, 0.3, 0.5, 0.3, 0.02);
   }

   private void applySlow(LivingEntity target, int stacks) {
      if (stacks >= this.maxFrost()) {
         target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 1, true, false, true));
      } else if (stacks >= this.abilityInt("frost-slow-at", 3)) {
         target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 25, 0, true, false, true));
      }
   }

   /** Twice a second: slows refresh; stacks thaw one at a time after frost-thaw without new frost. */
   private void frostTick() {
      long now = System.currentTimeMillis();
      long thaw = this.abilityLong("frost-thaw", 40L) * 50L;
      for (Map.Entry<UUID, Frost> entry : new java.util.ArrayList<>(this.frost.entrySet())) {
         Entity entity = this.plugin.getServer().getEntity(entry.getKey());
         Frost f = entry.getValue();
         if (!(entity instanceof LivingEntity living) || living.isDead()) {
            this.frost.remove(entry.getKey());
            continue;
         }

         int stacks = f.stacks();
         if (now - f.last() > thaw) {
            stacks--;
            if (stacks <= 0) {
               this.frost.remove(entry.getKey());
               continue;
            }

            this.frost.put(entry.getKey(), new Frost(stacks, now));
         }

         this.applySlow(living, stacks);
         ParticleManager.spawn(Particle.SNOWFLAKE, living.getLocation().add(0, 1.2, 0), stacks, 0.25, 0.3, 0.25, 0.0);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHit(EntityDamageByEntityEvent event) {
      if (!AbilityDamage.active() && event.getDamager() instanceof Player attacker && this.passive(attacker) > 0.0
         && event.getEntity() instanceof LivingEntity target && this.canAffect(attacker, target)) {
         this.addFrost(target, 1);
      }
   }

   // ------------------------------------------------------------------ the root

   private void root(LivingEntity target) {
      if (!this.takeImmunity("root", target, this.abilityLong("root-immunity", 100L) + this.abilityLong("root-duration", 30L))) {
         return;                                          // rooted recently: no chain-roots
      }

      this.rooted.put(target.getUniqueId(), System.currentTimeMillis() + this.abilityLong("root-duration", 30L) * 50L);
      SpriteFx.burst(target, 0.6, ICE, 1.6, 1, 4, 4);
      this.plugin.getSoundManager().world(target.getLocation(), Sound.BLOCK_GLASS_PLACE, 1.0F, 0.6F);
   }

   /** Rooted: no walking, no jumping. Every tick, until it ends. */
   private void rootTick() {
      long now = System.currentTimeMillis();
      for (Map.Entry<UUID, Long> entry : new java.util.ArrayList<>(this.rooted.entrySet())) {
         Entity entity = this.plugin.getServer().getEntity(entry.getKey());
         if (entry.getValue() < now || !(entity instanceof LivingEntity living) || living.isDead()) {
            this.rooted.remove(entry.getKey());
            continue;
         }

         Vector v = living.getVelocity();
         living.setVelocity(new Vector(0.0, Math.min(0.0, v.getY()), 0.0));
         if (now % 4 == 0) {
            ParticleManager.spawn(Particle.SNOWFLAKE, living.getLocation().add(0, 0.2, 0), 2, 0.3, 0.1, 0.3, 0.0);
         }
      }
   }

   // ------------------------------------------------------------------ Ice Lance

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, LANCE_KEY, this.abilityLong("lance-cooldown", 220L))) {
         return;
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1.0F, 1.4F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.6F, 1.8F);
      Particle.DustOptions ice = new Particle.DustOptions(ICE, 1.0F);
      this.fly(player, player.getEyeLocation(), player.getEyeLocation().getDirection(), this.ability("lance-speed", 2.2), this.ability("lance-range", 20.0), 0.6, new Flight() {
         @Override
         protected void draw(Location at, int tick) {
            ParticleManager.spawn(Particle.DUST, at, 3, 0.05, 0.05, 0.05, 0.0, ice);
            ParticleManager.spawn(Particle.SNOWFLAKE, at, 1, 0.05, 0.05, 0.05, 0.0);
         }

         @Override
         protected boolean hit(LivingEntity target, Location at) {
            AbilityDamage.hit(player, target, GlacialGlaiveListener.this.ability("lance-damage", 5.0), 0.0, at, "{victim} was impaled by {killer}'s Glacial Glaive");
            GlacialGlaiveListener.this.addFrost(target, GlacialGlaiveListener.this.abilityInt("lance-frost", 1));
            return false;                                 // it pierces: on through the line
         }

         @Override
         protected void end(Location at) {
            ParticleManager.spawn(Particle.ITEM_SNOWBALL, at, 8, 0.2, 0.2, 0.2, 0.05);
         }
      });
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Frozen Ground

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, GROUND_KEY, this.abilityLong("ground-cooldown", 360L))) {
         return;
      }

      Location centre = this.aimedGround(player, this.ability("ground-range", 12.0));
      double radius = this.ability("ground-radius", 4.0);
      int duration = Math.max(20, this.abilityInt("ground-duration", 120));
      SpriteFx.Held ring = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_inner", ICE, false, radius * 2.0 / 0.95, duration + 10);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_POWDER_SNOW_PLACE, 1.2F, 0.6F);
      int[] tick = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         tick[0] += 5;
         if (tick[0] > duration || !player.isOnline()) {
            if (ring != null) {
               ring.release(6);
            }

            task.cancel();
            return;
         }

         ParticleManager.spawn(Particle.SNOWFLAKE, centre.clone().add(0, 0.3, 0), 12, radius / 2.0, 0.1, radius / 2.0, 0.0);
         for (LivingEntity target : this.around(player, centre, radius)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15, 1, true, false, true));
            if (tick[0] % 40 == 0) {
               this.addFrost(target, 1);
            }
         }
      }, 0L, 5L);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Absolute Zero

   @Override
   protected void ultimate(Player player) {
      Location centre = player.getLocation();
      double radius = this.ability("zero-radius", 7.0);
      ParticleManager.showcase(Particle.SNOWFLAKE, centre.clone().add(0, 1, 0), 120, radius / 2.0, 1.0, radius / 2.0, 0.08, null);
      ParticleManager.showcase(Particle.FLASH, centre.clone().add(0, 1, 0), 1, 0.0, 0.0, 0.0, 0.0, ICE);
      SpriteFx.wave(centre, DEEP_ICE, radius, 10);
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_PLAYER_HURT_FREEZE, 1.5F, 0.6F);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_GLASS_BREAK, 1.2F, 0.5F);
      for (LivingEntity target : this.around(player, centre, radius)) {
         AbilityDamage.hit(player, target, this.ability("zero-damage", 6.0), 0.0, centre, "{victim} froze in {killer}'s Absolute Zero");
         this.addFrost(target, this.maxFrost());
         this.root(target);
      }

      HudManager.message(player, Component.text("Absolute Zero", NamedTextColor.AQUA));
   }
}
