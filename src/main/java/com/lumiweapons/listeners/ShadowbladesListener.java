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
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

/**
 * Shadowblades -- an assassin's pair. Very mobile, light hits: the value is in landing many.
 *
 *   Passive, Blood Mark: hits in a row on the same target build marks on them (they fade if you
 *     stop, or move on to someone else).
 *   Ability 1, Shadowstep: a short dash the way you look.
 *   Ability 2, Crosscut: two quick slashes; when both land on the same enemy, a bonus cut.
 *   Ultimate, Execution: step behind your marked enemy and strike; more marks, more damage (capped).
 */
public class ShadowbladesListener extends UltimateWeapon {
   public static final String KEY = "shadowblades";
   private static final String STEP_KEY = "shadowblades-shadowstep";
   private static final String CROSS_KEY = "shadowblades-crosscut";
   private static final Color BLOOD = Color.fromRGB(0xB0102A);
   private static final Color SHADOW = Color.fromRGB(0x2A1A3A);

   private record Marks(UUID target, int count, long last) {
   }

   private final Map<UUID, Marks> marks = new HashMap<>();

   public ShadowbladesListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::drawMarks, 10L, 10L);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Execution";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.DARK_RED;
   }

   private int maxMarks() {
      return Math.max(1, this.abilityInt("mark-max", 5));
   }

   /** The wielder's live marks, or null once they have faded. */
   private Marks live(Player player) {
      Marks m = this.marks.get(player.getUniqueId());
      if (m != null && System.currentTimeMillis() - m.last() > this.abilityLong("mark-window", 100L) * 50L) {
         this.marks.remove(player.getUniqueId());
         return null;
      }

      return m;
   }

   @Override
   public Component hud(Player player) {
      Marks m = this.live(player);
      return HudManager.pips("Marks", m == null ? 0 : m.count(), this.maxMarks(), NamedTextColor.RED).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Shadowstep", STEP_KEY)).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Crosscut", CROSS_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Blood Mark

   private void mark(Player player, LivingEntity target) {
      Marks before = this.live(player);
      int count = before != null && before.target().equals(target.getUniqueId()) ? Math.min(this.maxMarks(), before.count() + 1) : 1;
      this.marks.put(player.getUniqueId(), new Marks(target.getUniqueId(), count, System.currentTimeMillis()));
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHit(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker) && event.getEntity() instanceof LivingEntity target
         && !AbilityDamage.active() && this.canAffect(attacker, target)) {
         this.mark(attacker, target);
      }
   }

   private void drawMarks() {
      for (Map.Entry<UUID, Marks> entry : new java.util.ArrayList<>(this.marks.entrySet())) {
         Player owner = this.plugin.getServer().getPlayer(entry.getKey());
         Entity target = this.plugin.getServer().getEntity(entry.getValue().target());
         if (owner == null || this.live(owner) == null || !(target instanceof LivingEntity living) || living.isDead()) {
            this.marks.remove(entry.getKey());
            continue;
         }

         Particle.DustOptions blood = new Particle.DustOptions(BLOOD, 0.7F);
         for (int i = 0; i < entry.getValue().count(); i++) {
            double a = Math.PI * 2.0 * i / entry.getValue().count();
            ParticleManager.showcase(Particle.DUST, living.getEyeLocation().add(Math.cos(a) * 0.35, 0.5, Math.sin(a) * 0.35), 1, 0.0, 0.0, 0.0, 0.0, blood);
         }
      }
   }

   // ------------------------------------------------------------------ Shadowstep

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, STEP_KEY, this.abilityLong("shadowstep-cooldown", 100L))) {
         return;
      }

      Location from = player.getLocation();
      Vector direction = player.getEyeLocation().getDirection();
      player.setVelocity(direction.multiply(this.ability("shadowstep-power", 1.6)).setY(Math.max(0.15, direction.getY() * 0.6)));
      this.protectFall(player, 40L);
      ParticleManager.spawn(Particle.LARGE_SMOKE, from.clone().add(0, 1, 0), 14, 0.3, 0.5, 0.3, 0.02);
      ParticleManager.spawn(Particle.DUST, from.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.0, new Particle.DustOptions(SHADOW, 1.2F));
      this.plugin.getSoundManager().world(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 1.6F);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Crosscut

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, CROSS_KEY, this.abilityLong("crosscut-cooldown", 200L))) {
         return;
      }

      double range = this.ability("crosscut-range", 3.5);
      double damage = this.ability("crosscut-damage", 3.0);
      Map<UUID, Integer> landed = new HashMap<>();
      for (int cut = 0; cut < 2; cut++) {
         final int which = cut;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
               return;
            }

            SpriteFx.slash(player, which == 0 ? BLOOD : SHADOW, 2.2, which == 0 ? 0.8 : -0.8);
            this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9F, 1.5F + which * 0.2F);
            for (LivingEntity target : this.arc(player, range, 110.0, 0.0)) {
               AbilityDamage.hit(player, target, damage, 0.0, player.getLocation(), "{victim} was cut down by {killer}'s Shadowblades");
               this.mark(player, target);
               if (landed.merge(target.getUniqueId(), 1, Integer::sum) == 2) {
                  // both cuts landed: the cross opens
                  AbilityDamage.hit(player, target, this.ability("crosscut-bonus-damage", 4.0), 0.2, player.getLocation(), "{victim} was cut down by {killer}'s Shadowblades");
                  ParticleManager.spawn(Particle.DUST, target.getLocation().add(0, 1, 0), 16, 0.3, 0.4, 0.3, 0.0, new Particle.DustOptions(BLOOD, 1.2F));
                  this.plugin.getSoundManager().world(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0F, 0.8F);
               }
            }
         }, cut * 4L);
      }

      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Execution

   private LivingEntity markedTarget(Player player) {
      Marks m = this.live(player);
      Entity target = m == null ? null : this.plugin.getServer().getEntity(m.target());
      return target instanceof LivingEntity living && !living.isDead() && living.getWorld().equals(player.getWorld())
         && living.getLocation().distance(player.getLocation()) <= this.ability("execution-range", 16.0) && player.hasLineOfSight(living) ? living : null;
   }

   @Override
   protected String ultimateBlocked(Player player) {
      LivingEntity target = this.markedTarget(player);
      if (target == null) {
         return "Execution needs a marked enemy in sight (within " + (int)this.ability("execution-range", 16.0) + " blocks).";
      }

      return this.behind(target) == null ? "There's no room behind them." : null;
   }

   /** A safe spot just behind the target (two free blocks over something solid), or null. */
   private Location behind(LivingEntity target) {
      Vector back = horizontal(target.getLocation().getDirection());
      back = back.lengthSquared() < 1.0E-4 ? new Vector(0, 0, -1) : back.normalize().multiply(-1.2);
      for (double scale : new double[]{1.0, 0.6}) {
         Location spot = target.getLocation().add(back.clone().multiply(scale));
         Block feet = spot.getBlock();
         if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable()) {
            spot.setDirection(target.getLocation().toVector().subtract(spot.toVector()));
            return spot;
         }
      }

      return null;
   }

   @Override
   protected void ultimate(Player player) {
      LivingEntity target = this.markedTarget(player);
      Location spot = target == null ? null : this.behind(target);
      if (target == null || spot == null) {
         return;
      }

      int count = Math.min(this.live(player).count(), Math.max(1, this.abilityInt("execution-mark-cap", 5)));
      Location from = player.getLocation();
      ParticleManager.spawn(Particle.LARGE_SMOKE, from.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
      player.teleport(spot);
      double damage = this.ability("execution-damage", 6.0) + count * this.ability("execution-damage-per-mark", 2.5);
      AbilityDamage.hit(player, target, damage, this.ability("execution-armour-pierce", 0.3), spot, "{victim} was executed by {killer}'s Shadowblades");
      this.marks.remove(player.getUniqueId());
      SpriteFx.slash(player, BLOOD, 3.0, 0.0);
      ParticleManager.showcase(Particle.DUST, target.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.0, new Particle.DustOptions(BLOOD, 1.4F));
      this.plugin.getSoundManager().world(spot, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8F, 0.6F);
      this.plugin.getSoundManager().world(spot, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2F, 0.6F);
      HudManager.message(player, Component.text("Execution: " + count + (count == 1 ? " mark" : " marks"), NamedTextColor.RED));
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.marks.remove(event.getPlayer().getUniqueId());
   }
}
