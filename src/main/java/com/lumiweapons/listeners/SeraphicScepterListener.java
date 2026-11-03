package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Seraphic Scepter -- support for team fights. Healing is limited on purpose: everything it heals
 * on one player counts toward heal-cap per heal-cap-window, so nobody becomes unkillable.
 * Allies are you, your teammates, and players you /trust.
 *
 *   Passive, Divine Light: healing allies builds Divine energy, which Divine Sanctuary runs on.
 *   Ability 1, Healing Ray: a beam to the ally you look at, restoring a little health.
 *   Ability 2, Blessing: a small circle; allies inside take less damage while it lasts.
 *   Ultimate, Divine Sanctuary: a large field that slowly heals allies inside it.
 */
public class SeraphicScepterListener extends UltimateWeapon {
   public static final String KEY = "seraphic-scepter";
   private static final String RAY_KEY = "seraphic-healing-ray";
   private static final String BLESS_KEY = "seraphic-blessing";
   private static final Color HOLY = Color.fromRGB(0xFFF2B0);
   private static final Color SKY = Color.fromRGB(0x9FD8FF);
   /** target -> recent heals { when (ms), amount }, for the heal cap. */
   private final Map<UUID, ArrayDeque<double[]>> healed = new HashMap<>();

   public SeraphicScepterListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Divine Sanctuary";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.YELLOW;
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "Healing Ray", RAY_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Blessing", BLESS_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   /**
    * Heals an ally by up to `amount`, within the heal cap and their max health. Returns what was
    * healed; healing builds Divine energy for the caster (ultimate-charge-per-heal per health).
    */
   private double heal(Player caster, Player ally, double amount) {
      long now = System.currentTimeMillis();
      long window = this.abilityLong("heal-cap-window", 200L) * 50L;
      ArrayDeque<double[]> recent = this.healed.computeIfAbsent(ally.getUniqueId(), id -> new ArrayDeque<>());
      recent.removeIf(entry -> now - entry[0] > window);
      double used = recent.stream().mapToDouble(entry -> entry[1]).sum();
      double room = Math.max(0.0, this.ability("heal-cap", 10.0) - used);
      var max = ally.getAttribute(Attribute.MAX_HEALTH);
      double missing = (max == null ? 20.0 : max.getValue()) - ally.getHealth();
      double healing = Math.min(amount, Math.min(room, missing));
      if (healing <= 0.0) {
         return 0.0;
      }

      ally.setHealth(ally.getHealth() + healing);
      recent.addLast(new double[]{now, healing});
      ParticleManager.spawn(Particle.HEART, ally.getEyeLocation().add(0, 0.4, 0), 2, 0.3, 0.2, 0.3, 0.0);
      if (!ally.equals(caster)) {
         this.chargeUltimate(caster, healing * this.ability("ultimate-charge-per-heal", 4.0));
      }

      return healing;
   }

   // ------------------------------------------------------------------ Healing Ray

   @Override
   protected void firstAbility(Player player) {
      double range = this.ability("ray-range", 20.0);
      Location eye = player.getEyeLocation();
      RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.6,
         entity -> entity instanceof Player other && !other.equals(player) && this.isAlly(player, other));
      if (hit == null || !(hit.getHitEntity() instanceof Player ally)) {
         HudManager.message(player, Component.text("Look at an ally to heal them.", NamedTextColor.GRAY));
         return;
      }

      if (!this.tryCooldown(player, RAY_KEY, this.abilityLong("ray-cooldown", 200L))) {
         return;
      }

      Location end = ally.getEyeLocation().subtract(0, 0.3, 0);
      Vector line = end.toVector().subtract(eye.toVector());
      double length = line.length();
      Particle.DustOptions light = new Particle.DustOptions(HOLY, 1.0F);
      for (double d = 0.5; d < length; d += 0.3) {
         ParticleManager.spawn(Particle.DUST, eye.clone().add(line.clone().multiply(d / length)), 1, 0.02, 0.02, 0.02, 0.0, light);
      }

      double healed = this.heal(player, ally, this.ability("ray-heal", 4.0));
      this.plugin.getSoundManager().world(ally.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0F, 1.6F);
      HudManager.message(player, Component.text(healed > 0.0 ? "Healed " + this.plugin.getEventManager().publicName(ally) + " for " + Math.round(healed / 2.0 * 10.0) / 10.0 + "❤"
         : this.plugin.getEventManager().publicName(ally) + " can't be healed more right now", healed > 0.0 ? NamedTextColor.YELLOW : NamedTextColor.GRAY));
   }

   // ------------------------------------------------------------------ Blessing

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, BLESS_KEY, this.abilityLong("blessing-cooldown", 400L))) {
         return;
      }

      Location centre = player.getLocation();
      double radius = this.ability("blessing-radius", 4.0);
      int duration = Math.max(20, this.abilityInt("blessing-duration", 100));
      SpriteFx.Held ring = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_inner", SKY, false, radius * 2.0 / 0.95, duration + 10);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_ACTIVATE, 0.8F, 1.6F);
      int[] tick = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         tick[0] += 10;
         if (tick[0] > duration) {
            if (ring != null) {
               ring.release(6);
            }

            task.cancel();
            return;
         }

         ParticleManager.spawn(Particle.END_ROD, centre.clone().add(0, 0.4, 0), 6, radius / 2.0, 0.3, radius / 2.0, 0.01);
         if (player.isOnline()) {
            for (Player ally : this.allies(player, centre, radius, true)) {
               ally.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 15, Math.max(0, this.abilityInt("blessing-resistance", 1) - 1), true, false, true));
            }
         }
      }, 0L, 10L);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Divine Sanctuary

   @Override
   protected void ultimate(Player player) {
      Location centre = player.getLocation();
      double radius = this.ability("sanctuary-radius", 7.0);
      int duration = Math.max(40, this.abilityInt("sanctuary-duration", 160));
      double perSecond = this.ability("sanctuary-heal-per-second", 1.0);
      SpriteFx.Held outer = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_outer", HOLY, false, radius * 2.0 / 0.965, duration + 10);
      ParticleManager.showcase(Particle.FLASH, centre.clone().add(0, 1, 0), 1, 0.0, 0.0, 0.0, 0.0, HOLY);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_POWER_SELECT, 1.2F, 1.4F);
      this.plugin.getSoundManager().world(centre, Sound.ITEM_TOTEM_USE, 0.6F, 1.8F);
      HudManager.message(player, Component.text("Divine Sanctuary", NamedTextColor.YELLOW));
      int[] tick = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         tick[0] += 10;
         if (tick[0] > duration) {
            if (outer != null) {
               outer.release(8);
            }

            task.cancel();
            return;
         }

         // pillars of light at the edge, motes rising inside
         for (int i = 0; i < 6; i++) {
            double a = Math.PI * 2.0 * i / 6.0 + tick[0] * 0.02;
            ParticleManager.showcase(Particle.END_ROD, centre.clone().add(Math.cos(a) * radius, 0.5 + (tick[0] % 40) / 20.0, Math.sin(a) * radius), 1, 0.0, 0.2, 0.0, 0.0, null);
         }

         ParticleManager.spawn(Particle.DUST, centre.clone().add(0, 0.6, 0), 8, radius / 2.0, 0.4, radius / 2.0, 0.0, new Particle.DustOptions(HOLY, 1.0F));
         if (player.isOnline() && tick[0] % 20 == 0) {
            for (Player ally : this.allies(player, centre, radius, true)) {
               this.heal(player, ally, perSecond);
            }
         }
      }, 0L, 10L);
   }
}
