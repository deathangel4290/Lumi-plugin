package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Chione's Blade.
 *
 * Freeze: 5 uninterrupted full-swing hits on a player freeze them for 1.5s, then they are immune
 * to being frozen again for 5s so nobody is chain-frozen.
 *
 * The freeze used to pin the target by resetting their velocity every tick. The client kept
 * trying to jump against that, and the moment the pin released the queued jump fired -- which is
 * what sent people flying. It now zeroes the target's movement-speed and jump-strength attributes
 * instead, so the client itself cannot move or jump and there is nothing to release. The vanilla
 * powder-snow overlay is shown on top so it reads instantly.
 *
 * Ice Wave leaves frost on the ground for a few seconds; the wielder gets the cold-ground speed
 * boost on it, so the passive works in any arena, not just snowy ones.
 */
public class ChionesBladeListener extends AbstractWeaponListener {
   private static final String COMBO_KEY = "chiones-freeze";
   private static final String WAVE_COOLDOWN_KEY = "chiones-ice-wave";
   private final NamespacedKey freezeSpeedKey;
   private final NamespacedKey freezeJumpKey;
   private final Map<UUID, BukkitRunnable> frozen = new HashMap<>();
   private final Map<UUID, Long> immuneUntil = new HashMap<>();
   private final List<Frost> frost = new ArrayList<>();

   private record Frost(World world, double x, double y, double z, double radius, long expiresAt) {
      boolean contains(Location loc) {
         if (!loc.getWorld().equals(this.world) || Math.abs(loc.getY() - this.y) > 1.6) {
            return false;
         } else {
            double dx = loc.getX() - this.x;
            double dz = loc.getZ() - this.z;
            return dx * dx + dz * dz <= this.radius * this.radius;
         }
      }
   }

