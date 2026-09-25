package com.legendaryweapons.listeners;

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

   @Override
   public String weaponKey() {
      return "axe-of-zeus";
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
            if (mainHand.getAmount() > 1) {
               mainHand.setAmount(mainHand.getAmount() - 1);
            } else {
               player.getInventory().setItemInMainHand(null);
            }

            this.launchAxe(player);
         }
      }
   }

   private void launchAxe(Player player) {
      Location startLoc = player.getEyeLocation().clone();
      Vector direction = startLoc.getDirection().normalize();
      ItemDisplay axeDisplay = (ItemDisplay)player.getWorld().spawnEntity(startLoc, EntityType.ITEM_DISPLAY);
      axeDisplay.setPersistent(false);
      axeDisplay.setInvulnerable(true);
      ItemStack axeItem = this.weaponManager.createWeapon("axe-of-zeus");
      if (axeItem == null || axeItem.getType().isAir()) {
         axeItem = new ItemStack(Material.NETHERITE_AXE);
      }

      axeDisplay.setItemStack(axeItem);
      axeDisplay.getPersistentDataContainer().set(this.axeKey, PersistentDataType.BYTE, (byte)1);
      axeDisplay.getPersistentDataContainer().set(new NamespacedKey(this.plugin, "zeus-owner"), PersistentDataType.STRING, player.getUniqueId().toString());
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8F, 1.1F);
      this.startAxeTracking(axeDisplay, player, direction);
   }

   private void orientAxe(ItemDisplay display, double spinRadians) {
      Transformation current = display.getTransformation();
      Quaternionf left = new Quaternionf().rotateY((float)Math.toRadians(90.0)).rotateX((float)spinRadians);
      display.setTransformation(new Transformation(current.getTranslation(), left, current.getScale(), current.getRightRotation()));
   }

   private boolean isBlocked(Location location) {
      Block block = location.getBlock();
      return block.getType().isSolid() && !block.isPassable();
   }

   private LivingEntity findTarget(Location location, UUID ownerId) {
      for (Entity nearby : location.getWorld().getNearbyEntities(location, 0.6, 0.6, 0.6)) {
         if (nearby instanceof LivingEntity living && !nearby.getUniqueId().equals(ownerId)) {
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
                     axe.getWorld().spawnParticle(Particle.CRIT, this.currentLocation, 1, 0.05, 0.05, 0.05, 0.01);
                  }
               } else {
                  axe.remove();
                  AxeOfZeusListener.this.axeTasks.remove(axeUUID);
                  this.cancel();
               }
            } else {
               AxeOfZeusListener.this.axeTasks.remove(axeUUID);
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
      this.plugin.getSoundManager().world(hitLoc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7F, 1.2F);
      if (hitEntity != null) {
         hitEntity.damage(this.ability("throw-damage", 8.0), owner);
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
               if (owner != null && owner.isOnline()) {
                  AxeOfZeusListener.this.giveAxeBack(owner);
               }

               this.cancel();
            } else if (owner != null && owner.isOnline()) {
               Location playerLoc = owner.getEyeLocation();
               Vector toPlayer = playerLoc.toVector().subtract(this.currentLocation.toVector());
               if (toPlayer.length() < 1.0) {
                  axe.remove();
                  AxeOfZeusListener.this.giveAxeBack(owner);
                  AxeOfZeusListener.this.axeTasks.remove(axeUUID);
                  this.cancel();
               } else {
                  Vector movement = toPlayer.normalize().multiply(returnSpeed);
                  this.currentLocation.add(movement);
                  Location facing = this.currentLocation.clone();
                  facing.setDirection(movement);
                  axe.teleport(facing);
                  this.spin = this.spin + spinPerTick;
                  AxeOfZeusListener.this.orientAxe((ItemDisplay)axe, this.spin);
                  axe.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, this.currentLocation, 1, 0.05, 0.05, 0.05, 0.01);
               }
            } else {
               axe.remove();
               AxeOfZeusListener.this.axeTasks.remove(axeUUID);
               this.cancel();
            }
         }
      };
      task.runTaskTimer(this.plugin, 1L, 1L);
      this.axeTasks.put(axeUUID, task);
   }

   private void giveAxeBack(Player player) {
      ItemStack axe = this.weaponManager.createWeapon("axe-of-zeus");
      if (axe != null && !axe.getType().isAir()) {
         Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack[]{axe});
         if (!leftover.isEmpty()) {
            player.getWorld().dropItem(player.getLocation(), leftover.get(0));
            player.sendActionBar(Component.text("Axe of Zeus dropped - inventory full", NamedTextColor.YELLOW));
         }

         this.plugin.getSoundManager().self(player, Sound.ITEM_TRIDENT_RETURN, 0.6F, 1.2F);
      }
   }

   private void disableShield(Player victim) {
      victim.setCooldown(Material.SHIELD, this.abilityInt("shield-break-duration", 160));
      this.plugin.getSoundManager().world(victim.getLocation(), Sound.ITEM_SHIELD_BREAK, 0.7F, 1.0F);
   }

   @EventHandler
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player damager) {
         ItemStack weapon = damager.getInventory().getItemInMainHand();
         if (this.weaponManager.isLegendaryWeapon(weapon) && "axe-of-zeus".equals(this.weaponManager.getWeaponKey(weapon))) {
            this.handleMeleeAttack(damager, event);
         }
      }
   }

   private void handleMeleeAttack(Player damager, EntityDamageByEntityEvent event) {
      if (!(damager.getAttackCooldown() <= 0.9F)) {
         if (event.getEntity() instanceof LivingEntity victim && Math.random() < this.ability("melee-lightning-chance", 0.1)) {
            Location loc = victim.getLocation();
            loc.getWorld().strikeLightningEffect(loc);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5F, 1.5F);
            loc.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, loc, 20, 1.0, 1.0, 1.0, 0.1);
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, this.abilityInt("melee-stun-duration", 20), 2, true, false));
            victim.setVelocity(new Vector(0.0, 0.3, 0.0));
         }

         if (event.getEntity() instanceof Player victim && victim.isBlocking()) {
            this.disableShield(victim);
         }
      }
   }
}
