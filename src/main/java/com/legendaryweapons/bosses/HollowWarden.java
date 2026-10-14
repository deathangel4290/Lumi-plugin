package com.legendaryweapons.bosses;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.Contribution;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Warden;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The Hollow Warden -- the coordination boss. Damage is not the hard part.
 *
 *  - It hunts by NOISE, not sight. Sprinting, jumping and hitting it make you loud; sneaking makes you
 *    quiet. It always goes for the loudest player. Your noise is on your action bar.
 *  - Sonic lines: a marked line toward the loudest player, then a blast along it that ignores armour.
 *    Step sideways.
 *  - Sculk nodes (at 66% and 33%): it buries itself in sculk and is shielded until every node is
 *    charged AT THE SAME TIME (stand on a node to charge it; empty nodes drain). One node per fighter,
 *    up to three -- it needs people working together.
 *  - Tendrils (phase 2+): it seizes someone and drags them in. Anyone else can cut them free by
 *    right-clicking the tendril; nobody does, and it slams them.
 *  - Darkness (phase 3): the light goes out in pulses.
 *  - It LEARNS: if one kind of damage (one weapon, arrows, explosions...) does most of the damage for a
 *    while, it resists that kind for a time. Crystal-crystal-crystal does not work.
 */
final class HollowWarden extends Boss {
   private static final TextColor TEAL = TextColor.color(40, 190, 175);
   private final Map<UUID, Double> noise = new HashMap<>();
   private final ArrayDeque<Object[]> recent = new ArrayDeque<>();      // time, category, amount
   private final Map<String, Long> resisting = new HashMap<>();
   private final List<Node> nodes = new ArrayList<>();
   private long nextSonic;
   private long nextTendril;
   private long nextDarkness;
   private long nextEruption;
   /** Jumps since the last noise update. Players move client-side, so jumps are seen leaving the ground. */
   private final Map<UUID, Integer> jumps = new HashMap<>();
   private final Map<UUID, Boolean> grounded = new HashMap<>();
   private long nextRoar;
   private Tendril tendril;

   private static final class Node {
      final Location at;
      final BlockDisplay block;
      final TextDisplay label;
      double charge;

      Node(Location at, BlockDisplay block, TextDisplay label) {
         this.at = at;
         this.block = block;
         this.label = label;
      }
   }

   private static final class Tendril {
      final UUID victim;
      final Interaction handle;
      final long releaseAt;

      Tendril(UUID victim, Interaction handle, long releaseAt) {
         this.victim = victim;
         this.handle = handle;
         this.releaseAt = releaseAt;
      }
   }

   HollowWarden(LegendaryWeapons plugin, BossManager manager, BossManager.Arena arena, TempBlocks terrain) {
      super(plugin, manager, "hollow_warden", arena, terrain);
   }

   @Override
   public String displayName() {
      return "Hollow Warden";
   }

   @Override
   protected TextColor colour() {
      return TEAL;
   }

   @Override
   protected BossBar.Color barColour() {
      return BossBar.Color.BLUE;
   }

   @Override
   protected EntityType bodyType() {
      return EntityType.WARDEN;
   }

   @Override
   protected double[] defaultPhases() {
      return new double[]{0.66, 0.33};
   }

   @Override
   protected void onSpawned() {
      long now = System.currentTimeMillis();
      this.nextSonic = now + 8000L;
      this.nextTendril = now + 20000L;
      this.say("It cannot see you. It can HEAR you. Sneak to stay quiet - the loudest player is its prey.");
   }

