package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Oculus -- the staff of the eclipse eye.
 *
 *   Passive, Watcher: the eye turns toward the nearest enemy you can actually see, within
 *     watch-radius. It names them on your action bar. Line of sight only: nothing through walls,
 *     nothing across the map.
 *   Ability, Eye Beam: the eye opens (a short charge-up anyone near can see and hear), then a
 *     focused beam down your aim. The first enemy in its path takes moderate damage and is slowed.
 *     Step out of the line and it misses.
 *   Ultimate, Judgement of Oculus: a giant spectral eye opens over the spot you look at, the area
 *     it will strike glows on the ground, and after a delay it fires down into it. Anyone still
 *     inside takes heavy damage. Blocks are never harmed.
 */
public class OculusListener extends UltimateWeapon {
   public static final String KEY = "oculus";
   private static final String COOLDOWN_KEY = "oculus-eye-beam";
   private static final Color FLAME = Color.fromRGB(0xFF8A1F);
   private static final Color CORONA = Color.fromRGB(0xFFD27A);
   private final Set<UUID> charging = new HashSet<>();
   private final Set<ItemDisplay> eyes = new HashSet<>();

   public OculusListener(LumiWeapons plugin) {
      super(plugin);
      // Watcher from the off-hand: the eye still names who it sees (the main weapon's HUD comes first)
      plugin.getHudManager().register(player -> {
         if (this.isHolding(player) || com.lumiweapons.abilities.Passives.strength(plugin, player, KEY) <= 0.0) {
            return null;
         }

         Player seen = this.watched(player);
         return seen == null ? null : Component.text("👁 ", NamedTextColor.GOLD)
            .append(Component.text(plugin.getEventManager().publicName(seen) + " " + (int)seen.getLocation().distance(player.getLocation()) + "m", NamedTextColor.WHITE));
      });
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Judgement";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.GOLD;
   }

   // ------------------------------------------------------------------ Watcher

   /** The nearest enemy within watch-radius this player can see right now, or null. */
   private Player watched(Player player) {
      double radius = this.ability("watch-radius", 16.0);
      Player best = null;
      double bestDistance = radius * radius;
      for (Player other : player.getWorld().getPlayers()) {
         if (other.equals(player) || other.getGameMode() == GameMode.SPECTATOR || other.isDead()
            || other.hasPotionEffect(PotionEffectType.INVISIBILITY) || !player.canSee(other) || !this.canAffect(player, other)) {
            continue;
         }

         double distance = other.getLocation().distanceSquared(player.getLocation());
         if (distance <= bestDistance && player.hasLineOfSight(other)) {
            bestDistance = distance;
            best = other;
         }
      }

      return best;
   }

   @Override
   public Component hud(Player player) {
      Component out = HudManager.cooldown(player, "Eye Beam", COOLDOWN_KEY);
      Player seen = this.watched(player);
      if (seen != null) {
         out = out.append(Component.text("  👁 ", NamedTextColor.GOLD))
            .append(Component.text(this.plugin.getEventManager().publicName(seen) + " " + (int)seen.getLocation().distance(player.getLocation()) + "m", NamedTextColor.WHITE));
      }

      return out.append(Component.text("  ")).append(this.ultimateHud(player));
   }

   @Override
   protected void firstAbility(Player player) {
      if (!this.charging.contains(player.getUniqueId()) && this.tryCooldown(player, COOLDOWN_KEY, this.abilityLong("beam-cooldown", 260L))) {
         this.chargeBeam(player);
         this.chargeFromAbility(player);
      }
   }

   @Override
   protected void ultimate(Player player) {
      this.judgement(player);
   }

   // ------------------------------------------------------------------ Eye Beam

