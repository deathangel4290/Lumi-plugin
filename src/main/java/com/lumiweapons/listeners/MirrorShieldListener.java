package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

/**
 * Mirror Shield -- rewards timing. It works in either hand. It only answers blows and
 * projectiles: explosions, magic, fire and falls go straight past it.
 *
 *   Passive, Reflection: blocking a hit stores Reflection energy. Raising the shield just
 *     before the hit lands (a timed block) stores the whole blow; a lazy block stores a little.
 *   Ability 1, Mirror Parry: a brief perfect-parry window. Blows and projectiles that land in it,
 *     from any side, are mostly turned aside and stored in full.
 *   Ability 2, Reflect: release the stored energy as a bolt of light. Its damage is based on
 *     what you absorbed, up to a cap.
 *   Ultimate, Mirror Realm: a dome around you for a few seconds. Enemy projectiles that enter it
 *     are thrown back, and allies inside take less damage from blows and projectiles.
 */
public class MirrorShieldListener extends UltimateWeapon {
   public static final String KEY = "mirror-shield";
   private static final String PARRY_KEY = "mirror-parry";
   private static final String REFLECT_KEY = "mirror-reflect";
   private static final Color MIRROR = Color.fromRGB(0xCFEFFF);
   private static final Color SHINE = Color.fromRGB(0xFFFFFF);

   private record Realm(UUID owner, Location centre, double radius, long until) {
   }

   /** player -> stored Reflection energy. */
   private final Map<UUID, Double> stored = new HashMap<>();
   /** player -> when they raised the shield (ms). */
   private final Map<UUID, Long> raised = new HashMap<>();
   /** player -> until when Mirror Parry is open (ms). */
   private final Map<UUID, Long> parry = new HashMap<>();
   private final List<Realm> realms = new ArrayList<>();
   private final Set<UUID> deflected = new HashSet<>();