   @Override
   public Component hud(Player player) {
      double level = this.noise.getOrDefault(player.getUniqueId(), 0.0);
      int bars = (int)Math.round(level / 10.0);
      NamedTextColor colour = level > 60.0 ? NamedTextColor.RED : (level > 30.0 ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
      return Component.text("Noise ", NamedTextColor.GRAY).append(Component.text("■".repeat(bars), colour)).append(Component.text("■".repeat(10 - bars), NamedTextColor.DARK_GRAY));
   }

   private void addNoise(Player player, double amount) {
      this.noise.merge(player.getUniqueId(), amount, (a, b) -> Math.max(0.0, Math.min(100.0, a + b)));
   }

   private Player loudest() {
      Player best = null;
      double bestNoise = -1.0;
      for (Player player : this.fighters()) {
         double level = this.noise.getOrDefault(player.getUniqueId(), 0.0);
         if (level > bestNoise) {
            bestNoise = level;
            best = player;
         }
      }

      return best;
   }

   @Override
   protected Player chooseTarget(List<Player> fighters) {
      Player loud = this.loudest();
      if (loud != null && this.noise.getOrDefault(loud.getUniqueId(), 0.0) >= 15.0) {
         if (this.body instanceof Warden warden) {
            warden.setAnger(loud, 150);
         }

         return loud;
      }

      // everyone is quiet: it still knows roughly where people are. Keeping it angry also stops a
      // vanilla Warden from digging back into the ground (and vanishing) after a calm minute.
      Player near = super.chooseTarget(fighters);
      if (near != null && this.body instanceof Warden warden) {
         warden.setAnger(near, 90);
      }

      return near;
   }

   // ------------------------------------------------------------------ damage rules: shield + learning

   private static String category(Player attacker, EntityDamageEvent event) {
      if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
         return "explosions";
      }

      if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Projectile projectile) {
         return projectile.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "s";
      }

      if (attacker == null) {
         return "unknown";
      }

