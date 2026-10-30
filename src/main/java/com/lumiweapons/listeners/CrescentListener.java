package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Crescent -- the moon sickle.
 *
 *   Ability, Harvest Mark: a cut at the enemy in front of you that leaves a glowing crescent on
 *     them (mark-duration). Three of your hits on them before it fades detonate it for bonus
 *     damage. One mark at a time: a new one replaces the old.
 *   Ultimate, Moonlit Execution: the sickle glows, then three quick slashes at everyone in front
 *     of you, and a final strike that hits harder for every slash that landed. You stay where you
 *     are: nothing teleports or chases.
 */
public class CrescentListener extends UltimateWeapon {
   public static final String KEY = "crescent";
   private static final String COOLDOWN_KEY = "crescent-harvest-mark";
   private static final Color MOON = Color.fromRGB(0xD8DCF0);
   private static final Color NIGHT = Color.fromRGB(0x3F5A8A);

   /** The one mark a wielder has out. */
   private record Mark(UUID target, long until, int hits) {
   }

   private final Map<UUID, Mark> marks = new HashMap<>();
   private final Set<UUID> executing = new HashSet<>();

   public CrescentListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::markTick, 5L, 5L);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Moonlit Execution";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.BLUE;
   }

   @Override
   public Component hud(Player player) {
      Component out = HudManager.cooldown(player, "Harvest Mark", COOLDOWN_KEY);
      Mark mark = this.marks.get(player.getUniqueId());
      if (mark != null && mark.until() > System.currentTimeMillis()) {
         out = out.append(Component.text("  ☾ " + mark.hits() + "/" + this.hitsToDetonate(), NamedTextColor.AQUA));
      }

      return out.append(Component.text("  ")).append(this.ultimateHud(player));
   }

   private int hitsToDetonate() {
      return Math.max(1, this.abilityInt("mark-hits", 3));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return true;
      }

      if (secondary) {
         if (!this.executing.contains(player.getUniqueId()) && this.spendUltimate(player)) {
            this.execution(player);
         }
      } else {
         this.harvestMark(player);
      }

      return true;
   }

   // ------------------------------------------------------------------ Harvest Mark

   private void harvestMark(Player player) {
      double range = this.ability("mark-range", 4.0);
      LivingEntity target = null;
      double best = Double.MAX_VALUE;
      for (LivingEntity candidate : this.arc(player, range, 70.0, 0.0)) {
         double distance = candidate.getLocation().distanceSquared(player.getLocation());
         if (candidate != player && distance < best && player.hasLineOfSight(candidate)) {
            best = distance;
            target = candidate;
         }
      }

      if (target == null) {
         HudManager.message(player, Component.text("Nobody in reach to mark.", NamedTextColor.GRAY));
         return;
      }

      if (!this.tryCooldown(player, COOLDOWN_KEY, this.abilityLong("mark-cooldown", 320L))) {
         return;
      }

      AbilityDamage.hit(player, target, this.ability("mark-damage", 3.0), 0.0, player.getLocation(), "{victim} was cut down by {killer}'s Crescent");
      long duration = this.abilityLong("mark-duration", 100L);
      this.marks.put(player.getUniqueId(), new Mark(target.getUniqueId(), System.currentTimeMillis() + duration * 50L, 0));
      target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, (int)duration, 0, true, false, false));
      SpriteFx.slash(player, MOON, 2.0, 0.5);
      SpriteFx.burst(target, 1.0, NIGHT, 1.2, 1, 2, 4);
      this.plugin.getSoundManager().world(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F, 1.6F);
      this.plugin.getSoundManager().world(target.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, 1.5F);
      this.chargeFromAbility(player);
   }

   /** The crescent hovering over each marked target; expired marks go. */
   private void markTick() {
      long now = System.currentTimeMillis();
      for (Map.Entry<UUID, Mark> entry : new java.util.ArrayList<>(this.marks.entrySet())) {
         Mark mark = entry.getValue();
         org.bukkit.entity.Entity target = this.plugin.getServer().getEntity(mark.target());
         if (mark.until() <= now || !(target instanceof LivingEntity living) || living.isDead()) {
            this.marks.remove(entry.getKey());
            continue;
         }

         Location above = living.getEyeLocation().add(0.0, 0.55, 0.0);
         Particle.DustOptions dust = new Particle.DustOptions(MOON, 0.8F);
         for (int i = 0; i <= 8; i++) {
            double a = Math.PI * (0.15 + 0.7 * i / 8.0);              // a crescent, open to one side
            ParticleManager.showcase(Particle.DUST, above.clone().add(Math.cos(a) * 0.3, Math.sin(a) * 0.3 - 0.15, 0.0), 1, 0.0, 0.0, 0.0, 0.0, dust);
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHit(EntityDamageByEntityEvent event) {
      if (AbilityDamage.active() || !(event.getDamager() instanceof Player attacker) || !this.isHolding(attacker)) {
         return;
      }

      Mark mark = this.marks.get(attacker.getUniqueId());
      if (mark == null || mark.until() <= System.currentTimeMillis() || !mark.target().equals(event.getEntity().getUniqueId())) {
         return;
      }

      int hits = mark.hits() + 1;
      if (hits < this.hitsToDetonate()) {
         this.marks.put(attacker.getUniqueId(), new Mark(mark.target(), mark.until(), hits));
         this.plugin.getSoundManager().self(attacker, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6F, 1.0F + hits * 0.25F);
         return;
      }

      // three hits: the mark bursts
      this.marks.remove(attacker.getUniqueId());
      LivingEntity target = (LivingEntity)event.getEntity();
      target.removePotionEffect(PotionEffectType.GLOWING);
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         if (!target.isDead() && attacker.isOnline()) {
            AbilityDamage.hit(attacker, target, this.ability("mark-detonation-damage", 5.0), this.ability("mark-detonation-armour-pierce", 0.3),
               target.getLocation(), "{victim} was reaped by {killer}'s Harvest Mark");
            SpriteFx.burst(target, 1.0, MOON, 2.2, 1, 2, 5);
            ParticleManager.showcase(Particle.END_ROD, target.getLocation().add(0.0, 1.0, 0.0), 16, 0.3, 0.5, 0.3, 0.12, null);
            this.plugin.getSoundManager().world(target.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.9F, 1.4F);
         }
      });
   }

   // ------------------------------------------------------------------ Moonlit Execution

   private void execution(Player player) {
      this.executing.add(player.getUniqueId());
      this.ulting.add(player.getUniqueId());
      int glow = Math.max(4, this.abilityInt("execution-windup", 10));
      int gap = Math.max(2, this.abilityInt("execution-slash-interval", 4));
      double range = this.ability("execution-range", 4.0);
      int[] landed = {0};
      ParticleManager.showcase(Particle.END_ROD, player.getLocation().add(0.0, 1.2, 0.0), 20, 0.4, 0.6, 0.4, 0.05, null);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8F, 1.9F);
      for (int slash = 0; slash < 3; slash++) {
         final int which = slash;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (!player.isOnline() || player.isDead()) {
               return;
            }

            SpriteFx.slash(player, which % 2 == 0 ? MOON : NIGHT, 2.4, which % 2 == 0 ? 0.7 : -0.7);
            this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.9F, 1.3F + which * 0.15F);
            boolean hit = false;
            for (LivingEntity target : this.arc(player, range, 120.0, 0.0)) {
               AbilityDamage.hit(player, target, this.ability("execution-slash-damage", 2.5), 0.0, player.getLocation(), "{victim} was executed by {killer}'s Crescent");
               hit = true;
            }

            landed[0] += hit ? 1 : 0;
         }, glow + (long)slash * gap);
      }

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         this.executing.remove(player.getUniqueId());
         this.ulting.remove(player.getUniqueId());
         if (!player.isOnline() || player.isDead()) {
            return;
         }

         double damage = this.ability("execution-final-damage", 4.0) + landed[0] * this.ability("execution-final-per-slash", 3.0);
         SpriteFx.slash(player, MOON, 3.4, 0.0);
         SpriteFx.wave(player.getLocation(), NIGHT, range, 6);
         this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_THUNDER, 0.5F, 1.8F);
         List<LivingEntity> targets = this.arc(player, range, 120.0, 0.0);
         for (LivingEntity target : targets) {
            AbilityDamage.hit(player, target, damage, this.ability("execution-final-armour-pierce", 0.25), player.getLocation(),
               "{victim} was executed by {killer}'s Crescent");
            ParticleManager.showcase(Particle.END_ROD, target.getLocation().add(0.0, 1.0, 0.0), 10, 0.2, 0.4, 0.2, 0.1, null);
         }

         if (landed[0] > 0) {
            HudManager.message(player, Component.text("Moonlit Execution: " + landed[0] + "/3 slashes landed", NamedTextColor.AQUA));
         }
      }, glow + 3L * gap);
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.marks.remove(event.getEntity().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.marks.remove(event.getPlayer().getUniqueId());
      this.executing.remove(event.getPlayer().getUniqueId());
      this.ulting.remove(event.getPlayer().getUniqueId());
   }
}
