package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Twin Requiem -- the double scythe.
 *
 *   Passive, Endless Rotation: each hit in a row builds a Momentum stack. Waiting too long
 *     (momentum-window) or swinging at nothing resets them. At full stacks the next special attack
 *     (Cross Reap or Final Harvest) reaches further, and spends them.
 *   Ability, Cross Reap: one blade sweeps forward, then the other sweeps back round you. The
 *     backswing hits harder when the first sweep connected.
 *   Ultimate, Final Harvest: the scythe rises (a long, loud wind-up with the area drawn on the
 *     ground, so anyone can get out or raise a shield), then one huge circular swing that slows.
 */
public class TwinRequiemListener extends UltimateWeapon {
   public static final String KEY = "twin-requiem";
   private static final String COOLDOWN_KEY = "twin-requiem-cross-reap";
   private static final Color STEEL = Color.fromRGB(0xC8D8F0);
   private static final Color FROST = Color.fromRGB(0x6FA8FF);
   private final Map<UUID, Integer> stacks = new HashMap<>();
   private final Map<UUID, Long> lastHit = new HashMap<>();
   /** player -> tick of their last landed hit, to tell a miss (a swing with no hit) from a hit. */
   private final Map<UUID, Integer> hitTick = new HashMap<>();
   private final Map<UUID, BukkitRunnable> harvesting = new HashMap<>();

