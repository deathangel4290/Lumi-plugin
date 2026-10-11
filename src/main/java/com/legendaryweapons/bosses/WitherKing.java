package com.legendaryweapons.bosses;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.Contribution;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Wither King -- the raid boss.
 *
 * Four Soul Anchors around the arena keep it shielded, and each one breaks a different way:
 *   North - melee only     South - ranged only
 *   East  - stand on it    West  - kill the skeletons it summons
 * Nobody is forced into a class, but the fight sorts people into roles on its own.
 *
 * The arena falls apart as it goes (all temporary, healed after the fight):
 *   Phase 1  the King flies; anchors up until broken
 *   Phase 2  (70%) soul soil and wither roses spread across the ground; charged skull volleys
 *   Phase 3  (40%) the anchors rise again, darkness, skulls rain on marked circles
 *   Phase 4  (15%) THE KING LANDS - no more flying, it fights on the ground
 */
final class WitherKing extends Boss {
   private static final TextColor ASH = TextColor.color(150, 110, 200);
   private final List<Anchor> anchors = new ArrayList<>();
   private final Set<UUID> westSkeletons = new HashSet<>();
   private boolean landed;
   private long nextVolley;
   private long nextRain;
   private long nextSlam;
   private long nextDarkness;
   private int decay;

   private enum Kind {
      NORTH("Melee only", 0, -1), SOUTH("Ranged only", 0, 1), EAST("Stand on it", 1, 0), WEST("Slay its summons", -1, 0);

      final String rule;
      final int dx;
      final int dz;

      Kind(String rule, int dx, int dz) {
         this.rule = rule;
         this.dx = dx;
         this.dz = dz;
      }
   }

   private static final class Anchor {
      final Kind kind;
      final Location at;
      final Shulker hitbox;
      final BlockDisplay look;
      final TextDisplay label;
      double progress;                      // 0..100: damage dealt, time stood, or kills made
      boolean broken;

      Anchor(Kind kind, Location at, Shulker hitbox, BlockDisplay look, TextDisplay label) {
         this.kind = kind;
         this.at = at;
         this.hitbox = hitbox;
         this.look = look;
         this.label = label;
      }
   }

   WitherKing(LegendaryWeapons plugin, BossManager manager, BossManager.Arena arena, TempBlocks terrain) {
      super(plugin, manager, "wither_king", arena, terrain);
   }

   @Override
   public String displayName() {
      return "Wither King";
   }

   @Override
   protected TextColor colour() {
      return ASH;
   }

   @Override
   protected BossBar.Color barColour() {
      return BossBar.Color.PURPLE;
   }

   @Override
   protected EntityType bodyType() {
      return EntityType.WITHER;
   }

   @Override
   protected double[] defaultPhases() {
      return new double[]{0.7, 0.4, 0.15};
   }

   private void tameVanillaWither() {
      if (this.body instanceof Wither wither) {
         wither.setInvulnerableTicks(0);                 // no spawn charge-up explosion
         if (wither.getBossBar() != null) {
            wither.getBossBar().setVisible(false);       // ours replaces it
         }
      }
   }

   @Override
   protected void onSpawned() {
      this.tameVanillaWither();
      long now = System.currentTimeMillis();
      this.nextVolley = now + 10000L;
      this.raiseAnchors();
   }

   // ------------------------------------------------------------------ anchors

   private double anchorScale() {
      return 1.0 + 0.25 * Math.max(0, this.fighters().size() - 1);
   }

