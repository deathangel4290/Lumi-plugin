package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Chakram of Chaos -- mid-range zoning. Like the Glaive, it never leaves your hand: an echo of it
 * flies. The way back matters as much as the way out: it hits on both, so good positioning hits
 * the same enemy twice.
 *
 *   Passive, Returning Edge: a chakram that comes home after hitting someone sharpens your next
 *     melee hit a little.
 *   Ability 1, Spiral Throw: out along your aim and back to you, cutting on both legs.
 *   Ability 2, Orbit: the chakram circles you for a few seconds, cutting anyone it touches.
 *   Ultimate, Chaos Storm: spectral chakrams fly out in every direction and come back.
 */
public class ChakramListener extends UltimateWeapon {
   public static final String KEY = "chakram-of-chaos";
   private static final String THROW_KEY = "chakram-spiral-throw";
   private static final String ORBIT_KEY = "chakram-orbit";
   private static final Color CHAOS = Color.fromRGB(0xD0306A);
   private static final Color SPECTRAL = Color.fromRGB(0x8F7BFF);
   /** player -> until when their next melee hit carries the Returning Edge bonus (ms). */
   private final Map<UUID, Long> edge = new HashMap<>();
   private final Set<ItemDisplay> flying = new HashSet<>();

   public ChakramListener(LumiWeapons plugin) {
      super(plugin);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Chaos Storm";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.LIGHT_PURPLE;
   }

   @Override
   public Component hud(Player player) {
      Component out = HudManager.cooldown(player, "Spiral Throw", THROW_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Orbit", ORBIT_KEY));
      Long until = this.edge.get(player.getUniqueId());
      if (until != null && until > System.currentTimeMillis()) {
         out = out.append(Component.text("  ◈ Edge", NamedTextColor.LIGHT_PURPLE));
      }

      return out.append(Component.text("  ")).append(this.ultimateHud(player));
   }

   // ------------------------------------------------------------------ Returning Edge

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onMelee(EntityDamageByEntityEvent event) {
      if (AbilityDamage.active() || !(event.getDamager() instanceof Player player) || !this.isHolding(player)) {
         return;
      }

      Long until = this.edge.remove(player.getUniqueId());
      if (until != null && until > System.currentTimeMillis()) {
         event.setDamage(event.getDamage() + this.ability("edge-bonus-damage", 2.0));
         ParticleManager.spawn(Particle.DUST, event.getEntity().getLocation().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.0, new Particle.DustOptions(CHAOS, 1.1F));
      }
   }

   @EventHandler
   public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
      this.edge.remove(event.getPlayer().getUniqueId());
   }

   // ------------------------------------------------------------------ the flying chakram

   private ItemDisplay spawnDisc(Player player, Location at, boolean spectral) {
      ItemStack look = player.getInventory().getItemInMainHand().clone();
      look.setAmount(1);
      ItemDisplay disc = at.getWorld().spawn(at, ItemDisplay.class, display -> {
         display.setPersistent(false);
         display.setItemStack(look);
         display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setTeleportDuration(1);
         if (spectral) {
            display.setGlowing(true);
            display.setGlowColorOverride(SPECTRAL);
         }
      });
      this.flying.add(disc);
      return disc;
   }

