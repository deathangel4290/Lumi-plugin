package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.WeaponAbility;
import com.legendaryweapons.managers.WeaponManager;
import java.util.HashMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.entity.AbstractArrow.PickupStatus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public class EnderGlaiveListener implements Listener, WeaponAbility {
   private static final String PEARL_COOLDOWN_KEY = "ender-glaive-pearl";
   private final LegendaryWeapons plugin;
   private final WeaponManager weaponManager;
   private final HashMap<UUID, Long> chargingPlayers = new HashMap<>();
   private final HashMap<UUID, BukkitRunnable> chargeTasks = new HashMap<>();
   private final NamespacedKey glaiveKey;
   private final HashMap<UUID, UUID> tridentOwners = new HashMap<>();
   private final HashMap<UUID, ItemStack> glaiveItems = new HashMap<>();
   /** trident -> the display that shows the real glaive model flying in its place */
   private final HashMap<UUID, org.bukkit.entity.ItemDisplay> glaiveDisplays = new HashMap<>();
   /** Glaives owed to a player, kept on the player so a logout or restart mid-flight can't lose one. */
   private final com.legendaryweapons.managers.OwedItems owedGlaives;

   @Override
   public String weaponKey() {
      return "ender-glaive";
   }

   @Override
   public net.kyori.adventure.text.Component hud(Player player) {
      return com.legendaryweapons.managers.HudManager.cooldown("Pearl", this.plugin.getCooldownManager().getRemainingSeconds(player, PEARL_COOLDOWN_KEY));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      this.triggerAbility(player, secondary);
      return true;
   }

   public EnderGlaiveListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.glaiveKey = new NamespacedKey(plugin, "ender-glaive");
      this.owedGlaives = new com.legendaryweapons.managers.OwedItems(plugin, "glaives-owed");
   }

   @EventHandler
   public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
      Player player = event.getPlayer();
      if (this.owedGlaives.count(player) > 0 && !this.tridentOwners.containsValue(player.getUniqueId())) {
         ItemStack glaive;
         while ((glaive = this.owedGlaives.pop(player, () -> this.weaponManager.createWeapon("ender-glaive"))) != null) {
            player.getInventory().addItem(glaive).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
         }
      }

      // late joiners must not see the stand-in trident either
      for (UUID tridentId : this.tridentOwners.keySet()) {
         Entity trident = Bukkit.getEntity(tridentId);
         if (trident != null) {
            player.hideEntity(this.plugin, trident);
         }
      }
   }

   /** Plugin disable: return every glaive still in the air. */
   public void cleanup() {
      for (UUID tridentId : new java.util.ArrayList<>(this.tridentOwners.keySet())) {
         this.returnGlaiveToPlayer(tridentId);
      }
   }

   public void triggerAbility(Player player) {
      this.triggerAbility(player, player.isSneaking());
   }

   public void triggerAbility(Player player, boolean secondary) {
      ItemStack mainHand = player.getInventory().getItemInMainHand();
      if (this.weaponManager.isLegendaryWeapon(mainHand) && "ender-glaive".equals(this.weaponManager.getWeaponKey(mainHand))) {
         if (secondary) {
            if (this.plugin.getCooldownManager().isOnCooldown(player, "ender-glaive-pearl")) {
               this.plugin.getCooldownManager().notifyOnCooldown(player, "ender-glaive-pearl");
               return;
            }

            if (!player.isSneaking()) {
               this.fireSpearPearlInstantly(player);
               return;
            }

            if (!this.chargingPlayers.containsKey(player.getUniqueId())) {
               this.chargingPlayers.put(player.getUniqueId(), System.currentTimeMillis());
               player.sendMessage("§5Charging Spear Pearl...");
               this.startChargeEffects(player);
            }
         } else {
            ItemStack glaiveItem = mainHand.clone();
            glaiveItem.setAmount(1);
            if (mainHand.getAmount() > 1) {
               mainHand.setAmount(mainHand.getAmount() - 1);
            } else {
               player.getInventory().setItemInMainHand(null);
            }

            this.throwTrident(player, glaiveItem);
         }
      }
   }

   private void fireSpearPearlInstantly(Player player) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("ender-glaive");
      long maxChargeTime = abilities != null ? abilities.getLong("max-charge-time", 3000L) : 3000L;
      long pearlCooldown = abilities != null ? abilities.getLong("pearl-cooldown", 200L) : 200L;
      this.plugin.getCooldownManager().set(player, "ender-glaive-pearl", pearlCooldown);
      this.throwSpearPearl(player, maxChargeTime);
   }

   public void releaseAbility(Player player) {
      if (this.chargingPlayers.containsKey(player.getUniqueId())) {
         long chargeTime = System.currentTimeMillis() - this.chargingPlayers.remove(player.getUniqueId());
         this.stopChargeEffects(player);
         ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("ender-glaive");
         long pearlCooldown = abilities != null ? abilities.getLong("pearl-cooldown", 200L) : 200L;
         this.plugin.getCooldownManager().set(player, "ender-glaive-pearl", pearlCooldown);
         this.throwSpearPearl(player, chargeTime);
      }
   }

   private void startChargeEffects(final Player player) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("ender-glaive");
      final long maxChargeTime = abilities != null ? abilities.getLong("max-charge-time", 3000L) : 3000L;
      final long startedAt = System.currentTimeMillis();
      BukkitRunnable chargeTask = new BukkitRunnable() {
         int ticks = 0;
         boolean announcedFull = false;

         public void run() {
            if (EnderGlaiveListener.this.chargingPlayers.containsKey(player.getUniqueId()) && player.isOnline()) {
               Location loc = player.getLocation().add(0.0, 1.0, 0.0);
               double progress = Math.min(1.0, (double)(System.currentTimeMillis() - startedAt) / maxChargeTime);
               if (this.ticks % 2 == 0) {
                  EnderGlaiveListener.this.plugin.getParticleManager().trail(loc, Particle.PORTAL, 4, 0.4, 0.5, 0.4, 0.05);
               }

               if (!this.announcedFull) {
                  com.legendaryweapons.managers.HudManager.message(player, Component.text("Spear Pearl ", NamedTextColor.DARK_PURPLE)
                        .append(Component.text((int)(progress * 100.0) + "%", NamedTextColor.LIGHT_PURPLE))
                  );
               }

               if (progress >= 1.0 && !this.announcedFull) {
                  this.announcedFull = true;
                  com.legendaryweapons.managers.HudManager.message(player, Component.text("Spear Pearl ready", NamedTextColor.LIGHT_PURPLE));
                  EnderGlaiveListener.this.plugin.getSoundManager().self(player, Sound.BLOCK_BEACON_POWER_SELECT, 0.7F, 1.6F);
                  ParticleManager.spawn(Particle.END_ROD, loc, 25, 0.4, 0.4, 0.4, 0.06);
               }

               if (this.ticks % 20 == 0) {
                  EnderGlaiveListener.this.plugin.getSoundManager().ambient(loc, Sound.ENTITY_ENDER_PEARL_THROW, 0.4F, 1.5F);
               }

               this.ticks++;
            } else {
               this.cancel();
            }
         }
      };
      chargeTask.runTaskTimer(this.plugin, 0L, 1L);
      this.chargeTasks.put(player.getUniqueId(), chargeTask);
   }

   private void stopChargeEffects(Player player) {
      if (this.chargeTasks.containsKey(player.getUniqueId())) {
         this.chargeTasks.get(player.getUniqueId()).cancel();
         this.chargeTasks.remove(player.getUniqueId());
      }
   }

   private void throwSpearPearl(Player player, long chargeTime) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("ender-glaive");
      long maxChargeTime = abilities.getLong("max-charge-time", 3000L);
      double maxThrowStrength = abilities.getDouble("max-throw-strength", 2.0);
      double chargeRatio = (double)Math.min(chargeTime, maxChargeTime) / maxChargeTime;
      double throwStrength = 0.5 + (maxThrowStrength - 0.5) * chargeRatio;
      Location startLoc = player.getEyeLocation().clone();
      Vector direction = startLoc.getDirection();
      EnderPearl pearl = (EnderPearl)player.launchProjectile(EnderPearl.class);
      pearl.setVelocity(direction.multiply(throwStrength));
      pearl.setShooter(player);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 0.7F, 1.0F);
      this.trackSpearPearl(pearl, player);
   }

   private void trackSpearPearl(EnderPearl pearl, Player player) {
      final UUID pearlUUID = pearl.getUniqueId();
      final UUID playerUUID = player.getUniqueId();
      BukkitRunnable task = new BukkitRunnable() {
         public void run() {
            Entity pearlEntity = Bukkit.getEntity(pearlUUID);
            Player owner = Bukkit.getPlayer(playerUUID);
            if (pearlEntity == null || !pearlEntity.isValid()) {
               this.cancel();
            } else if (owner != null && owner.isOnline()) {
               Location pearlLoc = pearlEntity.getLocation();
               ParticleManager.spawn(Particle.PORTAL, pearlLoc, 3, 0.1, 0.1, 0.1, 0.01);
               ParticleManager.spawn(Particle.REVERSE_PORTAL, pearlLoc, 1, 0.1, 0.1, 0.1, 0.01);
            } else {
               pearlEntity.remove();
               this.cancel();
            }
         }
      };
      task.runTaskTimer(this.plugin, 1L, 1L);
   }

   private void throwTrident(Player player, ItemStack glaiveItem) {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("ender-glaive");
      double baseDamage = abilities.getDouble("spear-damage", 5.0);
      final Trident trident = (Trident)player.launchProjectile(Trident.class);
      trident.setVelocity(player.getLocation().getDirection().multiply(2.0));
      trident.setPickupStatus(PickupStatus.DISALLOWED);
      trident.getPersistentDataContainer().set(this.glaiveKey, PersistentDataType.BYTE, (byte)1);
      trident.getPersistentDataContainer().set(new NamespacedKey(this.plugin, "glaive-owner"), PersistentDataType.STRING, player.getUniqueId().toString());
      trident.getPersistentDataContainer().set(new NamespacedKey(this.plugin, "glaive-damage"), PersistentDataType.DOUBLE, baseDamage);
      this.tridentOwners.put(trident.getUniqueId(), player.getUniqueId());
      com.legendaryweapons.managers.SpriteFx.slash(player, org.bukkit.Color.fromRGB(150, 60, 235), 2.4, 0.25);
      this.glaiveItems.put(trident.getUniqueId(), glaiveItem);
      this.owedGlaives.push(player, glaiveItem);
      // The thrown entity would render as a vanilla trident; hide it and fly the glaive model instead.
      for (Player viewer : player.getWorld().getPlayers()) {
         viewer.hideEntity(this.plugin, trident);
      }

      org.bukkit.entity.ItemDisplay shown = player.getWorld().spawn(trident.getLocation(), org.bukkit.entity.ItemDisplay.class, display -> {
         display.setPersistent(false);
         display.setItemStack(glaiveItem.clone());
         display.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.FIXED);
         display.setTeleportDuration(1);
      });
      this.glaiveDisplays.put(trident.getUniqueId(), shown);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.7F, 1.2F);
      (new BukkitRunnable() {
         public void run() {
            if (trident == null || !trident.isValid()) {
               EnderGlaiveListener.this.returnGlaiveToPlayer(trident.getUniqueId());
               this.cancel();
            } else if (!trident.isOnGround() && !trident.isInBlock()) {
               ParticleManager.spawn(Particle.REVERSE_PORTAL, trident.getLocation(), 1, 0.1, 0.1, 0.1, 0.005);
               EnderGlaiveListener.this.followTrident(trident);
            } else {
               EnderGlaiveListener.this.followTrident(trident);
               (new BukkitRunnable() {
                  public void run() {
                     EnderGlaiveListener.this.returnGlaiveToPlayer(trident.getUniqueId());
                  }
               }).runTaskLater(EnderGlaiveListener.this.plugin, 20L);
               this.cancel();
            }
         }
      }).runTaskTimer(this.plugin, 1L, 1L);
   }

   /** Keep the glaive model on the hidden trident, blade pointing along its flight. */
   private void followTrident(Trident trident) {
      org.bukkit.entity.ItemDisplay display = this.glaiveDisplays.get(trident.getUniqueId());
      if (display != null && display.isValid()) {
         Vector velocity = trident.getVelocity();
         display.teleport(trident.getLocation());
         if (velocity.lengthSquared() > 1.0E-4) {
            org.joml.Quaternionf pointing = new org.joml.Quaternionf().rotationTo(0.0F, 1.0F, 0.0F,
               (float)velocity.getX(), (float)velocity.getY(), (float)velocity.getZ());
            org.bukkit.util.Transformation now = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTransformation(new org.bukkit.util.Transformation(now.getTranslation(), pointing, now.getScale(), now.getRightRotation()));
         }
      }
   }

   private void returnGlaiveToPlayer(UUID tridentId) {
      org.bukkit.entity.ItemDisplay display = this.glaiveDisplays.remove(tridentId);
      if (display != null && display.isValid()) {
         display.remove();
      }

      if (this.tridentOwners.containsKey(tridentId) && this.glaiveItems.containsKey(tridentId)) {
         UUID playerId = this.tridentOwners.get(tridentId);
         ItemStack glaiveItem = this.glaiveItems.get(tridentId);
         Player player = Bukkit.getPlayer(playerId);
         if (player != null && player.isOnline()) {
            this.owedGlaives.pop(player, () -> null);                  // offline owners keep it owed until they rejoin
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{glaiveItem});
            if (leftover.isEmpty()) {
               this.plugin.getSoundManager().self(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5F, 1.2F);
               ParticleManager.spawn(Particle.PORTAL, player.getLocation().add(0.0, 1.0, 0.0), 10, 0.5, 0.5, 0.5, 0.1);
            } else {
               player.getWorld().dropItem(player.getLocation(), glaiveItem);
               this.plugin.getSoundManager().self(player, Sound.ENTITY_ITEM_PICKUP, 0.5F, 1.0F);
               com.legendaryweapons.managers.HudManager.message(player, Component.text("Ender Glaive dropped - inventory full", NamedTextColor.LIGHT_PURPLE));
               ParticleManager.spawn(Particle.PORTAL, player.getLocation(), 10, 0.5, 0.5, 0.5, 0.1);
            }
         }

         this.tridentOwners.remove(tridentId);
         this.glaiveItems.remove(tridentId);
         Entity trident = Bukkit.getEntity(tridentId);
         if (trident != null && trident.isValid()) {
            trident.remove();
         }
      }
   }

   @EventHandler
   public void onProjectileHit(ProjectileHitEvent event) {
      if (event.getEntity() instanceof Trident) {
         final Trident trident = (Trident)event.getEntity();
         if (trident.getPersistentDataContainer().has(this.glaiveKey, PersistentDataType.BYTE)) {
            Location hitLoc = trident.getLocation();
            this.plugin.getSoundManager().world(hitLoc, Sound.ENTITY_ENDERMAN_HURT, 0.5F, 1.2F);
            ParticleManager.spawn(Particle.PORTAL, hitLoc, 10, 0.5, 0.5, 0.5, 0.1);
            (new BukkitRunnable() {
               public void run() {
                  EnderGlaiveListener.this.returnGlaiveToPlayer(trident.getUniqueId());
               }
            }).runTaskLater(this.plugin, 20L);
         }
      }

      if (event.getEntity() instanceof EnderPearl) {
         EnderPearl pearl = (EnderPearl)event.getEntity();
         if (pearl.getShooter() instanceof Player) {
            Player player = (Player)pearl.getShooter();
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (this.weaponManager.isLegendaryWeapon(mainHand) && "ender-glaive".equals(this.weaponManager.getWeaponKey(mainHand))) {
               Location hitLoc = event.getEntity().getLocation();
               this.plugin.getSoundManager().world(hitLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 1.0F);
               ParticleManager.spawn(Particle.PORTAL, hitLoc, 20, 1.0, 1.0, 1.0, 0.2);
               com.legendaryweapons.managers.AnimationFx.ring(player.getLocation(), Material.PURPLE_STAINED_GLASS, 0.3, 1.6, 8, true);
               com.legendaryweapons.managers.AnimationFx.ring(hitLoc, Material.PURPLE_STAINED_GLASS, 1.6, 0.3, 8, true);
               com.legendaryweapons.managers.SpriteFx.burst(hitLoc.clone().add(0.0, 1.0, 0.0), org.bukkit.Color.fromRGB(150, 60, 235), 1.8, 2, 1, 5);
            }
         }
      }
   }

   @EventHandler
   public void onProjectileLaunch(ProjectileLaunchEvent event) {
      if (event.getEntity() instanceof Trident) {
         Trident trident = (Trident)event.getEntity();
         final UUID tridentId = trident.getUniqueId();
         (new BukkitRunnable() {
            public void run() {
               if (EnderGlaiveListener.this.tridentOwners.containsKey(tridentId)) {
                  EnderGlaiveListener.this.returnGlaiveToPlayer(tridentId);
               }
            }
         }).runTaskLater(this.plugin, 600L);
      }
   }

   @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Trident) {
         Trident trident = (Trident)event.getDamager();
         if (trident.getPersistentDataContainer().has(this.glaiveKey, PersistentDataType.BYTE)) {
            Double damage = (Double)trident.getPersistentDataContainer().get(new NamespacedKey(this.plugin, "glaive-damage"), PersistentDataType.DOUBLE);
            if (damage != null) {
               double finalDamage = damage + Math.random() * 2.0;
               event.setDamage(finalDamage);
               this.plugin.getSoundManager().hit(event.getEntity().getLocation(), Sound.ENTITY_ENDERMAN_HURT, 0.6F, 1.2F);
            }
         }
      }

      if (event.getDamager() instanceof Player) {
         Player damager = (Player)event.getDamager();
         ItemStack weapon = damager.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "ender-glaive".equals(this.weaponManager.getWeaponKey(weapon))) {
            ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("ender-glaive");
            double meleeOverride = abilities != null ? abilities.getDouble("melee-damage", 0.0) : 0.0;
            if (meleeOverride > 0.0) {
               event.setDamage(meleeOverride);
            }

            this.plugin.getSoundManager().hit(event.getEntity().getLocation(), Sound.ENTITY_ENDERMAN_HURT, 0.5F, 1.2F);
            if (Math.random() < 0.1) {
               Location loc = event.getEntity().getLocation();
               ParticleManager.spawn(Particle.PORTAL, loc, 5, 0.5, 0.5, 0.5, 0.1);
            }
         }
      }
   }
}
