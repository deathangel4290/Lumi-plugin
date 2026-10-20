package com.legendaryweapons.listeners;

import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.WeaponAbility;
import com.legendaryweapons.managers.WeaponManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

public class AxeOfZeusListener implements Listener, WeaponAbility {
   private static final String WEAPON_KEY = "axe-of-zeus";
   private static final String COOLDOWN_KEY = "axe-of-zeus-throw";
   private static final double MAX_STEP = 0.25;
   private final LegendaryWeapons plugin;
   private final WeaponManager weaponManager;
   private final NamespacedKey axeKey;
   private final Map<UUID, BukkitRunnable> axeTasks = new HashMap<>();
   /** axe display -> owner, so a shutdown can hand every axe in flight back. */
   private final Map<UUID, UUID> axeOwners = new HashMap<>();
   /** owner -> hotbar slot the axe was thrown from, so it comes back where it was. */
   private final Map<UUID, Integer> thrownFrom = new HashMap<>();
   /**
    * Axes owed to a player, stored on the player so it survives a logout or restart mid-flight
    * (the thrown axe used to be deleted if its owner left while it was in the air).
    */
   private final com.legendaryweapons.managers.OwedItems owedAxes;

   @Override
   public String weaponKey() {
      return "axe-of-zeus";
   }

   @Override
   public net.kyori.adventure.text.Component hud(Player player) {
      return com.legendaryweapons.managers.HudManager.cooldown(player, "Throw", COOLDOWN_KEY);
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      this.triggerAbility(player);
      return true;
   }