   /** Lie the disc flat and spin it. */
   private static void spin(ItemDisplay disc, double angle) {
      disc.setInterpolationDelay(0);
      disc.setInterpolationDuration(1);
      disc.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float)angle).rotateX((float)(Math.PI / 2.0)), new Vector3f(0.9F), new Quaternionf()));
   }

   /**
    * Out `distance` along `direction` and back to the thrower, cutting each enemy once per leg. A
    * wall turns it back early. If it hit anyone, the Returning Edge sharpens the next melee hit.
    */
   private void throwDisc(Player player, Vector direction, double distance, double damage, boolean spectral) {
      Vector heading = direction.clone().normalize();
      Location at = player.getEyeLocation().subtract(0, 0.4, 0);
      ItemDisplay disc = this.spawnDisc(player, at, spectral);
      double speed = this.ability("throw-speed", 1.1);
      Set<UUID> out = new HashSet<>();
      Set<UUID> back = new HashSet<>();
      boolean[] returning = {false};
      boolean[] struck = {false};
      double[] travelled = {0.0};
      int[] tick = {0};
      Particle.DustOptions trail = new Particle.DustOptions(spectral ? SPECTRAL : CHAOS, 0.8F);
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         if (!player.isOnline() || !disc.isValid() || tick[0]++ > 120 || !disc.getWorld().equals(player.getWorld())) {
            this.drop(disc);
            task.cancel();
            return;
         }

         Location now = disc.getLocation();
         if (!returning[0]) {
            Location next = now.clone().add(heading.clone().multiply(speed));
            travelled[0] += speed;
            if (travelled[0] >= distance || !next.getBlock().isPassable()) {
               returning[0] = true;
            } else {
               disc.teleport(next);
            }
         } else {
            Vector home = player.getEyeLocation().subtract(0, 0.4, 0).toVector().subtract(now.toVector());
            if (home.length() < 1.2) {
               if (struck[0] && !spectral) {
                  this.edge.put(player.getUniqueId(), System.currentTimeMillis() + this.abilityLong("edge-duration", 100L) * 50L);
                  this.plugin.getSoundManager().self(player, Sound.ITEM_TRIDENT_RETURN, 0.8F, 1.6F);
               }

               this.drop(disc);
               task.cancel();
               return;
            }

            disc.teleport(now.clone().add(home.normalize().multiply(Math.min(speed * 1.2, home.length()))));
         }

         spin(disc, tick[0] * 0.9);
         ParticleManager.spawn(Particle.DUST, disc.getLocation(), 1, 0.05, 0.05, 0.05, 0.0, trail);
         if (tick[0] % 4 == 0) {
            this.plugin.getSoundManager().world(disc.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.3F, 1.8F);
         }

         Set<UUID> leg = returning[0] ? back : out;
         for (LivingEntity target : this.around(player, disc.getLocation(), 1.0)) {
            if (Math.abs(target.getLocation().getY() + 1.0 - disc.getLocation().getY()) < 1.6 && leg.add(target.getUniqueId())) {
               AbilityDamage.hit(player, target, damage, 0.0, disc.getLocation(), "{victim} was cut down by {killer}'s Chakram of Chaos");
               ParticleManager.spawn(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 1, 0, 0, 0, 0);
               struck[0] = true;
            }
         }
      }, 1L, 1L);
   }

   private void drop(ItemDisplay disc) {
      this.flying.remove(disc);
      if (disc.isValid()) {
         disc.remove();
      }
   }

   /** Plugin disable: no chakrams left in the air. */
   public void cleanup() {
      for (ItemDisplay disc : new HashSet<>(this.flying)) {
         this.drop(disc);
      }
   }

   // ------------------------------------------------------------------ Spiral Throw

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, THROW_KEY, this.abilityLong("throw-cooldown", 160L))) {
         return;
      }

      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1.0F, 1.4F);
      this.throwDisc(player, player.getEyeLocation().getDirection(), this.ability("throw-distance", 12.0), this.ability("throw-damage", 5.0), false);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Orbit

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, ORBIT_KEY, this.abilityLong("orbit-cooldown", 300L))) {
         return;
      }

      int duration = Math.max(20, this.abilityInt("orbit-duration", 100));
      double radius = this.ability("orbit-radius", 2.0);
      long rehit = this.abilityLong("orbit-hit-interval", 15L) * 50L;
      ItemDisplay disc = this.spawnDisc(player, player.getLocation().add(radius, 1.0, 0), false);
      Map<UUID, Long> lastHit = new HashMap<>();
      int[] tick = {0};
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8F, 1.6F);
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         if (!player.isOnline() || player.isDead() || !disc.isValid() || tick[0]++ > duration) {
            this.drop(disc);
            task.cancel();
            return;
         }

         double a = tick[0] * 0.35;
         Location at = player.getLocation().add(Math.cos(a) * radius, 1.0, Math.sin(a) * radius);
         disc.teleport(at);
         spin(disc, tick[0] * 0.9);
         ParticleManager.spawn(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0.0, new Particle.DustOptions(CHAOS, 0.8F));
         long now = System.currentTimeMillis();
         for (LivingEntity target : this.around(player, at, 1.0)) {
            if (now - lastHit.getOrDefault(target.getUniqueId(), 0L) >= rehit) {
               lastHit.put(target.getUniqueId(), now);
               AbilityDamage.hit(player, target, this.ability("orbit-damage", 2.5), 0.0, at, "{victim} was cut down by {killer}'s Chakram of Chaos");
            }
         }
      }, 1L, 1L);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Chaos Storm

   @Override
   protected void ultimate(Player player) {
      int count = Math.max(2, this.abilityInt("storm-chakrams", 6));
      double distance = this.ability("storm-distance", 9.0);
      double damage = this.ability("storm-damage", 4.0);
      Vector base = facing(player);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_EVOKER_CAST_SPELL, 1.0F, 1.2F);
      HudManager.message(player, Component.text("Chaos Storm!", NamedTextColor.LIGHT_PURPLE));
      for (int i = 0; i < count; i++) {
         Vector direction = base.clone().rotateAroundY(Math.PI * 2.0 * i / count);
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline()) {
               this.throwDisc(player, direction, distance, damage, true);
            }
         }, i);
      }
   }
}
