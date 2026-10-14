package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.abilities.ChargedWeapon;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Titan's Axe -- wins the long fight by wrecking armour.
 *
 * Anti-spam: Slam charge only comes from full-swing hits on players (not mobs, so it cannot be
 * farmed on a zombie), the same victim can only feed it every half second, it drains out of
 * combat, a slam has a visible and audible wind-up, and it goes on a 30s cooldown.
 */
public class TitansAxeListener extends AbstractWeaponListener implements ChargedWeapon {
   private static final String CHARGE_KEY = "titans-slam";
   private static final String CRIT_COMBO_KEY = "titans-crits";
   private static final String SLAM_COOLDOWN_KEY = "titans-slam-cd";
   private final Map<UUID, Long> lastChargeFromVictim = new HashMap<>();
   private final Set<UUID> windingUp = new HashSet<>();

   public TitansAxeListener(LegendaryWeapons plugin) {
      super(plugin);
      (new BukkitRunnable() {
         public void run() {
            long delay = TitansAxeListener.this.abilityLong("charge-drain-delay", 200L) * 50L;
            double perSecond = TitansAxeListener.this.ability("charge-drain-per-second", 2.0);

            for (Player player : TitansAxeListener.this.plugin.getServer().getOnlinePlayers()) {
               TitansAxeListener.this.plugin.getChargeMeter().drain(player, CHARGE_KEY, delay, perSecond, TitansAxeListener.this.max());
            }
         }
      }).runTaskTimer(plugin, 20L, 20L);
   }

   @Override
   public String weaponKey() {
      return "titans-axe";
   }

   private double max() {
      return Math.max(1.0, this.ability("slam-charge-max", 100.0));
   }

   @Override
   public void setChargeFraction(Player player, double fraction) {
      this.plugin.getChargeMeter().set(player, CHARGE_KEY, fraction * this.max(), this.max());
      this.plugin.getChargeMeter().markCombat(player);
   }

   @Override
   public double getChargeFraction(Player player) {
      return this.plugin.getChargeMeter().get(player, CHARGE_KEY) / this.max();
   }