   public MirrorShieldListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::raiseTick, 1L, 1L);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Mirror Realm";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.AQUA;
   }

   /** Either hand: it's a shield. */
   @Override
   protected boolean wields(Player player) {
      return this.isThisWeapon(player.getInventory().getItemInMainHand()) || this.isThisWeapon(player.getInventory().getItemInOffHand());
   }

   private double maxEnergy() {
      return Math.max(1.0, this.ability("reflect-max-energy", 20.0));
   }

   @Override
   public Component hud(Player player) {
      double energy = this.stored.getOrDefault(player.getUniqueId(), 0.0);
      return HudManager.cooldown(player, "Parry", PARRY_KEY).append(Component.text("  "))
         .append(HudManager.meter("Reflection", energy / this.maxEnergy(), NamedTextColor.WHITE)).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Reflect", REFLECT_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      UUID id = event.getPlayer().getUniqueId();
      this.stored.remove(id);
      this.raised.remove(id);
      this.parry.remove(id);
   }

   /** Remembers when each wielder raised the shield, for timed blocks; moves the realms. */
   private void raiseTick() {
      long now = System.currentTimeMillis();
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         boolean up = player.isHandRaised() && this.isThisWeapon(player.getActiveItem());
         if (!up) {
            this.raised.remove(player.getUniqueId());
         } else {
            this.raised.putIfAbsent(player.getUniqueId(), now);
         }
      }

      this.realms.removeIf(realm -> realm.until() < now);
      this.parry.values().removeIf(until -> until < now);
      for (Realm realm : this.realms) {
         this.deflect(realm);
      }
   }

   private static boolean answers(EntityDamageEvent.DamageCause cause) {
      return cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
         || cause == EntityDamageEvent.DamageCause.PROJECTILE;
   }

   private void store(Player player, double amount) {
      if (amount <= 0.0) {
         return;
      }

      double before = this.stored.getOrDefault(player.getUniqueId(), 0.0);
      double after = Math.min(this.maxEnergy(), before + amount);
      this.stored.put(player.getUniqueId(), after);
      this.chargeUltimate(player, (after - before) * this.ability("ultimate-charge-per-energy", 3.0));
      this.plugin.getChargeMeter().markCombat(player);
   }

   // ------------------------------------------------------------------ Reflection and Mirror Parry

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onHit(EntityDamageByEntityEvent event) {
      if (!(event.getEntity() instanceof Player player) || !answers(event.getCause())) {
         return;
      }

      // Mirror Realm: allies inside take less from blows and projectiles
      for (Realm realm : this.realms) {
         Player owner = this.plugin.getServer().getPlayer(realm.owner());
         if (owner != null && player.getWorld().equals(realm.centre().getWorld()) && this.isAlly(owner, player)
            && player.getLocation().distanceSquared(realm.centre()) <= realm.radius() * realm.radius()) {
            event.setDamage(event.getDamage() * (1.0 - this.ability("realm-damage-reduction", 0.3)));
            break;
         }
      }

      if (!this.wields(player) || AbilityDamage.active()) {
         return;
      }

      long now = System.currentTimeMillis();
      double raw = event.getDamage();
      Long parryUntil = this.parry.get(player.getUniqueId());
      if (parryUntil != null && parryUntil >= now) {
         // a perfect parry: most of the blow turned aside, all of it stored
         event.setDamage(raw * (1.0 - this.ability("parry-reduction", 0.8)));
         this.store(player, raw);
         this.flash(player, true);
         Entity attacker = event.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity s ? s : event.getDamager();
         if (event.getCause() != EntityDamageEvent.DamageCause.PROJECTILE && attacker instanceof LivingEntity living && this.canAffect(player, living)) {
            push(living, player.getLocation(), this.ability("parry-knockback", 0.6), 0.2);
         }

         return;
      }

      if (player.isBlocking() && event.getFinalDamage() < raw) {
         Long since = this.raised.get(player.getUniqueId());
         boolean timed = since != null && now - since <= this.abilityLong("timed-block-window", 10L) * 50L;
         this.store(player, raw * (timed ? 1.0 : this.ability("lazy-block-energy", 0.3)));
         this.flash(player, timed);
      }
   }

   private void flash(Player player, boolean perfect) {
      Location at = player.getEyeLocation().add(player.getLocation().getDirection().multiply(0.6)).subtract(0, 0.3, 0);
      ParticleManager.spawn(Particle.DUST, at, perfect ? 14 : 5, 0.3, 0.3, 0.3, 0.0, new Particle.DustOptions(perfect ? SHINE : MIRROR, 1.0F));
      if (perfect) {
         ParticleManager.spawn(Particle.END_ROD, at, 6, 0.2, 0.2, 0.2, 0.05);
         this.plugin.getSoundManager().world(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.2F, 1.8F);
         this.plugin.getSoundManager().world(at, Sound.ITEM_SHIELD_BLOCK, 1.0F, 1.6F);
      }
   }

   @Override
   protected void firstAbility(Player player) {
      if (!this.tryCooldown(player, PARRY_KEY, this.abilityLong("parry-cooldown", 160L))) {
         return;
      }

      this.parry.put(player.getUniqueId(), System.currentTimeMillis() + this.abilityLong("parry-window", 15L) * 50L);
      SpriteFx.burst(player.getEyeLocation().add(player.getLocation().getDirection()), MIRROR, 0.8, 1, 1, 3);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 1.0F, 1.6F);
   }

   // ------------------------------------------------------------------ Reflect

   @Override
   protected void secondAbility(Player player) {
      double energy = this.stored.getOrDefault(player.getUniqueId(), 0.0);
      if (energy < this.ability("reflect-min-energy", 2.0)) {
         HudManager.message(player, Component.text("Block some hits first.", NamedTextColor.GRAY));
         return;
      }

      if (!this.tryCooldown(player, REFLECT_KEY, this.abilityLong("reflect-cooldown", 100L))) {
         return;
      }

      this.stored.remove(player.getUniqueId());
      double damage = Math.min(this.ability("reflect-damage-cap", 12.0), energy * this.ability("reflect-damage-per-energy", 0.6));
      float size = (float)(0.8 + energy / this.maxEnergy());
      Particle.DustOptions light = new Particle.DustOptions(SHINE, size);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.0F, 1.8F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.8F, 1.6F);
      this.fly(player, player.getEyeLocation(), player.getEyeLocation().getDirection(), this.ability("reflect-speed", 2.0), this.ability("reflect-range", 24.0), 0.6, new Flight() {
         @Override
         protected void draw(Location at, int tick) {
            ParticleManager.spawn(Particle.DUST, at, 3, 0.05, 0.05, 0.05, 0.0, light);
            ParticleManager.spawn(Particle.END_ROD, at, 1, 0.02, 0.02, 0.02, 0.0);
         }

         @Override
         protected boolean hit(LivingEntity target, Location at) {
            AbilityDamage.hit(player, target, damage, 0.0, at, "{victim} met their reflection in {killer}'s Mirror Shield");
            SpriteFx.burst(at, MIRROR, 1.0, 1, 1, 3);
            return true;
         }

         @Override
         protected void end(Location at) {
            ParticleManager.spawn(Particle.END_ROD, at, 8, 0.2, 0.2, 0.2, 0.05);
         }
      });
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Mirror Realm

   @Override
   protected void ultimate(Player player) {
      double radius = this.ability("realm-radius", 4.0);
      int duration = Math.max(40, this.abilityInt("realm-duration", 120));
      Location centre = player.getLocation();
      this.realms.add(new Realm(player.getUniqueId(), centre, radius, System.currentTimeMillis() + duration * 50L));
      SpriteFx.Held ring = SpriteFx.hold(centre.clone().add(0, 0.05, 0), "vfx/ritual_circle_outer", MIRROR, false, radius * 2.0 / 0.965, duration + 10);
      ParticleManager.showcase(Particle.FLASH, centre.clone().add(0, 1, 0), 1, 0.0, 0.0, 0.0, 0.0, SHINE);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_BEACON_ACTIVATE, 1.2F, 1.8F);
      this.plugin.getSoundManager().world(centre, Sound.BLOCK_GLASS_PLACE, 1.2F, 0.8F);
      HudManager.message(player, Component.text("Mirror Realm", NamedTextColor.AQUA));
      this.ulting.add(player.getUniqueId());
      int[] tick = {0};
      this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, task -> {
         tick[0] += 4;
         if (tick[0] > duration) {
            if (ring != null) {
               ring.release(8);
            }

            this.ulting.remove(player.getUniqueId());
            task.cancel();
            return;
         }

         // the dome: a shimmer of points on its surface
         for (int i = 0; i < 14; i++) {
            double a = Math.random() * Math.PI * 2.0;
            double b = Math.random() * Math.PI / 2.0;
            Location at = centre.clone().add(Math.cos(a) * Math.cos(b) * radius, Math.sin(b) * radius, Math.sin(a) * Math.cos(b) * radius);
            ParticleManager.spawn(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, new Particle.DustOptions(MIRROR, 0.9F));
         }
      }, 0L, 4L);
   }

   /** Enemy projectiles inside a realm are turned around and become the owner's. */
   private void deflect(Realm realm) {
      Player owner = this.plugin.getServer().getPlayer(realm.owner());
      if (owner == null || realm.centre().getWorld() == null) {
         return;
      }

      double r = realm.radius() + 1.0;
      for (Entity entity : realm.centre().getWorld().getNearbyEntities(realm.centre(), r, r, r)) {
         if (!(entity instanceof Projectile projectile) || this.deflected.contains(projectile.getUniqueId())
            || projectile.getShooter() instanceof Player shooter && this.isAlly(owner, shooter)
            || projectile.getLocation().distanceSquared(realm.centre()) > r * r) {
            continue;
         }

         Vector v = projectile.getVelocity();
         if (v.lengthSquared() < 0.01) {
            continue;
         }

         this.deflected.add(projectile.getUniqueId());
         projectile.setVelocity(v.multiply(-1.0));
         projectile.setShooter(owner);
         ParticleManager.spawn(Particle.END_ROD, projectile.getLocation(), 6, 0.1, 0.1, 0.1, 0.05);
         this.plugin.getSoundManager().world(projectile.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.8F, 1.8F);
         UUID id = projectile.getUniqueId();
         this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.deflected.remove(id), 200L);
      }
   }
}
