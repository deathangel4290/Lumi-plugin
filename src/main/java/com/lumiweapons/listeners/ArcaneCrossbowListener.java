package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Arcane Crossbow -- precision at range. Every bolt can be dodged and nothing homes: Ricochet
 * bounces off walls, and when it does hit someone it jumps once, in a straight line, to the
 * nearest other enemy. Starfall shows where it will land before it does.
 *
 *   Passive, Arcane Charge: bolts from it that hit players build Arcane energy (the ultimate).
 *   Ability 1, Ricochet Bolt: a bolt that bounces off surfaces and can carry on into a second enemy.
 *   Ability 2, Triple Shot: three bolts in a spread.
 *   Ultimate, Starfall: mark an area; after a short warning, magic bolts rain down on it.
 */
public class ArcaneCrossbowListener extends UltimateWeapon {
   public static final String KEY = "arcane-crossbow";
   private static final String RICOCHET_KEY = "arcane-ricochet";
   private static final String TRIPLE_KEY = "arcane-triple";
   private static final Color ARCANE = Color.fromRGB(0xB45CFF);
   private static final Color STAR = Color.fromRGB(0x7FD8FF);
   private final NamespacedKey shotKey;
   private final NamespacedKey damageKey;

   public ArcaneCrossbowListener(LumiWeapons plugin) {
      super(plugin);
      this.shotKey = LumiWeapons.key("arcane-shot");
      this.damageKey = LumiWeapons.key("arcane-bolt-damage");
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Starfall";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.LIGHT_PURPLE;
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "Ricochet", RICOCHET_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Triple Shot", TRIPLE_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Arcane Charge

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onShoot(EntityShootBowEvent event) {
      if (event.getEntity() instanceof Player && this.isThisWeapon(event.getBow()) && event.getProjectile() instanceof AbstractArrow arrow) {
         arrow.getPersistentDataContainer().set(this.shotKey, PersistentDataType.BYTE, (byte)1);
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onBoltHit(EntityDamageByEntityEvent event) {
      if (!(event.getDamager() instanceof AbstractArrow arrow) || !(arrow.getShooter() instanceof Player shooter)) {
         return;
      }

      Double damage = arrow.getPersistentDataContainer().get(this.damageKey, PersistentDataType.DOUBLE);
      if (damage != null) {
         event.setDamage(damage);
      }

      if (event.getEntity() instanceof Player victim && !victim.equals(shooter) && arrow.getPersistentDataContainer().has(this.shotKey, PersistentDataType.BYTE)) {
         this.chargeUltimate(shooter, this.ability("ultimate-charge-per-bolt", 6.0));
         this.plugin.getChargeMeter().markCombat(victim);
      }
   }

   // ------------------------------------------------------------------ Ricochet Bolt

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, RICOCHET_KEY, this.abilityLong("ricochet-cooldown", 200L))) {
         return;
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 1.0F, 1.4F);
      this.ricochet(player, player.getEyeLocation(), player.getEyeLocation().getDirection(), this.ability("ricochet-bounces", 2), true,
         this.ability("ricochet-damage", 6.0));
      this.chargeFromAbility(player);
   }

   private void ricochet(Player player, Location from, Vector direction, double bouncesLeft, boolean mayJump, double damage) {
      Particle.DustOptions dust = new Particle.DustOptions(ARCANE, 0.9F);
      int[] bounces = {(int)bouncesLeft};
      this.fly(player, from, direction, this.ability("ricochet-speed", 2.0), this.ability("ricochet-range", 32.0), 0.5, new Flight() {
         @Override
         protected void draw(Location at, int tick) {
            ParticleManager.spawn(Particle.DUST, at, 2, 0.03, 0.03, 0.03, 0.0, dust);
            ParticleManager.spawn(Particle.ENCHANT, at, 2, 0.05, 0.05, 0.05, 0.2);
         }

         @Override
         protected boolean block(Location at, Vector heading, BlockFace face) {
            if (bounces[0]-- <= 0) {
               return false;
            }

            Vector normal = face.getDirection();
            heading.subtract(normal.clone().multiply(2.0 * heading.dot(normal)));      // reflect off the face
            ParticleManager.spawn(Particle.ENCHANTED_HIT, at, 8, 0.1, 0.1, 0.1, 0.1);
            ArcaneCrossbowListener.this.plugin.getSoundManager().world(at, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0F, 1.6F);
            return true;
         }

         @Override
         protected boolean hit(LivingEntity target, Location at) {
            AbilityDamage.hit(player, target, damage, 0.0, at, "{victim} was struck by {killer}'s Ricochet Bolt");
            SpriteFx.burst(at, ARCANE, 1.0, 1, 1, 3);
            if (target instanceof Player) {
               ArcaneCrossbowListener.this.chargeUltimate(player, ArcaneCrossbowListener.this.ability("ultimate-charge-per-bolt", 6.0));
            }

            if (mayJump) {
               // once: a straight line on to the nearest other enemy (no tracking after that)
               LivingEntity next = null;
               double best = Math.pow(ArcaneCrossbowListener.this.ability("ricochet-jump-range", 6.0), 2);
               for (LivingEntity other : ArcaneCrossbowListener.this.around(player, at, Math.sqrt(best))) {
                  double d = other.getLocation().distanceSquared(at);
                  if (!other.equals(target) && d < best) {
                     best = d;
                     next = other;
                  }
               }

               if (next != null) {
                  Location start = at.clone();
                  Vector toward = next.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(start.toVector());
                  start.add(toward.clone().normalize().multiply(0.8));
                  ArcaneCrossbowListener.this.ricochet(player, start, toward, 0, false, damage * ArcaneCrossbowListener.this.ability("ricochet-jump-damage", 0.7));
               }
            }

            return true;
         }
      });
   }

