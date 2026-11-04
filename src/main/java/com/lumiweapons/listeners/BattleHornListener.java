package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
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
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Battle Horn -- a team support weapon. Its buffs are small on purpose: a nudge for a group,
 * not a win button.
 *
 *   Passive, Rally: you and your allies fighting near you build Rally energy. The horn has to be
 *     in your hand or hotbar.
 *   Ability 1, War Cry: allies around you get a short burst of speed.
 *   Ability 2, Dreadful Roar: a shockwave that pushes nearby enemies back and weakens them.
 *   Ultimate, Call to War: allies around you hit harder for a while, and enemies within a wider
 *     (but limited) radius glow so everyone can see them.
 */
public class BattleHornListener extends UltimateWeapon {
   public static final String KEY = "battle-horn";
   private static final String CRY_KEY = "horn-war-cry";
   private static final String ROAR_KEY = "horn-dreadful-roar";
   private static final Color WAR = Color.fromRGB(0xC8462A);
   private static final Color BRASS = Color.fromRGB(0xE0B050);

   public BattleHornListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Call to War";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.RED;
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "War Cry", CRY_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Roar", ROAR_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Rally

   /** In the main hand, the off-hand or the hotbar. */
   private boolean carries(Player player) {
      if (this.isThisWeapon(player.getInventory().getItemInOffHand())) {
         return true;
      }

      for (int slot = 0; slot < 9; slot++) {
         ItemStack item = player.getInventory().getItem(slot);
         if (item != null && this.isThisWeapon(item)) {
            return true;
         }
      }

      return false;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onFight(EntityDamageByEntityEvent event) {
      Entity damager = event.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity s ? s : event.getDamager();
      if (!(damager instanceof Player fighter) || !(event.getEntity() instanceof LivingEntity victim) || victim.equals(fighter)
         || victim instanceof Player hit && this.isAlly(fighter, hit)) {
         return;
      }

      double radius = this.ability("rally-radius", 12.0);
      double charge = this.ability("rally-charge-per-hit", 3.0) * (victim instanceof Player ? 1.0 : 0.5);
      for (Player holder : fighter.getWorld().getPlayers()) {
         if ((holder.equals(fighter) || this.isAlly(holder, fighter)) && holder.getLocation().distanceSquared(fighter.getLocation()) <= radius * radius
            && this.carries(holder)) {
            this.chargeUltimate(holder, charge);
            this.plugin.getChargeMeter().markCombat(holder);
         }
      }
   }

   private void horn(Player player, Sound sound, float pitch) {
      this.plugin.getSoundManager().world(player.getLocation(), sound, 3.0F, pitch);
   }

   // ------------------------------------------------------------------ War Cry

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, CRY_KEY, this.abilityLong("war-cry-cooldown", 400L))) {
         return;
      }

      int duration = this.abilityInt("war-cry-duration", 120);
      int level = Math.max(0, this.abilityInt("war-cry-speed", 1) - 1);
      this.horn(player, Sound.ITEM_GOAT_HORN_SOUND_0, 1.0F);
      SpriteFx.wave(player.getLocation(), BRASS, this.ability("war-cry-radius", 10.0), 8);
      int count = 0;
      for (Player ally : this.allies(player, player.getLocation(), this.ability("war-cry-radius", 10.0), true)) {
         ally.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, level, true, true, true));
         ParticleManager.spawn(Particle.DUST, ally.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.0, new Particle.DustOptions(BRASS, 1.0F));
         count++;
      }

      HudManager.message(player, Component.text("War Cry! " + count + (count == 1 ? " ally" : " allies") + " sped up", NamedTextColor.GOLD));
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Dreadful Roar

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, ROAR_KEY, this.abilityLong("roar-cooldown", 360L))) {
         return;
      }

      double radius = this.ability("roar-radius", 6.0);
      Location at = player.getLocation();
      this.horn(player, Sound.ITEM_GOAT_HORN_SOUND_7, 0.8F);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_RAVAGER_ROAR, 1.0F, 0.9F);
      SpriteFx.wave(at, WAR, radius, 6);
      ParticleManager.spawn(Particle.SONIC_BOOM, at.clone().add(0, 1, 0), 1, 0, 0, 0, 0);
      for (LivingEntity target : this.around(player, at, radius)) {
         if (this.ability("roar-damage", 0.0) > 0.0) {
            AbilityDamage.hit(player, target, this.ability("roar-damage", 0.0), 0.0, at, "{victim} was shaken apart by {killer}'s Dreadful Roar");
         }

         target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, this.abilityInt("roar-weakness-duration", 100), Math.max(0, this.abilityInt("roar-weakness", 1) - 1), true, true, true));
         push(target, at, this.ability("roar-knockback", 0.9), 0.3);
      }

      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Call to War

   @Override
   protected void ultimate(Player player) {
      Location at = player.getLocation();
      int duration = this.abilityInt("call-duration", 160);
      int level = Math.max(0, this.abilityInt("call-strength", 1) - 1);
      this.horn(player, Sound.ITEM_GOAT_HORN_SOUND_2, 0.9F);
      this.plugin.getSoundManager().world(at, Sound.EVENT_RAID_HORN, 2.0F, 1.0F);
      ParticleManager.showcase(Particle.FLASH, at.clone().add(0, 1, 0), 1, 0.0, 0.0, 0.0, 0.0, WAR);
      SpriteFx.wave(at, WAR, this.ability("call-ally-radius", 12.0), 10);
      for (Player ally : this.allies(player, at, this.ability("call-ally-radius", 12.0), true)) {
         ally.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, duration, level, true, true, true));
         ParticleManager.spawn(Particle.DUST, ally.getLocation().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.0, new Particle.DustOptions(WAR, 1.2F));
      }

      int revealed = 0;
      for (LivingEntity enemy : this.around(player, at, this.ability("call-reveal-radius", 30.0))) {
         enemy.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, duration, 0, true, false, false));
         if (enemy instanceof Player) {
            revealed++;
         }
      }

      HudManager.message(player, Component.text("Call to War! " + revealed + (revealed == 1 ? " enemy" : " enemies") + " revealed", NamedTextColor.RED));
   }
}