   @Override
   public Component hud(Player player) {
      double cooldown = this.cooldownLeft(player, SLAM_COOLDOWN_KEY);
      Component meter = HudManager.meter("Slam", this.getChargeFraction(player), NamedTextColor.GOLD);
      return cooldown > 0.0 ? meter.append(Component.text("  cooldown " + HudManager.seconds(cooldown), NamedTextColor.RED)) : meter;
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.lastChargeFromVictim.remove(event.getPlayer().getUniqueId());
      this.windingUp.remove(event.getPlayer().getUniqueId());
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (com.legendaryweapons.abilities.AbilityDamage.active() && event.getDamager() instanceof org.bukkit.entity.Player) {
         return;                                      // an ability's own damage, not a swing
      }

      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker)) {
         if (event.getEntity() instanceof LivingEntity victim && this.isFullSwing(attacker)) {
            boolean mobsCount = this.abilities() != null && this.abilities().getBoolean("charge-from-mobs", false);
            if (victim instanceof Player || mobsCount) {
               long now = System.currentTimeMillis();
               Long last = this.lastChargeFromVictim.get(victim.getUniqueId());
               if (last == null || now - last >= this.abilityLong("charge-victim-cooldown", 10L) * 50L) {
                  this.lastChargeFromVictim.put(victim.getUniqueId(), now);
                  double before = this.plugin.getChargeMeter().get(attacker, CHARGE_KEY);
                  double charge = this.plugin.getChargeMeter().add(attacker, CHARGE_KEY, this.ability("slam-charge-per-hit", 10.0), this.max());
                  if (charge >= this.max() && before < this.max()) {
                     this.plugin.getSoundManager().self(attacker, Sound.BLOCK_ANVIL_LAND, 0.5F, 1.8F);
                     HudManager.message(attacker, Component.text("Slam ready", NamedTextColor.GOLD));
                  }
               }
            }

            if (victim instanceof Player && this.canAffect(attacker, victim)) {
               int needed = this.abilityInt("crit-streak-required", 3);
               int streak = this.plugin.getComboTracker().hit(attacker, CRIT_COMBO_KEY, this.abilityLong("crit-streak-window-millis", 4000L));
               if (streak >= needed) {
                  this.plugin.getComboTracker().reset(attacker, CRIT_COMBO_KEY);
                  this.damageArmour(victim, this.abilityInt("crit-streak-armour-damage", 15), attacker);
               }
            }

            if (victim instanceof Player blocking && blocking.isBlocking() && this.canAffect(attacker, blocking)) {
               final int disable = this.abilityInt("shield-break-duration", 160);
               // One tick later so vanilla's own 5s axe disable cannot overwrite the longer one.
               this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
                  if (blocking.isOnline()) {
                     blocking.setCooldown(Material.SHIELD, disable);
                  }
               }, 1L);
               this.plugin.getSoundManager().world(blocking.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.7F, 0.8F);
               com.legendaryweapons.managers.SpriteFx.burst(blocking, 0.55, org.bukkit.Color.fromRGB(205, 220, 240), 1.5, 1, 1, 4);
            }
         }
      }
   }

   private void damageArmour(LivingEntity target, int amount, Player source) {
      EntityEquipment equipment = target.getEquipment();
      if (equipment != null && amount > 0) {
         boolean any = false;
         ItemStack[] armour = equipment.getArmorContents();

         for (int i = 0; i < armour.length; i++) {
            ItemStack piece = armour[i];
            if (piece != null && !piece.getType().isAir()) {
               ItemMeta meta = piece.getItemMeta();
               if (meta instanceof Damageable damageable && !meta.isUnbreakable() && piece.getType().getMaxDurability() > 0) {
                  int max = piece.getType().getMaxDurability();
                  int updated = damageable.getDamage() + amount;
                  if (updated >= max) {
                     armour[i] = null;
                     this.plugin.getSoundManager().world(target.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8F, 1.0F);
                  } else {
                     damageable.setDamage(updated);
                     piece.setItemMeta(meta);
                  }

                  any = true;
               }
            }
         }

         if (any) {
            equipment.setArmorContents(armour);
            Location loc = target.getLocation().add(0.0, 1.0, 0.0);
            ParticleManager.spawn(Particle.CRIT, loc, 12, 0.4, 0.5, 0.4, 0.1);
            com.legendaryweapons.managers.SpriteFx.burst(target, 0.55, org.bukkit.Color.fromRGB(255, 190, 80), 1.4, 1, 1, 4);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 0.5F, 1.4F);
            HudManager.message(source, Component.text("Armour cracked (-" + amount + " durability)", NamedTextColor.GOLD));
         }
      }
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (this.windingUp.contains(player.getUniqueId())) {
         return true;
      } else if (this.plugin.getCooldownManager().isOnCooldown(player, SLAM_COOLDOWN_KEY)) {
         this.plugin.getCooldownManager().notifyOnCooldown(player, SLAM_COOLDOWN_KEY);
         return true;
      } else {
         double required = this.ability("slam-charge-required", 100.0);
         double charge = this.plugin.getChargeMeter().get(player, CHARGE_KEY);
         if (charge < required) {
            HudManager.message(
               player,
               Component.text("Slam ", NamedTextColor.GRAY)
                  .append(Component.text((int)Math.round(charge / this.max() * 100.0) + "%", NamedTextColor.GOLD))
                  .append(Component.text(" - not ready", NamedTextColor.GRAY))
            );
         } else {
            double spent = this.plugin.getChargeMeter().consume(player, CHARGE_KEY);
            this.plugin.getCooldownManager().setWithItemFeedback(player, SLAM_COOLDOWN_KEY, this.abilityLong("slam-cooldown", 600L), this.weaponMaterial());
            this.windUp(player, spent);
         }

         return true;
      }
   }

   /** The tell: the axe is raised with a grinding charge-up and the ground ring closes in. */
   private void windUp(final Player player, final double spent) {
      final int ticks = Math.max(0, this.abilityInt("slam-windup", 12));
      final double radius = this.ability("slam-radius", 6.0);
      final UUID id = player.getUniqueId();
      this.windingUp.add(id);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1.0F, 0.6F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_ANVIL_PLACE, 0.6F, 0.5F);
      // the telegraph: a molten rune circle at the slam's full reach that closes in on the player
      final com.legendaryweapons.managers.SpriteFx.Held ring = com.legendaryweapons.managers.SpriteFx.hold(player.getLocation().add(0.0, 0.06, 0.0), "vfx/ritual_circle_outer", org.bukkit.Color.fromRGB(255, 120, 30), false, radius * 2.0 / 0.965, ticks + 40);
      (new BukkitRunnable() {
         int elapsed = 0;

         public void run() {
            if (ring != null) {
               ring.follow(player.getLocation().add(0.0, 0.06, 0.0));
               if (this.elapsed == 2) {
                  ring.animate(1.2, 1.4F, Math.max(1, ticks - 2));
               }
            }

            if (!player.isOnline() || player.isDead() || !TitansAxeListener.this.isHolding(player)) {
               TitansAxeListener.this.windingUp.remove(id);
               this.cancel();
               if (ring != null) {
                  ring.release(3);
               }

               // An interrupted slam never landed: give the charge and the cooldown back.
               TitansAxeListener.this.plugin.getChargeMeter().set(player, CHARGE_KEY, spent, TitansAxeListener.this.max());
               TitansAxeListener.this.plugin.getCooldownManager().clear(player, SLAM_COOLDOWN_KEY, TitansAxeListener.this.weaponMaterial());
               if (player.isOnline()) {
                  HudManager.message(player, Component.text("Slam interrupted - charge kept", NamedTextColor.GRAY));
               }
            } else if (this.elapsed++ >= ticks) {
               TitansAxeListener.this.windingUp.remove(id);
               this.cancel();
               if (ring != null) {
                  ring.remove();                                 // the slam's own shockwave takes over
               }

               TitansAxeListener.this.slam(player);
            } else if (this.elapsed % 3 == 0) {
               double r = radius * (1.0 - (double)this.elapsed / (ticks + 1));
               Location centre = player.getLocation().add(0.0, 0.1, 0.0);

               for (int i = 0; i < 16; i++) {
                  double angle = Math.PI * 2.0 * i / 16.0;
                  ParticleManager.spawn(Particle.BLOCK, centre.clone().add(Math.cos(angle) * r, 0.0, Math.sin(angle) * r), 1, 0.0, 0.0, 0.0, 0.0, Material.DEEPSLATE.createBlockData());
               }
            }
         }
      }).runTaskTimer(this.plugin, 1L, 1L);
   }

   private void slam(Player player) {
      double radius = this.ability("slam-radius", 6.0);
      double knockback = this.ability("slam-knockback", 1.6);
      double damage = this.ability("slam-damage", 6.0);
      int armourDamage = this.abilityInt("slam-armour-damage", 40);
      Location centre = player.getLocation();
      ParticleManager.spawn(Particle.EXPLOSION, centre, 2, 0.5, 0.1, 0.5, 0.0);
      ParticleManager.spawn(Particle.BLOCK, centre, 40, radius / 2.0, 0.2, radius / 2.0, 0.2, Material.DEEPSLATE.createBlockData());
      ParticleManager.spawn(Particle.LAVA, centre, 6, radius / 3.0, 0.1, radius / 3.0, 0.0);
      this.plugin.getSoundManager().world(centre, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0F, 0.6F);
      com.legendaryweapons.managers.AnimationFx.shockwave(centre, Material.DEEPSLATE, radius, 12);
      if (!com.legendaryweapons.managers.SpriteFx.wave(centre, com.legendaryweapons.managers.SpriteFx.Sprite.SHOCKWAVE_MAGMA, radius, 7)) {
         com.legendaryweapons.managers.AnimationFx.ring(centre, Material.MAGMA_BLOCK, 0.8, radius, 10, true);
      }

      for (Entity nearby : centre.getWorld().getNearbyEntities(centre, radius, radius / 2.0, radius)) {
         if (nearby instanceof LivingEntity living && this.canAffect(player, nearby)) {
            double distance = nearby.getLocation().distance(centre);
            if (distance > radius) {
               continue;                                // the search box has corners; the blast is round
            }

            // full force at the centre, never less than slam-edge-damage of it inside the ring
            double edge = Math.max(0.0, Math.min(1.0, this.ability("slam-edge-damage", 0.4)));
            double falloff = 1.0 - (1.0 - edge) * (distance / radius);
            if (damage > 0.0) {
               com.legendaryweapons.abilities.AbilityDamage.hit(player, living, damage * falloff, this.ability("slam-armour-pierce", 0.4), centre, "{victim} was crushed by {killer}'s Slam");
            }

            this.damageArmour(living, (int)Math.round(armourDamage * falloff), player);
            Vector offset = nearby.getLocation().toVector().subtract(centre.toVector());
            Vector direction = offset.lengthSquared() < 1.0E-4 ? new Vector(0.0, 1.0, 0.0) : offset.normalize();
            nearby.setVelocity(direction.multiply(knockback * falloff).setY(Math.max(0.45, knockback * falloff * 0.6)));
         }
      }
   }
}
