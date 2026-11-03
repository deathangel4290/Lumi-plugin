package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

/**
 * Abyssal Trident -- range and displacement. Excellent at controlling distance, but it can't
 * chain pulls: a target pulled once can't be pulled again for pull-immunity.
 *
 *   Passive, Deep Pressure: hitting the same enemy again and again builds Pressure stacks; each
 *     adds a little damage to your hits on them. Switching targets or pausing resets them.
 *   Ability 1, Tidal Pull: a watery bolt that tugs the enemy it hits a little toward you.
 *   Ability 2, Undertow: a wave carries you forward and pushes enemies out of your path.
 *   Ultimate, Abyssal Surge: a great wave rolls forward, damaging and carrying everyone in it.
 */
public class AbyssalTridentListener extends UltimateWeapon {
   public static final String KEY = "abyssal-trident";
   private static final String PULL_KEY = "abyssal-tidal-pull";
   private static final String UNDERTOW_KEY = "abyssal-undertow";
   private static final Color DEEP = Color.fromRGB(0x1A3F7A);
   private static final Color FOAM = Color.fromRGB(0x7FE3E0);

   private record Pressure(UUID target, int stacks, long last) {
   }

   private final Map<UUID, Pressure> pressure = new HashMap<>();

   public AbyssalTridentListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Abyssal Surge";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.DARK_AQUA;
   }

   private int stacks(Player player) {
      Pressure p = this.pressure.get(player.getUniqueId());
      return p == null || System.currentTimeMillis() - p.last() > this.abilityLong("pressure-window", 80L) * 50L ? 0 : p.stacks();
   }

   @Override
   public Component hud(Player player) {
      int max = this.abilityInt("pressure-max-stacks", 5);
      return HudManager.pips("Pressure", this.stacks(player), max, NamedTextColor.AQUA).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Pull", PULL_KEY)).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Undertow", UNDERTOW_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Deep Pressure

   /** Melee hits and thrown-trident hits on the same target stack up and hit a little harder. */
   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onHit(EntityDamageByEntityEvent event) {
      if (AbilityDamage.active() || !(event.getEntity() instanceof LivingEntity target)) {
         return;
      }

      Player attacker = null;
      if (event.getDamager() instanceof Player player && this.isHolding(player)) {
         attacker = player;
      } else if (event.getDamager() instanceof Trident trident && trident.getShooter() instanceof Player thrower && this.isThisWeapon(trident.getItemStack())) {
         attacker = thrower;
      }

      if (attacker == null || !this.canAffect(attacker, target)) {
         return;
      }

      int max = Math.max(1, this.abilityInt("pressure-max-stacks", 5));
      Pressure before = this.pressure.get(attacker.getUniqueId());
      int stacks = before != null && before.target().equals(target.getUniqueId()) ? Math.min(max, this.stacks(attacker) + 1) : 1;
      this.pressure.put(attacker.getUniqueId(), new Pressure(target.getUniqueId(), stacks, System.currentTimeMillis()));
      event.setDamage(event.getDamage() + (stacks - 1) * this.ability("pressure-damage-per-stack", 0.5));
      ParticleManager.spawn(Particle.BUBBLE_POP, target.getLocation().add(0, 1, 0), 4 + stacks * 2, 0.3, 0.4, 0.3, 0.05);
   }

   // ------------------------------------------------------------------ Tidal Pull

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, PULL_KEY, this.abilityLong("pull-cooldown", 240L))) {
         return;
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_DROWNED_SHOOT, 1.0F, 0.8F);
      Particle.DustOptions water = new Particle.DustOptions(FOAM, 1.1F);
      this.fly(player, player.getEyeLocation(), player.getEyeLocation().getDirection(), this.ability("pull-speed", 1.6), this.ability("pull-range", 20.0), 0.6, new Flight() {
         @Override
         protected void draw(Location at, int tick) {
            ParticleManager.spawn(Particle.DUST, at, 3, 0.08, 0.08, 0.08, 0.0, water);
            ParticleManager.spawn(Particle.BUBBLE, at, 2, 0.1, 0.1, 0.1, 0.02);
         }

         @Override
         protected boolean hit(LivingEntity target, Location at) {
            AbilityDamage.hit(player, target, AbyssalTridentListener.this.ability("pull-damage", 3.0), 0.0, at, "{victim} was dragged under by {killer}'s Abyssal Trident");
            if (AbyssalTridentListener.this.takeImmunity("pull", target, AbyssalTridentListener.this.abilityLong("pull-immunity", 100L))) {
               Vector toward = player.getLocation().toVector().subtract(target.getLocation().toVector());
               double distance = toward.length();
               // a tug, not a reel: never further than pull-strength, and never closer than 3 blocks
               double strength = Math.min(AbyssalTridentListener.this.ability("pull-strength", 1.0), Math.max(0.0, (distance - 3.0) * 0.18));
               if (distance > 0.1 && strength > 0.0) {
                  target.setVelocity(toward.normalize().multiply(strength).setY(0.25));
               }
            }

            ParticleManager.spawn(Particle.SPLASH, at, 20, 0.4, 0.4, 0.4, 0.1);
            AbyssalTridentListener.this.plugin.getSoundManager().world(at, Sound.ENTITY_PLAYER_SPLASH, 0.8F, 1.2F);
            AbyssalTridentListener.this.chargeFromAbility(player);
            return true;
         }

         @Override
         protected void end(Location at) {
            ParticleManager.spawn(Particle.SPLASH, at, 10, 0.3, 0.3, 0.3, 0.1);
         }
      });
   }

   // ------------------------------------------------------------------ Undertow

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, UNDERTOW_KEY, this.abilityLong("undertow-cooldown", 260L))) {
         return;
      }

      Vector forward = facing(player);
      player.setVelocity(forward.clone().multiply(this.ability("undertow-power", 1.4)).setY(0.25));
      this.protectFall(player, 60L);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_2, 1.0F, 1.0F);
      Set<UUID> pushed = new HashSet<>();
      for (int tick = 0; tick < 12; tick++) {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (!player.isOnline()) {
               return;
            }

            Location at = player.getLocation();
            ParticleManager.spawn(Particle.SPLASH, at.clone().add(0, 0.2, 0), 12, 0.6, 0.1, 0.6, 0.1);
            ParticleManager.spawn(Particle.DUST, at.clone().add(0, 0.3, 0), 4, 0.5, 0.1, 0.5, 0.0, new Particle.DustOptions(DEEP, 1.2F));
            Vector side = new Vector(-forward.getZ(), 0, forward.getX());
            for (LivingEntity target : this.around(player, at, this.ability("undertow-width", 2.5))) {
               if (pushed.add(target.getUniqueId())) {
                  // pushed aside: away from your line, never toward it
                  double sideways = side.dot(target.getLocation().toVector().subtract(at.toVector()));
                  target.setVelocity(side.clone().multiply(sideways >= 0 ? 1.0 : -1.0).multiply(this.ability("undertow-push", 1.0)).setY(0.3));
                  AbilityDamage.hit(player, target, this.ability("undertow-damage", 2.0));
               }
            }
         }, tick);
      }

      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Abyssal Surge

   @Override
   protected void ultimate(Player player) {
      UUID id = player.getUniqueId();
      Vector forward = facing(player);
      Vector side = new Vector(-forward.getZ(), 0, forward.getX());
      double width = this.ability("surge-width", 5.0);
      double length = this.ability("surge-length", 16.0);
      int ticks = Math.max(10, this.abilityInt("surge-ticks", 30));
      Location start = player.getLocation().add(forward.clone().multiply(1.5));
      Set<UUID> struck = new HashSet<>();
      this.ulting.add(id);
      this.plugin.getSoundManager().world(start, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 1.2F, 0.6F);
      this.plugin.getSoundManager().world(start, Sound.ITEM_TRIDENT_THUNDER, 0.8F, 0.7F);
      HudManager.message(player, Component.text("Abyssal Surge!", NamedTextColor.DARK_AQUA));
      int[] tick = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         if (tick[0]++ >= ticks) {
            this.ulting.remove(id);
            task.cancel();
            return;
         }

         Location front = start.clone().add(forward.clone().multiply(length * tick[0] / ticks));
         // the wave: a wall of water across its width, curling at the top
         for (double s = -width / 2.0; s <= width / 2.0; s += 0.5) {
            Location base = front.clone().add(side.clone().multiply(s));
            ParticleManager.showcase(Particle.SPLASH, base.clone().add(0, 0.5, 0), 3, 0.2, 0.4, 0.2, 0.1, null);
            ParticleManager.showcase(Particle.DUST, base.clone().add(0, 1.2 + Math.random() * 1.6, 0), 1, 0.1, 0.1, 0.1, 0.0, new Particle.DustOptions(Math.random() < 0.5 ? DEEP : FOAM, 1.4F));
         }

         if (tick[0] % 5 == 0) {
            this.plugin.getSoundManager().world(front, Sound.WEATHER_RAIN_ABOVE, 1.0F, 0.6F);
         }

         if (!player.isOnline()) {
            return;
         }

         for (LivingEntity target : this.around(player, front, width / 2.0 + 0.5)) {
            if (struck.add(target.getUniqueId())) {
               AbilityDamage.hit(player, target, this.ability("surge-damage", 10.0), this.ability("surge-armour-pierce", 0.2), front, "{victim} was swept away by {killer}'s Abyssal Surge");
            }

            // caught in it: carried along with the wave
            target.setVelocity(forward.clone().multiply(this.ability("surge-carry", 0.8)).setY(0.2));
         }
      }, 4L, 1L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.pressure.remove(event.getPlayer().getUniqueId());
      this.ulting.remove(event.getPlayer().getUniqueId());
   }
}
