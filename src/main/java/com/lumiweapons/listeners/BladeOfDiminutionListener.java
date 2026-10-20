package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbstractWeaponListener;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Blade of Diminution.
 *
 * Diminish shrinks the WIELDER only: a smaller hitbox to land hits on, paid for with reach that
 * shrinks by the same factor. The passive can still enlarge an enemy you hit (a bigger target).
 *
 * The old version overwrote the SCALE base value and remembered the "original" to put back. Any
 * overlap, death, reload or restore that ran while the player was dead lost that value and left
 * them stuck at the wrong size. Sizes are now attribute MODIFIERS with fixed keys: applying one
 * replaces the previous, removing it is always safe, and they are stripped on join, respawn,
 * death and shutdown, so a player can never be left resized.
 */
public class BladeOfDiminutionListener extends AbstractWeaponListener {
   private static final String COOLDOWN_KEY = "diminution-diminish";
   private final NamespacedKey shrinkKey;
   private final NamespacedKey shrinkReachKey;
   private final NamespacedKey shrinkBlockReachKey;
   private final NamespacedKey enlargeKey;
   private final Map<UUID, Long> shrinkUntil = new HashMap<>();
   private final Map<UUID, Long> enlargeUntil = new HashMap<>();
   private final Map<UUID, Long> enlargeImmuneUntil = new HashMap<>();
   private final Map<UUID, Long> noRoomWarned = new HashMap<>();

