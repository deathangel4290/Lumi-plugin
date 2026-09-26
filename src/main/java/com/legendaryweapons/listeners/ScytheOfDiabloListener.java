package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.WeaponManager;
import java.util.HashMap;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.Particle.DustOptions;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class ScytheOfDiabloListener implements Listener {
   private final LegendaryWeapons plugin;
   private final WeaponManager weaponManager;
   private final HashMap<UUID, Integer> comboMap = new HashMap<>();
   private final HashMap<UUID, Long> lastHitMap = new HashMap<>();

   public ScytheOfDiabloListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      plugin.getHudManager().register(player -> {
         ItemStack held = player.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(held) && "scythe-of-diablo".equals(this.weaponManager.getWeaponKey(held))) {
            int required = plugin.getConfig().getInt("weapons.scythe-of-diablo.abilities.combo-hits-required", 5);
            return net.kyori.adventure.text.Component.text("Combo ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
               .append(net.kyori.adventure.text.Component.text(Math.min(this.comboMap.getOrDefault(player.getUniqueId(), 0), required) + "/" + required, net.kyori.adventure.text.format.NamedTextColor.RED));
         } else {
            return null;
         }
      });
   }

   @EventHandler
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player killed = event.getEntity();
      Player killer = killed.getKiller();
      if (killer != null) {
         ItemStack weapon = killer.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "scythe-of-diablo".equals(this.weaponManager.getWeaponKey(weapon))) {
            AttributeInstance maxHealth = killer.getAttribute(Attribute.MAX_HEALTH);
            killer.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
            this.createSoulAbsorptionEffect(killed.getLocation(), killer);
         }
      }
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player && event.getEntity() instanceof LivingEntity) {
         Player damager = (Player)event.getDamager();
         LivingEntity victim = (LivingEntity)event.getEntity();
         ItemStack weapon = damager.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "scythe-of-diablo".equals(this.weaponManager.getWeaponKey(weapon))) {
            ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("scythe-of-diablo");
            long currentTime = System.currentTimeMillis();
            if (this.lastHitMap.containsKey(damager.getUniqueId())) {
               long lastHit = this.lastHitMap.get(damager.getUniqueId());
               if (currentTime - lastHit > 3000L) {
                  this.comboMap.put(damager.getUniqueId(), 0);
               }
            }

            this.lastHitMap.put(damager.getUniqueId(), currentTime);
            this.createAttackEffects(damager.getLocation(), victim.getLocation());
            boolean isBackstab = false;
            boolean isSweep = false;
            if (victim instanceof Player) {
               Vector damagerDirection = damager.getLocation().getDirection();
               Vector victimDirection = victim.getLocation().getDirection();
               if (damagerDirection.dot(victimDirection) > 0.0) {
                  double backstabMultiplier = abilities.getDouble("backstab-damage-multiplier", 1.5);
                  event.setDamage(event.getDamage() * backstabMultiplier);
                  isBackstab = true;
                  this.createBackstabEffects(victim.getLocation());
               }
            }

            if (event.getCause() == DamageCause.ENTITY_SWEEP_ATTACK) {
               double sweepMultiplier = abilities.getDouble("sweep-damage-multiplier", 1.25);
               event.setDamage(event.getDamage() * sweepMultiplier);
               isSweep = true;
               this.createSweepEffects(damager.getLocation());
            }

            int combo = this.comboMap.getOrDefault(damager.getUniqueId(), 0);
            if (victim instanceof Player && !isSweep && damager.getAttackCooldown() > 0.9F) {
               this.comboMap.put(damager.getUniqueId(), ++combo);
               this.createComboEffects(damager, victim.getLocation(), combo);
               if (combo >= abilities.getInt("combo-hits-required", 5)) {
                  int duration = abilities.getInt("combo-absorption-duration", 200);
                  int amplifier = abilities.getInt("combo-absorption-amplifier", 0);
                  damager.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, duration, amplifier));
                  this.createComboCompletionEffects(damager, victim.getLocation());
                  this.comboMap.put(damager.getUniqueId(), 0);
               }
            }

            this.playAttackSound(damager.getLocation(), isBackstab, isSweep, combo);
         }
      }

      if (event.getEntity() instanceof Player) {
         Player victim = (Player)event.getEntity();
         this.comboMap.remove(victim.getUniqueId());
         this.lastHitMap.remove(victim.getUniqueId());
      }
   }

   private void createAttackEffects(Location damagerLoc, Location victimLoc) {
      World world = damagerLoc.getWorld();
      this.createEnergyBeam(damagerLoc, victimLoc);
      ParticleManager.spawn(Particle.BLOCK, victimLoc, 15, 0.5, 0.5, 0.5, 0.1, Material.GRAVEL.createBlockData());
      ParticleManager.spawn(Particle.DAMAGE_INDICATOR, victimLoc, 5, 0.3, 0.3, 0.3, 0.0);
      

      for (int i = 0; i < 3; i++) {
         Vector direction = damagerLoc.toVector().subtract(victimLoc.toVector()).normalize();
         Location soulLoc = victimLoc.clone().add((Math.random() - 0.5) * 0.5, Math.random() * 1.0, (Math.random() - 0.5) * 0.5);
         ParticleManager.spawn(Particle.SOUL, soulLoc, 1, direction.getX() * 0.1, direction.getY() * 0.1, direction.getZ() * 0.1, 0.1);
      }
   }

   private void createEnergyBeam(Location start, Location end) {
      World world = start.getWorld();
      Vector direction = end.toVector().subtract(start.toVector());
      double distance = start.distance(end);
      int steps = (int)(distance * 3.0);

      for (int i = 0; i <= steps; i++) {
         double ratio = (double)i / steps;
         Location point = start.clone().add(direction.clone().multiply(ratio));
         ParticleManager.spawn(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0.0, new DustOptions(Color.fromRGB(75, 0, 130), 1.5F));
         if (i % 3 == 0) {
            ParticleManager.spawn(Particle.DUST, point, 1, 0.1, 0.1, 0.1, 0.0, new DustOptions(Color.RED, 0.8F));
         }

         if (i % 7 == 0) {
            ParticleManager.spawn(Particle.SOUL_FIRE_FLAME, point, 1, 0.05, 0.05, 0.05, 0.0);
         }
      }
   }

   private void createBackstabEffects(final Location victimLoc) {
      World world = victimLoc.getWorld();
      ParticleManager.spawn(Particle.CRIT, victimLoc.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.4, 0.3, 0.2);

      for (int i = 0; i < 6; i++) {
         Vector bloodDir = new Vector((Math.random() - 0.5) * 2.0, Math.random() * 1.5, (Math.random() - 0.5) * 2.0).normalize();
         Location bloodLoc = victimLoc.clone().add(bloodDir.multiply(0.5));
         ParticleManager.spawn(Particle.BLOCK, bloodLoc, 3, 0.2, 0.2, 0.2, 0.1, Material.GRAVEL.createBlockData());
      }

      for (int i = 0; i < 3; i++) {
         final int ring = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 1.0 + ring * 0.8;
               ScytheOfDiabloListener.this.createDarkEnergyRing(victimLoc, radius);
            }
         }).runTaskLater(this.plugin, ring * 2L);
      }

      if (victimLoc.getWorld().getPlayers().stream().anyMatch(p -> p.getLocation().distance(victimLoc) < 3.0)) {
         for (Player nearby : victimLoc.getWorld().getPlayers()) {
            if (nearby.getLocation().distance(victimLoc) < 10.0) {
            }
         }
      }
   }

   private void createSweepEffects(final Location damagerLoc) {
      final World world = damagerLoc.getWorld();

      for (int i = 0; i < 2; i++) {
         final int wave = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 2.0 + wave * 1.5;

               for (int j = 0; j < 16; j++) {
                  double angle = j * 3.141592653589793 / 8.0;
                  double x = Math.cos(angle) * radius;
                  double z = Math.sin(angle) * radius;
                  Location wavePoint = damagerLoc.clone().add(x, 0.5, z);
                  ParticleManager.spawn(Particle.SWEEP_ATTACK, wavePoint, 1, 0.0, 0.0, 0.0, 0.0);
                  ParticleManager.spawn(Particle.LARGE_SMOKE, wavePoint, 1, 0.1, 0.1, 0.1, 0.05);
               }
            }
         }).runTaskLater(this.plugin, wave * 3L);
      }

      for (int i = 0; i < 8; i++) {
         double angle = i * 3.141592653589793 / 4.0;
         double distance = 3.0;
         Vector dir = new Vector(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
         Location crackLoc = damagerLoc.clone().add(dir);
         ParticleManager.spawn(Particle.BLOCK, crackLoc, 5, 0.5, 0.1, 0.5, 0.0, Material.OBSIDIAN.createBlockData());
      }
   }

   private void createComboEffects(Player damager, Location victimLoc, int combo) {
      World world = damager.getWorld();
      Location damagerLoc = damager.getLocation();
      Location textLoc = damagerLoc.clone().add(0.0, 2.5, 0.0);
      
      double auraSize = 0.5 + combo * 0.1;

      for (int i = 0; i < combo; i++) {
         double angle = System.currentTimeMillis() / 50.0 % 360.0 + i * (360.0 / combo);
         double radians = Math.toRadians(angle);
         Vector point = new Vector(Math.cos(radians) * auraSize, Math.sin(radians) * auraSize * 0.5, Math.sin(radians) * auraSize);
         Location auraPoint = damagerLoc.clone().add(point);
         Color auraColor = this.getComboColor(combo);
         ParticleManager.spawn(Particle.DUST, auraPoint, 1, 0.0, 0.0, 0.0, 0.0, new DustOptions(auraColor, 1.0F));
      }

      if (combo > 1) {
         for (int i = 0; i < Math.min(combo, 5); i++) {
            Vector direction = damagerLoc.toVector().subtract(victimLoc.toVector()).normalize();
            Location numLoc = victimLoc.clone().add((Math.random() - 0.5) * 1.0, Math.random() * 1.5, (Math.random() - 0.5) * 1.0);
            ParticleManager.spawn(Particle.ENCHANT, numLoc, 1, direction.getX() * 0.2, direction.getY() * 0.2, direction.getZ() * 0.2, 1.0);
         }
      }
   }

   private void createComboCompletionEffects(Player damager, Location victimLoc) {
      World world = damager.getWorld();
      final Location damagerLoc = damager.getLocation();
      com.legendaryweapons.managers.AnimationFx.slash(damager, org.bukkit.Color.fromRGB(255, 70, 60), org.bukkit.Color.fromRGB(70, 0, 10));
      com.legendaryweapons.managers.AnimationFx.ring(damagerLoc, Material.RED_STAINED_GLASS, 0.5, 2.5, 8, true);
      

      for (int i = 0; i < 3; i++) {
         final int layer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 3.0 - layer * 1.0;
               ScytheOfDiabloListener.this.createSoulVortex(damagerLoc, radius);
            }
         }).runTaskLater(this.plugin, layer * 2L);
      }

      for (int i = 0; i < 4; i++) {
         double angle = i * 3.141592653589793 / 2.0;
         double distance = 2.0;
         Vector dir = new Vector(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
         Location pillarLoc = damagerLoc.clone().add(dir);
         this.createEnergyPillar(pillarLoc, 3.0);
      }

      damager.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0));
   }

   private void createSoulAbsorptionEffect(final Location deathLoc, final Player killer) {
      World world = deathLoc.getWorld();

      for (int i = 0; i < 2; i++) {
         final int layer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 4.0 - layer * 0.8;
               ScytheOfDiabloListener.this.createSoulVortex(deathLoc, radius);
            }
         }).runTaskLater(this.plugin, layer * 3L);
      }

      this.createSoulStream(deathLoc, killer.getLocation());
      

      for (int i = 0; i < 3; i++) {
         final int healLayer = i;
         (new BukkitRunnable() {
            public void run() {
               double radius = 1.0 + healLayer * 0.5;
               ScytheOfDiabloListener.this.createHealingRing(killer.getLocation(), radius);
            }
         }).runTaskLater(this.plugin, 10L + healLayer * 5L);
      }

      this.plugin.getSoundManager().world(killer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 0.5F);
   }

   private void createSoulVortex(Location center, double radius) {
      World world = center.getWorld();

      for (int i = 0; i < 16; i++) {
         double angle = System.currentTimeMillis() / 20.0 % 360.0 + i * 22.5;
         double radians = Math.toRadians(angle);
         double x = Math.cos(radians) * radius;
         double z = Math.sin(radians) * radius;
         Location vortexPoint = center.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.SOUL, vortexPoint, 1, 0.0, 0.1, 0.0, 0.1);
         ParticleManager.spawn(Particle.SOUL_FIRE_FLAME, vortexPoint, 1, 0.05, 0.05, 0.05, 0.0);
      }
   }

   private void createSoulStream(Location start, Location end) {
      World world = start.getWorld();
      Vector direction = end.toVector().subtract(start.toVector());
      double distance = start.distance(end);
      int steps = (int)(distance * 3.0);

      for (int i = 0; i <= steps; i++) {
         double ratio = (double)i / steps;
         Location point = start.clone().add(direction.clone().multiply(ratio));
         ParticleManager.spawn(Particle.SOUL, point, 2, 0.1, 0.1, 0.1, 0.05);
         if (i % 3 == 0) {
            ParticleManager.spawn(Particle.DUST, point, 1, 0.05, 0.05, 0.05, 0.0, new DustOptions(Color.fromRGB(50, 0, 100), 1.0F));
         }
      }
   }

   private void createDarkEnergyRing(Location center, double radius) {
      World world = center.getWorld();

      for (int i = 0; i < 12; i++) {
         double angle = i * 3.141592653589793 / 6.0;
         double x = Math.cos(angle) * radius;
         double z = Math.sin(angle) * radius;
         Location ringPoint = center.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.DUST, ringPoint, 2, 0.1, 0.1, 0.1, 0.0, new DustOptions(Color.fromRGB(30, 0, 60), 1.5F));
         ParticleManager.spawn(Particle.LARGE_SMOKE, ringPoint, 1, 0.0, 0.0, 0.0, 0.05);
      }
   }

   private void createEnergyPillar(Location base, double height) {
      World world = base.getWorld();

      for (double y = 0.0; y <= height; y += 0.5) {
         Location pillarPoint = base.clone().add(0.0, y, 0.0);
         ParticleManager.spawn(Particle.FLAME, pillarPoint, 1, 0.1, 0.1, 0.1, 0.02);
         ParticleManager.spawn(Particle.SMOKE, pillarPoint, 1, 0.1, 0.1, 0.1, 0.01);
      }
   }

   private void createHealingRing(Location center, double radius) {
      World world = center.getWorld();

      for (int i = 0; i < 16; i++) {
         double angle = i * 3.141592653589793 / 8.0;
         double x = Math.cos(angle) * radius;
         double z = Math.sin(angle) * radius;
         Location ringPoint = center.clone().add(x, 0.5, z);
         ParticleManager.spawn(Particle.HEART, ringPoint, 1, 0.1, 0.1, 0.1, 0.0);
         ParticleManager.spawn(Particle.HAPPY_VILLAGER, ringPoint, 1, 0.1, 0.1, 0.1, 0.0);
      }
   }

   private Color getComboColor(int combo) {
      return switch (combo) {
         case 1 -> Color.GRAY;
         case 2 -> Color.WHITE;
         case 3 -> Color.YELLOW;
         case 4 -> Color.ORANGE;
         case 5 -> Color.RED;
         default -> Color.PURPLE;
      };
   }

   private void playAttackSound(Location location, boolean isBackstab, boolean isSweep, int combo) {
      if (isBackstab) {
         this.plugin.getSoundManager().world(location, Sound.ENTITY_PLAYER_HURT, 0.8F, 0.6F);
      } else {
         this.plugin.getSoundManager().hit(location, isSweep ? Sound.ENTITY_PLAYER_ATTACK_SWEEP : Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.6F, 0.9F);
      }
   }
}
