package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Horizon -- the celestial longbow.
 *
 *   Passive, True Aim: hold a full draw while standing still and the shot focuses: a little more
 *     arrow speed the longer you hold, and at full focus a small damage bonus. Moving resets it.
 *     The arrow never homes.
 *   Ability, Piercing Arrow: a fast glowing arrow, no draw needed. Bonus damage, passes through
 *     one enemy, but a raised shield facing it stops it.
 *   Ultimate, Heaven's Fall: you charge one enormous arrow while the spot it will land on glows,
 *     then it falls from the sky: heavy damage in the centre, less toward the edge. Only people the
 *     blast can reach are hurt, so cover works, and nothing is destroyed.
 */
public class HorizonListener extends UltimateWeapon {
   public static final String KEY = "horizon";
   private static final String COOLDOWN_KEY = "horizon-piercing-arrow";
   private static final Color STAR = Color.fromRGB(0x4B4BFF);
   private static final Color GOLD = Color.fromRGB(0xF2C45A);
   private final NamespacedKey focusKey;
   private final NamespacedKey pierceKey;
   private final NamespacedKey shotKey;
   private final Map<UUID, Integer> focus = new HashMap<>();
   private final Map<UUID, Location> lastSpot = new HashMap<>();
   private final Set<ItemDisplay> falling = new HashSet<>();