   public TwinRequiemListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Final Harvest";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.AQUA;
   }

   private int maxStacks() {
      return Math.max(1, this.abilityInt("momentum-max-stacks", 5));
   }

   private int stacksOf(Player player) {
      Long last = this.lastHit.get(player.getUniqueId());
      if (last == null || System.currentTimeMillis() - last > this.abilityLong("momentum-window", 60L) * 50L) {
         this.stacks.remove(player.getUniqueId());
         return 0;
      }

      return this.stacks.getOrDefault(player.getUniqueId(), 0);
   }

   /** Full Momentum: the special attack reaches momentum-range-bonus further, and the stacks are spent. */
   private double reach(Player player, double base) {
      if (this.stacksOf(player) >= this.maxStacks()) {
         this.stacks.remove(player.getUniqueId());
         ParticleManager.showcase(Particle.SOUL_FIRE_FLAME, player.getLocation().add(0.0, 1.0, 0.0), 14, 0.5, 0.5, 0.5, 0.05, null);
         return base * this.ability("momentum-range-bonus", 1.5);
      }

      return base;
   }

   @Override
   public Component hud(Player player) {
      int stacks = this.stacksOf(player);
      return HudManager.pips("Momentum", stacks, this.maxStacks(), stacks >= this.maxStacks() ? NamedTextColor.AQUA : NamedTextColor.WHITE)
         .append(Component.text("  "))
         .append(HudManager.cooldown(player, "Cross Reap", COOLDOWN_KEY))
         .append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   @Override
   protected void firstAbility(Player player) {
      if (this.tryCooldown(player, COOLDOWN_KEY, this.abilityLong("cross-reap-cooldown", 400L))) {
         this.crossReap(player);
         this.chargeFromAbility(player);
      }
   }

   @Override
   protected String ultimateBlocked(Player player) {
      return this.harvesting.containsKey(player.getUniqueId()) ? "Already harvesting." : null;
   }

   @Override
   protected void ultimate(Player player) {
      this.finalHarvest(player);
   }

   // ------------------------------------------------------------------ Endless Rotation

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHit(EntityDamageByEntityEvent event) {
      if (AbilityDamage.active() || !(event.getDamager() instanceof Player attacker) || !this.isHolding(attacker)
         || !(event.getEntity() instanceof LivingEntity)) {
         return;
      }

      this.hitTick.put(attacker.getUniqueId(), attacker.getServer().getCurrentTick());
      int now = Math.min(this.maxStacks(), this.stacksOf(attacker) + 1);
      this.stacks.put(attacker.getUniqueId(), now);
      this.lastHit.put(attacker.getUniqueId(), System.currentTimeMillis());
      if (now == this.maxStacks()) {
         this.plugin.getSoundManager().self(attacker, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7F, 1.4F);
      }
   }

   /** A swing that lands on nothing ends the rotation. The hit (if any) arrives first, in the same tick. */
   @EventHandler(ignoreCancelled = true)
   public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent event) {
      Player player = event.getPlayer();
      if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !this.isHolding(player) || this.stacksOf(player) == 0) {
         return;
      }

      int swung = player.getServer().getCurrentTick();
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         Integer hit = this.hitTick.get(player.getUniqueId());
         if (hit == null || swung - hit > 1) {
            this.stacks.remove(player.getUniqueId());
         }
      });
   }

   // ------------------------------------------------------------------ Cross Reap

   private void crossReap(Player player) {
      double radius = this.reach(player, this.ability("cross-reap-radius", 3.5));
      double first = this.ability("cross-reap-damage", 5.0);
      double second = this.ability("cross-reap-backswing-damage", 6.0);
      SpriteFx.slash(player, STEEL, 2.8 * radius / 3.5, 0.2);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 0.7F);
      boolean connected = false;
      for (LivingEntity target : this.arc(player, radius, 140.0, 0.0)) {
         AbilityDamage.hit(player, target, first, 0.0, player.getLocation(), "{victim} was reaped by {killer}'s Twin Requiem");
         connected = true;
      }

      final boolean landed = connected;
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (!player.isOnline() || player.isDead()) {
            return;
         }

         // the second blade comes back round: behind you as well as in front
         SpriteFx.slash(player, FROST, 3.0 * radius / 3.5, -0.6);
         SpriteFx.wave(player.getLocation(), FROST, radius, 6);
         this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8F, 1.4F);
         double damage = second * (landed ? this.ability("cross-reap-connect-bonus", 1.25) : 1.0);
         for (LivingEntity target : this.arc(player, radius, 360.0, 0.0)) {
            AbilityDamage.hit(player, target, damage, 0.0, player.getLocation(), "{victim} was reaped by {killer}'s Twin Requiem");
         }
      }, 6L);
   }

   // ------------------------------------------------------------------ Final Harvest

   private void finalHarvest(Player player) {
      final double radius = this.reach(player, this.ability("final-harvest-radius", 6.0));
      final int windup = Math.max(10, this.abilityInt("final-harvest-windup", 30));
      final Location centre = player.getLocation();
      this.ulting.add(player.getUniqueId());
      player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, windup, 2, true, false, false));
      SpriteFx.Held ring = SpriteFx.hold(centre.clone().add(0.0, 0.05, 0.0), "vfx/ritual_circle_outer", FROST, false, 0.4, windup + 20);
      if (ring != null) {
         ring.animate(radius * 2.0 / 0.965, windup);
      }

      this.plugin.getSoundManager().world(centre, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.0F, 1.2F);
      HudManager.message(player, Component.text("Final Harvest...", NamedTextColor.AQUA));
      BukkitRunnable task = new BukkitRunnable() {
         int tick = 0;

         @Override
         public void run() {
            if (!player.isOnline() || player.isDead()) {
               TwinRequiemListener.this.endHarvest(player, ring);
               return;
            }

            if (this.tick % 4 == 0) {
               // the edge of the swing, drawn on the ground while it winds up
               for (int i = 0; i < 24; i++) {
                  double a = Math.PI * 2.0 * i / 24.0 + this.tick * 0.05;
                  ParticleManager.showcase(Particle.SOUL_FIRE_FLAME, centre.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0.0, 0.02, 0.0, 0.0, null);
               }

               ParticleManager.spawn(Particle.SWEEP_ATTACK, player.getEyeLocation().add(0.0, 0.6, 0.0), 1, 0.0, 0.0, 0.0, 0.0);
            }

            if (++this.tick >= windup) {
               TwinRequiemListener.this.harvest(player, centre, radius);
               TwinRequiemListener.this.endHarvest(player, ring);
            }
         }
      };
      task.runTaskTimer(this.plugin, 1L, 1L);
      this.harvesting.put(player.getUniqueId(), task);
   }

   private void harvest(Player player, Location centre, double radius) {
      SpriteFx.wave(centre, STEEL, radius, 8);
      SpriteFx.slash(player, FROST, radius * 0.9, 0.0);
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4F, 0.5F);
      this.plugin.getSoundManager().world(centre, Sound.ITEM_TRIDENT_THUNDER, 0.6F, 1.6F);
      List<LivingEntity> targets = this.around(player, centre, radius);
      int slow = this.abilityInt("final-harvest-slow-duration", 50);
      for (LivingEntity target : targets) {
         AbilityDamage.hit(player, target, this.ability("final-harvest-damage", 12.0), this.ability("final-harvest-armour-pierce", 0.2), centre,
            "{victim} was harvested by {killer}'s Twin Requiem");
         target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slow, 1, true, false, true));
      }
   }

   private void endHarvest(Player player, SpriteFx.Held ring) {
      BukkitRunnable task = this.harvesting.remove(player.getUniqueId());
      if (task != null) {
         task.cancel();
      }

      if (ring != null) {
         ring.release(4);
      }

      this.ulting.remove(player.getUniqueId());
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.forget(event.getEntity());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.forget(event.getPlayer());
   }

   private void forget(Player player) {
      UUID id = player.getUniqueId();
      BukkitRunnable task = this.harvesting.remove(id);
      if (task != null) {
         task.cancel();
      }

      this.ulting.remove(id);
      this.stacks.remove(id);
      this.lastHit.remove(id);
      this.hitTick.remove(id);
   }
}