   public BladeOfDiminutionListener(LumiWeapons plugin) {
      super(plugin);
      this.shrinkKey = LumiWeapons.key("size_diminish");
      this.shrinkReachKey = LumiWeapons.key("size_diminish_reach");
      this.shrinkBlockReachKey = LumiWeapons.key("size_diminish_block_reach");
      this.enlargeKey = LumiWeapons.key("size_enlarge");
      (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            BladeOfDiminutionListener.this.tick(this.ticks++ % 4 == 0);
         }
      }).runTaskTimer(plugin, 5L, 5L);
   }

   @Override
   public String weaponKey() {
      return "blade-of-diminution";
   }

   @Override
   public Component hud(Player player) {
      Component line = HudManager.cooldown(player, "Diminish", COOLDOWN_KEY);
      Long until = this.shrinkUntil.get(player.getUniqueId());
      if (until != null) {
         long left = until - System.currentTimeMillis();
         line = line.append(Component.text(left > 0L ? "  small " + HudManager.seconds(left / 1000.0) : "  growing back...", NamedTextColor.LIGHT_PURPLE));
      }

      return line;
   }

   private void tick(boolean speedPass) {
      long now = System.currentTimeMillis();
      Iterator<Entry<UUID, Long>> shrinking = this.shrinkUntil.entrySet().iterator();

      while (shrinking.hasNext()) {
         Entry<UUID, Long> entry = shrinking.next();
         if (entry.getValue() <= now) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
               shrinking.remove();
            } else if (this.hasRoomToGrow(player) || now - entry.getValue() > this.abilityLong("regrow-timeout", 200L) * 50L) {
               this.removeShrink(player);
               shrinking.remove();
               ParticleManager.spawn(Particle.WITCH, player.getLocation().add(0.0, 1.0, 0.0), 10, 0.3, 0.5, 0.3, 0.02);
               com.lumiweapons.managers.SpriteFx.wave(player.getLocation(), org.bukkit.Color.fromRGB(190, 90, 255), 1.6, 5);
               this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5F, 0.8F);
            } else {
               Long warned = this.noRoomWarned.get(player.getUniqueId());
               if (warned == null || now - warned > 3000L) {
                  this.noRoomWarned.put(player.getUniqueId(), now);
                  HudManager.message(player, Component.text("No room to grow back - move somewhere open", NamedTextColor.LIGHT_PURPLE));
               }
            }
         }
      }

      Iterator<Entry<UUID, Long>> growing = this.enlargeUntil.entrySet().iterator();

      while (growing.hasNext()) {
         Entry<UUID, Long> entry = growing.next();
         if (entry.getValue() <= now) {
            growing.remove();
            if (Bukkit.getEntity(entry.getKey()) instanceof LivingEntity living) {
               remove(living, Attribute.SCALE, this.enlargeKey);
               com.lumiweapons.managers.SpriteFx.burst(living, 0.5, org.bukkit.Color.fromRGB(190, 90, 255), 1.2, 1, 0, 4);
            }
         }
      }

      if (speedPass) {
         int amplifier = this.abilityInt("hold-speed-amplifier", 0);

         for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            double strength = this.passive(player);
            if (strength > 0.0) {
               player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, com.lumiweapons.abilities.Passives.amplifier(amplifier, strength), true, false, true));
            }
         }
      }
   }

   /** Full-size players are about 1.8 tall; check the blocks the regrown body would occupy. */
   private boolean hasRoomToGrow(Player player) {
      Location feet = player.getLocation();

      for (double y = 0.2; y <= 1.8; y += 0.8) {
         if (!feet.clone().add(0.0, y, 0.0).getBlock().isPassable()) {
            return false;
         }
      }

      return true;
   }

   private static void set(LivingEntity entity, Attribute attribute, NamespacedKey key, double multiplier) {
      AttributeInstance instance = entity.getAttribute(attribute);
      if (instance != null) {
         remove(instance, key);
         instance.addModifier(new AttributeModifier(key, multiplier - 1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
      }
   }

   private static void remove(LivingEntity entity, Attribute attribute, NamespacedKey key) {
      AttributeInstance instance = entity.getAttribute(attribute);
      if (instance != null) {
         remove(instance, key);
      }
   }

   private static void remove(AttributeInstance instance, NamespacedKey key) {
      for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
         if (key.equals(modifier.getKey())) {
            instance.removeModifier(modifier);
         }
      }
   }

   private void removeShrink(LivingEntity entity) {
      remove(entity, Attribute.SCALE, this.shrinkKey);
      remove(entity, Attribute.ENTITY_INTERACTION_RANGE, this.shrinkReachKey);
      remove(entity, Attribute.BLOCK_INTERACTION_RANGE, this.shrinkBlockReachKey);
   }

   /** Strip every size modifier this weapon can apply. Safe to call on anyone at any time. */
   public void resetSize(LivingEntity entity) {
      this.removeShrink(entity);
      remove(entity, Attribute.SCALE, this.enlargeKey);
      this.shrinkUntil.remove(entity.getUniqueId());
      this.enlargeUntil.remove(entity.getUniqueId());
   }

   /**
    * Older versions changed the SCALE base value directly. Anyone still carrying a non-default base
    * from that is put back to normal size the next time they join.
    */
   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      this.resetSize(event.getPlayer());
      AttributeInstance scale = event.getPlayer().getAttribute(Attribute.SCALE);
      if (scale != null && scale.getBaseValue() != 1.0 && this.plugin.getConfig().getBoolean("weapons.blade-of-diminution.abilities.fix-legacy-scale", true)) {
         scale.setBaseValue(1.0);
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.resetSize(event.getEntity());
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent event) {
      this.resetSize(event.getPlayer());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.resetSize(event.getPlayer());
   }

   public void cleanup() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         this.resetSize(player);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
      if (com.lumiweapons.abilities.AbilityDamage.active() && event.getDamager() instanceof org.bukkit.entity.Player) {
         return;                                      // an ability's own damage, not a swing
      }

      // Players only: a mob whose chunk unloaded mid-enlarge kept the giant size saved on it for good.
      double strength = event.getDamager() instanceof Player hitter ? this.passive(hitter) : 0.0;
      if (event.getDamager() instanceof Player attacker && strength > 0.0 && event.getEntity() instanceof Player victim) {
         long now = System.currentTimeMillis();
         Long immune = this.enlargeImmuneUntil.get(victim.getUniqueId());
         if ((immune == null || immune <= now) && !(Math.random() >= this.ability("enlarge-chance", 0.12) * strength) && this.canAffect(attacker, victim)) {
            long duration = this.abilityLong("enlarge-duration", 100L) * 50L;
            this.enlargeImmuneUntil.put(victim.getUniqueId(), now + duration + this.abilityLong("enlarge-target-cooldown", 200L) * 50L);
            set(victim, Attribute.SCALE, this.enlargeKey, clampScale(this.ability("enlarge-scale", 1.3)));
            this.enlargeUntil.put(victim.getUniqueId(), now + duration);
            ParticleManager.spawn(Particle.WITCH, victim.getLocation().add(0.0, 1.0, 0.0), 12, 0.4, 0.6, 0.4, 0.02);
            this.plugin.getSoundManager().world(victim.getLocation(), Sound.ENTITY_PUFFER_FISH_BLOW_UP, 0.8F, 0.8F);
            com.lumiweapons.managers.AnimationFx.ring(victim.getLocation(), Material.PURPLE_STAINED_GLASS, 0.3, 1.8, 8, true);
            com.lumiweapons.managers.SpriteFx.burst(victim, 0.6, org.bukkit.Color.fromRGB(190, 90, 255), 2.0, 2, 1, 5);
            if (victim instanceof Player enlarged) {
               HudManager.message(enlarged, Component.text("You have been enlarged!", NamedTextColor.LIGHT_PURPLE));
            }
         }
      }
   }

   private static double clampScale(double scale) {
      return Math.max(0.2, Math.min(3.0, scale));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (this.shrinkUntil.containsKey(player.getUniqueId())) {
         HudManager.message(player, Component.text("You are already diminished.", NamedTextColor.GRAY));
         return true;
      } else {
         if (this.tryCooldown(player, COOLDOWN_KEY, this.abilityLong("diminish-cooldown", 400L))) {
            double scale = clampScale(this.ability("diminish-scale", 0.6));
            set(player, Attribute.SCALE, this.shrinkKey, scale);
            if (this.abilities() == null || this.abilities().getBoolean("scale-reach", true)) {
               set(player, Attribute.ENTITY_INTERACTION_RANGE, this.shrinkReachKey, scale);
               set(player, Attribute.BLOCK_INTERACTION_RANGE, this.shrinkBlockReachKey, scale);
            }

            this.shrinkUntil.put(player.getUniqueId(), System.currentTimeMillis() + this.abilityLong("diminish-duration", 160L) * 50L);
            Location loc = player.getLocation();
            ParticleManager.spawn(Particle.WITCH, loc.clone().add(0.0, 0.8, 0.0), 16, 0.4, 0.6, 0.4, 0.03);
            com.lumiweapons.managers.AnimationFx.ring(loc, Material.PURPLE_STAINED_GLASS, 1.8, 0.2, 10, true);
            com.lumiweapons.managers.SpriteFx.burst(loc.clone().add(0.0, 0.8, 0.0), org.bukkit.Color.fromRGB(190, 90, 255), 1.6, 1, 1, 6);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.5F, 1.8F);
            this.plugin.getSoundManager().world(loc, Sound.ENTITY_PUFFER_FISH_BLOW_OUT, 0.8F, 1.3F);
            HudManager.message(player, Component.text("Diminished - smaller, and so is your reach", NamedTextColor.LIGHT_PURPLE));
         }

         return true;
      }
   }

   /** Used by the testing commands. */
   public boolean isShrunk(Entity entity) {
      return this.shrinkUntil.containsKey(entity.getUniqueId());
   }
}