   private void raiseAnchors() {
      this.clearAnchors();
      this.shielded = true;
      double r = this.arena.radius() * 0.65;
      for (Kind kind : Kind.values()) {
         Location raw = this.arena.centre().clone().add(kind.dx * r, 0.0, kind.dz * r);
         Location at = this.arena.surface(raw);
         Shulker hitbox = at.getWorld().spawn(at, Shulker.class, s -> {
            s.setPersistent(false);
            s.setAI(false);
            s.setSilent(true);
            s.setInvisible(true);
            s.setRemoveWhenFarAway(false);
         });
         BlockDisplay look = at.getWorld().spawn(at.clone().add(-0.75, 0.0, -0.75), BlockDisplay.class, d -> {
            d.setBlock(Material.RESPAWN_ANCHOR.createBlockData());
            d.setPersistent(false);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(150, 110, 200));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.5F, 1.5F, 1.5F), new Quaternionf()));
         });
         TextDisplay label = at.getWorld().spawn(at.clone().add(0.0, 2.6, 0.0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
         });
         this.extras.add(hitbox);
         this.extras.add(look);
         this.extras.add(label);
         this.anchors.add(new Anchor(kind, at, hitbox, look, label));
      }

      this.plugin.getSoundManager().world(this.arena.centre(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5F, 0.5F);
      this.say("☠ Four Soul Anchors shield the King. North: melee only. South: ranged only. East: stand on it. West: slay its summons.");
   }

   private void clearAnchors() {
      for (Anchor anchor : this.anchors) {
         for (Entity entity : new Entity[]{anchor.hitbox, anchor.look, anchor.label}) {
            entity.remove();
            this.extras.remove(entity);
         }
      }

      this.anchors.clear();
   }

   private Anchor anchorOf(Entity entity) {
      for (Anchor anchor : this.anchors) {
         if (!anchor.broken && anchor.hitbox.equals(entity)) {
            return anchor;
         }
      }

      return null;
   }

   @Override
   protected boolean onPartDamaged(Entity part, Player attacker, EntityDamageEvent event) {
      Anchor anchor = this.anchorOf(part);
      if (anchor == null) {
         return false;
      }

      event.setCancelled(true);                          // anchors keep their own score
      if (attacker == null) {
         return true;
      }

      boolean ranged = event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile;
      boolean counts = anchor.kind == Kind.NORTH && !ranged || anchor.kind == Kind.SOUTH && ranged;
      if (!counts) {
         com.legendaryweapons.managers.HudManager.message(attacker, Component.text(anchor.kind.name().charAt(0) + anchor.kind.name().substring(1).toLowerCase() + " Anchor: " + anchor.kind.rule.toLowerCase() + ".", ASH));
         return true;
      }

      double needed = this.num("anchor-health", 60.0) * this.anchorScale();
      anchor.progress = Math.min(100.0, anchor.progress + event.getDamage() / needed * 100.0);
      this.credit(attacker, Contribution.Kind.MECHANIC, event.getDamage() / 4.0);
      part.getWorld().playSound(part.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_SET_SPAWN, 0.6F, 1.6F);
      this.checkAnchor(anchor, attacker);
      return true;
   }

   private void checkAnchor(Anchor anchor, Player breaker) {
      if (anchor.broken || anchor.progress < 100.0) {
         return;
      }

      anchor.broken = true;
      ParticleManager.showcase(Particle.SOUL_FIRE_FLAME, anchor.at.clone().add(0.0, 1.0, 0.0), 40, 0.6, 1.0, 0.6, 0.05, null);
      ParticleManager.showcase(Particle.EXPLOSION, anchor.at.clone().add(0.0, 1.0, 0.0), 2, 0.3, 0.3, 0.3, 0.0, null);
      this.plugin.getSoundManager().world(anchor.at, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.5F, 0.7F);
      for (Entity entity : new Entity[]{anchor.hitbox, anchor.look, anchor.label}) {
         entity.remove();
         this.extras.remove(entity);
      }

      if (breaker != null) {
         this.credit(breaker, Contribution.Kind.MECHANIC, 15.0);
      }

      long left = this.anchors.stream().filter(a -> !a.broken).count();
      this.say("☠ The " + anchor.kind.name().toLowerCase() + " anchor breaks! " + (left == 0 ? "The King's shield shatters!" : left + " remain."));
      if (left == 0) {
         this.anchors.clear();
         this.shielded = false;
         this.body.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 4, true, false));
         this.plugin.getSoundManager().world(this.body.getLocation(), Sound.ENTITY_WITHER_HURT, 1.5F, 0.6F);
      }
   }

   private void tickAnchors() {
      for (Anchor anchor : new ArrayList<>(this.anchors)) {
         if (anchor.broken) {
            continue;
         }

         if (anchor.kind == Kind.EAST) {
            Player stander = null;
            for (Player player : this.fighters()) {
               if (player.getLocation().distanceSquared(anchor.at) <= 2.5 * 2.5) {
                  stander = player;
                  break;
               }
            }

            if (stander != null) {
               anchor.progress = Math.min(100.0, anchor.progress + 100.0 / (this.num("anchor-stand-seconds", 15.0) * this.anchorScale()));
               this.credit(stander, Contribution.Kind.SUPPORT, 1.0);
               this.checkAnchor(anchor, stander);
            } else {
               anchor.progress = Math.max(0.0, anchor.progress - 2.0);
            }
         } else if (anchor.kind == Kind.WEST) {
            this.westSkeletons.removeIf(id -> {
               Entity e = this.plugin.getServer().getEntity(id);
               return e == null || e.isDead();
            });
            if (this.westSkeletons.size() < 3) {
               Location spot = this.arena.surface(anchor.at.clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 0.0, ThreadLocalRandom.current().nextDouble(-3, 3)));
               WitherSkeleton skeleton = this.spawnMinion(spot, WitherSkeleton.class);
               skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
               this.westSkeletons.add(skeleton.getUniqueId());
               ParticleManager.showcase(Particle.SOUL, spot.clone().add(0.0, 1.0, 0.0), 12, 0.3, 0.6, 0.3, 0.03, null);
            }
         }

         if (anchor.broken) {
            continue;
         }

         int bars = (int)Math.round(anchor.progress / 10.0);
         anchor.label.text(Component.text(anchor.kind.name().charAt(0) + anchor.kind.name().substring(1).toLowerCase() + " Anchor", ASH, TextDecoration.BOLD)
            .append(Component.newline()).append(Component.text(anchor.kind.rule, NamedTextColor.GRAY))
            .append(Component.newline()).append(Component.text("■".repeat(bars), NamedTextColor.LIGHT_PURPLE)).append(Component.text("■".repeat(10 - bars), NamedTextColor.DARK_GRAY)));
         dustLine(anchor.at.clone().add(0.0, 1.5, 0.0), this.body.getLocation().add(0.0, 1.5, 0.0), Color.fromRGB(110, 70, 170), 0.8F);
      }
   }

   @Override
   protected void onMinionDeath(LivingEntity minion, Player killer) {
      if (this.westSkeletons.remove(minion.getUniqueId())) {
         for (Anchor anchor : this.anchors) {
            if (anchor.kind == Kind.WEST && !anchor.broken) {
               anchor.progress = Math.min(100.0, anchor.progress + 100.0 / Math.max(1.0, Math.round(this.num("anchor-kills", 6.0) * this.anchorScale())));
               if (killer != null) {
                  this.credit(killer, Contribution.Kind.MECHANIC, 3.0);
               }

               this.checkAnchor(anchor, killer);
            }
         }
      }
   }

   // ------------------------------------------------------------------ phases

   @Override
   protected void onPhase(int phase) {
      switch (phase) {
         case 1 -> {
            this.decay = 60;
            this.say("☠ The ground withers beneath the King.");
         }
         case 2 -> {
            this.raiseAnchors();
            this.say("☠ The anchors rise again - and the light dies.");
            this.model.setState("enraged");
         }
         case 3 -> this.land();
         default -> {
         }
      }
   }

   private void land() {
      this.landed = true;
      this.clearAnchors();
      this.shielded = false;
      Location at = this.arena.surface(this.body.getLocation());
      this.allowTeleport = true;
      try {
         this.body.teleport(at);
      } finally {
         this.allowTeleport = false;
      }

      this.swapBody(EntityType.WITHER_SKELETON, this.num("landed-scale", 2.6), "landed");
      this.body.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
      setBase(this.body, Attribute.MOVEMENT_SPEED, 0.33);
      setBase(this.body, Attribute.ATTACK_DAMAGE, this.num("landed-attack-damage", 12.0));
      for (Player player : this.fighters()) {
         player.showTitle(Title.title(Component.text("THE KING LANDS", ASH, TextDecoration.BOLD), Component.text("no more flying", NamedTextColor.GRAY)));
      }

      ParticleManager.showcase(Particle.EXPLOSION_EMITTER, at, 2, 1.0, 0.2, 1.0, 0.0, null);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_WITHER_BREAK_BLOCK, 1.5F, 0.5F);
      this.nextSlam = System.currentTimeMillis() + 5000L;
   }

   // ------------------------------------------------------------------ main loop

   @Override
   protected void onTick() {
      long now = System.currentTimeMillis();
      if (!this.landed) {
         this.tameVanillaWither();
      }

      if (this.ticks % 20L == 0L && !this.anchors.isEmpty()) {
         this.tickAnchors();
      }

      if (this.decay > 0 && this.ticks % 4L == 0L) {
         this.decay--;
         this.witherGround();
      }

      if (!this.landed && this.phase >= 1 && now >= this.nextVolley) {
         this.nextVolley = now + (long)(this.num("volley-interval", 8.0) * 1000.0);
         this.volley();
      }

      if (!this.landed && this.phase >= 2 && now >= this.nextRain) {
         this.nextRain = now + (long)(this.num("rain-interval", 9.0) * 1000.0);
         this.skullRain();
      }

      if (this.phase >= 2 && now >= this.nextDarkness) {
         this.nextDarkness = now + 7000L;
         for (Player player : this.fighters()) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, true));
         }
      }

      if (this.landed && now >= this.nextSlam) {
         this.nextSlam = now + (long)(this.num("slam-interval", 8.0) * 1000.0);
         this.slam();
      }
   }

   /** Phase 2: soul soil and wither roses creep over the arena floor (temporary). */
   private void witherGround() {
      ThreadLocalRandom random = ThreadLocalRandom.current();
      for (int i = 0; i < 3; i++) {
         double a = random.nextDouble(Math.PI * 2.0);
         double r = Math.sqrt(random.nextDouble()) * this.arena.radius() * 0.9;
         Location spot = this.arena.surface(this.arena.centre().clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r));
         Block ground = spot.getBlock().getRelative(0, -1, 0);
         Block above = spot.getBlock();
         if (Tag.DIRT.isTagged(ground.getType()) || ground.getType() == Material.STONE || Tag.SAND.isTagged(ground.getType())) {
            this.terrain.set(ground, Material.SOUL_SOIL);
            if (above.getType().isAir() && random.nextDouble() < 0.4) {
               this.terrain.set(above, Material.WITHER_ROSE);
            }

            ParticleManager.showcase(Particle.SOUL, spot, 3, 0.3, 0.2, 0.3, 0.01, null);
         }
      }

      this.manager.save();
   }

   private void volley() {
      for (Player target : this.fighters()) {
         if (ThreadLocalRandom.current().nextDouble() < 0.6) {
            Location from = this.body.getLocation().add(0.0, 2.5, 0.0);
            Vector dir = target.getLocation().add(0.0, 1.0, 0.0).toVector().subtract(from.toVector()).normalize();
            WitherSkull skull = from.getWorld().spawn(from.clone().add(dir), WitherSkull.class, s -> {
               s.setShooter(this.body);
               s.setCharged(true);
               s.setDirection(dir);
            });
            skull.setVelocity(dir.multiply(0.9));
         }
      }

      this.plugin.getSoundManager().world(this.body.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1.0F, 0.7F);
   }

   /** Phase 3: circles mark where skulls will fall, then they fall. */
   private void skullRain() {
      List<Location> marks = new ArrayList<>();
      for (Player player : this.fighters()) {
         marks.add(player.getLocation());
      }

      for (int i = 0; i < 2; i++) {
         Player random = this.randomFighter(null);
         if (random != null) {
            marks.add(random.getLocation().add(ThreadLocalRandom.current().nextDouble(-5, 5), 0.0, ThreadLocalRandom.current().nextDouble(-5, 5)));
         }
      }

      for (Location mark : marks) {
         for (int t = 0; t < 30; t += 5) {
            this.later(t, () -> dustRing(mark, 2.5, Color.fromRGB(60, 20, 90), 1.4F));
         }

         this.later(30L, () -> {
            Location from = mark.clone().add(0.0, 14.0, 0.0);
            WitherSkull skull = from.getWorld().spawn(from, WitherSkull.class, s -> {
               s.setShooter(this.body);
               s.setCharged(true);
               s.setDirection(new Vector(0, -1, 0));
            });
            skull.setVelocity(new Vector(0.0, -1.4, 0.0));
         });
      }

      this.plugin.getSoundManager().world(this.arena.centre(), Sound.ENTITY_WITHER_AMBIENT, 1.5F, 0.5F);
   }

   /** Landed: a marked ground slam around the King. */
   private void slam() {
      final Location at = this.body.getLocation();
      final double radius = this.num("slam-radius", 6.0);
      this.model.setState("attack");
      for (int t = 0; t < 30; t += 5) {
         this.later(t, () -> dustRing(at, radius, Color.fromRGB(90, 30, 120), 1.6F));
      }

      this.later(30L, () -> {
         ParticleManager.showcase(Particle.EXPLOSION, at, 6, radius / 2.0, 0.2, radius / 2.0, 0.0, null);
         this.plugin.getSoundManager().world(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.5F, 0.6F);
         for (Player player : this.fighters()) {
            if (player.getLocation().distanceSquared(at) <= radius * radius) {
               this.trueDamage(player, this.num("slam-damage", 8.0));
               Vector push = player.getLocation().toVector().subtract(at.toVector()).setY(0.0);
               player.setVelocity((push.lengthSquared() < 0.01 ? new Vector(1, 0, 0) : push.normalize()).multiply(1.3).setY(0.6));
            }
         }

         this.model.setState("landed");
      });
   }

   @Override
   protected void cleanup() {
      this.clearAnchors();
      super.cleanup();
   }
}
