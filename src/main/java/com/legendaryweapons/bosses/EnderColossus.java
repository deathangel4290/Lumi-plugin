package com.legendaryweapons.bosses;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The Ender Colossus -- the chaos boss, and the PvP crowd's favourite.
 *
 * Everything it does is announced by a tell first (a sound and a particle signature), so the chaos
 * can be read and played around once people learn it:
 *   SWAP      purple tether between two players, then they trade places (alone: you and it trade)
 *   BANISH    an eye-spiral around whoever hurt it most, then they are thrown to the arena edge
 *   PULL      portal sparks at the farthest player's feet, then they are dragged in front of it
 *   DECOYS    (phase 2+) it splits into look-alikes; the real one drips portal particles
 *   SCRAMBLE  (phase 3) everyone in the arena glows violet, then all positions are shuffled
 */
final class EnderColossus extends Boss {
   private static final TextColor VOID = TextColor.color(175, 80, 235);
   private final Map<UUID, Double> recentDamage = new HashMap<>();
   private final Set<UUID> decoys = new HashSet<>();
   private long nextSwap;
   private long nextBanish;
   private long nextPull;
   private long nextDecoys;
   private long nextScramble;

   EnderColossus(LegendaryWeapons plugin, BossManager manager, BossManager.Arena arena, TempBlocks terrain) {
      super(plugin, manager, "ender_colossus", arena, terrain);
   }

   @Override
   public String displayName() {
      return "Ender Colossus";
   }

   @Override
   protected TextColor colour() {
      return VOID;
   }

   @Override
   protected BossBar.Color barColour() {
      return BossBar.Color.PURPLE;
   }

   @Override
   protected EntityType bodyType() {
      return EntityType.ENDERMAN;
   }

   @Override
   protected double[] defaultPhases() {
      return new double[]{0.6, 0.3};
   }

   @Override
   protected void onSpawned() {
      long now = System.currentTimeMillis();
      this.nextSwap = now + 10000L;
      this.nextBanish = now + 16000L;
      this.nextPull = now + 22000L;
      this.say("Watch for its tells. Every trick it plays is announced a moment before.");
   }

   @Override
   protected double incoming(Player attacker, EntityDamageEvent event, double damage) {
      if (attacker != null) {
         this.recentDamage.merge(attacker.getUniqueId(), damage, Double::sum);
      }

      return damage;
   }

   private void move(Player player, Location to) {
      Location target = to.clone();
      target.setYaw(player.getLocation().getYaw());
      target.setPitch(player.getLocation().getPitch());
      this.events().relics().teleportAllowed(player, target);
      ParticleManager.showcase(Particle.REVERSE_PORTAL, target.clone().add(0.0, 1.0, 0.0), 30, 0.3, 0.8, 0.3, 0.05, null);
      this.plugin.getSoundManager().world(target, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 0.6F);
   }

   private void moveBody(Location to) {
      this.allowTeleport = true;
      try {
         this.body.teleport(to);
      } finally {
         this.allowTeleport = false;
      }
   }