   private Location eye(Player player) {
      return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.8)).subtract(0.0, 0.25, 0.0);
   }

   private void chargeBeam(Player player) {
      int charge = Math.max(4, this.abilityInt("beam-charge", 15));
      this.charging.add(player.getUniqueId());
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, 1.0F, 1.8F);
      for (int tick = 0; tick < charge; tick += 2) {
         final int at = tick;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            if (player.isOnline() && !player.isDead()) {
               Location eye = this.eye(player);
               double spread = 0.6 * (1.0 - at / (double)charge);
               ParticleManager.showcase(Particle.DUST, eye, 4, spread, spread, spread, 0.0, new Particle.DustOptions(CORONA, 0.7F));
            }
         }, tick);
      }

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         this.charging.remove(player.getUniqueId());
         if (player.isOnline() && !player.isDead() && this.isHolding(player)) {
            this.fireBeam(player);
         }
      }, charge);
   }

   private void fireBeam(Player player) {
      double range = this.ability("beam-range", 24.0);
      Location from = this.eye(player);
      Vector direction = player.getEyeLocation().getDirection();
      RayTraceResult hit = player.getWorld().rayTrace(from, direction, range, FluidCollisionMode.NEVER, true, 0.35,
         entity -> entity instanceof LivingEntity && !entity.equals(player) && this.canAffect(player, entity));
      Location end = hit == null ? from.clone().add(direction.clone().multiply(range)) : hit.getHitPosition().toLocation(player.getWorld());
      this.drawBeam(from, end, 0.25, FLAME, 0.9F);
      this.plugin.getSoundManager().world(from, Sound.ENTITY_GUARDIAN_ATTACK, 1.0F, 1.6F);
      this.plugin.getSoundManager().world(end, Sound.BLOCK_FIRE_EXTINGUISH, 0.6F, 1.4F);
      if (hit != null && hit.getHitEntity() instanceof LivingEntity target) {
         AbilityDamage.hit(player, target, this.ability("beam-damage", 6.0), this.ability("beam-armour-pierce", 0.2), from, "{victim} was seen by {killer}'s Oculus");
         target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, this.abilityInt("beam-slow-duration", 40), 1, true, false, true));
         SpriteFx.burst(target, 1.0, FLAME, 1.4, 1, 1, 4);
      }
   }

   private void drawBeam(Location from, Location to, double step, Color colour, float size) {
      Vector line = to.toVector().subtract(from.toVector());
      double length = line.length();
      if (length < 0.01) {
         return;
      }

      Vector unit = line.multiply(1.0 / length);
      Particle.DustOptions dust = new Particle.DustOptions(colour, size);
      for (double d = 0.0; d <= length; d += step) {
         Location at = from.clone().add(unit.clone().multiply(d));
         ParticleManager.showcase(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0.0, dust);
         if (((int)(d / step)) % 6 == 0) {
            ParticleManager.showcase(Particle.END_ROD, at, 1, 0.0, 0.0, 0.0, 0.0, null);
         }
      }
   }

   // ------------------------------------------------------------------ Judgement of Oculus

   private ItemStack eyeItem() {
      ItemStack eye = new ItemStack(Material.ENDER_EYE);
      String model = this.plugin.getConfig().getString("weapons." + KEY + ".eye-model", "");
      NamespacedKey key = model == null || model.isBlank() ? null : NamespacedKey.fromString(model.trim());
      if (key != null) {
         eye.editMeta(meta -> meta.setItemModel(key));
      }

      return eye;
   }

   /** Where the eye will strike: the block you look at, else the ground straight ahead. */
   private Location target(Player player) {
      double range = this.ability("judgement-range", 40.0);
      RayTraceResult look = player.rayTraceBlocks(range, FluidCollisionMode.NEVER);
      Location aim = look != null ? look.getHitPosition().toLocation(player.getWorld())
         : player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(Math.min(range, 20.0)));
      RayTraceResult ground = player.getWorld().rayTraceBlocks(aim.clone().add(0.0, 1.0, 0.0), new Vector(0, -1, 0), 40.0, FluidCollisionMode.NEVER, true);
      return ground != null ? ground.getHitPosition().toLocation(player.getWorld()) : aim;
   }

   private void judgement(Player player) {
      Location ground = this.target(player);
      double radius = this.ability("judgement-radius", 5.0);
      int delay = Math.max(20, this.abilityInt("judgement-delay", 50));
      World world = ground.getWorld();
      Location sky = ground.clone().add(0.0, this.ability("judgement-eye-height", 12.0), 0.0);
      this.ulting.add(player.getUniqueId());
      ItemDisplay eye = world.spawn(sky, ItemDisplay.class, display -> {
         display.setPersistent(false);
         display.setItemStack(this.eyeItem());
         display.setBillboard(Display.Billboard.CENTER);
         display.setBrightness(new Display.Brightness(15, 15));
         display.setGlowing(true);
         display.setGlowColorOverride(FLAME);
         display.setViewRange(4.0F);
         display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.1F), new Quaternionf()));
      });
      this.eyes.add(eye);
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> grow(eye, 6.0F, 12), 2L);
      SpriteFx.Held ring = SpriteFx.hold(ground.clone().add(0.0, 0.06, 0.0), "vfx/ritual_circle_outer", FLAME, false, radius * 2.0 / 0.965, delay + 20);
      this.plugin.getSoundManager().world(ground, Sound.BLOCK_END_PORTAL_SPAWN, 0.8F, 1.5F);
      this.plugin.getSoundManager().world(sky, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0F, 0.8F);
      HudManager.message(player, Component.text("The eye opens...", NamedTextColor.GOLD));

      // the warning: the edge of the strike traced on the ground, and a thin line from the eye
      for (int tick = 0; tick < delay; tick += 4) {
         final int at = tick;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            for (int i = 0; i < 28; i++) {
               double a = Math.PI * 2.0 * i / 28.0 + at * 0.03;
               ParticleManager.showcase(Particle.FLAME, ground.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0.0, 0.02, 0.0, 0.0, null);
            }

            this.drawBeam(sky, ground, 1.2, CORONA, 0.6F);
         }, tick);
      }

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         this.ulting.remove(player.getUniqueId());
         this.strike(player, ground, sky, radius);
         if (ring != null) {
            ring.release(6);
         }

         grow(eye, 0.0F, 10);
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            this.eyes.remove(eye);
            if (eye.isValid()) {
               eye.remove();
            }
         }, 12L);
      }, delay);
   }

   private static void grow(ItemDisplay display, float size, int ticks) {
      if (display.isValid()) {
         display.setInterpolationDelay(0);
         display.setInterpolationDuration(ticks);
         display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size), new Quaternionf()));
      }
   }

   private void strike(Player player, Location ground, Location sky, double radius) {
      for (double r = 0.0; r <= radius; r += radius / 3.0) {
         for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2.0 * i / 8.0;
            this.drawBeam(sky, ground.clone().add(Math.cos(a) * r * 0.5, 0.0, Math.sin(a) * r * 0.5), 0.5, FLAME, 1.4F);
         }
      }

      ParticleManager.showcase(Particle.FLASH, ground.clone().add(0.0, 1.0, 0.0), 1, 0.0, 0.0, 0.0, 0.0, CORONA);
      ParticleManager.showcase(Particle.LAVA, ground, 20, radius / 2.0, 0.2, radius / 2.0, 0.0, null);
      SpriteFx.wave(ground, FLAME, radius, 8);
      this.plugin.getSoundManager().world(ground, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.2F, 0.8F);
      this.plugin.getSoundManager().world(ground, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8F, 1.4F);
      if (!player.isOnline()) {
         return;
      }

      for (LivingEntity target : this.around(player, ground, radius)) {
         double dy = target.getLocation().getY() - ground.getY();
         if (dy > -2.0 && dy < 6.0) {
            AbilityDamage.hit(player, target, this.ability("judgement-damage", 14.0), this.ability("judgement-armour-pierce", 0.3), ground,
               "{victim} was judged by {killer}'s Oculus");
         }
      }
   }

   /** Plugin disable: no giant eyes left hanging in the sky. */
   public void cleanup() {
      for (ItemDisplay eye : this.eyes) {
         if (eye.isValid()) {
            eye.remove();
         }
      }

      this.eyes.clear();
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.charging.remove(event.getPlayer().getUniqueId());
   }
}
