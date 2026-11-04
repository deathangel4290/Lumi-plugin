package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
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
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Chronoscourge -- control and outplay. It bends time around the fight, never your health:
 * Rewind moves you, it doesn't heal you or undo damage.
 *
 *   Passive, Time Fracture: hitting enemies builds Temporal energy (the ultimate meter).
 *   Ability 1, Time Snare: a temporal field where you look; enemies inside are slowed.
 *   Ability 2, Rewind: back to where you stood a few seconds ago, if that's close enough.
 *   Ultimate, Time Collapse: a large field around you that slows enemies and the projectiles
 *     flying through it.
 */
public class ChronoscourgeListener extends UltimateWeapon {
   public static final String KEY = "chronoscourge";
   private static final String SNARE_KEY = "chrono-time-snare";
   private static final String REWIND_KEY = "chrono-rewind";
   private static final Color TIME = Color.fromRGB(0xE8C66A);
   private static final Color VOID = Color.fromRGB(0x5A6BD8);
   private static final int SAMPLE_TICKS = 5;
   /** player -> where they stood, newest last, one sample every SAMPLE_TICKS. */
   private final Map<UUID, Deque<Location>> history = new HashMap<>();

   public ChronoscourgeListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::record, SAMPLE_TICKS, SAMPLE_TICKS);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Time Collapse";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.YELLOW;
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "Time Snare", SNARE_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Rewind", REWIND_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.history.remove(event.getPlayer().getUniqueId());
   }

   // ------------------------------------------------------------------ the time trail

   private int samples() {
      return Math.max(1, (int)(this.abilityLong("rewind-ticks-back", 60L) / SAMPLE_TICKS));
   }

   /** Remembers where wielders stand. Only while held, so Rewind can't fetch a spot from before. */
   private void record() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (!this.isHolding(player) || player.isDead()) {
            this.history.remove(player.getUniqueId());
            continue;
         }

         Deque<Location> trail = this.history.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
         if (!trail.isEmpty() && !trail.peekLast().getWorld().equals(player.getWorld())) {
            trail.clear();
         }

         trail.addLast(player.getLocation());
         while (trail.size() > this.samples() + 1) {
            trail.removeFirst();
         }
      }
   }

   // ------------------------------------------------------------------ Time Snare

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, SNARE_KEY, this.abilityLong("snare-cooldown", 300L))) {
         return;
      }

      Location centre = this.aimedGround(player, this.ability("snare-range", 14.0));
      double radius = this.ability("snare-radius", 3.5);
      int duration = Math.max(20, this.abilityInt("snare-duration", 100));
      int level = Math.max(0, this.abilityInt("snare-slowness", 2) - 1);
      SpriteFx.Held ring = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_inner", TIME, false, radius * 2.0 / 0.95, duration + 10);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_AMBIENT, 1.2F, 1.8F);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 0.6F);
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

         ParticleManager.spawn(Particle.DUST, centre.clone().add(0, 0.4, 0), 8, radius / 2.0, 0.3, radius / 2.0, 0.0, new Particle.DustOptions(TIME, 0.9F));
         for (LivingEntity target : this.around(player, centre, radius)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15, level, true, false, true));
         }
      }, 0L, 5L);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Rewind

   @Override
   protected void secondAbility(Player player) {
      Deque<Location> trail = this.history.get(player.getUniqueId());
      double max = this.ability("rewind-max-distance", 20.0);
      Location here = player.getLocation();
      Location back = null;
      if (trail != null) {
         // the oldest remembered spot that is still within reach
         for (Iterator<Location> it = trail.iterator(); it.hasNext(); ) {
            Location spot = it.next();
            if (spot.getWorld().equals(here.getWorld()) && spot.distance(here) <= max && spot.distance(here) > 1.0) {
               back = spot;
               break;
            }
         }
      }

      if (back == null) {
         HudManager.message(player, Component.text("Nowhere to rewind to.", NamedTextColor.GRAY));
         return;
      }

      if (!this.tryCooldown(player, REWIND_KEY, this.abilityLong("rewind-cooldown", 360L))) {
         return;
      }

      // a line of time-dust from here back to there
      Vector line = back.toVector().subtract(here.toVector());
      double length = line.length();
      Particle.DustOptions dust = new Particle.DustOptions(TIME, 1.0F);
      for (double d = 0; d < length; d += 0.5) {
         ParticleManager.spawn(Particle.DUST, here.clone().add(line.clone().multiply(d / length)).add(0, 1, 0), 1, 0.05, 0.05, 0.05, 0.0, dust);
      }

      ParticleManager.spawn(Particle.REVERSE_PORTAL, here.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
      Location to = back.clone();
      to.setYaw(here.getYaw());
      to.setPitch(here.getPitch());
      player.teleport(to);
      player.setVelocity(new Vector());
      player.setFallDistance(0.0F);                        // position only: health, effects and fire stay as they are
      this.history.remove(player.getUniqueId());
      ParticleManager.spawn(Particle.REVERSE_PORTAL, to.clone().add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.05);
      this.plugin.getSoundManager().world(to, Sound.BLOCK_BELL_RESONATE, 0.8F, 1.8F);
      this.plugin.getSoundManager().world(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.6F);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Time Collapse

   @Override
   protected void ultimate(Player player) {
      Location centre = player.getLocation();
      double radius = this.ability("collapse-radius", 8.0);
      int duration = Math.max(40, this.abilityInt("collapse-duration", 120));
      int level = Math.max(0, this.abilityInt("collapse-slowness", 3) - 1);
      double projectileSpeed = this.ability("collapse-projectile-speed", 0.3);
      SpriteFx.Held outer = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_outer", VOID, false, radius * 2.0 / 0.965, duration + 10);
      SpriteFx.Held inner = SpriteFx.hold(centre.clone().add(0, 0.1, 0), "vfx/ritual_circle_inner", TIME, false, radius * 1.2 / 0.95, duration + 10);
      ParticleManager.showcase(Particle.FLASH, centre.clone().add(0, 1, 0), 1, 0.0, 0.0, 0.0, 0.0, TIME);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BELL_USE, 1.4F, 0.5F);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_DEACTIVATE, 1.2F, 0.6F);
      HudManager.message(player, Component.text("Time Collapse", NamedTextColor.YELLOW));
      this.ulting.add(player.getUniqueId());
      int[] tick = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         if (++tick[0] > duration || !player.isOnline()) {
            if (outer != null) {
               outer.release(8);
            }

            if (inner != null) {
               inner.release(8);
            }

            this.ulting.remove(player.getUniqueId());
            task.cancel();
            return;
         }

         // projectiles that aren't ours crawl through the field
         for (Entity entity : centre.getWorld().getNearbyEntities(centre, radius, radius, radius)) {
            if (entity instanceof Projectile projectile && !this.friendly(player, projectile)
               && entity.getLocation().distanceSquared(centre) <= radius * radius) {
               Vector v = projectile.getVelocity();
               if (v.length() > projectileSpeed) {
                  projectile.setVelocity(v.normalize().multiply(projectileSpeed));
                  ParticleManager.spawn(Particle.DUST, projectile.getLocation(), 2, 0.05, 0.05, 0.05, 0.0, new Particle.DustOptions(TIME, 0.8F));
               }
            }
         }

         if (tick[0] % 5 == 0) {
            ParticleManager.spawn(Particle.DUST, centre.clone().add(0, 1.0, 0), 12, radius / 2.0, 1.0, radius / 2.0, 0.0, new Particle.DustOptions(VOID, 1.0F));
            for (LivingEntity target : this.around(player, centre, radius)) {
               target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 15, level, true, false, true));
               target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 15, 0, true, false, true));
            }
         }

         if (tick[0] % 20 == 0) {
            this.plugin.getSoundManager().world(centre, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8F, 0.5F);
         }
      }, 1L, 1L);
   }

   private boolean friendly(Player player, Projectile projectile) {
      return projectile.getShooter() instanceof Player shooter && this.isAlly(player, shooter);
   }
}