   public AxeOfZeusListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
      this.axeKey = new NamespacedKey(plugin, "zeus-axe");
      this.owedAxes = new com.legendaryweapons.managers.OwedItems(plugin, "zeus-axes-owed");
   }

   private int owed(Player player) {
      return this.owedAxes.count(player);
   }

   private boolean inFlightFor(UUID owner) {
      return this.axeOwners.containsValue(owner);
   }

   /** Rejoining with an axe still owed from a flight that never finished: hand it back. */
   @EventHandler
   public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
      Player player = event.getPlayer();
      int owed = this.owed(player);
      if (owed > 0 && !this.inFlightFor(player.getUniqueId())) {
         for (int i = 0; i < owed; i++) {
            this.giveAxeBack(player);
         }
      }
   }

   /** Plugin disable: every axe still flying goes straight back to its owner. */
   public void cleanup() {
      for (Map.Entry<UUID, UUID> entry : new HashMap<>(this.axeOwners).entrySet()) {
         BukkitRunnable task = this.axeTasks.remove(entry.getKey());
         if (task != null) {
            task.cancel();
         }

         Entity axe = Bukkit.getEntity(entry.getKey());
         if (axe != null) {
            axe.remove();
         }

         Player owner = Bukkit.getPlayer(entry.getValue());
         if (owner != null && owner.isOnline()) {
            this.giveAxeBack(owner);
         }
      }

      this.axeOwners.clear();
   }

   private ConfigurationSection abilities() {
      return this.plugin.getConfigManager().getWeaponAbilities("axe-of-zeus");
   }

   private double ability(String key, double fallback) {
      ConfigurationSection section = this.abilities();
      return section != null ? section.getDouble(key, fallback) : fallback;
   }

   private int abilityInt(String key, int fallback) {
      ConfigurationSection section = this.abilities();
      return section != null ? section.getInt(key, fallback) : fallback;
   }

   public void triggerAbility(Player player) {
      ItemStack mainHand = player.getInventory().getItemInMainHand();
      if (this.weaponManager.isLegendaryWeapon(mainHand) && "axe-of-zeus".equals(this.weaponManager.getWeaponKey(mainHand))) {
         long cooldown = (long)this.ability("throw-cooldown", 200.0);
         if (this.plugin.getCooldownManager().tryUseWithItemFeedback(player, "axe-of-zeus-throw", cooldown)) {
            // the player's own axe flies and comes back: enchantments, name and all
            ItemStack thrown = mainHand.clone();
            thrown.setAmount(1);
            this.owedAxes.push(player, thrown);
            this.thrownFrom.put(player.getUniqueId(), player.getInventory().getHeldItemSlot());
            if (mainHand.getAmount() > 1) {
               mainHand.setAmount(mainHand.getAmount() - 1);
            } else {
               player.getInventory().setItemInMainHand(null);
            }

            this.launchAxe(player, thrown);
         }
      }
   }

   private void launchAxe(Player player, ItemStack thrown) {
      Location startLoc = player.getEyeLocation().clone();
      Vector direction = startLoc.getDirection().normalize();
      ItemDisplay axeDisplay = (ItemDisplay)player.getWorld().spawnEntity(startLoc, EntityType.ITEM_DISPLAY);
      axeDisplay.setPersistent(false);
      axeDisplay.setInvulnerable(true);
      // FIXED = the model's item-frame size; with no transform the 3D axe rendered three blocks tall
      axeDisplay.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
      axeDisplay.setTeleportDuration(1);                               // client smooths the per-tick moves
      axeDisplay.setItemStack(thrown.clone());
      axeDisplay.getPersistentDataContainer().set(this.axeKey, PersistentDataType.BYTE, (byte)1);
      axeDisplay.getPersistentDataContainer().set(new NamespacedKey(this.plugin, "zeus-owner"), PersistentDataType.STRING, player.getUniqueId().toString());
      this.axeOwners.put(axeDisplay.getUniqueId(), player.getUniqueId());
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8F, 1.1F);
      com.legendaryweapons.managers.SpriteFx.slash(player, org.bukkit.Color.fromRGB(120, 220, 255), 2.6, -0.3);
      this.startAxeTracking(axeDisplay, player, direction);
   }

   private void orientAxe(ItemDisplay display, double spinRadians) {
      Transformation current = display.getTransformation();
      Quaternionf left = new Quaternionf().rotateY((float)Math.toRadians(90.0)).rotateX((float)spinRadians);
      display.setInterpolationDelay(0);
      display.setInterpolationDuration(1);                          // the spin turns smoothly between ticks
      display.setTransformation(new Transformation(current.getTranslation(), left, current.getScale(), current.getRightRotation()));
   }

   private boolean isBlocked(Location location) {
      Block block = location.getBlock();
      return block.getType().isSolid() && !block.isPassable();
   }

   private LivingEntity findTarget(Location location, UUID ownerId) {
      for (Entity nearby : location.getWorld().getNearbyEntities(location, 0.6, 0.6, 0.6)) {
         if (nearby instanceof LivingEntity living && !nearby.getUniqueId().equals(ownerId) && !living.isDead()
            && !(living instanceof Player p && p.getGameMode() == org.bukkit.GameMode.SPECTATOR)) {
            return living;
         }
      }

      return null;
   }

   private void startAxeTracking(final ItemDisplay axeDisplay, Player player, final Vector initialDirection) {
      final int lifetime = this.abilityInt("projectile-lifetime", 200);
      final double speed = this.ability("projectile-speed", 1.6);
      final double maxDistance = this.ability("max-travel-distance", 40.0);
      final double spinPerTick = Math.toRadians(this.ability("spin-degrees-per-tick", 50.0));
      final UUID axeUUID = axeDisplay.getUniqueId();
      final UUID playerUUID = player.getUniqueId();
      BukkitRunnable task = new BukkitRunnable() {
         private int ticksLived = 0;
         private double travelled = 0.0;
         private double spin = 0.0;
         private final Vector direction = initialDirection.clone();
         private Location currentLocation = axeDisplay.getLocation().clone();

         public void run() {
            Entity axe = Bukkit.getEntity(axeUUID);
            Player owner = Bukkit.getPlayer(playerUUID);
            if (axe != null && axe.isValid()) {
               if (owner != null && owner.isOnline()) {
                  if (this.ticksLived++ > lifetime) {
                     AxeOfZeusListener.this.beginReturn(axe, owner);
                  } else {
                     int steps = Math.max(1, (int)Math.ceil(speed / 0.25));
                     Vector step = this.direction.clone().multiply(speed / steps);

                     for (int i = 0; i < steps; i++) {
                        this.currentLocation.add(step);
                        this.travelled = this.travelled + step.length();
                        LivingEntity target = AxeOfZeusListener.this.findTarget(this.currentLocation, playerUUID);
                        if (target != null) {
                           axe.teleport(this.currentLocation);
                           AxeOfZeusListener.this.onAxeHit(axe, owner, target);
                           return;
                        }

                        if (AxeOfZeusListener.this.isBlocked(this.currentLocation)) {
                           this.currentLocation.subtract(step);
                           axe.teleport(this.currentLocation);
                           AxeOfZeusListener.this.onAxeHit(axe, owner, null);
                           return;
                        }

                        if (this.travelled >= maxDistance) {
                           axe.teleport(this.currentLocation);
                           AxeOfZeusListener.this.beginReturn(axe, owner);
                           return;
                        }
                     }

                     Location facing = this.currentLocation.clone();
                     facing.setDirection(this.direction);
                     axe.teleport(facing);
                     this.spin = this.spin + spinPerTick;
                     AxeOfZeusListener.this.orientAxe((ItemDisplay)axe, this.spin);
                     ParticleManager.spawn(Particle.CRIT, this.currentLocation, 1, 0.05, 0.05, 0.05, 0.01);
                  }
               } else {
                  axe.remove();                               // owner left: the axe stays owed until they rejoin
                  AxeOfZeusListener.this.axeTasks.remove(axeUUID);
                  AxeOfZeusListener.this.axeOwners.remove(axeUUID);
                  this.cancel();
               }
            } else {
               AxeOfZeusListener.this.axeTasks.remove(axeUUID);
               AxeOfZeusListener.this.axeOwners.remove(axeUUID);
               if (owner != null && owner.isOnline()) {
                  AxeOfZeusListener.this.giveAxeBack(owner);    // display vanished (chunk unload, killed): still return it
               }

               this.cancel();
            }
         }
      };
      task.runTaskTimer(this.plugin, 1L, 1L);
      this.axeTasks.put(axeUUID, task);
   }

   private void onAxeHit(Entity axe, Player owner, LivingEntity hitEntity) {
      Location hitLoc = axe.getLocation();
      hitLoc.getWorld().strikeLightningEffect(hitLoc);
      if (!com.legendaryweapons.managers.SpriteFx.wave(hitLoc, org.bukkit.Color.fromRGB(120, 220, 255), 3.0, 6)) {
         com.legendaryweapons.managers.AnimationFx.ring(hitLoc, Material.LIGHT_BLUE_STAINED_GLASS, 0.3, 2.5, 8, true);
      }

      com.legendaryweapons.managers.SpriteFx.burst(hitLoc.clone().add(0.0, 1.0, 0.0), org.bukkit.Color.fromRGB(120, 220, 255), 2.6, 2, 1, 6);
      this.plugin.getSoundManager().world(hitLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7F, 1.2F);
      if (hitEntity != null && this.plugin.getProtectionManager().canAffect(owner, hitEntity)) {
         com.legendaryweapons.abilities.AbilityDamage.hit(owner, hitEntity, this.ability("throw-damage", 8.0), this.ability("throw-armour-pierce", 0.25), hitLoc, "{victim} was struck down by {killer}'s Axe of Zeus");
         int stunDuration = this.abilityInt("throw-stun-duration", 40);
         int weaknessDuration = this.abilityInt("throw-weakness-duration", 100);
         hitEntity.setVelocity(new Vector(0.0, 0.5, 0.0));
         hitEntity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, stunDuration, 10, true, false));
         hitEntity.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, weaknessDuration, 1, true, false));
         hitEntity.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20, 0, true, false));
         if (hitEntity instanceof Player victim && victim.isBlocking()) {
            this.disableShield(victim);
         }
      }

      this.beginReturn(axe, owner);
   }

   private void beginReturn(Entity axe, Player owner) {
      UUID axeUUID = axe.getUniqueId();
      BukkitRunnable existing = this.axeTasks.remove(axeUUID);
      if (existing != null) {
         existing.cancel();
      }

      this.startAxeReturnTracking((ItemDisplay)axe, owner);
   }

   private void startAxeReturnTracking(final ItemDisplay axeDisplay, Player player) {
      final double returnSpeed = this.ability("return-speed", 2.0);
      final double spinPerTick = Math.toRadians(this.ability("spin-degrees-per-tick", 50.0));
      final UUID axeUUID = axeDisplay.getUniqueId();
      final UUID playerUUID = player.getUniqueId();
      BukkitRunnable task = new BukkitRunnable() {
         private double spin = 0.0;
         private Location currentLocation = axeDisplay.getLocation().clone();

         public void run() {
            Entity axe = Bukkit.getEntity(axeUUID);
            Player owner = Bukkit.getPlayer(playerUUID);
            if (axe == null || !axe.isValid()) {
               AxeOfZeusListener.this.axeTasks.remove(axeUUID);
               AxeOfZeusListener.this.axeOwners.remove(axeUUID);
               if (owner != null && owner.isOnline()) {
                  AxeOfZeusListener.this.giveAxeBack(owner);
               }

               this.cancel();
            } else if (owner != null && owner.isOnline() && owner.isDead()) {
               // hang in the air until the owner respawns, then come home
            } else if (owner != null && owner.isOnline() && !owner.getWorld().equals(this.currentLocation.getWorld())) {
               axe.remove();                                    // owner changed dimension: skip the flight home
               AxeOfZeusListener.this.axeTasks.remove(axeUUID);
               AxeOfZeusListener.this.axeOwners.remove(axeUUID);
               AxeOfZeusListener.this.giveAxeBack(owner);
               this.cancel();
            } else if (owner != null && owner.isOnline()) {
               Location playerLoc = owner.getEyeLocation();
               Vector toPlayer = playerLoc.toVector().subtract(this.currentLocation.toVector());
               if (toPlayer.length() < 1.0) {
                  axe.remove();
                  AxeOfZeusListener.this.axeTasks.remove(axeUUID);
                  AxeOfZeusListener.this.axeOwners.remove(axeUUID);
                  AxeOfZeusListener.this.giveAxeBack(owner);
                  this.cancel();
               } else {
                  Vector movement = toPlayer.normalize().multiply(returnSpeed);
                  this.currentLocation.add(movement);
                  Location facing = this.currentLocation.clone();
                  facing.setDirection(movement);
                  axe.teleport(facing);
                  this.spin = this.spin + spinPerTick;
                  AxeOfZeusListener.this.orientAxe((ItemDisplay)axe, this.spin);
                  ParticleManager.spawn(Particle.ELECTRIC_SPARK, this.currentLocation, 1, 0.05, 0.05, 0.05, 0.01);
               }
            } else {
               axe.remove();                                    // owner offline: still owed, given on rejoin
               AxeOfZeusListener.this.axeTasks.remove(axeUUID);
               AxeOfZeusListener.this.axeOwners.remove(axeUUID);
               this.cancel();
            }
         }
      };
      task.runTaskTimer(this.plugin, 1L, 1L);
      this.axeTasks.put(axeUUID, task);
   }

   private void giveAxeBack(Player player) {
      ItemStack axe = this.owedAxes.pop(player, () -> this.weaponManager.createWeapon("axe-of-zeus"));
      if (axe != null && !axe.getType().isAir()) {                // null: nothing owed, never mint a second axe
         Integer slot = this.thrownFrom.remove(player.getUniqueId());
         if (slot != null && slot >= 0 && slot < 9) {
            ItemStack there = player.getInventory().getItem(slot);
            if (there == null || there.getType().isAir()) {
               player.getInventory().setItem(slot, axe);            // back in the hand it left
               this.plugin.getSoundManager().self(player, Sound.ITEM_TRIDENT_RETURN, 0.6F, 1.2F);
               return;
            }
         }

         Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{axe});
         if (!leftover.isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), leftover.get(0));
            com.legendaryweapons.managers.HudManager.message(player, Component.text("Axe of Zeus dropped - inventory full", NamedTextColor.YELLOW));
         }

         this.plugin.getSoundManager().self(player, Sound.ITEM_TRIDENT_RETURN, 0.6F, 1.2F);
      }
   }

   private void disableShield(Player victim) {
      final int duration = this.abilityInt("shield-break-duration", 160);
      victim.setCooldown(Material.SHIELD, duration);
      // and again a tick later: vanilla's own 5s axe disable lands after this event and would overwrite it
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (victim.isOnline()) {
            victim.setCooldown(Material.SHIELD, duration);
         }
      }, 1L);
      this.plugin.getSoundManager().world(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.7F, 1.0F);
      com.legendaryweapons.managers.SpriteFx.burst(victim, 0.55, org.bukkit.Color.fromRGB(205, 220, 240), 1.5, 1, 1, 4);
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (com.legendaryweapons.abilities.AbilityDamage.active() && event.getDamager() instanceof org.bukkit.entity.Player) {
         return;                                      // an ability's own damage, not a swing
      }

      if (event.getDamager() instanceof Player damager) {
         ItemStack weapon = damager.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "axe-of-zeus".equals(this.weaponManager.getWeaponKey(weapon))) {
            this.handleMeleeAttack(damager, event);
         }
      }
   }

   private void handleMeleeAttack(Player damager, EntityDamageByEntityEvent event) {
      if (!(damager.getAttackCooldown() <= 0.9F) && !event.isCancelled() && this.plugin.getProtectionManager().canAffect(damager, event.getEntity())) {
         if (event.getEntity() instanceof LivingEntity victim && Math.random() < this.ability("melee-lightning-chance", 0.1)) {
            Location loc = victim.getLocation();
            loc.getWorld().strikeLightningEffect(loc);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5F, 1.5F);
            ParticleManager.spawn(Particle.ELECTRIC_SPARK, loc, 20, 1.0, 1.0, 1.0, 0.1);
            com.legendaryweapons.managers.SpriteFx.burst(victim, 0.55, org.bukkit.Color.fromRGB(120, 220, 255), 2.2, 1, 1, 5);
            com.legendaryweapons.managers.SpriteFx.wave(loc, org.bukkit.Color.fromRGB(120, 220, 255), 2.5, 5);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, this.abilityInt("melee-stun-duration", 20), 2, true, false));
            victim.setVelocity(new Vector(0.0, 0.3, 0.0));
         }

         if (event.getEntity() instanceof Player victim && victim.isBlocking()) {
            this.disableShield(victim);
         }
      }
   }
}