   private Location edgePoint() {
      double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
      double r = this.arena.radius() * 0.85;
      return this.arena.surface(this.arena.centre().clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r));
   }

   // ------------------------------------------------------------------ main loop

   @Override
   protected void onTick() {
      long now = System.currentTimeMillis();
      if (!this.decoys.isEmpty() && this.ticks % 10L == 0L) {
         // the tell that gives the real one away
         ParticleManager.showcase(Particle.PORTAL, this.body.getLocation().add(0.0, 0.2, 0.0), 10, 0.4, 0.1, 0.4, 0.3, null);
         this.decoys.removeIf(id -> {
            Entity entity = this.plugin.getServer().getEntity(id);
            return entity == null || entity.isDead();
         });
      }

      if (this.ticks % 200L == 0L) {
         this.recentDamage.clear();                  // "most damage" means lately, not all fight
      }

      if (now >= this.nextSwap) {
         this.nextSwap = now + (long)(this.num("swap-interval", 12.0) * 1000.0);
         this.swap();
      }

      if (now >= this.nextBanish) {
         this.nextBanish = now + (long)(this.num("banish-interval", 15.0) * 1000.0);
         this.banish();
      }

      if (this.phase >= 1 && now >= this.nextPull) {
         this.nextPull = now + (long)(this.num("pull-interval", 18.0) * 1000.0);
         this.pull();
      }

      if (this.phase >= 1 && now >= this.nextDecoys && this.decoys.isEmpty()) {
         this.nextDecoys = now + (long)(this.num("decoy-interval", 30.0) * 1000.0);
         this.splitIntoDecoys();
      }

      if (this.phase >= 2 && now >= this.nextScramble) {
         this.nextScramble = now + (long)(this.num("scramble-interval", 20.0) * 1000.0);
         this.scramble();
      }
   }

   @Override
   protected void onPhase(int phase) {
      long now = System.currentTimeMillis();
      if (phase == 1) {
         this.nextDecoys = now + 2000L;
         this.say("☠ The Colossus begins to split...");
      } else if (phase == 2) {
         this.nextScramble = now + 5000L;
         this.model.setState("enraged");
         this.say("☠ Space itself comes loose.");
      }
   }

   // ------------------------------------------------------------------ abilities

   private void swap() {
      List<Player> fighters = this.fighters();
      if (fighters.isEmpty()) {
         return;
      }

      Collections.shuffle(fighters);
      final Player a = fighters.get(0);
      final Player b = fighters.size() > 1 ? fighters.get(1) : null;
      this.model.setState("cast");
      this.plugin.getSoundManager().world(this.body.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1.2F, 0.5F);
      for (int t = 0; t < 30; t += 3) {
         this.later(t, () -> {
            Location to = b != null ? b.getLocation().add(0.0, 1.0, 0.0) : this.body.getLocation().add(0.0, 2.0, 0.0);
            dustLine(a.getLocation().add(0.0, 1.0, 0.0), to, Color.fromRGB(170, 60, 230), 1.2F);
         });
      }

      this.later(30L, () -> {
         if (!a.isOnline() || a.isDead()) {
            return;
         }

         Location first = a.getLocation();
         if (b != null && b.isOnline() && !b.isDead() && this.arena.contains(b.getLocation(), 4.0)) {
            Location second = b.getLocation();
            this.move(a, second);
            this.move(b, first);
         } else {
            Location self = this.body.getLocation();
            this.moveBody(first);
            this.move(a, self);
         }

         this.model.setState(this.phase >= 2 ? "enraged" : "idle");
      });
   }

   private void banish() {
      UUID top = null;
      double best = 0.0;
      for (Map.Entry<UUID, Double> entry : this.recentDamage.entrySet()) {
         if (entry.getValue() > best) {
            best = entry.getValue();
            top = entry.getKey();
         }
      }

      final Player target = top == null ? this.randomFighter(null) : this.plugin.getServer().getPlayer(top);
      if (target == null || !this.arena.contains(target.getLocation(), 4.0)) {
         return;
      }

      this.plugin.getSoundManager().world(target.getLocation(), Sound.ENTITY_ENDERMAN_SCREAM, 1.0F, 0.6F);
      for (int t = 0; t < 30; t += 2) {
         final int step = t;
         this.later(t, () -> {
            double a = step * 0.6;
            for (int i = 0; i < 3; i++) {
               double angle = a + i * Math.PI * 2.0 / 3.0;
               ParticleManager.showcase(Particle.REVERSE_PORTAL, target.getLocation().add(Math.cos(angle) * 0.8, 0.2 + step / 15.0, Math.sin(angle) * 0.8), 1, 0.0, 0.0, 0.0, 0.0, null);
            }
         });
      }

      this.later(30L, () -> {
         if (target.isOnline() && !target.isDead() && this.arena.contains(target.getLocation(), 4.0)) {
            this.move(target, this.edgePoint());
            target.sendMessage(Component.text("☠ The Colossus casts you out.", VOID));
         }
      });
      this.recentDamage.remove(target.getUniqueId());
   }

   private void pull() {
      Player farthest = null;
      double far = 0.0;
      for (Player player : this.fighters()) {
         double d = player.getLocation().distanceSquared(this.body.getLocation());
         if (d > far) {
            far = d;
            farthest = player;
         }
      }

      final Player target = farthest;
      if (target == null || far < 36.0) {
         return;
      }

      for (int t = 0; t < 20; t += 2) {
         this.later(t, () -> ParticleManager.showcase(Particle.PORTAL, target.getLocation(), 12, 0.4, 0.1, 0.4, 0.4, null));
      }

      this.plugin.getSoundManager().world(target.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.6F, 1.6F);
      this.later(20L, () -> {
         if (target.isOnline() && !target.isDead()) {
            Location front = this.body.getLocation().add(this.body.getLocation().getDirection().setY(0.0).normalize().multiply(2.5));
            this.move(target, this.arena.surface(front));
            this.later(6L, () -> this.hit(target, this.num("pull-damage", 6.0)));
         }
      });
   }

   private void splitIntoDecoys() {
      int count = (int)this.num("decoys", 3.0);
      List<Location> spots = new ArrayList<>();
      spots.add(this.body.getLocation());
      for (int i = 0; i < count; i++) {
         double a = Math.PI * 2.0 * i / count + ThreadLocalRandom.current().nextDouble(0.5);
         double r = this.arena.radius() * 0.5;
         spots.add(this.arena.surface(this.arena.centre().clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r)));
      }

      Collections.shuffle(spots);
      this.moveBody(spots.get(0));
      for (int i = 1; i < spots.size(); i++) {
         Enderman decoy = this.spawnMinion(spots.get(i), Enderman.class);
         setBase(decoy, Attribute.SCALE, this.num("scale", 3.0));
         setBase(decoy, Attribute.MAX_HEALTH, this.num("decoy-health", 30.0));
         decoy.setHealth(this.num("decoy-health", 30.0));
         decoy.customName(this.body.customName());
         decoy.setCustomNameVisible(false);
         this.decoys.add(decoy.getUniqueId());
         ParticleManager.showcase(Particle.REVERSE_PORTAL, spots.get(i).clone().add(0.0, 2.0, 0.0), 40, 0.6, 1.5, 0.6, 0.05, null);
         this.later((long)(this.num("decoy-seconds", 20.0) * 20.0), () -> {
            if (decoy.isValid()) {
               ParticleManager.showcase(Particle.PORTAL, decoy.getLocation().add(0.0, 2.0, 0.0), 40, 0.6, 1.5, 0.6, 0.3, null);
               decoy.remove();
            }
         });
      }

      this.plugin.getSoundManager().world(this.arena.centre(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.5F, 0.5F);
      this.say("☠ The Colossus splits! Only one of them is real - watch its feet.");
   }

   @Override
   protected void onMinionDeath(LivingEntity minion, Player killer) {
      if (this.decoys.remove(minion.getUniqueId())) {
         Location at = minion.getLocation();
         ParticleManager.showcase(Particle.REVERSE_PORTAL, at.clone().add(0.0, 2.0, 0.0), 60, 1.0, 1.5, 1.0, 0.1, null);
         this.plugin.getSoundManager().world(at, Sound.ENTITY_ENDERMAN_DEATH, 1.0F, 1.4F);
         for (Player player : this.fighters()) {
            if (player.getLocation().distanceSquared(at) <= 16.0) {
               player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, true, false, true));
            }
         }

         if (killer != null) {
            killer.sendMessage(Component.text("A decoy! It bursts into the void.", VOID));
         }
      }
   }

   private void scramble() {
      final List<Player> fighters = this.fighters();
      if (fighters.isEmpty()) {
         return;
      }

      for (Player player : fighters) {
         player.showTitle(Title.title(Component.empty(), Component.text("SCRAMBLE", VOID, TextDecoration.BOLD)));
         player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, true, false, false));
      }

      this.plugin.getSoundManager().world(this.arena.centre(), Sound.BLOCK_END_PORTAL_SPAWN, 0.6F, 1.6F);
      for (int t = 0; t < 40; t += 4) {
         this.later(t, () -> {
            for (Player player : fighters) {
               ParticleManager.showcase(Particle.REVERSE_PORTAL, player.getLocation(), 6, 0.3, 0.1, 0.3, 0.1, null);
            }
         });
      }

      this.later(40L, () -> {
         List<Player> still = new ArrayList<>();
         for (Player player : fighters) {
            if (player.isOnline() && !player.isDead() && this.arena.contains(player.getLocation(), 4.0)) {
               still.add(player);
            }
         }

         if (still.size() == 1) {
            this.move(still.get(0), this.edgePoint());
            return;
         }

         List<Location> spots = new ArrayList<>();
         for (Player player : still) {
            spots.add(player.getLocation());
         }

         for (int i = 0; i < still.size(); i++) {
            this.move(still.get(i), spots.get((i + 1) % spots.size()));   // a rotation: nobody stays put
         }
      });
   }

   @Override
   protected void cleanup() {
      this.decoys.clear();
      super.cleanup();
   }
}
