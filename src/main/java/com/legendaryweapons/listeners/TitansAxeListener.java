package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.abilities.ChargedWeapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

public class TitansAxeListener extends AbstractWeaponListener implements ChargedWeapon {
   private static final String WEAPON_KEY = "titans-axe";
   private static final String CHARGE_KEY = "titans-slam";
   private static final String CRIT_COMBO_KEY = "titans-crits";
   private static final String SLAM_COOLDOWN_KEY = "titans-slam-cd";

   public TitansAxeListener(LegendaryWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return "titans-axe";
   }

   @Override
   public void setChargeFraction(Player player, double fraction) {
      double max = this.ability("slam-charge-max", 100.0);
      this.plugin.getChargeMeter().set(player, "titans-slam", fraction * max, max);
   }

   @Override
   public double getChargeFraction(Player player) {
      double max = this.ability("slam-charge-max", 100.0);
      return max <= 0.0 ? 0.0 : this.plugin.getChargeMeter().get(player, "titans-slam") / max;
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker)) {
         if (event.getEntity() instanceof LivingEntity victim) {
            if (this.isFullSwing(attacker)) {
               double max = this.ability("slam-charge-max", 100.0);
               double gain = this.ability("slam-charge-per-hit", 12.0);
               double charge = this.plugin.getChargeMeter().add(attacker, "titans-slam", gain, max);
               this.plugin.getChargeMeter().showActionBar(attacker, "Slam", "titans-slam", max, NamedTextColor.GOLD);
               if (charge >= max) {
                  this.plugin.getSoundManager().self(attacker, Sound.BLOCK_ANVIL_LAND, 0.5F, 1.8F);
               }

               int needed = this.abilityInt("crit-streak-required", 3);
               int streak = this.plugin.getComboTracker().hit(attacker, "titans-crits", this.abilityLong("crit-streak-window-millis", 4000L));
               if (streak >= needed) {
                  this.plugin.getComboTracker().reset(attacker, "titans-crits");
                  this.damageArmour(victim, this.abilityInt("crit-streak-armour-damage", 25), attacker);
               }

               if (victim instanceof Player blocking && blocking.isBlocking()) {
                  blocking.setCooldown(Material.SHIELD, this.abilityInt("shield-break-duration", 200));
                  this.plugin.getSoundManager().world(blocking.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.7F, 0.8F);
               }
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
                     target.getWorld().playSound(target.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8F, 1.0F);
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
            loc.getWorld().spawnParticle(Particle.CRIT, loc, 20, 0.4, 0.5, 0.4, 0.1);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_ITEM_BREAK, 0.5F, 0.7F);
            source.sendActionBar(Component.text("Armour cracked", NamedTextColor.GOLD));
         }
      }
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (this.plugin.getCooldownManager().isOnCooldown(player, "titans-slam-cd")) {
         this.plugin.getCooldownManager().notifyOnCooldown(player, "titans-slam-cd");
         return true;
      } else {
         double max = this.ability("slam-charge-max", 100.0);
         double required = this.ability("slam-charge-required", 100.0);
         double charge = this.plugin.getChargeMeter().get(player, "titans-slam");
         if (charge < required) {
            player.sendActionBar(
               ((TextComponent)Component.text("Slam ", NamedTextColor.GRAY)
                     .append(Component.text((int)Math.round(charge / max * 100.0) + "%", NamedTextColor.GOLD)))
                  .append(Component.text(" - not ready", NamedTextColor.GRAY))
            );
            return true;
         } else {
            this.plugin.getChargeMeter().consume(player, "titans-slam");
            this.plugin.getCooldownManager().setWithItemFeedback(player, "titans-slam-cd", this.abilityLong("slam-cooldown", 100L), this.weaponMaterial());
            this.slam(player);
            return true;
         }
      }
   }

   private void slam(Player player) {
      double radius = this.ability("slam-radius", 6.0);
      double knockback = this.ability("slam-knockback", 1.6);
      double damage = this.ability("slam-damage", 6.0);
      int armourDamage = this.abilityInt("slam-armour-damage", 60);
      Location centre = player.getLocation();
      centre.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, centre, 1, 0.0, 0.0, 0.0, 0.0);
      centre.getWorld().spawnParticle(Particle.BLOCK, centre, 80, radius / 2.0, 0.2, radius / 2.0, 0.2, Material.DEEPSLATE.createBlockData());
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_GENERIC_EXPLODE, 0.9F, 0.5F);

      for (Entity nearby : centre.getWorld().getNearbyEntities(centre, radius, radius / 2.0, radius)) {
         if (nearby instanceof LivingEntity living
            && !nearby.equals(player)
            && !(
               nearby instanceof Player target
                  && this.plugin.getTrustManager().isTrusted(player, target)
                  && this.plugin.getConfigManager().isPreventAbilityDamage()
            )) {
            double distance = nearby.getLocation().distance(centre);
            double falloff = Math.max(0.0, 1.0 - distance / radius);
            if (damage > 0.0) {
               living.damage(damage * falloff, player);
            }

            this.damageArmour(living, (int)Math.round(armourDamage * falloff), player);
            Vector offset = nearby.getLocation().toVector().subtract(centre.toVector());
            Vector direction = offset.lengthSquared() < 1.0E-4 ? new Vector(0.0, 1.0, 0.0) : offset.normalize();
            nearby.setVelocity(direction.multiply(knockback * falloff).setY(Math.max(0.45, knockback * falloff * 0.6)));
         }
      }
   }
}