   public ChionesBladeListener(LegendaryWeapons plugin) {
      super(plugin);
      this.freezeSpeedKey = new NamespacedKey(plugin, "chione_freeze_speed");
      this.freezeJumpKey = new NamespacedKey(plugin, "chione_freeze_jump");
      (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            ChionesBladeListener.this.frostTick(this.ticks++ % 2 == 0);
         }
      }).runTaskTimer(this.plugin, 10L, 10L);
   }

   @Override
   public String weaponKey() {
      return "chiones-blade";
   }

   @Override
   public Component hud(Player player) {
      int required = this.abilityInt("combo-hits-required", 5);
      int combo = this.plugin.getComboTracker().get(player, COMBO_KEY);
      return HudManager.cooldown("Ice Wave", this.cooldownLeft(player, WAVE_COOLDOWN_KEY))
         .append(Component.text("  Frost ", NamedTextColor.GRAY))
         .append(Component.text(Math.min(combo, required) + "/" + required, NamedTextColor.AQUA));
   }

   private void frostTick(boolean render) {
      long now = System.currentTimeMillis();
      Iterator<Frost> it = this.frost.iterator();

      while (it.hasNext()) {
         Frost patch = it.next();
         if (patch.expiresAt() <= now) {
            it.remove();
         } else if (render) {
            ParticleManager.spawn(Particle.SNOWFLAKE, new Location(patch.world(), patch.x(), patch.y() + 0.1, patch.z()), 2, patch.radius() / 2.0, 0.02, patch.radius() / 2.0, 0.0);
         }
      }

      int amplifier = this.abilityInt("cold-ground-speed-amplifier", 1);

      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (this.isHolding(player) && (this.isOnCold(player) || this.onFrost(player))) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30, amplifier, true, false, true));
         }
      }
   }

   private boolean onFrost(Player player) {
      Location loc = player.getLocation();

      for (Frost patch : this.frost) {
         if (patch.contains(loc)) {
            return true;
         }
      }

      return false;
   }

   private boolean isOnCold(Player player) {
      Material below = player.getLocation().subtract(0.0, 0.1, 0.0).getBlock().getType();

      return switch (below) {
         case ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE, SNOW, SNOW_BLOCK, POWDER_SNOW -> true;
         default -> false;
      };
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.isHolding(attacker)) {
         if (event.getEntity() instanceof Player victim && event.getCause() != DamageCause.ENTITY_SWEEP_ATTACK && this.isFullSwing(attacker)) {
            int required = this.abilityInt("combo-hits-required", 5);
            long window = this.abilityLong("combo-window-millis", 3000L);
            int combo = this.plugin.getComboTracker().hit(attacker, COMBO_KEY, window);
            if (combo >= required) {
               this.plugin.getComboTracker().reset(attacker, COMBO_KEY);
               if (this.isImmune(victim)) {
                  HudManager.message(attacker, Component.text(victim.getName() + " is still thawing - immune", NamedTextColor.GRAY));
               } else if (this.canAffect(attacker, victim)) {
                  this.freeze(victim, attacker);
               }
            }
         }
      }
   }

   private boolean isImmune(LivingEntity target) {
      Long until = this.immuneUntil.get(target.getUniqueId());
      return until != null && until > System.currentTimeMillis();
   }

   private void freeze(final LivingEntity target, Player source) {
      final int duration = Math.max(1, this.abilityInt("freeze-duration", 30));
      final UUID id = target.getUniqueId();
      this.unfreeze(target);
      this.immuneUntil.put(id, System.currentTimeMillis() + (duration + this.abilityLong("freeze-immunity", 100L)) * 50L);
      this.addModifier(target, Attribute.MOVEMENT_SPEED, this.freezeSpeedKey);
      this.addModifier(target, Attribute.JUMP_STRENGTH, this.freezeJumpKey);
      Vector v = target.getVelocity();
      target.setVelocity(new Vector(0.0, Math.min(0.0, v.getY()), 0.0));
      final boolean overlay = this.abilities() == null || this.abilities().getBoolean("freeze-overlay", true);
      if (overlay) {
         // Just under fully frozen (140): full frost vignette, but vanilla never deals freeze damage.
         target.setFreezeTicks(Math.min(target.getMaxFreezeTicks() - 1, 139));
         target.lockFreezeTicks(true);
      }

      BukkitRunnable release = new BukkitRunnable() {
         public void run() {
            ChionesBladeListener.this.unfreeze(target);
         }
      };
      this.frozen.put(id, release);
      release.runTaskLater(this.plugin, duration);
      Location loc = target.getLocation().add(0.0, 1.0, 0.0);
      ParticleManager.spawn(Particle.SNOWFLAKE, loc, 16, 0.35, 0.6, 0.35, 0.01);
      ParticleManager.spawn(Particle.BLOCK, loc, 10, 0.3, 0.5, 0.3, 0.0, Material.BLUE_ICE.createBlockData());
      this.plugin.getSoundManager().world(loc, Sound.BLOCK_GLASS_BREAK, 0.7F, 0.6F);
      this.plugin.getSoundManager().world(loc, Sound.ENTITY_PLAYER_HURT_FREEZE, 0.8F, 1.0F);
      if (target instanceof Player frozenPlayer) {
         HudManager.message(frozenPlayer, Component.text("❄ Frozen!", NamedTextColor.AQUA));
      }

      HudManager.message(source, Component.text("Froze " + (target instanceof Player p ? p.getName() : "target"), NamedTextColor.AQUA));
   }

   private void addModifier(LivingEntity target, Attribute attribute, NamespacedKey key) {
      AttributeInstance instance = target.getAttribute(attribute);
      if (instance != null) {
         removeModifier(instance, key);
         instance.addModifier(new AttributeModifier(key, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
      }
   }

   private static void removeModifier(AttributeInstance instance, NamespacedKey key) {
      for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
         if (key.equals(modifier.getKey())) {
            instance.removeModifier(modifier);
         }
      }
   }

   /** Idempotent: safe to call on anyone, frozen or not, online or on their way out. */
   private void unfreeze(LivingEntity target) {
      BukkitRunnable pending = this.frozen.remove(target.getUniqueId());
      if (pending != null) {
         pending.cancel();
      }

      AttributeInstance speed = target.getAttribute(Attribute.MOVEMENT_SPEED);
      if (speed != null) {
         removeModifier(speed, this.freezeSpeedKey);
      }

      AttributeInstance jump = target.getAttribute(Attribute.JUMP_STRENGTH);
      if (jump != null) {
         removeModifier(jump, this.freezeJumpKey);
      }

      if (pending != null) {
         target.lockFreezeTicks(false);
         target.setFreezeTicks(0);
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.unfreeze(event.getEntity());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.unfreeze(event.getPlayer());
   }

   /** Modifiers are saved with the player; clear any left over from a crash or reload mid-freeze. */
   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      this.unfreeze(event.getPlayer());
      event.getPlayer().lockFreezeTicks(false);
   }

   public void cleanup() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         if (this.frozen.containsKey(player.getUniqueId())) {
            this.unfreeze(player);
         }
      }
   }

   public void resetImmunity(Player player) {
      this.immuneUntil.remove(player.getUniqueId());
      this.unfreeze(player);
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else {
         if (this.tryCooldown(player, WAVE_COOLDOWN_KEY, this.abilityLong("ice-wave-cooldown", 240L))) {
            this.iceWave(player);
         }

         return true;
      }
   }

   private void iceWave(final Player player) {
      final double range = this.ability("ice-wave-range", 12.0);
      final double speed = Math.max(0.2, this.ability("ice-wave-speed", 0.9));
      final double width = this.ability("ice-wave-width", 2.0);
      final double damage = this.ability("ice-wave-damage", 0.0);
      final int slowDuration = this.abilityInt("ice-wave-slowness-duration", 80);
      final int slowAmplifier = this.abilityInt("ice-wave-slowness-amplifier", 1);
      final long frostMillis = this.abilityLong("frost-duration", 120L) * 50L;
      Vector flat = player.getLocation().getDirection().setY(0.0);
      final Vector facing = flat.lengthSquared() < 1.0E-4 ? new Vector(0, 0, 1) : flat.normalize();
      final Vector across = new Vector(-facing.getZ(), 0.0, facing.getX()).normalize();
      final Location origin = player.getLocation().add(0.0, 0.3, 0.0);
      com.legendaryweapons.managers.AnimationFx.spikeLine(player.getLocation(), facing, range, speed, Material.PACKED_ICE, false);
      final Set<UUID> hit = new HashSet<>();
      this.plugin.getSoundManager().world(origin, Sound.BLOCK_POWDER_SNOW_BREAK, 0.9F, 0.7F);
      this.plugin.getSoundManager().world(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.6F, 0.6F);
      (new BukkitRunnable() {
            double travelled = 0.0;
            double lastFrost = -10.0;

            public void run() {
               if (this.travelled >= range || !player.isOnline()) {
                  this.cancel();
                  return;
               }

               this.travelled += speed;
               Location front = origin.clone().add(facing.clone().multiply(this.travelled));

               for (double offset = -width; offset <= width; offset += 0.75) {
                  Location point = front.clone().add(across.clone().multiply(offset));
                  ParticleManager.spawn(Particle.SNOWFLAKE, point, 1, 0.05, 0.2, 0.05, 0.01);
               }

               if (this.travelled - this.lastFrost >= width) {
                  this.lastFrost = this.travelled;
                  ChionesBladeListener.this.frost.add(new Frost(front.getWorld(), front.getX(), front.getY() - 0.3, front.getZ(), width, System.currentTimeMillis() + frostMillis));
               }

               for (Entity nearby : front.getWorld().getNearbyEntities(front, width, 2.0, width)) {
                  if (nearby instanceof LivingEntity living && hit.add(nearby.getUniqueId()) && ChionesBladeListener.this.canAffect(player, nearby)) {
                     living.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowDuration, slowAmplifier, true, false));
                     if (damage > 0.0) {
                        living.damage(damage, player);
                     }

                     ParticleManager.spawn(Particle.SNOWFLAKE, living.getLocation().add(0.0, 1.0, 0.0), 10, 0.3, 0.5, 0.3, 0.01);
                  }
               }
            }
         })
         .runTaskTimer(this.plugin, 1L, 1L);
   }
}
