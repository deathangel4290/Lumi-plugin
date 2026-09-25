package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

public class BladeOfDiminutionListener extends AbstractWeaponListener {
   private static final String WEAPON_KEY = "blade-of-diminution";
   private static final String COOLDOWN_KEY = "diminution-diminish";
   private static final double DEFAULT_SCALE = 1.0;
   private final Map<UUID, Double> originalScale = new HashMap<>();

   public BladeOfDiminutionListener(LegendaryWeapons plugin) {
      super(plugin);
      this.startSpeedRefresher();
   }

   @Override
   public String weaponKey() {
      return "blade-of-diminution";
   }

   private void startSpeedRefresher() {
      (new BukkitRunnable() {
         public void run() {
            int amplifier = BladeOfDiminutionListener.this.abilityInt("hold-speed-amplifier", 0);

            for (Player player : BladeOfDiminutionListener.this.plugin.getServer().getOnlinePlayers()) {
               if (BladeOfDiminutionListener.this.isHolding(player)) {
                  player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, amplifier, true, false, true));
               }
            }
         }
      }).runTaskTimer(this.plugin, 20L, 20L);
   }

   private void applyScale(final LivingEntity target, double scale, int durationTicks) {
      AttributeInstance attribute = target.getAttribute(Attribute.SCALE);
      if (attribute != null) {
         UUID id = target.getUniqueId();
         this.originalScale.putIfAbsent(id, attribute.getBaseValue());
         attribute.setBaseValue(Math.max(0.1, Math.min(4.0, scale)));
         (new BukkitRunnable() {
            public void run() {
               BladeOfDiminutionListener.this.restoreScale(target);
            }
         }).runTaskLater(this.plugin, durationTicks);
      }
   }

   private void restoreScale(LivingEntity target) {
      Double previous = this.originalScale.remove(target.getUniqueId());
      if (previous != null && target.isValid()) {
         AttributeInstance attribute = target.getAttribute(Attribute.SCALE);
         if (attribute != null) {
            attribute.setBaseValue(previous);
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      AttributeInstance attribute = event.getPlayer().getAttribute(Attribute.SCALE);
      if (attribute != null && attribute.getBaseValue() != 1.0 && !this.originalScale.containsKey(event.getPlayer().getUniqueId())) {
         attribute.setBaseValue(1.0);
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.restoreScale(event.getEntity());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.restoreScale(event.getPlayer());
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker)) {
         if (event.getEntity() instanceof LivingEntity victim) {
            if (!(Math.random() >= this.ability("enlarge-chance", 0.15))) {
               if (!(
                  victim instanceof Player target
                     && this.plugin.getTrustManager().isTrusted(attacker, target)
                     && this.plugin.getConfigManager().isPreventAbilityDamage()
               )) {
                  this.applyScale(victim, this.ability("enlarge-scale", 1.5), this.abilityInt("enlarge-duration", 100));
                  Location loc = victim.getLocation();
                  loc.getWorld().spawnParticle(Particle.END_ROD, loc.clone().add(0.0, 1.0, 0.0), 20, 0.4, 0.6, 0.4, 0.03);
                  this.plugin.getSoundManager().hit(loc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5F, 0.6F);
               }
            }
         }
      }
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else {
         boolean onSelf = "self".equalsIgnoreCase(this.abilities() != null ? this.abilities().getString("diminish-target", "enemy") : "enemy");
         double range = this.ability("diminish-range", 6.0);
         LivingEntity target;
         if (onSelf) {
            target = player;
         } else {
            if (!(player.getTargetEntity((int)Math.round(range)) instanceof LivingEntity aimed)) {
               player.sendActionBar(Component.text("No target in range.", NamedTextColor.RED));
               return true;
            }

            if (aimed instanceof Player aimedPlayer
               && this.plugin.getTrustManager().isTrusted(player, aimedPlayer)
               && this.plugin.getConfigManager().isPreventAbilityDamage()) {
               return true;
            }

            target = aimed;
         }

         if (!this.tryCooldown(player, "diminution-diminish", this.abilityLong("diminish-cooldown", 240L))) {
            return true;
         } else {
            this.applyScale(target, this.ability("diminish-scale", 0.5), this.abilityInt("diminish-duration", 120));
            Location loc = target.getLocation();
            loc.getWorld().spawnParticle(Particle.WITCH, loc.clone().add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.05);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.7F, 1.8F);
            if (target instanceof Player shrunk && !shrunk.equals(player)) {
               shrunk.sendActionBar(Component.text("Diminished!", NamedTextColor.LIGHT_PURPLE));
            }

            return true;
         }
      }
   }
}