   public HorizonListener(LumiWeapons plugin) {
      super(plugin);
      this.focusKey = LumiWeapons.key("horizon-focus");
      this.pierceKey = LumiWeapons.key("horizon-pierce");
      this.shotKey = LumiWeapons.key("horizon-shot");
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::focusTick, 2L, 2L);
      // off-hand True Aim: show the focus while a plain bow is drawn
      plugin.getHudManager().register(player -> {
         int held = this.focus.getOrDefault(player.getUniqueId(), 0);
         return this.isHolding(player) || held <= 0 ? null : HudManager.meter("Focus", held / (double)this.focusTicks(), NamedTextColor.AQUA);
      });
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Heaven's Fall";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.BLUE;
   }

   private int focusTicks() {
      return Math.max(2, this.abilityInt("focus-ticks", 40));
   }

   @Override
   public Component hud(Player player) {
      Component out = HudManager.cooldown(player, "Piercing Arrow", COOLDOWN_KEY);
      int held = this.focus.getOrDefault(player.getUniqueId(), 0);
      if (held > 0) {
         out = out.append(Component.text("  ")).append(HudManager.meter("Focus", held / (double)this.focusTicks(), NamedTextColor.AQUA));
      }

      return out.append(Component.text("  ")).append(this.ultimateHud(player));
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return true;
      }

      if (secondary) {
         if (this.spendUltimate(player)) {
            this.heavensFall(player);
         }
      } else if (this.tryCooldown(player, COOLDOWN_KEY, this.abilityLong("piercing-cooldown", 280L))) {
         this.piercingArrow(player);
         this.chargeFromAbility(player);
      }

      return true;
   }

   // ------------------------------------------------------------------ True Aim

   /** Every 2 ticks: a full draw held still builds focus; any movement (or lowering the bow) drops it. */
   private void focusTick() {
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         UUID id = player.getUniqueId();
         Location now = player.getLocation();
         Location before = this.lastSpot.put(id, now);
         boolean drawn = this.aims(player) && player.isHandRaised() && player.getActiveItem().getType() == Material.BOW
            && player.getActiveItemUsedTime() >= 20;
         boolean still = before != null && before.getWorld() == now.getWorld()
            && horizontal(now.toVector().subtract(before.toVector())).lengthSquared() < 0.0016;
         if (!drawn || !still) {
            if (this.focus.remove(id) != null && drawn) {
               HudManager.message(player, Component.text("Focus lost", NamedTextColor.GRAY));
            }

            continue;
         }

         int was = this.focus.getOrDefault(id, 0);
         int next = Math.min(this.focusTicks(), was + 2);
         this.focus.put(id, next);
         if (next == this.focusTicks() && was < next) {
            this.plugin.getSoundManager().self(player, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8F, 1.8F);
         }
      }
   }

   /** True Aim works for Horizon in hand, or from the off-hand for any bow drawn in the main hand. */
   private boolean aims(Player player) {
      return this.isHolding(player) || this.offHandAim(player) > 0.0;
   }

   /** Off-hand True Aim strength while a plain bow is in the main hand (0 otherwise). */
   private double offHandAim(Player player) {
      return player.getInventory().getItemInMainHand().getType() == Material.BOW
         && !this.plugin.getWeaponManager().isLegendaryWeapon(player.getInventory().getItemInMainHand())
         ? com.lumiweapons.abilities.Passives.strength(this.plugin, player, KEY) : 0.0;
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onShoot(EntityShootBowEvent event) {
      if (!(event.getEntity() instanceof Player player) || !(event.getProjectile() instanceof AbstractArrow arrow)) {
         return;
      }

      boolean horizon = this.isThisWeapon(event.getBow());
      double strength = horizon ? 1.0 : this.offHandAim(player);
      if (strength <= 0.0) {
         return;
      }

      if (horizon) {
         arrow.getPersistentDataContainer().set(this.shotKey, PersistentDataType.BYTE, (byte)1);    // its hits feed Heaven's Fall
      }

      int held = this.focus.getOrDefault(player.getUniqueId(), 0);
      this.focus.remove(player.getUniqueId());
      if (held <= 0 || event.getForce() < 0.99F) {
         return;
      }

      double fraction = held / (double)this.focusTicks();
      arrow.setVelocity(arrow.getVelocity().multiply(1.0 + this.ability("focus-velocity-bonus", 0.2) * fraction * strength));
      if (fraction >= 1.0) {
         arrow.getPersistentDataContainer().set(this.focusKey, PersistentDataType.DOUBLE, this.ability("focus-damage-bonus", 3.0) * strength);
         this.trail(arrow, GOLD, 60);
         this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 0.8F, 1.6F);
      }
   }

   // ------------------------------------------------------------------ Piercing Arrow

   private void piercingArrow(Player player) {
      Vector direction = player.getEyeLocation().getDirection();
      Arrow arrow = player.launchProjectile(Arrow.class, direction.multiply(this.ability("piercing-speed", 4.0)));
      arrow.setPierceLevel(Math.max(0, this.abilityInt("piercing-pierce", 1)));
      arrow.setCritical(true);
      arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
      arrow.getPersistentDataContainer().set(this.pierceKey, PersistentDataType.DOUBLE, this.ability("piercing-damage", 9.0));
      this.trail(arrow, STAR, 100);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, 1.0F, 1.2F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8F, 1.6F);
   }

   private void trail(AbstractArrow arrow, Color colour, int life) {
      Particle.DustOptions dust = new Particle.DustOptions(colour, 0.9F);
      int[] lived = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         if (!arrow.isValid() || arrow.isInBlock() || lived[0]++ > life) {
            task.cancel();
            return;
         }

         ParticleManager.showcase(Particle.DUST, arrow.getLocation(), 2, 0.03, 0.03, 0.03, 0.0, dust);
         if (lived[0] % 3 == 0) {
            ParticleManager.showcase(Particle.END_ROD, arrow.getLocation(), 1, 0.0, 0.0, 0.0, 0.0, null);
         }
      }, 1L, 1L);
   }

   /** Arrows from Horizon that hit a player fill Heaven's Fall (ultimate-charge-per-arrow). */
   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onArrowLanded(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof AbstractArrow arrow && arrow.getShooter() instanceof Player shooter && event.getEntity() instanceof Player victim
         && !victim.equals(shooter) && arrow.getPersistentDataContainer().has(this.shotKey, PersistentDataType.BYTE)) {
         this.chargeUltimate(shooter, this.ability("ultimate-charge-per-arrow", 6.0));
         this.plugin.getChargeMeter().markCombat(victim);
      }
   }

   /** Focus bonus and Piercing Arrow damage; a raised shield facing a Piercing Arrow stops it. */
   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onArrowHit(EntityDamageByEntityEvent event) {
      if (!(event.getDamager() instanceof AbstractArrow arrow)) {
         return;
      }

      Double pierce = arrow.getPersistentDataContainer().get(this.pierceKey, PersistentDataType.DOUBLE);
      if (pierce != null) {
         if (event.getEntity() instanceof Player victim && victim.isBlocking()) {
            Vector toArrow = horizontal(arrow.getLocation().toVector().subtract(victim.getLocation().toVector()));
            if (toArrow.lengthSquared() < 1.0E-4 || toArrow.normalize().dot(horizontal(victim.getLocation().getDirection()).normalize()) > 0.0) {
               event.setCancelled(true);
               arrow.remove();
               this.plugin.getSoundManager().world(victim.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0F, 1.0F);
               return;
            }
         }

         event.setDamage(pierce);
         ParticleManager.showcase(Particle.END_ROD, event.getEntity().getLocation().add(0.0, 1.0, 0.0), 8, 0.2, 0.3, 0.2, 0.08, null);
         return;
      }

      Double bonus = arrow.getPersistentDataContainer().get(this.focusKey, PersistentDataType.DOUBLE);
      if (bonus != null) {
         event.setDamage(event.getDamage() + bonus);
      }
   }

   // ------------------------------------------------------------------ Heaven's Fall

   private Location landing(Player player) {
      double range = this.ability("heavens-fall-range", 60.0);
      RayTraceResult look = player.rayTraceBlocks(range, FluidCollisionMode.NEVER);
      Location aim = look != null ? look.getHitPosition().toLocation(player.getWorld())
         : player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(Math.min(range, 25.0)));
      RayTraceResult ground = player.getWorld().rayTraceBlocks(aim.clone().add(0.0, 1.0, 0.0), new Vector(0, -1, 0), 40.0, FluidCollisionMode.NEVER, true);
      return ground != null ? ground.getHitPosition().toLocation(player.getWorld()) : aim;
   }

   private void heavensFall(Player player) {
      Location ground = this.landing(player);
      double radius = this.ability("heavens-fall-radius", 6.0);
      int charge = Math.max(20, this.abilityInt("heavens-fall-charge", 40));
      World world = ground.getWorld();
      this.ulting.add(player.getUniqueId());
      player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, charge, 1, true, false, false));
      SpriteFx.Held ring = SpriteFx.hold(ground.clone().add(0.0, 0.06, 0.0), "vfx/ritual_circle_outer", STAR, false, radius * 2.0 / 0.965, charge + 30);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 1.4F);
      this.plugin.getSoundManager().world(ground, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0F, 0.6F);
      HudManager.message(player, Component.text("Heaven's Fall...", NamedTextColor.BLUE));

      for (int tick = 0; tick < charge; tick += 4) {
         final int at = tick;
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            for (int i = 0; i < 28; i++) {
               double a = Math.PI * 2.0 * i / 28.0 - at * 0.03;
               ParticleManager.showcase(Particle.DUST, ground.clone().add(Math.cos(a) * radius, 0.2, Math.sin(a) * radius), 1, 0.0, 0.0, 0.0, 0.0,
                  new Particle.DustOptions(i % 2 == 0 ? STAR : GOLD, 1.0F));
            }

            ParticleManager.showcase(Particle.END_ROD, ground.clone().add(0.0, 0.5 + at * 0.4, 0.0), 2, 0.1, 0.4, 0.1, 0.0, null);
            if (player.isOnline()) {
               ParticleManager.showcase(Particle.END_ROD, player.getEyeLocation().add(0.0, 0.6, 0.0), 2, 0.2, 0.2, 0.2, 0.02, null);
            }
         }, tick);
      }

      // the arrow: a giant one falling point-first from the sky over the last 8 ticks
      double height = this.ability("heavens-fall-height", 30.0);
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         Location top = ground.clone().add(0.0, height, 0.0);
         top.setYaw(0.0F);
         top.setPitch(0.0F);
         ItemDisplay arrow = world.spawn(top, ItemDisplay.class, display -> {
            display.setPersistent(false);
            display.setItemStack(new ItemStack(Material.SPECTRAL_ARROW));
            display.setBrightness(new Display.Brightness(15, 15));
            display.setGlowing(true);
            display.setGlowColorOverride(GOLD);
            display.setViewRange(4.0F);
            display.setTeleportDuration(8);
            // the arrow item points up and to the right: turn it to point straight down
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationZ((float)Math.toRadians(135.0)), new Vector3f(5.0F), new Quaternionf()));
         });
         this.falling.add(arrow);
         this.plugin.getSoundManager().world(top, Sound.ENTITY_ARROW_SHOOT, 2.0F, 0.5F);
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            Location low = ground.clone().add(0.0, 2.0, 0.0);
            low.setYaw(0.0F);
            low.setPitch(0.0F);
            if (arrow.isValid()) {
               arrow.teleport(low);
            }
         }, 1L);
      }, Math.max(0, charge - 9));

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         this.ulting.remove(player.getUniqueId());
         if (ring != null) {
            ring.release(6);
         }

         this.impact(player, ground, radius);
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
            for (ItemDisplay arrow : new java.util.ArrayList<>(this.falling)) {
               if (arrow.isValid() && arrow.getWorld().equals(ground.getWorld()) && arrow.getLocation().distanceSquared(ground) < 16.0) {
                  arrow.remove();
                  this.falling.remove(arrow);
               }
            }
         }, 6L);
      }, charge);
   }

   private void impact(Player player, Location ground, double radius) {
      Location centre = ground.clone().add(0.0, 1.0, 0.0);
      ParticleManager.showcase(Particle.FLASH, centre, 1, 0.0, 0.0, 0.0, 0.0, GOLD);
      ParticleManager.showcase(Particle.END_ROD, centre, 60, radius / 3.0, 1.0, radius / 3.0, 0.25, null);
      ParticleManager.showcase(Particle.EXPLOSION, centre, 3, radius / 4.0, 0.3, radius / 4.0, 0.0, null);
      SpriteFx.wave(ground, STAR, radius, 8);
      SpriteFx.burst(centre, GOLD, radius * 0.8, 2, 2, 6);
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_GENERIC_EXPLODE, 1.4F, 1.2F);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_DEACTIVATE, 1.2F, 1.8F);
      if (!player.isOnline()) {
         return;
      }

      double damage = this.ability("heavens-fall-damage", 16.0);
      double edge = Math.max(0.0, Math.min(1.0, this.ability("heavens-fall-edge-damage", 0.25)));
      for (LivingEntity target : this.around(player, ground, radius)) {
         // cover works: the blast has to reach them
         Vector toEyes = target.getEyeLocation().toVector().subtract(centre.toVector());
         RayTraceResult wall = centre.getWorld().rayTraceBlocks(centre, toEyes, toEyes.length(), FluidCollisionMode.NEVER, true);
         if (wall != null && wall.getHitBlock() != null) {
            continue;
         }

         double distance = horizontal(target.getLocation().toVector().subtract(ground.toVector())).length();
         double falloff = 1.0 - (1.0 - edge) * Math.min(1.0, distance / radius);
         AbilityDamage.hit(player, target, damage * falloff, this.ability("heavens-fall-armour-pierce", 0.25), centre, "{victim} was struck from the heavens by {killer}'s Horizon");
      }
   }

   /** Plugin disable: no giant arrows left in the sky. */
   public void cleanup() {
      for (ItemDisplay arrow : this.falling) {
         if (arrow.isValid()) {
            arrow.remove();
         }
      }

      this.falling.clear();
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.focus.remove(event.getPlayer().getUniqueId());
      this.lastSpot.remove(event.getPlayer().getUniqueId());
      this.ulting.remove(event.getPlayer().getUniqueId());
   }
}