      org.bukkit.inventory.ItemStack held = attacker.getInventory().getItemInMainHand();
      return held.getType().isAir() ? "fists" : held.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
   }

   @Override
   protected double incoming(Player attacker, EntityDamageEvent event, double damage) {
      if (attacker != null) {
         this.addNoise(attacker, 8.0);
      }

      String category = category(attacker, event);
      long now = System.currentTimeMillis();
      this.recent.addLast(new Object[]{now, category, damage});
      Long until = this.resisting.get(category);
      if (until != null && until > now) {
         ParticleManager.spawn(Particle.SCULK_SOUL, this.body.getLocation().add(0.0, 2.0, 0.0), 4, 0.5, 0.5, 0.5, 0.02);
         if (attacker != null) {
            com.legendaryweapons.managers.HudManager.message(attacker, Component.text("It has adapted to " + category + " - try something else!", TEAL));
         }

         return damage * this.num("adapted-damage", 0.35);
      }

      return damage;
   }

   private void learn() {
      long now = System.currentTimeMillis();
      long window = (long)(this.num("adapt-window-seconds", 20.0) * 1000.0);
      while (!this.recent.isEmpty() && now - (long)this.recent.peekFirst()[0] > window) {
         this.recent.pollFirst();
      }

      Map<String, Double> byCategory = new HashMap<>();
      double total = 0.0;
      for (Object[] entry : this.recent) {
         byCategory.merge((String)entry[1], (Double)entry[2], Double::sum);
         total += (Double)entry[2];
      }

      if (total < this.num("adapt-minimum-damage", 60.0)) {
         return;
      }

      for (Map.Entry<String, Double> entry : byCategory.entrySet()) {
         Long until = this.resisting.get(entry.getKey());
         if (entry.getValue() / total >= this.num("adapt-share", 0.55) && (until == null || until < now) && !entry.getKey().equals("unknown")) {
            this.resisting.put(entry.getKey(), now + (long)(this.num("adapt-seconds", 20.0) * 1000.0));
            this.recent.removeIf(e -> e[1].equals(entry.getKey()));
            this.say("☠ The Hollow Warden adapts to " + entry.getKey() + ". It shrugs them off for a while.");
            this.plugin.getSoundManager().world(this.body.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.0F, 1.2F);
            this.model.setState("roar");
            this.later(30L, () -> this.model.setState("idle"));
            return;
         }
      }
   }

   // ------------------------------------------------------------------ main loop

   @Override
   protected void onTick() {
      long now = System.currentTimeMillis();
      for (Player player : this.fighters()) {
         boolean ground = player.isOnGround();
         Boolean was = this.grounded.put(player.getUniqueId(), ground);
         if (Boolean.TRUE.equals(was) && !ground && !player.isFlying() && !player.isInWater()) {
            this.jumps.merge(player.getUniqueId(), 1, Integer::sum);
         }
      }

      if (this.ticks % 20L == 0L) {
         for (Player player : this.fighters()) {
            double change = player.isSneaking() ? -12.0 : -5.0;
            if (player.isSprinting()) {
               change += 9.0;
            }

            Integer jumped = this.jumps.remove(player.getUniqueId());
            if (jumped != null) {
               change += 4.0 * Math.min(3, jumped);
            }

            this.addNoise(player, change);
         }

         this.learn();
         if (this.phase >= 2 && now >= this.nextDarkness) {
            this.nextDarkness = now + 6000L;
            for (Player player : this.fighters()) {
               player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, true));
            }
         }
      }

      if (!this.nodes.isEmpty()) {
         this.tickNodes();
      }

      if (this.tendril != null) {
         this.tickTendril(now);
      }

      if (now >= this.nextSonic) {
         this.nextSonic = now + (long)(this.num(this.phase >= 2 ? "sonic-interval-enraged" : "sonic-interval", this.phase >= 2 ? 8.0 : 12.0) * 1000.0);
         this.sonicLine();
      }

      if (this.nextEruption == 0L) {
         this.nextEruption = now + 10000L;
         this.nextRoar = now + 16000L;
      }

      if (this.nodes.isEmpty() && now >= this.nextEruption) {
         this.nextEruption = now + (long)(this.num("eruption-interval", 15.0) * 1000.0);
         this.sculkEruption();
      }

      if (this.phase >= 1 && this.nodes.isEmpty() && now >= this.nextRoar) {
         this.nextRoar = now + (long)(this.num("roar-interval", 20.0) * 1000.0);
         this.shockwaveRoar();
      }

      if (this.phase >= 1 && this.tendril == null && this.nodes.isEmpty() && now >= this.nextTendril) {
         this.nextTendril = now + (long)(this.num("tendril-interval", 18.0) * 1000.0);
         this.seize();
      }
   }

   @Override
   protected void onPhase(int phase) {
      this.raiseNodes();
      if (phase >= 2) {
         this.model.setState("enraged");
         this.say("☠ The light is going out...");
      }
   }

   // ------------------------------------------------------------------ eruptions and the roar

   /** The loudest players' ground is marked, then sculk spikes burst out of it. Sneak, keep moving. */
   private void sculkEruption() {
      List<Player> loud = new ArrayList<>(this.fighters());
      loud.sort((a, b) -> Double.compare(this.noise.getOrDefault(b.getUniqueId(), 0.0), this.noise.getOrDefault(a.getUniqueId(), 0.0)));
      int count = Math.min(loud.size(), this.integer("eruption-targets", 2));
      final Color mark = Color.fromRGB(20, 110, 120);
      final double radius = this.num("eruption-radius", 2.5);
      for (int i = 0; i < count; i++) {
         final Location at = this.arena.surface(loud.get(i).getLocation());
         this.plugin.getSoundManager().world(at, Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.2F, 0.7F);
         for (int t = 0; t < 30; t += 5) {
            this.later(t, () -> {
               dustRing(at, radius, mark, 1.3F);
               ParticleManager.showcase(Particle.SCULK_CHARGE_POP, at, 6, radius / 2.0, 0.1, radius / 2.0, 0.02, null);
            });
         }

         this.later(30L, () -> {
            com.legendaryweapons.managers.AnimationFx.spikes(at, Material.SCULK, 7, radius * 0.6, 2.2, 16, true);
            ParticleManager.showcase(Particle.SCULK_SOUL, at.clone().add(0.0, 0.5, 0.0), 20, radius / 2.0, 0.6, radius / 2.0, 0.05, null);
            this.plugin.getSoundManager().world(at, Sound.BLOCK_SCULK_BREAK, 1.5F, 0.5F);
            for (Player player : this.fighters()) {
               if (player.getLocation().distanceSquared(at) <= radius * radius) {
                  this.trueDamage(player, this.num("eruption-damage", 9.0));
                  player.setVelocity(new Vector(0.0, 1.0, 0.0));
                  player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, true, false, true));
                  this.addNoise(player, 20.0);
               }
            }
         });
      }
   }

   /**
    * It roars, then a shockwave rolls out along the ground. Anyone standing on the ground when it
    * passes is thrown; jump over it.
    */
   private void shockwaveRoar() {
      final Location centre = this.arena.surface(this.body.getLocation());
      final double reach = this.num("roar-radius", 16.0);
      final int travel = 20;
      this.body.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 6, true, false));
      this.plugin.getSoundManager().world(centre, Sound.ENTITY_WARDEN_ROAR, 2.0F, 0.8F);
      for (Player player : this.fighters()) {
         com.legendaryweapons.managers.HudManager.message(player, Component.text("☠ The Warden roars - jump the shockwave!", NamedTextColor.DARK_AQUA));
      }

      final java.util.Set<UUID> struck = new java.util.HashSet<>();
      final Color ring = Color.fromRGB(40, 200, 210);
      this.later(28L, () -> {
         this.plugin.getSoundManager().world(centre, Sound.ENTITY_WARDEN_SONIC_BOOM, 2.0F, 0.6F);
         com.legendaryweapons.managers.SpriteFx.wave(centre, ring, reach, travel);
      });
      for (int t = 0; t <= travel; t++) {
         final double r = reach * t / (double)travel;
         this.later(30L + t, () -> {
            if (r > 0.5) {
               dustRing(centre, r, ring, 1.4F);
            }

            for (Player player : this.fighters()) {
               Vector off = player.getLocation().toVector().subtract(centre.toVector());
               double flat = Math.hypot(off.getX(), off.getZ());
               if (Math.abs(flat - r) <= 1.0 && player.isOnGround() && Math.abs(off.getY()) < 3.0 && struck.add(player.getUniqueId())) {
                  this.trueDamage(player, this.num("roar-damage", 10.0));
                  Vector push = off.setY(0.0);
                  player.setVelocity((push.lengthSquared() < 0.01 ? new Vector(1, 0, 0) : push.normalize()).multiply(1.4).setY(0.8));
                  player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, true, false, true));
               }
            }
         });
      }
   }

   // ------------------------------------------------------------------ sonic lines

   private void sonicLine() {
      Player target = this.loudest();
      if (target == null) {
         return;
      }

      final Location origin = this.body.getLocation().add(0.0, 1.0, 0.0);
      Vector direction = target.getLocation().add(0.0, 1.0, 0.0).toVector().subtract(origin.toVector());
      if (direction.lengthSquared() < 0.01) {
         return;
      }

      final Vector dir = direction.setY(direction.getY() * 0.3).normalize();
      final double length = this.num("sonic-length", 24.0);
      final Location end = origin.clone().add(dir.clone().multiply(length));
      this.model.setState("attack");
      this.plugin.getSoundManager().world(origin, Sound.ENTITY_WARDEN_SONIC_CHARGE, 1.2F, 1.0F);
      final int warn = (int)this.num("sonic-warning-ticks", 30.0);
      for (int t = 0; t < warn; t += 5) {
         this.later(t, () -> dustLine(origin, end, Color.fromRGB(20, 160, 150), 1.6F));
      }

      this.later(warn, () -> {
         this.plugin.getSoundManager().world(origin, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5F, 1.0F);
         com.legendaryweapons.managers.SpriteFx.burst(origin, Color.fromRGB(40, 200, 210), 2.8, 1, 1, 5);
         for (double d = 1.0; d <= length; d += 1.5) {
            ParticleManager.showcase(Particle.SONIC_BOOM, origin.clone().add(dir.clone().multiply(d)), 1, 0.0, 0.0, 0.0, 0.0, null);
         }

         for (Player player : this.fighters()) {
            Vector rel = player.getLocation().add(0.0, 1.0, 0.0).toVector().subtract(origin.toVector());
            double along = rel.dot(dir);
            if (along > 0.0 && along <= length && rel.clone().subtract(dir.clone().multiply(along)).length() <= this.num("sonic-width", 1.4)) {
               this.trueDamage(player, this.num("sonic-damage", 12.0));
               com.legendaryweapons.managers.SpriteFx.burst(player, 0.55, Color.fromRGB(40, 200, 210), 1.8, 1, 1, 4);
               player.setVelocity(dir.clone().multiply(1.2).setY(0.4));
            }
         }

         this.model.setState(this.phase >= 2 ? "enraged" : "idle");
      });
   }

   // ------------------------------------------------------------------ sculk nodes

   private void raiseNodes() {
      this.clearNodes();
      int count = Math.max(1, Math.min(3, this.fighters().size()));
      this.shielded = true;
      this.body.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2, true, false));
      ThreadLocalRandom random = ThreadLocalRandom.current();
      double start = random.nextDouble(Math.PI * 2.0);
      for (int i = 0; i < count; i++) {
         double angle = start + Math.PI * 2.0 * i / count;
         double r = this.arena.radius() * 0.6;
         Location raw = this.arena.centre().clone().add(Math.cos(angle) * r, 0.0, Math.sin(angle) * r);
         Location at = this.arena.surface(raw);
         BlockDisplay block = at.getWorld().spawn(at.clone().subtract(0.6, 0.0, 0.6), BlockDisplay.class, d -> {
            d.setBlock(Material.SCULK_SHRIEKER.createBlockData());
            d.setPersistent(false);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(30, 200, 190));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.2F, 1.2F, 1.2F), new Quaternionf()));
         });
         TextDisplay label = at.getWorld().spawn(at.clone().add(0.0, 2.0, 0.0), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
         });
         this.extras.add(block);
         this.extras.add(label);
         this.nodes.add(new Node(at, block, label));
      }

      this.plugin.getSoundManager().world(this.body.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, 1.5F, 0.7F);
      this.say("☠ The Warden buries itself in sculk. Charge all " + count + " sculk node" + (count == 1 ? "" : "s") + " at the same time to break its shield!");
   }

   private void tickNodes() {
      if (this.ticks % 5L != 0L) {
         return;
      }

      boolean allFull = true;
      for (Node node : this.nodes) {
         Player charger = null;
         for (Player player : this.fighters()) {
            if (player.getLocation().distanceSquared(node.at) <= 2.5 * 2.5) {
               charger = player;
               break;
            }
         }

         if (charger != null) {
            node.charge = Math.min(100.0, node.charge + this.num("node-charge-per-second", 25.0) / 4.0);
            this.credit(charger, Contribution.Kind.MECHANIC, 0.25);
            ParticleManager.showcase(Particle.SCULK_CHARGE_POP, node.at.clone().add(0.0, 1.0, 0.0), 3, 0.4, 0.4, 0.4, 0.0, null);
         } else {
            node.charge = Math.max(0.0, node.charge - this.num("node-drain-per-second", 12.0) / 4.0);
         }

         allFull &= node.charge >= 100.0;
         int bars = (int)Math.round(node.charge / 10.0);
         node.label.text(Component.text("Sculk Node", TEAL, TextDecoration.BOLD).append(Component.newline())
            .append(Component.text("■".repeat(bars), node.charge >= 100.0 ? NamedTextColor.AQUA : TEAL)).append(Component.text("■".repeat(10 - bars), NamedTextColor.DARK_GRAY)));
         dustRing(node.at, 2.5, node.charge >= 100.0 ? Color.AQUA : Color.fromRGB(20, 120, 110), 1.0F);
      }

      if (allFull) {
         this.clearNodes();
         this.shielded = false;
         this.say("☠ The sculk shatters! The Warden is exposed!");
         this.plugin.getSoundManager().world(this.body.getLocation(), Sound.BLOCK_SCULK_CATALYST_BLOOM, 1.5F, 0.6F);
         this.body.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 3, true, false));
         ParticleManager.showcase(Particle.SCULK_SOUL, this.body.getLocation().add(0.0, 1.5, 0.0), 40, 1.0, 1.0, 1.0, 0.05, null);
      }
   }

   private void clearNodes() {
      for (Node node : this.nodes) {
         node.block.remove();
         node.label.remove();
         this.extras.remove(node.block);
         this.extras.remove(node.label);
      }

      this.nodes.clear();
   }

   // ------------------------------------------------------------------ tendrils

   private void seize() {
      Player loud = this.loudest();
      Player victim = this.randomFighter(this.fighters().size() > 1 ? loud : null);
      if (victim == null) {
         return;
      }

      Interaction handle = victim.getWorld().spawn(victim.getLocation(), Interaction.class, i -> {
         i.setPersistent(false);
         i.setInteractionWidth(1.2F);
         i.setInteractionHeight(2.2F);
         i.setResponsive(true);
      });
      this.extras.add(handle);
      long hold = (long)(this.num("tendril-seconds", 6.0) * 1000.0);
      this.tendril = new Tendril(victim.getUniqueId(), handle, System.currentTimeMillis() + hold);
      this.plugin.getSoundManager().world(victim.getLocation(), Sound.ENTITY_WARDEN_TENDRIL_CLICKS, 1.5F, 0.6F);
      victim.sendMessage(Component.text("☠ A tendril has you! Someone must cut you free (right-click the tendril)!", NamedTextColor.RED));
      for (Player other : this.fighters()) {
         if (!other.equals(victim)) {
            com.legendaryweapons.managers.HudManager.message(other, Component.text("☠ " + this.events().publicName(victim) + " is seized - right-click the tendril to free them!", TEAL));
         }
      }
   }

   private void tickTendril(long now) {
      Player victim = this.plugin.getServer().getPlayer(this.tendril.victim);
      if (victim == null || victim.isDead() || !this.arena.contains(victim.getLocation(), 6.0)) {
         this.releaseTendril();
         return;
      }

      this.tendril.handle.teleport(victim.getLocation());
      Location from = this.body.getLocation().add(0.0, 1.5, 0.0);
      if (this.ticks % 2L == 0L) {
         dustLine(from, victim.getLocation().add(0.0, 1.0, 0.0), Color.fromRGB(10, 70, 65), 1.3F);
      }

      Vector pull = from.toVector().subtract(victim.getLocation().toVector());
      if (pull.lengthSquared() > 4.0) {
         victim.setVelocity(pull.normalize().multiply(0.12).setY(0.0));
      }

      if (this.ticks % 20L == 0L) {
         this.trueDamage(victim, this.num("tendril-damage-per-second", 1.0));
      }

      if (now >= this.tendril.releaseAt) {
         this.plugin.getSoundManager().world(victim.getLocation(), Sound.ENTITY_WARDEN_ATTACK_IMPACT, 1.5F, 0.6F);
         this.trueDamage(victim, this.num("tendril-slam-damage", 10.0));
         victim.setVelocity(new Vector(0.0, 0.9, 0.0));
         this.say("☠ Nobody freed " + this.events().publicName(victim) + ". The Warden slams them.");
         this.releaseTendril();
      }
   }

   private void releaseTendril() {
      if (this.tendril != null) {
         this.tendril.handle.remove();
         this.extras.remove(this.tendril.handle);
         this.tendril = null;
      }
   }

   @Override
   protected void onInteract(Player player, Entity clicked) {
      if (this.tendril != null && clicked.equals(this.tendril.handle) && !player.getUniqueId().equals(this.tendril.victim)) {
         Player victim = this.plugin.getServer().getPlayer(this.tendril.victim);
         this.credit(player, Contribution.Kind.SUPPORT, 10.0);
         this.say("☠ " + this.events().publicName(player) + " cut " + (victim == null ? "them" : this.events().publicName(victim)) + " free!");
         this.plugin.getSoundManager().world(clicked.getLocation(), Sound.BLOCK_SCULK_BREAK, 1.2F, 0.8F);
         this.addNoise(player, 15.0);                // heroics are loud
         this.releaseTendril();
      }
   }

   @Override
   protected void cleanup() {
      this.clearNodes();
      this.releaseTendril();
      super.cleanup();
   }
}