   // ------------------------------------------------------------------ Triple Shot

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, TRIPLE_KEY, this.abilityLong("triple-cooldown", 240L))) {
         return;
      }

      double spread = Math.toRadians(this.ability("triple-spread-degrees", 8.0));
      double speed = this.ability("triple-speed", 3.0);
      Vector aim = player.getEyeLocation().getDirection();
      for (double turn : new double[]{-spread, 0.0, spread}) {
         Arrow arrow = player.launchProjectile(Arrow.class, aim.clone().rotateAroundY(turn).multiply(speed));
         arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
         arrow.getPersistentDataContainer().set(this.shotKey, PersistentDataType.BYTE, (byte)1);
         arrow.getPersistentDataContainer().set(this.damageKey, PersistentDataType.DOUBLE, this.ability("triple-damage", 5.0));
         arrow.setCritical(true);
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 1.0F, 1.0F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 1.0F, 1.2F);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Starfall

   @Override
   protected void ultimate(Player player) {
      Location centre = this.aimedGround(player, this.ability("starfall-range", 50.0));
      double radius = this.ability("starfall-radius", 5.0);
      int warning = Math.max(10, this.abilityInt("starfall-warning", 30));
      int bolts = Math.max(1, this.abilityInt("starfall-bolts", 20));
      int over = Math.max(10, this.abilityInt("starfall-duration", 40));
      SpriteFx.Held ring = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_outer", ARCANE, false, radius * 2.0 / 0.965, warning + over + 20);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 1.8F);
      HudManager.message(player, Component.text("Starfall!", NamedTextColor.LIGHT_PURPLE));
      for (int t = 0; t < warning; t += 4) {
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            for (int i = 0; i < 24; i++) {
               double a = Math.PI * 2.0 * i / 24.0;
               ParticleManager.showcase(Particle.DUST, centre.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0, 0, 0, 0, new Particle.DustOptions(STAR, 1.0F));
            }
         }, t);
      }

      for (int b = 0; b < bolts; b++) {
         long when = warning + (long)b * over / bolts;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.fallingBolt(player, centre, radius), when);
      }

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (ring != null) {
            ring.release(6);
         }
      }, warning + over + 6L);
   }

   private void fallingBolt(Player player, Location centre, double radius) {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      double a = random.nextDouble(Math.PI * 2.0);
      double r = Math.sqrt(random.nextDouble()) * radius;
      Location ground = centre.clone().add(Math.cos(a) * r, 0.1, Math.sin(a) * r);
      Particle.DustOptions dust = new Particle.DustOptions(random.nextBoolean() ? ARCANE : STAR, 1.0F);
      for (int i = 0; i <= 5; i++) {
         final int step = i;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            Location at = ground.clone().add(0, 15.0 * (1.0 - step / 5.0), 0);
            ParticleManager.showcase(Particle.END_ROD, at, 2, 0.05, 0.3, 0.05, 0.0, null);
            ParticleManager.showcase(Particle.DUST, at, 3, 0.05, 0.4, 0.05, 0.0, dust);
            if (step == 5) {
               ParticleManager.spawn(Particle.ENCHANTED_HIT, ground, 10, 0.3, 0.1, 0.3, 0.2);
               this.plugin.getSoundManager().world(ground, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 0.6F, 1.4F);
               if (player.isOnline()) {
                  for (LivingEntity target : this.around(player, ground, this.ability("starfall-bolt-radius", 1.3))) {
                     AbilityDamage.hit(player, target, this.ability("starfall-bolt-damage", 3.0), 0.0, ground, "{victim} was struck by {killer}'s Starfall");
                  }
               }
            }
         }, i);
      }
   }
}
